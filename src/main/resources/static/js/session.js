/* Session lifetime for Jarurat Mail.
 *
 * The sign-in ends after eight hours of inactivity and until now nothing said so.
 * The first sign was a form post landing silently on the login page, which on a
 * campaign somebody had been composing all afternoon cost the afternoon. This file
 * puts a countdown behind that, warns a few minutes out with a way to stay, and when
 * it really is over says so instead of leaving a page whose next click fails.
 *
 * Three things shape how it is written.
 *
 * It does not poll on a timer. GET /api/session hands back an absolute expiry and the
 * server's own clock; everything after that is arithmetic in this file. The server is
 * asked again only on load, when the tab becomes visible after half a minute away,
 * when the browser comes back online, and at most once a minute once the countdown is
 * inside the warning band. A tab left open all day therefore costs a handful of
 * requests, not one a minute - which matters beyond politeness, because a request a
 * minute would itself keep the session alive and the countdown would be a lie. The
 * endpoint refuses to count its own reads as activity for the same reason; this side
 * simply must not lean on that.
 *
 * The countdown is anchored to the server's clock, not the browser's. The response
 * carries serverTime, the difference is kept, and every later reading subtracts it. A
 * phone four minutes fast would otherwise show four minutes less than it has.
 *
 * It survives a sleeping phone. A device that was shut for two hours wakes with
 * throttled timers and a stale idea of the time, so visibilitychange and the bfcache
 * pageshow both re-read the deadline before anything is shown.
 *
 * The markup and the styling are here rather than in a template and style.css
 * because, exactly as with pwa.js, this surface has to look the same on the console
 * and the mailbox and only some of those pages load style.css. The tokens on
 * #jmSession are copied from docs/UI-SPEC.md sections 2 to 8; this file cannot assume
 * the page it landed on defines any of them.
 */
(function () {
    'use strict';

    /* The contract path, and the same endpoint under the mail prefix. SecurityConfig
       closes everything outside MAIL_ONLY_PATHS to a session bought with a mailbox
       password, so on the phone mailbox the first path answers 403 and the second is
       the one that works. Which one is live is discovered once and then remembered. */
    var PATH = '/api/session';
    var MAIL_PATH = '/api/mail/session';

    /* Ask the server again at most this often while the warning is up. */
    var RESYNC_MS = 60000;
    /* Coming back to the tab after longer than this is worth a fresh reading. */
    var STALE_MS = 30000;
    /* Never sleep longer than this between recalculations, so a browser that
       throttled or discarded a long timer still gets a chance to catch up. No request
       is made when one of these fires outside the warning band; it is arithmetic. */
    var MAX_SLEEP_MS = 300000;
    /* Dismissing the warning buys quiet until one minute is left. That last one is
       not dismissible twice: somebody who waves it away at that point has decided. */
    var LAST_CALL = 60;

    var endpoint = PATH;
    var triedAlias = false;
    var syncing = false;

    var expiresAt = 0;      // epoch ms, on the server's clock
    var skew = 0;           // serverTime minus this browser's clock at the last reading
    var warnSec = 300;
    var timeoutSec = 0;
    var lastSync = 0;
    var timer = null;
    var mode = 'live';      // live, expired, or off
    var reshowAt = Infinity;
    var expiredHidden = false;

    function left() {
        if (!expiresAt) return Infinity;
        return Math.round((expiresAt - (Date.now() + skew)) / 1000);
    }

    /* ---------- icons ---------- */

    var SVG_NS = 'http://www.w3.org/2000/svg';

    /* Geometry copied verbatim from the matching <symbol> in
       templates/fragments/icons.html. Verbatim matters for the same reason it does in
       pwa.js: this dialog appears on pages that do not carry the sprite, and an icon
       that changed shape depending on which page you were standing on would be worse
       than either version on its own. Element type is carried alongside the
       attributes because i-clock is a circle and a path, and a fallback that only
       knew how to draw paths would render the hand with no face. */
    var INLINE = {
        'i-clock': [
            ['circle', { cx: '12', cy: '12', r: '8.6' }],
            ['path', { d: 'M12 7.2V12l3.2 1.9' }]
        ],
        'i-close': [
            ['path', { d: 'M6 6l12 12M18 6 6 18' }]
        ]
    };

    /* A <use> pointing at a symbol that is not in the document renders nothing at all
       and reports no error, so the sprite is tested for rather than assumed. */
    function icon(id) {
        var svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('class', 'jm-ic');
        svg.setAttribute('aria-hidden', 'true');
        if (document.getElementById(id)) {
            var use = document.createElementNS(SVG_NS, 'use');
            use.setAttribute('href', '#' + id);
            svg.appendChild(use);
            return svg;
        }
        svg.setAttribute('viewBox', '0 0 24 24');
        (INLINE[id] || []).forEach(function (part) {
            var node = document.createElementNS(SVG_NS, part[0]);
            Object.keys(part[1]).forEach(function (k) { node.setAttribute(k, part[1][k]); });
            svg.appendChild(node);
        });
        return svg;
    }

    /* ---------- styling ---------- */

    function css() {
        if (document.getElementById('jmSessionCss')) return;
        var s = document.createElement('style');
        s.id = 'jmSessionCss';
        s.textContent = [
            /* 140 and 150 are the two rows the stacking table in UI-SPEC section 15
               reserves for a scrim and for anything carrying aria-modal. The install
               card sits at 100 on purpose so it cannot cover this, and pwa.js reads
               that same attribute to know a dialog is open. Neither number is a
               preference and neither may drift. */
            '#jmSessionScrim{position:fixed;inset:0;z-index:140;background:rgba(0,0,0,.55);',
            '  opacity:0;transition:opacity 200ms cubic-bezier(.2,.7,.3,1)}',
            '#jmSessionScrim.on{opacity:1}',

            '#jmSession{',
            '  --jm-panel:#202020;--jm-panel-2:#252525;--jm-panel-3:#2c2c2c;--jm-panel-4:#343434;',
            '  --jm-border:rgba(255,255,255,.075);--jm-border-strong:rgba(255,255,255,.14);',
            '  --jm-text:#ededed;--jm-dim:#b9b9b9;--jm-mute:#949494;',
            '  --jm-primary:#2f6fed;--jm-primary-hover:#3a7bf5;--jm-warning-fg:#e3b341;',
            '  --jm-e3:0 12px 34px rgba(0,0,0,.50);',
            '  --jm-t-fast:120ms cubic-bezier(.2,.7,.3,1);',
            '  --jm-t-base:200ms cubic-bezier(.2,.7,.3,1);',
            '  position:fixed;z-index:150;',
            /* Phone first: a sheet on the bottom edge, clear of the home indicator.
               The five bottom tabs sit at 60 and this is above them by design. */
            '  left:12px;right:12px;bottom:calc(12px + env(safe-area-inset-bottom, 0px));',
            '  background:var(--jm-panel);color:var(--jm-text);',
            '  border:1px solid var(--jm-border-strong);border-radius:16px;',
            '  padding:16px;box-shadow:var(--jm-e3);',
            '  font:13.5px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;',
            '  transform:translateY(120%);opacity:0;',
            '  transition:transform var(--jm-t-base),opacity var(--jm-t-base)}',
            /* Each host page owns its own reset and two of the four set nothing. */
            '#jmSession,#jmSession *{box-sizing:border-box}',
            '#jmSession.on{transform:translateY(0);opacity:1}',
            /* Above 640 it becomes a centred modal. The enter transform has to carry
               the centring translate with it or the dialog animates in from the
               corner, and only transform and opacity are animated, per section 7. */
            '@media(min-width:640px){',
            '  #jmSession{left:50%;right:auto;top:50%;bottom:auto;',
            '    width:min(420px,calc(100vw - 32px));padding:20px;',
            '    transform:translate(-50%,calc(-50% + 10px)) scale(.985)}',
            '  #jmSession.on{transform:translate(-50%,-50%) scale(1)}}',
            '@media(prefers-reduced-motion:reduce){',
            '  #jmSession,#jmSessionScrim{transition:none}}',

            '#jmSession .jm-ic{width:18px;height:18px;flex:none;fill:none;stroke:currentColor;',
            '  stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round;pointer-events:none}',

            '#jmSession .jm-head{display:flex;gap:12px;align-items:flex-start}',
            '#jmSession .jm-badge{flex:0 0 auto;width:36px;height:36px;border-radius:999px;',
            '  background:var(--jm-panel-3);border:1px solid var(--jm-border);',
            '  color:var(--jm-warning-fg);display:grid;place-items:center}',
            '#jmSession .jm-badge .jm-ic{width:20px;height:20px}',
            '#jmSession .jm-c{flex:1 1 auto;min-width:0}',
            /* 34px clears the close button, which is a 44px hit area pulled up into
               the padding. Only the two head lines need it. */
            '#jmSession .jm-t{margin:0 0 4px;padding-right:34px;font-size:16px;line-height:1.3;',
            '  font-weight:620;letter-spacing:-.01em}',
            '#jmSession .jm-s{margin:0;padding-right:34px;color:var(--jm-dim);font-size:12.5px;',
            '  line-height:1.45}',

            /* Tabular figures, so the seconds column does not jitter the whole line
               once a second for five minutes. */
            '#jmSession .jm-count{margin:16px 0 0;font-size:30px;line-height:1.15;font-weight:700;',
            '  letter-spacing:-.03em;color:var(--jm-warning-fg);font-variant-numeric:tabular-nums}',
            '#jmSession .jm-count small{display:block;margin-top:2px;font-size:11.5px;line-height:1.4;',
            '  font-weight:500;letter-spacing:0;color:var(--jm-mute);font-variant-numeric:normal}',

            '#jmSession .jm-a{display:flex;gap:8px;margin-top:16px;flex-wrap:wrap}',
            '#jmSession button{font:inherit;font-size:13.5px;font-weight:600;cursor:pointer;',
            '  min-height:44px;padding:0 16px;border-radius:8px;',
            '  border:1px solid var(--jm-border-strong);',
            '  background:var(--jm-panel-3);color:var(--jm-text);',
            '  -webkit-tap-highlight-color:transparent;touch-action:manipulation;',
            '  transition:background var(--jm-t-fast),border-color var(--jm-t-fast)}',
            '#jmSession button:hover{background:var(--jm-panel-4)}',
            '#jmSession button:active{background:var(--jm-panel-2)}',
            '#jmSession button[disabled]{cursor:default;opacity:.62}',
            '#jmSession button.jm-p{background:var(--jm-primary);border-color:var(--jm-primary);color:#fff}',
            '#jmSession button.jm-p:hover{background:var(--jm-primary-hover);',
            '  border-color:var(--jm-primary-hover)}',
            '#jmSession button.jm-p[disabled]{background:var(--jm-primary);',
            '  border-color:var(--jm-primary)}',
            '#jmSession button:focus-visible{outline:2px solid var(--jm-primary);outline-offset:2px}',
            '@media(prefers-reduced-motion:reduce){#jmSession button{transition:none}}',

            '#jmSession .jm-x{position:absolute;top:6px;right:6px;width:44px;height:44px;',
            '  min-height:44px;padding:0;border:0;border-radius:8px;background:none;',
            '  color:var(--jm-mute);display:grid;place-items:center}',
            '#jmSession .jm-x:hover{background:var(--jm-panel-3);color:var(--jm-text)}',
            '#jmSession .jm-x:active{background:var(--jm-panel-2)}',

            /* The countdown redraws every second. Announcing that would talk over a
               screen reader continuously, so the visible figure is hidden from the
               accessibility tree and this line carries a coarse version that only
               changes when the minute does. */
            '#jmSession .jm-sr{position:absolute;width:1px;height:1px;margin:-1px;padding:0;',
            '  overflow:hidden;clip:rect(0 0 0 0);clip-path:inset(50%);white-space:nowrap;border:0}'
        ].join('');
        document.head.appendChild(s);
    }

    /* ---------- the dialog ---------- */

    var dlg = null, scrim = null, elTitle = null, elBody = null, elCount = null,
        elUnder = null, elSr = null, elPrimary = null, elSecondary = null, elClose = null;
    var lastReturn = null;
    var srMinutes = -1;
    /* The warning redraws once a second, so anything a click put on screen has to
       survive that redraw or it would be wiped a fraction of a second after it
       appeared. These two are what the redraw is told to leave alone: an extend in
       flight owns the primary button, and a message about a failure owns the body. */
    var pending = false;
    var note = '';
    var retries = 0;

    function build() {
        css();
        scrim = document.createElement('div');
        scrim.id = 'jmSessionScrim';

        dlg = document.createElement('div');
        dlg.id = 'jmSession';
        dlg.setAttribute('role', 'dialog');
        // Read by pwa.js to know a dialog is open, and the reason the install card
        // waits its turn instead of painting over this.
        dlg.setAttribute('aria-modal', 'true');
        dlg.setAttribute('aria-labelledby', 'jmSessionTitle');
        dlg.setAttribute('aria-describedby', 'jmSessionBody');
        dlg.tabIndex = -1;
        // Kept inline as well as in the stylesheet: the close button is positioned
        // against this element, so a dialog that ever fell back to static would fling
        // its own close control into the corner of the page.
        dlg.style.position = 'fixed';

        var head = document.createElement('div');
        head.className = 'jm-head';

        var badge = document.createElement('span');
        badge.className = 'jm-badge';
        badge.appendChild(icon('i-clock'));
        head.appendChild(badge);

        var col = document.createElement('div');
        col.className = 'jm-c';
        elTitle = document.createElement('p');
        elTitle.className = 'jm-t';
        elTitle.id = 'jmSessionTitle';
        elBody = document.createElement('p');
        elBody.className = 'jm-s';
        elBody.id = 'jmSessionBody';
        col.appendChild(elTitle);
        col.appendChild(elBody);
        head.appendChild(col);
        dlg.appendChild(head);

        elCount = document.createElement('p');
        elCount.className = 'jm-count';
        elCount.setAttribute('aria-hidden', 'true');
        elUnder = document.createElement('small');
        elCount.appendChild(document.createTextNode(''));
        elCount.appendChild(elUnder);
        dlg.appendChild(elCount);

        elSr = document.createElement('span');
        elSr.className = 'jm-sr';
        elSr.setAttribute('aria-live', 'polite');
        dlg.appendChild(elSr);

        var acts = document.createElement('div');
        acts.className = 'jm-a';
        elPrimary = document.createElement('button');
        elPrimary.className = 'jm-p';
        elPrimary.type = 'button';
        elSecondary = document.createElement('button');
        elSecondary.type = 'button';
        acts.appendChild(elPrimary);
        acts.appendChild(elSecondary);
        dlg.appendChild(acts);

        elClose = document.createElement('button');
        elClose.className = 'jm-x';
        elClose.type = 'button';
        elClose.setAttribute('aria-label', 'Dismiss');
        elClose.appendChild(icon('i-close'));
        elClose.addEventListener('click', dismiss);
        dlg.appendChild(elClose);

        // Tab must not walk out of an aria-modal dialog into the page behind it.
        // Every focusable thing in here is a button, so the selector is complete.
        // The element is captured rather than read off the module variable, which
        // close() sets to null and a second warning would point at a different node.
        var mine = dlg;
        mine.addEventListener('keydown', function (e) {
            if (e.key !== 'Tab') return;
            var f = mine.querySelectorAll('button:not([disabled])');
            if (!f.length) return;
            var first = f[0], last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        });

        document.body.appendChild(scrim);
        document.body.appendChild(dlg);
    }

    function open() {
        return dlg !== null && dlg.parentNode !== null;
    }

    function show() {
        if (open()) return;
        lastReturn = document.activeElement;
        build();
        requestAnimationFrame(function () {
            if (!dlg) return;
            dlg.classList.add('on');
            scrim.classList.add('on');
            // The primary action, so Enter does the reassuring thing rather than the
            // destructive one.
            elPrimary.focus();
        });
    }

    function close() {
        if (!open()) return;
        var goneDlg = dlg, goneScrim = scrim;
        dlg = null; scrim = null;
        goneDlg.classList.remove('on');
        goneScrim.classList.remove('on');
        // The fading copies keep their ids until they are removed, and a second
        // #jmSession in the document is one that getElementById resolves the wrong
        // way, so the id goes first.
        goneDlg.removeAttribute('id');
        goneScrim.removeAttribute('id');
        setTimeout(function () { goneDlg.remove(); goneScrim.remove(); }, 260);
        if (lastReturn && lastReturn.focus) {
            try { lastReturn.focus(); } catch (e) { /* gone from the document */ }
        }
        lastReturn = null;
        srMinutes = -1;
    }

    /* Tab cycling inside the dialog is handled on the dialog itself, but focus can
       also leave it sideways: clicking the scrim drops the active element to the
       body, and the next Tab would then walk into the page behind a modal. Pulling it
       back is the half of the trap that has to live on the document. */
    document.addEventListener('focusin', function (e) {
        if (!open() || dlg.contains(e.target)) return;
        if (elPrimary) elPrimary.focus();
    });

    function dismiss() {
        if (mode === 'expired') { expiredHidden = true; close(); return; }
        var r = left();
        // One reprieve, at one minute. Waving that one away means it stays away.
        reshowAt = r > LAST_CALL ? LAST_CALL : -1;
        close();
    }

    /* Escape closes every dialog in this product, which UI-SPEC section 15 states as
       a rule and pwa.js depends on: it synthesises this exact event on document to
       clear the way before showing the install card. Listening on document rather
       than on the dialog is what makes the synthetic one arrive. */
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape' && e.key !== 'Esc') return;
        if (!open()) return;
        e.stopPropagation();
        dismiss();
    });

    /* ---------- wording ---------- */

    function clock(sec) {
        if (sec < 0) sec = 0;
        var m = Math.floor(sec / 60), s = sec % 60;
        if (m >= 60) {
            var h = Math.floor(m / 60);
            return h + ':' + pad(m % 60) + ':' + pad(s);
        }
        return m + ':' + pad(s);
    }

    function pad(n) { return n < 10 ? '0' + n : String(n); }

    function words(sec) {
        var m = Math.max(0, Math.round(sec / 60));
        if (m <= 1) return 'less than a minute left';
        return m + ' minutes left';
    }

    /* The configured window, said the way a person would. The number comes from the
       server so it is never a second copy of server.servlet.session.timeout, and it
       is empty rather than invented when the first thing this page ever heard back
       was a 401, because a dead session carries no configuration to report. */
    function windowText() {
        if (!timeoutSec) return '';
        if (timeoutSec >= 5400) {
            var h = Math.round(timeoutSec / 3600);
            return h + (h === 1 ? ' hour' : ' hours');
        }
        var m = Math.max(1, Math.round(timeoutSec / 60));
        return m + (m === 1 ? ' minute' : ' minutes');
    }

    /* ---------- rendering ---------- */

    function renderWarning(r) {
        show();
        if (!dlg) return;
        elTitle.textContent = 'Your session is about to end';
        var window_ = windowText();
        elBody.textContent = note || ((window_ ? 'Sign-ins here last ' + window_ + ' and this one is'
            : 'This sign-in is') + ' nearly up. Stay signed in and nothing you have typed is lost.');
        elCount.firstChild.nodeValue = clock(r);
        elUnder.textContent = 'until you are signed out';
        elCount.style.display = '';

        var minutes = Math.max(0, Math.round(r / 60));
        if (minutes !== srMinutes) { srMinutes = minutes; elSr.textContent = words(r); }

        if (!pending) setPrimary(note ? 'Try again' : 'Stay signed in', extend);
        setSecondary('Sign out', function () { leave('/logout'); });
    }

    function renderExpired() {
        show();
        if (!dlg) return;
        elTitle.textContent = 'Your session ended';
        var span = windowText();
        elBody.textContent = (span ? span + ' went by without activity, so this sign-in is finished.'
            : 'This sign-in has finished after a spell without activity.')
            + ' Copy anything you still need off this page before you sign in again: signing in '
            + 'loads a new one.';
        elCount.style.display = 'none';
        if (srMinutes !== -2) { srMinutes = -2; elSr.textContent = 'Your session ended.'; }

        // Through /logout rather than straight to /login. The session may still be
        // resident here - this endpoint deliberately never destroys one - and /logout
        // is the path that already knows to drop the mailbox password out of the
        // server process on the way. It lands on /login?loggedOut either way.
        setPrimary('Sign in', function () { leave('/logout'); });
        setSecondary('Stay here', dismiss);
    }

    function setPrimary(label, fn) {
        elPrimary.disabled = false;
        elPrimary.textContent = label;
        elPrimary.onclick = fn;
    }

    function setSecondary(label, fn) {
        elSecondary.disabled = false;
        elSecondary.textContent = label;
        elSecondary.onclick = fn;
    }

    function leave(url) {
        // Nothing should tick or redraw while the browser is on its way out.
        mode = 'off';
        if (timer) clearTimeout(timer);
        location.href = url;
    }

    /* ---------- talking to the server ---------- */

    function csrfToken() {
        var m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function done() { syncing = false; }

    function sync() {
        if (syncing || mode === 'off') return;
        syncing = true;
        fetch(endpoint, {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json' }
        }).then(function (res) {
            lastSync = Date.now();
            if (res.status === 403) {
                // The console path is closed to a session bought with a mailbox
                // password. Try the mail-prefixed copy once, then give up quietly: a
                // countdown nobody can read the clock for is worse than none.
                if (endpoint === PATH && !triedAlias) {
                    triedAlias = true;
                    endpoint = MAIL_PATH;
                    syncing = false;
                    sync();
                    return;
                }
                mode = 'off';
                return;
            }
            if (res.status === 401) { expire(); return; }
            if (!res.ok) { recover(); return; }
            return res.json().then(apply);
        }).catch(function () {
            // Offline, or the box is down. Keep counting from the last known deadline
            // rather than claiming anything: erring towards the warning is the safe
            // direction, and claiming the session ended when the network blinked is not.
            lastSync = Date.now();
            recover();
        }).then(done, done);
    }

    /* A failed reading with a deadline already in hand is nothing: the countdown goes
       on from what it had. A failed first reading leaves nothing to count, and
       schedule() has no timer to set, so this is the only path that would otherwise
       leave the page with no session surface at all and never look again. Three tries
       and then it stays quiet, because a fourth would be a poll. */
    function recover() {
        if (expiresAt) { schedule(); return; }
        if (retries >= 3) return;
        retries++;
        if (timer) clearTimeout(timer);
        timer = setTimeout(sync, 30000);
    }

    function apply(state) {
        if (!state || !state.authenticated) { expire(); return; }
        skew = state.serverTime - Date.now();
        expiresAt = state.expiresAt || 0;
        warnSec = state.warnSeconds || warnSec;
        timeoutSec = state.timeoutSeconds || 0;
        mode = 'live';
        // The servlet specification's way of saying this session never times out.
        // There is nothing to count down to, so nothing is shown.
        if (!expiresAt) { mode = 'off'; close(); return; }
        tick();
    }

    function expire() {
        mode = 'expired';
        if (timer) { clearTimeout(timer); timer = null; }
        if (!expiredHidden) renderExpired();
    }

    /* Section 8 of the specification: an async action shows its pending state on the
       control that was pressed, not as a page-level spinner. The pending flag is also
       what stops the once-a-second redraw of the warning from putting the button back
       the way it was half a second after it was pressed. */
    function extend() {
        pending = true;
        note = '';
        elPrimary.disabled = true;
        elPrimary.textContent = 'Staying signed in';
        fetch(endpoint + '/extend', {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json', 'X-XSRF-TOKEN': csrfToken() }
        }).then(function (res) {
            lastSync = Date.now();
            pending = false;
            if (res.status === 401) { expire(); return; }
            if (!res.ok) throw new Error('extend refused');
            return res.json().then(function (state) {
                reshowAt = Infinity;
                expiredHidden = false;
                close();
                apply(state);
            });
        }).catch(function () {
            pending = false;
            if (mode !== 'live' || !open()) return;
            note = 'That did not reach the server. Try again, or sign out and back in to be certain.';
            elBody.textContent = note;
            setPrimary('Try again', extend);
        });
    }

    /* ---------- the clock ---------- */

    function schedule() {
        if (timer) clearTimeout(timer);
        timer = null;
        if (mode !== 'live' || !expiresAt) return;
        var r = left();
        var delay;
        if (open()) delay = 1000;
        else if (r > warnSec) delay = Math.min((r - warnSec) * 1000 + 250, MAX_SLEEP_MS);
        else delay = 1000;
        timer = setTimeout(tick, Math.max(250, delay));
    }

    function tick() {
        if (mode !== 'live' || !expiresAt) return;
        var r = left();

        if (r > warnSec) {
            // Comfortably alive. Anything showing is out of date, and a dismissal
            // made in an earlier warning band has been paid for by real activity.
            close();
            reshowAt = Infinity;
            note = '';
            schedule();
            return;
        }

        // Inside the band. Our own reading can be stale in the one direction that
        // matters, because work done in another tab moves the deadline out and
        // nothing tells this page. So the server is asked before anything is claimed,
        // and then not again for a minute.
        if (Date.now() - lastSync > RESYNC_MS) { sync(); return; }

        if (r <= 0) { expire(); return; }
        if (!open() && r > reshowAt) { schedule(); return; }
        renderWarning(r);
        schedule();
    }

    /* ---------- waking up ---------- */

    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState !== 'visible') return;
        // A phone that was asleep for two hours comes back with throttled timers and a
        // countdown that stopped where it was. Re-read before anything is shown.
        if (mode === 'expired') { expiredHidden = false; renderExpired(); return; }
        if (mode === 'off') return;
        if (Date.now() - lastSync > STALE_MS) sync();
        else tick();
    });

    // A back-forward-cache restore runs no script and fires no visibilitychange, so
    // the page can come back an hour later with every variable exactly as it was.
    window.addEventListener('pageshow', function (e) {
        if (!e.persisted) return;
        if (mode === 'off') return;
        sync();
    });

    window.addEventListener('online', function () {
        if (mode === 'off') return;
        sync();
    });

    function boot() {
        if (!window.fetch || !document.body) return;
        sync();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
