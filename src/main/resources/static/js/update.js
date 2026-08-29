/* Tells you when a new version has arrived, and installs it on one tap.
 *
 * WHY THIS EXISTS. A service worker that is already installed keeps running its old
 * script until every tab for the site is closed, which for an app added to a home
 * screen can be weeks. So a deploy would land on the server, the person would open the
 * app, and they would still be running the previous JavaScript. That happened here
 * repeatedly: fixes were shipped, verified live with curl, and simply not visible to
 * the person who asked for them, and the only reliable cure anybody knew was to delete
 * the app from the home screen and add it again. Asking somebody to reinstall an app to
 * pick up a bug fix is not a workaround, it is a defect with a manual step attached.
 *
 * WHAT IT DOES. Watches the registration for a worker that has installed and is waiting,
 * shows one unobtrusive bar, and on tap tells that worker to take over and reloads. It
 * also offers the same thing on demand, because somebody who has been told a fix is out
 * should not have to wait for a background check to notice.
 *
 * THE RELOAD IS GUARDED. controllerchange fires once when the new worker takes control,
 * and reloading from it without a flag is the classic way to build an infinite reload
 * loop, because the new controller can claim a page that is already current. The flag is
 * the whole reason this is not that.
 *
 * NOT SHOWN WHILE SOMETHING IS UNSAVED. A reload throws away a half written message, and
 * the update is never urgent enough to be worth that. The bar waits.
 */
(function () {
    'use strict';

    if (!('serviceWorker' in navigator)) return;

    var BAR_ID = 'jmUpdateBar';
    var CHECK_EVERY_MS = 30 * 60 * 1000;   // half an hour, so a long-lived tab notices
    var reloading = false;
    var waiting = null;

    /* Something the person would lose. mail.js owns the composer, so this asks it
       rather than reaching into its internals, and treats "no answer" as safe. */
    function busy() {
        try {
            if (window.composeHasContent && window.composeHasContent()) return true;
        } catch (e) { /* the mailbox is not on this page */ }
        var open = document.querySelector('[aria-modal="true"]');
        if (open) {
            var r = open.getBoundingClientRect();
            if (r.width > 0 && r.height > 0) return true;
        }
        return false;
    }

    function css() {
        if (document.getElementById('jmUpdateCss')) return;
        var s = document.createElement('style');
        s.id = 'jmUpdateCss';
        /* Written out rather than taken from style.css, because mail.html loads no
           stylesheet at all and this bar has to look the same on every page. */
        s.textContent = [
            '#' + BAR_ID + '{position:fixed;z-index:190;left:12px;right:12px;',
            '  bottom:calc(12px + env(safe-area-inset-bottom,0px));',
            '  background:#202020;color:#ededed;border:1px solid rgba(255,255,255,.14);',
            '  border-radius:12px;padding:12px 14px;box-shadow:0 12px 34px rgba(0,0,0,.5);',
            '  display:flex;gap:12px;align-items:center;',
            '  font:13.5px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;',
            '  transform:translateY(150%);transition:transform .2s cubic-bezier(.2,.7,.3,1)}',
            '#' + BAR_ID + '.on{transform:translateY(0)}',
            '@media(prefers-reduced-motion:reduce){#' + BAR_ID + '{transition:none}}',
            '@media(min-width:640px){#' + BAR_ID + '{left:auto;right:18px;width:380px}}',
            '#' + BAR_ID + ' p{margin:0;flex:1 1 auto;min-width:0}',
            '#' + BAR_ID + ' b{display:block;font-weight:620}',
            '#' + BAR_ID + ' small{display:block;color:#b9b9b9;font-size:12.5px}',
            '#' + BAR_ID + ' button{font:inherit;font-size:13px;font-weight:600;cursor:pointer;',
            '  min-height:44px;padding:0 14px;border-radius:8px;border:1px solid transparent;',
            '  background:#2f6fed;color:#fff;flex:none}',
            '#' + BAR_ID + ' button.later{background:transparent;color:#b9b9b9;',
            '  border-color:rgba(255,255,255,.14);min-width:44px}',
            '#' + BAR_ID + ' button:focus-visible{outline:2px solid #7aa8ff;outline-offset:2px}'
        ].join('');
        document.head.appendChild(s);
    }

    function hide() {
        var bar = document.getElementById(BAR_ID);
        if (!bar) return;
        bar.classList.remove('on');
        setTimeout(function () { if (bar.parentNode) bar.remove(); }, 250);
    }

    function show() {
        if (document.getElementById(BAR_ID) || !waiting) return;
        if (busy()) { setTimeout(show, 20000); return; }
        css();

        var bar = document.createElement('div');
        bar.id = BAR_ID;
        bar.setAttribute('role', 'status');

        var text = document.createElement('p');
        var b = document.createElement('b');
        b.textContent = 'A new version is ready';
        var s = document.createElement('small');
        s.textContent = 'Reload to get the latest fixes.';
        text.appendChild(b);
        text.appendChild(s);

        var go = document.createElement('button');
        go.type = 'button';
        go.textContent = 'Reload';
        go.addEventListener('click', applyNow);

        var later = document.createElement('button');
        later.type = 'button';
        later.className = 'later';
        later.setAttribute('aria-label', 'Not now');
        later.textContent = 'Later';
        later.addEventListener('click', hide);

        bar.appendChild(text);
        bar.appendChild(go);
        bar.appendChild(later);
        document.body.appendChild(bar);
        requestAnimationFrame(function () { bar.classList.add('on'); });
    }

    /* Tell the waiting worker to take over. The reload comes from controllerchange
       rather than from here, because reloading before it has claimed the page would
       load the old script one more time. */
    function applyNow() {
        if (!waiting) { hardReload(); return; }
        try { waiting.postMessage('skipWaiting'); } catch (e) { hardReload(); }
        setTimeout(function () { if (!reloading) hardReload(); }, 2500);
    }

    function hardReload() {
        if (reloading) return;
        reloading = true;
        window.location.reload();
    }

    function track(reg) {
        if (reg.waiting) { waiting = reg.waiting; show(); }

        reg.addEventListener('updatefound', function () {
            var incoming = reg.installing;
            if (!incoming) return;
            incoming.addEventListener('statechange', function () {
                // "installed" with a controller already present means an UPDATE, as
                // opposed to the very first install, where there is nothing to announce.
                if (incoming.state === 'installed' && navigator.serviceWorker.controller) {
                    waiting = incoming;
                    show();
                }
            });
        });
    }

    navigator.serviceWorker.ready.then(function (reg) {
        track(reg);
        // A check now and then, so a tab left open for a day still finds out.
        setInterval(function () { reg.update().catch(function () {}); }, CHECK_EVERY_MS);
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) reg.update().catch(function () {});
        });
    }).catch(function () { /* no worker; nothing to update */ });

    navigator.serviceWorker.addEventListener('controllerchange', function () {
        hardReload();
    });

    /* The manual route, for anybody told a fix is out. Any element carrying
       data-jm-update drives it, the same delegation pwa.js uses for install. */
    document.addEventListener('click', function (e) {
        var t = e.target.closest && e.target.closest('[data-jm-update]');
        if (!t) return;
        e.preventDefault();
        navigator.serviceWorker.ready.then(function (reg) {
            return reg.update().then(function () {
                if (reg.waiting) { waiting = reg.waiting; applyNow(); return; }
                // Already current. Say so rather than leaving the tap unanswered, and
                // reload anyway so a stale PAGE, which the worker does not cache, is
                // refreshed too.
                if (window.toast) window.toast('You are on the latest version');
                setTimeout(hardReload, 400);
            });
        }).catch(hardReload);
    });

    window.jmUpdate = {
        check: function () {
            return navigator.serviceWorker.ready.then(function (reg) { return reg.update(); });
        },
        apply: applyNow,
        pending: function () { return !!waiting; }
    };
})();
