/* New-mail notifications for Jarurat Mail.
 *
 * Two transports, and this file now drives both. The earlier version of this
 * comment said Web Push had deliberately not been started; that is no longer
 * true and the reasoning it gave has been overtaken, so it is replaced rather
 * than left to mislead the next person.
 *
 *   Web Push is the version that reaches a phone with the app shut, and the one
 *       thing that made it look expensive turned out not to be ours to pay.
 *       Stalwart implements JMAP PushSubscription including the VAPID signing
 *       and the aes128gcm encryption, so the browser's own subscription is
 *       registered with the mail server and Stalwart posts to Apple and Google
 *       directly. This application is not in the delivery path, holds no key,
 *       writes no crypto and keeps no mailbox password to do it. What this file
 *       owns is the browser half: asking, subscribing, handing the subscription
 *       to the server, and standing the poll down once a push has actually
 *       arrived. The rendering and the clicking live in sw.js.
 *
 *   In-app polling, which is the rest of this file, only works while a tab is
 *       open. It stays, because it is the fallback for every browser and every
 *       device where push is unavailable or unproven, and because it is the only
 *       thing that keeps the unread COUNT honest: a push fires on delivery and
 *       says nothing at all when somebody reads a message on another device.
 *
 * The two never run at full rate together. See pushWorking() and interval().
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

    /* Page-created notifications carry the same tag scheme sw.js uses, so a
       message announced by a poll and the same message announced by a push
       replace one another instead of appearing twice. That is not theoretical:
       the two paths overlap for the whole first minute after a subscription is
       made and again on every rotation. */
    function tagFor(id) { return 'jm-a:' + id; }

    /* Page-created notifications are capped at this many on screen at once, for
       the reason the collapse rule in sw.js gives: past three the browser starts
       folding them behind a summary of its own and we have lost control of what
       is read. */
    var PAGE_MAX = 3;

    var PUSH_CONFIG_URL = '/api/mail/push/config';
    var PUSH_SUBSCRIBE_URL = '/api/mail/push/subscribe';

    /* A stable id for THIS browser on THIS device, so a re-subscribe replaces
       the old row rather than adding a second one. Stalwart does not deduplicate
       by deviceClientId, so two subscriptions really do mean two notifications
       for the same message, and the id is the only thing that lets the server
       destroy the stale one. It identifies a browser profile and nothing else:
       no mailbox, no person, no secret. */
    var DEVICE_KEY = 'jm.notify.device';

    /* Where the poll interval goes once push has proved itself.

       Fifteen minutes and not zero, and the difference is worth defending. Push
       fires on delivery, so it covers the arrival case completely and the timer
       is genuinely not needed for it. What push says nothing about is a message
       being READ somewhere else, which is most of what moves the unread count on
       a shared mailbox: the phone would keep showing four on the tab title and
       the favicon for as long as the tab stayed open. So the timer stays as a
       slow correction, and the fast path is event driven, from the push handler
       itself, which posts jm-push-refresh to every open tab. A person with push
       working pays one request per fifteen minutes instead of one per
       forty-five seconds, and their badge is still right. */
    var PUSH_IDLE_MS = 900000;

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
        titleGuard: false,
        /* Everything known about push on this device. supported is the server's
           answer, which is false whenever Stalwart is not configured with a
           VAPID key; seen is the only honest proof that a push ever arrived,
           because the subscription verifies itself server side without one. */
        push: {
            checked: false,
            supported: false,
            key: null,
            emailPush: false,
            state: 'off',
            seen: false,
            endpoint: null,
            expiresAt: null
        },
        /* The mailbox pinned to this session, as the poll endpoint reports it.
           Read and never chosen: MailboxAccess pins it server side and no
           endpoint in this application takes it as a parameter, so this is a
           label for the notification tag and nothing more. */
        mailbox: ''
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

    /**
     * True when push is not merely subscribed but has demonstrably delivered
     * something to this device.
     *
     * Deliberately not "we have a subscription". A subscription is verified by
     * the server reading the verification code straight back off the mail
     * server, with no push involved, so a verified subscription is evidence of
     * nothing. Standing the poll down on that would be how a person ends up
     * with neither transport working and no way to tell.
     */
    function pushWorking() {
        return !!(S.push.seen
            && S.push.endpoint
            && typeof Notification !== 'undefined'
            && Notification.permission === 'granted');
    }

    function interval() {
        if (S.backoff) return S.backoff;
        if (pushWorking()) return PUSH_IDLE_MS;
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
        if (data.mailbox) S.mailbox = data.mailbox;

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

    /* The CSRF token travels inside the notification, because the action buttons
       are handled in sw.js and a worker has no document.cookie to read it from.
       It is the token this page already holds and it is scoped to this session,
       so nothing new is exposed by putting it there; what it buys is Archive and
       Mark read working on a notification this page created, on every engine,
       including the ones with no Cookie Store API in a worker. */
    function csrfToken() {
        var m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function announce(newest) {
        if (!canNotify() || !unattended()) return;

        var title = newest.from || 'New mail';
        var body = newest.subject || '(no subject)';
        var options = {
            body: body,
            // Tagged per message rather than with one rolling tag, so this and
            // the push path cannot both announce the same arrival. The cap below
            // is what stops a quiet morning turning into a stack of six.
            tag: tagFor(newest.id),
            renotify: true,
            icon: '/icons/icon-192.png',
            badge: '/icons/icon-192.png',
            // When the MAIL arrived, not when this poll noticed it. Up to
            // forty-five seconds separate the two, and a laptop that was asleep
            // makes the gap hours.
            timestamp: Date.parse(newest.receivedAt || '') || Date.now(),
            dir: 'auto',      // subjects arrive here in Devanagari as well as Latin
            lang: '',         // the sender's language, which we do not know
            actions: [
                { action: 'archive', title: 'Archive' },
                { action: 'read', title: 'Mark read' }
            ],
            data: {
                v: 1, lane: 'A', kind: 'mail',
                id: newest.id,
                mailbox: S.mailbox || '',
                url: '/mail?msg=' + encodeURIComponent(newest.id),
                csrf: csrfToken()
            }
        };

        // Through the service worker wherever there is one, because that is the
        // only route where a click can focus this tab: a Notification created by
        // the page dies with the page, and its onclick cannot reach a tab the
        // browser has since discarded.
        if (navigator.serviceWorker && navigator.serviceWorker.ready) {
            navigator.serviceWorker.ready.then(function (reg) {
                if (!reg.getNotifications) return reg.showNotification(title, options);
                return reg.getNotifications().then(function (open) {
                    var mine = open.filter(function (n) {
                        return n.tag && n.tag.indexOf('jm-a:') === 0;
                    });
                    // Oldest first out of the way, so what stays on screen is the
                    // three most recent rather than the three that got there first.
                    for (var i = 0; i <= mine.length - PAGE_MAX; i++) {
                        try { mine[i].close(); } catch (e) { /* already gone */ }
                    }
                    return reg.showNotification(title, options);
                });
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
    /**
     * Whether the composer is open with something in it.
     *
     * Read off the page's own markup rather than any internal of mail.js, for
     * the reason the header comment gives: #composeSheet.open is how that screen
     * states it is showing the composer, and #cTo, #cSubject and #cEditor are
     * ids with label[for] pointing at them, so they are public in the same sense
     * window.openMessage is. If any of it were ever renamed this returns false
     * and the behaviour degrades to what it was before, which is the safe
     * direction for a guess to be wrong in.
     */
    function composerDirty() {
        var sheet = document.getElementById('composeSheet');
        if (!sheet || !sheet.classList || !sheet.classList.contains('open')) return false;
        var editor = document.getElementById('cEditor');
        if (editor && editor.textContent && editor.textContent.trim()) return true;
        var subject = document.getElementById('cSubject');
        if (subject && subject.value && subject.value.trim()) return true;
        var to = document.getElementById('cTo');
        if (to && to.value && to.value.trim()) return true;
        // A committed address is a chip and no longer in the input, so an
        // otherwise blank composer addressed to three people is still dirty.
        return sheet.querySelectorAll('.chip').length > 0;
    }

    function open(id, guarded) {
        if (!id) return;

        /* A notification click never destroys typed text.
         *
         * Every other state in this file optimises for getting the person to the
         * message. This one does not. Opening a message closes the composer, and
         * a composer closed by a background event takes whatever was in it with
         * it. Losing text somebody typed to something they did not do is the
         * failure people never report and never forgive; they simply stop
         * trusting the app. So the id is parked and the person is asked. */
        if (guarded && composerDirty()) { offerOpen(id); return; }

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
            if (!msg) return;

            if (msg.type === 'jm-open-mail' && msg.id) { open(msg.id, msg.guarded); return; }

            /* A push arrived. The badge is the one thing push carries no number
               for, so this is where the poll is re-run: on the event rather than
               on the clock, which is what lets the timer stand down to fifteen
               minutes without the count going stale. */
            if (msg.type === 'jm-push-refresh') { schedule(0); return; }

            /* sw.js asking for the CSRF token, because it has no document and
               the engine it is running on has no Cookie Store API in a worker.
               Answered on the port the worker transferred, so nothing is
               broadcast and nothing is kept. */
            if (msg.type === 'jm-csrf') {
                var port = event.ports && event.ports[0];
                if (port) { try { port.postMessage({ csrf: csrfToken() }); } catch (e) { /* closed */ } }
                return;
            }
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

    /**
     * Builds the promotion card the permission ask and the guarded-open prompt
     * both use.
     *
     * Extracted rather than duplicated, because the second card has to look
     * exactly like the first one: two cards from the same application that sit
     * in the same corner and disagree about their padding read as a bug even
     * when neither is. Layer 100 and the rest of the styling are unchanged; see
     * css() above and section 15 of the UI spec.
     */
    function buildCard(spec) {
        css();
        var el = document.createElement('div');
        el.id = 'jmNotify';
        el.setAttribute('role', 'dialog');
        el.setAttribute('aria-label', spec.label || spec.title);
        el.style.position = 'fixed';

        var ico = document.createElement('span');
        ico.className = 'jn-ico';
        ico.appendChild(icon(spec.icon));
        el.appendChild(ico);

        var col = document.createElement('div');
        col.className = 'jn-c';

        var h = document.createElement('p');
        h.className = 'jn-t';
        h.textContent = spec.title;
        col.appendChild(h);

        var p = document.createElement('p');
        p.className = 'jn-s';
        p.textContent = spec.text;
        col.appendChild(p);

        var acts = document.createElement('div');
        acts.className = 'jn-a';
        (spec.buttons || []).forEach(function (b) {
            var btn = document.createElement('button');
            btn.type = 'button';
            if (b.primary) btn.className = 'jn-p';
            btn.textContent = b.label;
            btn.addEventListener('click', b.onClick);
            acts.appendChild(btn);
        });
        col.appendChild(acts);
        el.appendChild(col);

        var x = document.createElement('button');
        x.className = 'jn-x';
        x.type = 'button';
        x.setAttribute('aria-label', spec.dismissLabel || 'Close');
        x.appendChild(icon('i-close'));
        x.addEventListener('click', function () {
            if (spec.onDismiss) spec.onDismiss();
            closeCard();
        });
        el.appendChild(x);

        document.body.appendChild(el);
        card = el;
        requestAnimationFrame(function () { el.classList.add('on'); });
        return el;
    }

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
     * user gesture and both ignore a request made without one. Push subscribes
     * from inside that same gesture's promise; there is not a second ask anywhere.
     */
    function offerPermission() {
        if (card || !askable()) return;
        // Never two promotions at once. The install card is the same layer and got
        // there first; this one waits for the next arrival.
        if (document.getElementById('jmInstall')) return;

        /* iOS in a Safari tab has no PushManager at all, and it does not appear
         * when permission is granted; it appears when the site is opened from the
         * Home Screen. That has been true since Web Push landed in iOS 16.4 and
         * neither Declarative Web Push in 18.4 nor the standalone-by-default
         * change in iOS 26 altered it.
         *
         * So on that platform this card must not be a switch. A switch that
         * appears to work, is tapped, and then never produces a notification is
         * the single worst outcome available here: the person concludes the
         * feature is broken and never comes back to it. It becomes an
         * instruction instead, and the poll keeps doing what it can, which is
         * stated rather than implied. */
        if (needsInstall()) {
            buildCard({
                icon: 'i-share-ios',
                title: 'Notifications need Jarurat Mail on your Home Screen',
                label: 'How to turn on notifications on iPhone',
                text: 'Tap Share, then Add to Home Screen, then open Jarurat Mail '
                    + 'from there and turn this on. Safari cannot show notifications '
                    + 'from a tab. While this tab is open, new mail still updates '
                    + 'the count on the icon.',
                dismissLabel: 'Not now',
                onDismiss: snoozeAsk,
                buttons: [
                    { label: 'Got it', primary: true, onClick: function () { snoozeAsk(); closeCard(); } }
                ]
            });
            return;
        }

        buildCard({
            icon: 'i-bell',
            title: 'Tell you when someone replies?',
            label: 'Notify me about new mail',
            /* The volume limit and the quiet hours are stated BEFORE the button,
               because the fear that drives a refusal is being interrupted all
               evening, and answering it after the button is answering it too
               late. Everything this sentence promises is enforced in sw.js: the
               sound floor, the silent lane, and the fact that most mail never
               makes a noise at all. */
            text: 'We will notify you when someone writes to you directly or replies '
                + 'to a thread you are in. Everything else arrives quietly. '
                + (S.push.supported
                    ? 'This works with the app shut.'
                    : 'This works while the mailbox is open in a tab.'),
            dismissLabel: 'Not now',
            onDismiss: snoozeAsk,
            buttons: [
                {
                    label: 'Yes, notify me', primary: true, onClick: function () {
                        closeCard();
                        snoozeAsk();
                        request();
                    }
                },
                { label: 'Not now', onClick: function () { snoozeAsk(); closeCard(); } }
            ]
        });
    }

    /**
     * The guarded-open prompt from a notification click with a dirty composer.
     *
     * This deliberately does not save the draft first and then navigate. Saving
     * would mean this file POSTing a compose payload it does not own the shape
     * of, from a background event, and getting that wrong loses exactly the text
     * it exists to protect. Asking costs one tap and cannot lose anything, so
     * asking is what it does. The message is parked, not dropped: whichever
     * answer the person gives, nothing types itself over their letter.
     */
    function offerOpen(id) {
        if (card) closeCard();
        buildCard({
            icon: 'i-mail',
            title: 'Open the new message?',
            label: 'Open the message a notification was about',
            text: 'You have a message half written. Opening this one closes the '
                + 'composer, so nothing is opened until you say so.',
            dismissLabel: 'Keep writing',
            buttons: [
                {
                    label: 'Open it', primary: true, onClick: function () {
                        closeCard();
                        open(id);
                    }
                },
                { label: 'Keep writing', onClick: function () { closeCard(); } }
            ]
        });
    }


    /* ====================================================================
       Web Push.

       The division of labour, because it is not obvious from either file:
       this half asks, subscribes, and hands the subscription to the server.
       The server registers that same subscription with Stalwart as a JMAP
       PushSubscription. Stalwart encrypts and posts to Apple or Google when
       mail arrives. sw.js renders what comes out. Nothing in this file ever
       sees a key, a password or a payload.
       ==================================================================== */

    /** iPadOS 13 and later report themselves as a Mac, so touch points are the tell. */
    function isIos() {
        var ua = navigator.userAgent;
        return /iPad|iPhone|iPod/.test(ua)
            || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    }

    function standalone() {
        return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)
            || (window.matchMedia && window.matchMedia('(display-mode: fullscreen)').matches)
            || window.navigator.standalone === true;
    }

    /**
     * True on the one platform where the answer to "can this device receive a
     * notification" is not yes or no but "not until you install it".
     *
     * On iOS, PushManager is simply absent from a Safari tab. It is not a
     * permission that can be granted and it is not a capability that can be
     * polyfilled; the site has to be on the Home Screen and opened from there.
     * Checking for the missing PushManager as well as for iOS means a future
     * iOS that lifts the restriction stops showing the instruction on its own.
     */
    function needsInstall() {
        return isIos() && !standalone() && !('PushManager' in window);
    }

    /** Stable per browser profile, generated once, never sent anywhere else. */
    function deviceId() {
        var id = null;
        try { id = localStorage.getItem(DEVICE_KEY); } catch (e) { /* private mode */ }
        if (id) return id;
        if (window.crypto && crypto.randomUUID) id = crypto.randomUUID();
        else id = 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12);
        try { localStorage.setItem(DEVICE_KEY, id); } catch (e) { /* kept for this page only */ }
        return id;
    }

    /* subscribe() wants the raw 65-byte uncompressed P-256 point, not the
       base64url string the session document carries it as, and it rejects the
       string without saying why. Padding is restored first because atob refuses
       an unpadded input, which is the form the JMAP capability uses. */
    function keyBytes(b64) {
        var pad = '='.repeat((4 - (b64.length % 4)) % 4);
        var raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
        var out = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
        return out;
    }

    /** Keeps sw.js told who this device is; it has no page to ask when a push lands. */
    function tellWorker(extra) {
        if (!navigator.serviceWorker || !navigator.serviceWorker.controller) return;
        var msg = {
            type: 'jm-push-state',
            deviceClientId: deviceId(),
            mailbox: S.mailbox || '',
            applicationServerKey: S.push.key || ''
        };
        if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) msg[k] = extra[k];
        try { navigator.serviceWorker.controller.postMessage(msg); } catch (e) { /* gone */ }
    }

    /**
     * Asks the server what push can do here.
     *
     * Every failure answers the same way, with supported false: a 404 because
     * the server half is not deployed yet, a 409 because the mailbox is locked,
     * a 500, or no network at all. There is no state in which this file is
     * allowed to offer a switch it cannot honour, and the poll keeps working in
     * all of them, so failing closed costs nothing.
     */
    function loadPushConfig() {
        if (!('PushManager' in window) || !navigator.serviceWorker) {
            S.push.checked = true;
            return Promise.resolve(S.push);
        }
        /* The device id goes on the query string, and leaving it off is not a
           small omission: the endpoint answers about one device, so without it
           every answer comes back state "off" with no pushSeen, this file
           concludes push has never worked, and the poll never stands down even
           for somebody whose notifications are arriving perfectly. */
        return fetch(PUSH_CONFIG_URL + '?deviceClientId=' + encodeURIComponent(deviceId()), {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'Accept': 'application/json' }
        }).then(function (res) {
            if (!res.ok) return null;
            return res.json();
        }).then(function (cfg) {
            S.push.checked = true;
            if (!cfg || !cfg.supported || !cfg.applicationServerKey) return S.push;
            S.push.supported = true;
            S.push.key = cfg.applicationServerKey;
            S.push.emailPush = !!cfg.emailPush;
            S.push.state = cfg.state || 'off';
            S.push.seen = !!cfg.pushSeen;
            S.push.expiresAt = cfg.expiresAt || null;
            return S.push;
        }).catch(function () {
            S.push.checked = true;
            return S.push;
        });
    }

    /**
     * Creates or repairs the browser's push subscription and hands it over.
     *
     * Called from inside the permission gesture's promise on the first run, and
     * with no gesture at all on every later page load, which is correct and not
     * an oversight: a gesture is required to ASK for permission, never to use a
     * permission already granted. Without the second path a person who granted
     * last week and then had their subscription rotated would never get one
     * back, and nothing on screen would say so.
     *
     * The existing subscription is reused rather than replaced when its
     * applicationServerKey still matches the server's. Replacing it would change
     * the endpoint, and every endpoint change costs a round trip to Apple or
     * Google and a new row the server has to reconcile.
     */
    function subscribePush() {
        if (!S.push.supported || !S.push.key) return Promise.resolve(false);
        if (typeof Notification === 'undefined' || Notification.permission !== 'granted') {
            return Promise.resolve(false);
        }
        if (!navigator.serviceWorker || !('PushManager' in window)) return Promise.resolve(false);

        var wanted;
        try { wanted = keyBytes(S.push.key); } catch (e) { return Promise.resolve(false); }

        return navigator.serviceWorker.ready.then(function (reg) {
            return reg.pushManager.getSubscription().then(function (existing) {
                if (!existing) {
                    return reg.pushManager.subscribe({
                        userVisibleOnly: true,
                        applicationServerKey: wanted
                    });
                }
                var had = existing.options && existing.options.applicationServerKey;
                var same = had && new Uint8Array(had).length === wanted.length
                    && new Uint8Array(had).every(function (b, i) { return b === wanted[i]; });
                if (same) return existing;
                // The server rotated its VAPID key, so this subscription can
                // never be delivered to again and keeping it would be keeping a
                // dead endpoint that looks alive.
                return existing.unsubscribe().catch(function () { return null; }).then(function () {
                    return reg.pushManager.subscribe({
                        userVisibleOnly: true,
                        applicationServerKey: wanted
                    });
                });
            });
        }).then(function (sub) {
            if (!sub) return false;
            var json = sub.toJSON();
            S.push.endpoint = json.endpoint;
            tellWorker();
            return fetch(PUSH_SUBSCRIBE_URL, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    'X-XSRF-TOKEN': csrfToken()
                },
                body: JSON.stringify({
                    deviceClientId: deviceId(),
                    endpoint: json.endpoint,
                    keys: json.keys
                })
            }).then(function (res) {
                if (!res.ok) return false;
                return res.json().catch(function () { return null; });
            }).then(function (cfg) {
                if (cfg) {
                    S.push.state = cfg.state || S.push.state;
                    S.push.seen = !!cfg.pushSeen;
                    S.push.expiresAt = cfg.expiresAt || S.push.expiresAt;
                }
                tellWorker({ pushSeen: S.push.seen });
                // The interval changes the moment a push has been proved, so the
                // timer is re-armed rather than left to expire at the old rate.
                schedule(interval());
                return true;
            });
        }).catch(function () {
            // A refused subscribe, an unreachable server, a worker that never
            // became ready. The poll is untouched by all of it and the person
            // sees the badge exactly as before.
            return false;
        });
    }

    /**
     * Takes this device's subscription away, at both ends.
     *
     * Exported rather than wired to stop(). stop() runs when the console session
     * dies, which happens on its own after eight hours of inactivity, and pushing
     * a person off notifications because they went home for the night would be a
     * feature that switches itself off. Turning it off is a decision, so it needs
     * somebody to make it: a settings row calls this.
     */
    function disablePush() {
        var id = deviceId();
        var done = fetch(PUSH_SUBSCRIBE_URL, {
            method: 'DELETE',
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': csrfToken()
            },
            body: JSON.stringify({ deviceClientId: id })
        }).catch(function () { /* the row expires on its own within seven days */ });

        return done.then(function () {
            if (!navigator.serviceWorker || !('PushManager' in window)) return false;
            return navigator.serviceWorker.ready.then(function (reg) {
                return reg.pushManager.getSubscription();
            }).then(function (sub) {
                // The browser's own subscription goes too. Leaving it behind means
                // the push service keeps a live endpoint for a device the server has
                // forgotten, and the next subscribe would get a different endpoint
                // while the old one sat there being delivered to by nothing.
                return sub ? sub.unsubscribe() : false;
            }).catch(function () { return false; });
        }).then(function (gone) {
            S.push.endpoint = null;
            S.push.seen = false;
            S.push.state = 'off';
            tellWorker({ pushSeen: false });
            schedule(interval());     // straight back to the full-rate poll
            return gone;
        });
    }

    /**
     * Notices a permission changed outside this page.
     *
     * Somebody who denied once, was told to go into browser settings, and did
     * it, comes back to a tab that still believes it is denied. Without this
     * they turn the switch on, nothing happens, and they conclude the app is
     * broken, which is the commonest way a recovery instruction fails. Three
     * lines, and it closes that loop before they have finished putting the
     * phone down.
     */
    function watchPermission() {
        if (!navigator.permissions || !navigator.permissions.query) return;
        try {
            navigator.permissions.query({ name: 'notifications' }).then(function (status) {
                status.onchange = function () {
                    if (status.state === 'granted') subscribePush();
                    if (status.state === 'denied') {
                        S.push.endpoint = null;
                        schedule(interval());
                    }
                };
            }).catch(function () { /* the query name is not recognised here */ });
        } catch (e) { /* older Safari */ }
    }

    /**
     * The whole push bootstrap, run once per page load.
     *
     * Ordered so that nothing asks for anything: the config call is a GET, and
     * subscribePush only proceeds on a permission that already exists. A person
     * who has never granted anything sees exactly the same page they saw before
     * this feature existed.
     */
    function bootPush() {
        loadPushConfig().then(function () {
            if (!S.push.supported) return;
            tellWorker({ pushSeen: S.push.seen });
            if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
                subscribePush();
            }
        });
        watchPermission();
    }

    /**
     * Raises the browser's own permission prompt. Must be called from inside a
     * click handler or the browsers that require a gesture drop it on the floor,
     * which is why this is not exported as something a timer could reach.
     *
     * The push subscription is made from inside this promise and never from a
     * second button. One deliberate gesture buys both, because they are one
     * decision as far as the person is concerned, and a second switch labelled
     * something like "also on my phone" would be a second thing to refuse.
     */
    function request() {
        if (typeof Notification === 'undefined') return;
        try {
            var answer = Notification.requestPermission(function () { /* old callback form */ });
            if (answer && answer.then) {
                answer.then(function (result) {
                    if (result !== 'granted') return;
                    if (S.push.checked) { subscribePush(); return; }
                    // The gesture came before the config answer, which happens on
                    // a slow first load. Waiting for it here rather than giving up
                    // is what keeps that race from costing the whole feature.
                    loadPushConfig().then(subscribePush);
                }).catch(function () { /* dismissed */ });
            }
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
            bootPush();
            schedule(FIRST_MS);
        },
        /** For a sign-out path that wants the timer gone before the page unloads. */
        stop: stop,
        /** Must be called from a click. Exported so a settings row could offer it too. */
        enable: request,
        /** Turns push off for this device, at both ends. Safe to call twice. */
        disable: disablePush,
        /**
         * Everything a settings row needs to describe the true state, including
         * the three that are not a boolean: the browser has blocked us, this
         * iPhone needs the app installed first, and push is subscribed but has
         * never actually delivered anything. mailsettings.js owns that row and
         * this file does not draw one; what it can do is refuse to let that row
         * be written from a guess.
         */
        state: function () {
            return {
                unread: S.unread,
                stopped: S.stopped,
                paused: S.paused,
                permission: typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
                pushSupported: S.push.supported,
                pushSubscribed: !!S.push.endpoint,
                /* Whether the mail server puts the sender and subject inside the
                   payload or only says that something arrived. Not a failure
                   either way, but a materially less useful notification, and the
                   settings row should be able to say which one this is. */
                pushCarriesContent: S.push.emailPush,
                pushProved: pushWorking(),
                pushExpiresAt: S.push.expiresAt,
                installRequired: needsInstall(),
                pollEveryMs: interval()
            };
        }
    };

    window.jmNotify.start();
})();
