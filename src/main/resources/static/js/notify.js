/* New-mail notifications for Jarurat Mail.
 *
 * Two designs were on the table and only one of them is here, so the choice is
 * written down rather than left to be reverse engineered:
 *
 *   Web Push is the version that reaches a phone with the app shut. It needs a
 *       VAPID key pair, a PushSubscription row per user per device with its own
 *       expiry and re-subscription handling, a server that signs and posts to
 *       fcm.googleapis.com and web.push.apple.com, and above all something that
 *       tells this application a message arrived: Stalwart has to run a hook on
 *       delivery, or we hold a long-lived JMAP EventSource per mailbox. That is a
 *       database migration, a new outbound dependency, an Apple developer
 *       relationship for iOS, and a delivery path that fails silently. It is
 *       real work and it was deliberately not started here, because a half-built
 *       push subscription that never fires is worse than no push at all.
 *
 *   In-app polling, which is this file, only works while a tab is open. On a
 *       laptop left open all day that is most of the working day, it needs no
 *       schema, no keys and no third party, and it is honest about its limits.
 *
 * The part of this file that works for everybody, with no permission prompt and
 * no decision to make, is the unread count in the tab title and painted onto the
 * favicon. The desktop notification is the optional extra on top, and the browser
 * permission for it is asked for exactly once, from a button, after this tab has
 * actually watched a message arrive. Asking on page load is how an application
 * loses that permission permanently: most people refuse a prompt they did not ask
 * for, and a refusal can never be asked again from script.
 *
 * Self contained on purpose. mail.js and mail.html are owned elsewhere, so this
 * script adds itself with one line and touches nothing of theirs. The two things
 * it does read from the page are both public contract rather than internals:
 * window.openMessage, which is how the mailbox opens a message, and the aria-busy
 * flag on the message list, which is how the page says it has finished loading.
 * Both are optional; without them the badge still works and a notification click
 * still focuses the tab.
 *
 * Include with:  <script src="/js/notify.js?v=notify1"></script>
 */
(function () {
    'use strict';

    // Two copies would double the request rate and fight over the favicon.
    if (window.jmNotify) return;

    var POLL_URL = '/api/mail/poll';

    /* Forty-five seconds is the middle of the band this endpoint was costed for.
       Hidden tabs go to two minutes: a background tab is where a notification
       matters most, so this backs off rather than stopping, and browsers throttle
       background timers to roughly a minute anyway, which makes anything shorter
       a lie. */
    var ACTIVE_MS = 45000;
    var HIDDEN_MS = 120000;

    /* The mailbox screen opens with a status call and a folder call already in
       flight. Landing a third request on top of them delays the list for no
       reason: nothing has arrived in the first eight seconds of a page load. */
    var FIRST_MS = 8000;

    /* A mail server that is down must not be asked every 45 seconds by every open
       tab in the organisation. Doubling from the active interval to five minutes
       is the whole retry policy; success resets it. */
    var BACKOFF_MAX = 300000;

    /* After a locked mailbox stops the loop, the loop restarts on the next return
       to this tab, and never more than once a minute however often somebody
       switches windows. */
    var RELOCK_FLOOR_MS = 60000;

    var ASK_KEY = 'jm.notify.asked';
    var ASK_SNOOZE_DAYS = 14;

    var TAG = 'jm-newmail';

    var S = {
        timer: null,
        /* stopped is permanent and paused is not, and the difference is the whole
           stop rule: stopped means the session has gone and only a page load can
           fix it, paused means the mailbox is shut and a person can open it. */
        stopped: false,
        paused: false,
        restartedAt: 0,
        backoff: 0,
        started: false,
        unread: 0,
        lastId: null,
        lastAt: 0,
        /* False until one answer has been read. The first answer is a baseline and
           never an announcement, or every page load would fire a notification for
           whatever happened to be sitting at the top of the inbox. Tracked as its
           own flag rather than inferred from lastId being null, because an inbox
           that was empty on the first poll would then never announce its first
           message. */
        baselined: false,
        inFlight: false,
        baseTitle: document.title,
        titleGuard: false
    };

    /* ====================================================================
       The badge. No permission, no prompt, no decision. This is the half
       that works for every person on every browser, so it is the half that
       has to be right.
       ==================================================================== */

    var favLink = null;
    var favOriginal = null;
    var favImage = null;
    var favDrawn = -1;

    function favicon() {
        if (favLink) return favLink;
        favLink = document.querySelector('link[rel~="icon"]');
        if (!favLink) {
            favLink = document.createElement('link');
            favLink.rel = 'icon';
            favLink.href = '/logo.png';
            document.head.appendChild(favLink);
        }
        favOriginal = favLink.getAttribute('href');
        return favLink;
    }

    /* Decoded once and kept. The source is 115KB of PNG and redrawing the badge
       every 45 seconds must not mean decoding it every 45 seconds. */
    function withFaviconSource(then) {
        if (favImage) { then(); return; }
        var link = favicon();
        var img = new Image();
        img.onload = function () { favImage = img; then(); };
        // A favicon that will not decode is not worth a console error. The title
        // badge carries the count on its own.
        img.onerror = function () { favImage = null; };
        img.src = favOriginal || link.getAttribute('href') || '/logo.png';
    }

    function paintFavicon(count) {
        if (favDrawn === count) return;
        var link = favicon();
        if (count <= 0) {
            if (favOriginal) link.setAttribute('href', favOriginal);
            favDrawn = 0;
            return;
        }
        withFaviconSource(function () {
            if (!favImage) return;
            var size = 64;
            var canvas = document.createElement('canvas');
            canvas.width = size;
            canvas.height = size;
            var g = canvas.getContext ? canvas.getContext('2d') : null;
            if (!g) return;

            // Fitted rather than stretched. logo.png is 357x327 (measured), so
            // drawing it into a square box squashes it about nine per cent wider
            // than the browser's own letterboxed rendering of the same file, and
            // the badge would then arrive together with a subtly different logo.
            var scale = Math.min(size / favImage.width, size / favImage.height);
            var w = favImage.width * scale;
            var h = favImage.height * scale;
            g.drawImage(favImage, (size - w) / 2, (size - h) / 2, w, h);

            var r = 21;
            var cx = size - r - 1;
            var cy = r + 1;
            // The outer disc is the page ground, not a border: it punches the dot
            // out of the logo underneath so the count reads as an overlay rather
            // than a smudge, the same trick the install card's tick uses.
            g.beginPath();
            g.arc(cx, cy, r, 0, Math.PI * 2);
            g.fillStyle = '#191919';
            g.fill();
            g.beginPath();
            g.arc(cx, cy, r - 3, 0, Math.PI * 2);
            g.fillStyle = '#e0483c';        // --danger, docs/UI-SPEC.md section 2
            g.fill();

            var label = count > 9 ? '9+' : String(count);
            g.fillStyle = '#ffffff';
            g.font = 'bold ' + (label.length > 1 ? 24 : 30) + 'px -apple-system,'
                + 'BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif';
            g.textAlign = 'center';
            g.textBaseline = 'middle';
            g.fillText(label, cx, cy + 1);

            try {
                link.setAttribute('href', canvas.toDataURL('image/png'));
                favDrawn = count;
            } catch (e) { /* a tainted or disabled canvas; the title still carries it */ }
        });
    }

    /* The title is rewritten rather than prefixed blindly, so a second poll does
       not produce "(2) (1) Jarurat Mail". baseTitle is whatever the page called
       itself before this script touched it. */
    function paintTitle(count) {
        var next = count > 0 ? '(' + (count > 99 ? '99+' : count) + ') ' + S.baseTitle : S.baseTitle;
        if (document.title === next) return;
        S.titleGuard = true;
        document.title = next;
        S.titleGuard = false;
    }

    /* Installed apps get a real dot on the dock or launcher icon for free, and it
       needs no permission of any kind on desktop. */
    function paintAppBadge(count) {
        try {
            if (count > 0 && navigator.setAppBadge) navigator.setAppBadge(count);
            else if (navigator.clearAppBadge) navigator.clearAppBadge();
        } catch (e) { /* not supported, or the app is not installed */ }
    }

    function paint(count) {
        S.unread = count;
        paintTitle(count);
        paintFavicon(count);
        paintAppBadge(count);
    }

    /* If anything else on the page sets the title later, the count would silently
       vanish and stay vanished until the next arrival. Watching the title element
       costs one observer and makes the badge survive that. The guard is what keeps
       our own write from re-entering. */
    function watchTitle() {
        var node = document.querySelector('title');
        if (!node || !window.MutationObserver) return;
        new MutationObserver(function () {
            if (S.titleGuard) return;
            var shown = document.title;
            var stripped = shown.replace(/^\(\d+\+?\)\s+/, '');
            S.baseTitle = stripped;
            if (S.unread > 0) paintTitle(S.unread);
        }).observe(node, { childList: true });
    }

    /* ====================================================================
       The poll loop.
       ==================================================================== */

    function schedule(ms) {
        if (S.timer) clearTimeout(S.timer);
        if (S.stopped || S.paused) { S.timer = null; return; }
        S.timer = setTimeout(tick, ms);
    }

    function interval() {
        if (S.backoff) return S.backoff;
        return document.hidden ? HIDDEN_MS : ACTIVE_MS;
    }

    /**
     * A hard stop. The console session has gone, so every future request would be
     * a 401 as well. Deliberately does not redirect to /login: this runs on a
     * timer with nobody watching, and throwing away a half-written reply because a
     * background request expired is a worse failure than a stale screen. The next
     * thing the person actually does hits mail.js's own 401 handling.
     */
    function stop() {
        S.stopped = true;
        if (S.timer) clearTimeout(S.timer);
        S.timer = null;
        paint(0);
    }

    /**
     * A soft stop for a locked mailbox. There is nothing to count and nothing to
     * announce until somebody produces the mailbox password, and that happens in
     * this tab, so the loop is re-armed when the tab is looked at again rather
     * than by carrying on asking. A tab that is never returned to never polls
     * again, which is the correct behaviour for a mailbox nobody has opened.
     */
    function pause() {
        S.paused = true;
        if (S.timer) clearTimeout(S.timer);
        S.timer = null;
        paint(0);
    }

    function resume() {
        if (S.stopped || !S.paused) return;
        var now = Date.now();
        if (now - S.restartedAt < RELOCK_FLOOR_MS) return;
        S.restartedAt = now;
        S.paused = false;
        schedule(0);
    }

    function tick() {
        if (S.stopped || S.paused || S.inFlight) return;
        S.inFlight = true;

        fetch(POLL_URL, {
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'Accept': 'application/json' }
        }).then(function (res) {
            if (res.status === 401) { stop(); return null; }
            // 409 is the mailbox being shut, which is a different thing from the
            // session being gone and is the reason the endpoint distinguishes them.
            if (res.status === 409) { pause(); return null; }
            if (!res.ok) throw new Error('poll ' + res.status);
            return res.json();
        }).then(function (data) {
            if (!data) return;
            S.backoff = 0;
            absorb(data);
            schedule(interval());
        }).catch(function () {
            // Offline, or the mail server is not answering. Neither is a reason to
            // stop for good, and neither is a reason to keep asking at full rate.
            S.backoff = Math.min(S.backoff ? S.backoff * 2 : ACTIVE_MS, BACKOFF_MAX);
            schedule(interval());
        }).then(function () {
            S.inFlight = false;
        });
    }

    /**
     * Turns one poll answer into a badge and, at most, one notification.
     *
     * The arrival test is on the timestamp and not on the id. The endpoint answers
     * with the newest message in the inbox whether or not it has been read, so its
     * id only changes when something genuinely arrives, but a message can be
     * deleted and leave an older one on top; comparing times means that shows as
     * nothing rather than as new mail.
     */
    function absorb(data) {
        paint(typeof data.unread === 'number' ? data.unread : 0);

        var baselined = S.baselined;
        S.baselined = true;

        var newest = data.newest;
        // lastAt is deliberately left alone when the inbox empties. It is a high
        // water mark, and resetting it would make the next message to arrive look
        // older than one already announced.
        if (!newest || !newest.id) { S.lastId = null; return; }

        var at = Date.parse(newest.receivedAt || '') || 0;
        var arrived = baselined && newest.id !== S.lastId && at > S.lastAt;

        S.lastId = newest.id;
        if (at > S.lastAt) S.lastAt = at;
        if (!arrived) return;

        if (!newest.seen) announce(newest);
        // Offered on an arrival and never on a page load, because this is the one
        // moment the answer to the question is obviously yes.
        offerPermission();
    }

    /* ====================================================================
       The notification itself.
       ==================================================================== */

    function canNotify() {
        return typeof Notification !== 'undefined' && Notification.permission === 'granted';
    }

    /** Announcing mail to somebody who is looking straight at the mailbox is noise. */
    function unattended() {
        return document.hidden || (document.hasFocus && !document.hasFocus());
    }

    function announce(newest) {
        if (!canNotify() || !unattended()) return;

        var title = newest.from || 'New mail';
        var body = newest.subject || '(no subject)';
        var options = {
            body: body,
            // One tag, so a quiet morning of six messages leaves one notification
            // saying the newest rather than a stack of six.
            tag: TAG,
            renotify: true,
            icon: '/icons/icon-192.png',
            badge: '/icons/icon-192.png',
            data: { id: newest.id, url: '/mail?msg=' + encodeURIComponent(newest.id) }
        };

        // Through the service worker wherever there is one, because that is the
        // only route where a click can focus this tab: a Notification created by
        // the page dies with the page, and its onclick cannot reach a tab the
        // browser has since discarded.
        if (navigator.serviceWorker && navigator.serviceWorker.ready) {
            navigator.serviceWorker.ready.then(function (reg) {
                return reg.showNotification(title, options);
            }).catch(function () { plainNotification(title, options); });
            return;
        }
        plainNotification(title, options);
    }

    function plainNotification(title, options) {
        try {
            var n = new Notification(title, options);
            n.onclick = function () {
                window.focus();
                open(options.data && options.data.id);
                n.close();
            };
        } catch (e) { /* Safari without a worker, or notifications turned off */ }
    }

    /* ====================================================================
       Opening the message a notification was about.
       ==================================================================== */

    /**
     * Hands the id to the mailbox screen, and waits for it to have finished
     * loading first.
     *
     * mail.js finishes booting by choosing a folder, and choosing a folder clears
     * the reading pane, so a message opened before that lands on screen and is
     * then wiped a beat later. aria-busy on the message list is the page's own
     * public statement that it has stopped loading, which is why it is the thing
     * waited on rather than any internal of that file.
     */
    function open(id) {
        if (!id) return;
        var waited = 0;
        (function attempt() {
            var list = document.getElementById('list');
            var busy = list && list.getAttribute('aria-busy') === 'true';
            if ((busy || typeof window.openMessage !== 'function') && waited < 10000) {
                waited += 200;
                setTimeout(attempt, 200);
                return;
            }
            if (typeof window.openMessage === 'function') window.openMessage(id, {});
        })();
    }

    /* A click on a notification arrives here from sw.js once it has focused this
       tab, which is what keeps it from opening a second copy of the mailbox. */
    function listenForClicks() {
        if (!navigator.serviceWorker) return;
        navigator.serviceWorker.addEventListener('message', function (event) {
            var msg = event.data;
            if (msg && msg.type === 'jm-open-mail' && msg.id) open(msg.id);
        });
    }

    /* The other half of the same journey: when there was no tab to focus, sw.js
       opened one at /mail?msg=<id>. The parameter is read once and taken back out
       of the address bar, so a reload does not re-open the same message forever.
       mail.js keeps location.search when it rewrites the entry at boot, which is
       why a query parameter is used here and not a hash. */
    function openFromUrl() {
        var id = null;
        try { id = new URLSearchParams(location.search).get('msg'); } catch (e) { return; }
        if (!id) return;
        try {
            var params = new URLSearchParams(location.search);
            params.delete('msg');
            var rest = params.toString();
            history.replaceState(history.state, '', location.pathname + (rest ? '?' + rest : ''));
        } catch (e) { /* an entry we may not rewrite; opening still works */ }
        open(id);
    }

    /* ====================================================================
       Asking for permission, once, from a button.
       ==================================================================== */

    function askable() {
        if (typeof Notification === 'undefined') return false;
        if (Notification.permission !== 'default') return false;   // granted, or refused for good
        try {
            var t = parseInt(localStorage.getItem(ASK_KEY) || '0', 10);
            return !(t > 0 && (Date.now() - t) < ASK_SNOOZE_DAYS * 86400000);
        } catch (e) { return true; }
    }

    function snoozeAsk() {
        try { localStorage.setItem(ASK_KEY, String(Date.now())); } catch (e) { /* private mode */ }
    }

    function css() {
        if (document.getElementById('jmNotifyCss')) return;
        var s = document.createElement('style');
        s.id = 'jmNotifyCss';
        s.textContent = [
            /* Tokens are declared locally for the same reason pwa.js declares its
               own: this card has to render on a page whose stylesheet it cannot
               assume. The values are copied name for name from docs/UI-SPEC.md
               sections 2, 3, 4 and 7. */
            '#jmNotify{',
            '  --jn-panel:#202020;--jn-panel-2:#252525;--jn-panel-3:#2c2c2c;--jn-panel-4:#343434;',
            '  --jn-border-strong:rgba(255,255,255,.14);',
            '  --jn-text:#ededed;--jn-dim:#b9b9b9;--jn-mute:#949494;',
            '  --jn-primary:#2f6fed;--jn-primary-hover:#3a7bf5;',
            /* 100, the promotion layer in section 15's table. A card asking for
               something is a promotion, and a promotion never outranks a surface
               the person deliberately opened: the sheets are at 150 and the tab
               bar is at 60, so this sits between them exactly as the install card
               does. */
            '  position:fixed;z-index:100;left:12px;right:12px;',
            '  bottom:calc(12px + env(safe-area-inset-bottom, 0px));',
            '  background:var(--jn-panel);color:var(--jn-text);',
            '  border:1px solid var(--jn-border-strong);border-radius:16px;',
            '  padding:16px;box-shadow:0 12px 34px rgba(0,0,0,.50);',
            '  display:flex;gap:12px;align-items:flex-start;',
            '  font:13.5px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;',
            '  transform:translateY(140%);opacity:0;',
            '  transition:transform 200ms cubic-bezier(.2,.7,.3,1),opacity 200ms cubic-bezier(.2,.7,.3,1)}',
            '#jmNotify,#jmNotify *{box-sizing:border-box}',
            '#jmNotify.on{transform:translateY(0);opacity:1}',
            '@media(prefers-reduced-motion:reduce){#jmNotify{transition:none}}',
            '@media(min-width:640px){#jmNotify{left:auto;right:18px;',
            '  width:min(380px,calc(100vw - 36px))}}',
            /* The phone tab bar sits at the bottom of the mailbox, so the card is
               lifted clear of it there the same way mail.html lifts the install
               card. Kept to a single id selector so a host page can still outrank
               it without !important. */
            '@media(max-width:899.98px){#jmNotify{bottom:calc(76px + env(safe-area-inset-bottom, 0px))}}',

            '#jmNotify .jn-ic{width:18px;height:18px;flex:none;fill:none;stroke:currentColor;',
            '  stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round;pointer-events:none}',
            '#jmNotify .jn-ico{flex:0 0 auto;width:40px;height:40px;border-radius:12px;',
            '  background:var(--jn-panel-3);display:grid;place-items:center;color:var(--jn-dim)}',
            '#jmNotify .jn-ico .jn-ic{width:20px;height:20px}',

            '#jmNotify .jn-c{flex:1 1 auto;min-width:0}',
            '#jmNotify .jn-t{margin:0 0 4px;padding-right:34px;font-size:16px;line-height:1.3;',
            '  font-weight:620;letter-spacing:-.01em}',
            '#jmNotify .jn-s{margin:0;padding-right:34px;color:var(--jn-dim);font-size:12.5px;',
            '  line-height:1.45}',
            '#jmNotify .jn-a{display:flex;gap:8px;margin-top:16px;flex-wrap:wrap}',
            '#jmNotify button{font:inherit;font-size:13.5px;font-weight:600;cursor:pointer;',
            '  min-height:44px;padding:0 16px;border-radius:8px;',
            '  border:1px solid var(--jn-border-strong);',
            '  background:var(--jn-panel-3);color:var(--jn-text);',
            '  -webkit-tap-highlight-color:transparent;touch-action:manipulation;',
            '  transition:background 120ms cubic-bezier(.2,.7,.3,1)}',
            '#jmNotify button:hover{background:var(--jn-panel-4)}',
            '#jmNotify button:active{background:var(--jn-panel-2)}',
            '#jmNotify button.jn-p{background:var(--jn-primary);border-color:var(--jn-primary);color:#fff}',
            '#jmNotify button.jn-p:hover{background:var(--jn-primary-hover);',
            '  border-color:var(--jn-primary-hover)}',
            '#jmNotify button:focus-visible{outline:2px solid var(--jn-primary);outline-offset:2px}',
            '@media(prefers-reduced-motion:reduce){#jmNotify button{transition:none}}',
            '#jmNotify .jn-x{position:absolute;top:4px;right:4px;width:44px;height:44px;',
            '  min-height:44px;padding:0;border:0;border-radius:8px;background:none;',
            '  color:var(--jn-mute);display:grid;place-items:center}',
            '#jmNotify .jn-x:hover{background:var(--jn-panel-3);color:var(--jn-text)}'
        ].join('');
        document.head.appendChild(s);
    }

    /* The shape system is fragments/icons.html and a <use> reference is how every
       other surface in this application draws one. Nothing here invents a glyph. */
    function icon(id) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'jn-ic');
        svg.setAttribute('aria-hidden', 'true');
        var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', '#' + id);
        svg.appendChild(use);
        return svg;
    }

    var card = null;

    function closeCard() {
        if (!card) return;
        var gone = card;
        card = null;
        gone.classList.remove('on');
        // The id goes immediately and the node 300ms later, so a card reopened
        // inside the slide-out window is not the second #jmNotify in the document.
        gone.removeAttribute('id');
        setTimeout(function () { gone.remove(); }, 300);
    }

    /**
     * Offered after this tab has watched a message arrive, and never before.
     *
     * That ordering is the point of the whole feature. A prompt on arrival at the
     * page is refused by most people, and a refusal is permanent and cannot be
     * asked again from script, so the one chance to ask is spent at the moment the
     * answer is obviously yes: something just came in while the person had this
     * open. The browser's own prompt is still not raised here. It is raised by the
     * button below, inside the click, because Chrome and Firefox both require a
     * user gesture and both ignore a request made without one.
     */
    function offerPermission() {
        if (card || !askable()) return;
        // Never two promotions at once. The install card is the same layer and got
        // there first; this one waits for the next arrival.
        if (document.getElementById('jmInstall')) return;

        css();
        card = document.createElement('div');
        card.id = 'jmNotify';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-label', 'Notify me about new mail');
        card.style.position = 'fixed';

        var ico = document.createElement('span');
        ico.className = 'jn-ico';
        ico.appendChild(icon('i-bell'));
        card.appendChild(ico);

        var col = document.createElement('div');
        col.className = 'jn-c';

        var h = document.createElement('p');
        h.className = 'jn-t';
        h.textContent = 'Tell me when mail arrives';
        col.appendChild(h);

        var p = document.createElement('p');
        p.className = 'jn-s';
        p.textContent = 'A message just came in while this tab was open. '
            + 'Jarurat Mail can show a desktop notification next time. '
            + 'It only works while the mailbox is open in a tab.';
        col.appendChild(p);

        var acts = document.createElement('div');
        acts.className = 'jn-a';

        var yes = document.createElement('button');
        yes.className = 'jn-p';
        yes.type = 'button';
        yes.textContent = 'Turn on';
        yes.addEventListener('click', function () {
            closeCard();
            snoozeAsk();
            request();
        });
        acts.appendChild(yes);

        var no = document.createElement('button');
        no.type = 'button';
        no.textContent = 'Not now';
        no.addEventListener('click', function () { snoozeAsk(); closeCard(); });
        acts.appendChild(no);

        col.appendChild(acts);
        card.appendChild(col);

        var x = document.createElement('button');
        x.className = 'jn-x';
        x.type = 'button';
        x.setAttribute('aria-label', 'Not now');
        x.appendChild(icon('i-close'));
        x.addEventListener('click', function () { snoozeAsk(); closeCard(); });
        card.appendChild(x);

        document.body.appendChild(card);
        var mine = card;
        requestAnimationFrame(function () { mine.classList.add('on'); });
    }

    /**
     * Raises the browser's own permission prompt. Must be called from inside a
     * click handler or the browsers that require a gesture drop it on the floor,
     * which is why this is not exported as something a timer could reach.
     */
    function request() {
        if (typeof Notification === 'undefined') return;
        try {
            var answer = Notification.requestPermission(function () { /* old callback form */ });
            if (answer && answer.then) answer.catch(function () { /* dismissed */ });
        } catch (e) { /* not available on this origin */ }
    }

    /* ====================================================================
       Wiring.
       ==================================================================== */

    document.addEventListener('visibilitychange', function () {
        if (S.stopped) return;
        // A paused loop restarts on the way back TO the tab and not on the way out
        // of it: leaving a tab is not somebody opening a mailbox.
        if (S.paused) { if (!document.hidden) resume(); return; }
        // Coming back to a tab is the moment its badge is most obviously stale, so
        // it is refreshed straight away rather than up to two minutes later.
        if (!document.hidden) schedule(0);
        else schedule(interval());
    });

    document.addEventListener('keydown', function (e) {
        // Section 15: every dialog closes on Escape, and this card is no exception
        // even though it is a promotion rather than a modal.
        if (e.key === 'Escape' && card) { snoozeAsk(); closeCard(); }
    });

    window.jmNotify = {
        /** Starts, or restarts after a locked mailbox was opened. Safe to call twice. */
        start: function () {
            if (S.stopped) return;
            if (S.paused) { S.restartedAt = 0; resume(); return; }
            if (S.started) return;
            S.started = true;
            watchTitle();
            listenForClicks();
            openFromUrl();
            schedule(FIRST_MS);
        },
        /** For a sign-out path that wants the timer gone before the page unloads. */
        stop: stop,
        /** Must be called from a click. Exported so a settings row could offer it too. */
        enable: request,
        state: function () {
            return {
                unread: S.unread,
                stopped: S.stopped,
                paused: S.paused,
                permission: typeof Notification === 'undefined' ? 'unsupported' : Notification.permission
            };
        }
    };

    window.jmNotify.start();
})();
