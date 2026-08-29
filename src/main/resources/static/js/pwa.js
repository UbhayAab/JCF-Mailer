/* Install-to-home-screen for Jarurat Mail.
 *
 * Two separate jobs, because the two mobile platforms do not agree on any of it:
 *
 *   Android / Chrome / Edge / Brave  fire `beforeinstallprompt`. We stop the mini
 *       infobar, keep the event, and show our own card. The card's button calls
 *       prompt() on that saved event. The event is single use: once prompt() has
 *       been called it cannot be called again, so it is dropped afterwards.
 *
 *   iOS Safari  has no such event and never will. The only route is Share ->
 *       Add to Home Screen, done by hand, so all we can do is say so clearly.
 *       There is also no way to detect whether they already did it, other than
 *       navigator.standalone once they open the installed copy.
 *
 * The card is styled here rather than in style.css because it has to render
 * identically on the login page, the landing page, the console and the mailbox,
 * and only two of those four load style.css. For the same reason the design
 * tokens are declared locally on #jmInstall: the values are copied from
 * docs/UI-SPEC.md sections 2, 3, 4, 5 and 7, and this file cannot assume the
 * page it landed on defines them.
 */
(function () {
    'use strict';

    var KEY = 'jm.install.dismissed';
    var SNOOZE_DAYS = 14;

    function installed() {
        return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)
            || (window.matchMedia && window.matchMedia('(display-mode: minimal-ui)').matches)
            || window.navigator.standalone === true
            || document.referrer.indexOf('android-app://') === 0;
    }

    function snoozed() {
        try {
            var t = parseInt(localStorage.getItem(KEY) || '0', 10);
            return t > 0 && (Date.now() - t) < SNOOZE_DAYS * 86400000;
        } catch (e) { return false; }
    }

    function snooze() {
        try { localStorage.setItem(KEY, String(Date.now())); } catch (e) { /* private mode */ }
    }

    function isIos() {
        var ua = navigator.userAgent;
        // iPadOS 13+ reports itself as a Mac, so the touch-point count is the tell.
        return /iPad|iPhone|iPod/.test(ua)
            || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    }

    /* On iOS only Safari can install. Chrome, Firefox and Edge on iOS are Safari
       underneath but their share sheets have no Add to Home Screen, so telling
       their users to look for it sends them hunting for something absent. */
    function isIosSafari() {
        var ua = navigator.userAgent;
        return isIos() && !/CriOS|FxiOS|EdgiOS|OPiOS|Chrome/.test(ua);
    }

    var SVG_NS = 'http://www.w3.org/2000/svg';

    /* Path data copied verbatim from the matching <symbol> in
       templates/fragments/icons.html. Verbatim matters: this card also appears on
       the login and landing pages, which do not include the sprite, and an icon
       that changed shape depending on which page you were standing on would be
       worse than either version on its own. */
    var INLINE = {
        'i-close': ['M6 6l12 12M18 6 6 18'],
        'i-check': ['m4.5 12.5 5 5 10-11']
    };

    /* A <use> pointing at a symbol that is not in the document renders nothing at
       all and reports no error, so the sprite has to be tested for rather than
       assumed. */
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
        (INLINE[id] || []).forEach(function (d) {
            var p = document.createElementNS(SVG_NS, 'path');
            p.setAttribute('d', d);
            svg.appendChild(p);
        });
        return svg;
    }

    function css() {
        if (document.getElementById('jmPwaCss')) return;
        var s = document.createElement('style');
        s.id = 'jmPwaCss';
        s.textContent = [
            /* The bottom rule stays on a bare #jmInstall selector. mail.html lifts
               the card above the phone tab bar with `body #jmInstall{bottom:...}`,
               which only outranks this without !important while this side stays a
               single id. Do not qualify it. */
            '#jmInstall{',
            '  --jm-panel:#202020;--jm-panel-2:#252525;--jm-panel-3:#2c2c2c;--jm-panel-4:#343434;',
            '  --jm-border:rgba(255,255,255,.075);--jm-border-strong:rgba(255,255,255,.14);',
            '  --jm-text:#ededed;--jm-dim:#b9b9b9;--jm-mute:#949494;',
            '  --jm-primary:#2f6fed;--jm-primary-hover:#3a7bf5;--jm-success:#1f9d55;',
            '  --jm-e3:0 12px 34px rgba(0,0,0,.50);',
            '  --jm-t-fast:120ms cubic-bezier(.2,.7,.3,1);',
            '  --jm-t-base:200ms cubic-bezier(.2,.7,.3,1);',
            /* Below the app's own dialogs, deliberately. At 9998 this card sat over
               the mailbox account sheet and the folder sheet, which live at 150, and
               a driven phone run found it swallowing three of the four account rows
               and five of the six folders: the taps landed on the card's own markup
               and did nothing. An install promo must never outrank a surface the
               person deliberately opened. 100 keeps it over the tab bar at 60 and
               under every sheet and scrim. */
            '  position:fixed;z-index:100;left:12px;right:12px;',
            '  bottom:calc(12px + env(safe-area-inset-bottom, 0px));',
            '  background:var(--jm-panel);color:var(--jm-text);',
            '  border:1px solid var(--jm-border-strong);border-radius:16px;',
            '  padding:16px;box-shadow:var(--jm-e3);',
            '  display:flex;gap:12px;align-items:flex-start;',
            '  font:13.5px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;',
            '  transform:translateY(140%);opacity:0;',
            '  transition:transform var(--jm-t-base),opacity var(--jm-t-base)}',
            /* Each host page owns its own reset and two of the four set nothing. */
            '#jmInstall,#jmInstall *{box-sizing:border-box}',
            '#jmInstall.on{transform:translateY(0);opacity:1}',
            '@media(prefers-reduced-motion:reduce){#jmInstall{transition:none}}',
            '@media(min-width:640px){#jmInstall{left:auto;right:18px;',
            '  width:min(380px,calc(100vw - 36px))}}',

            '#jmInstall .jm-ic{width:18px;height:18px;flex:none;fill:none;stroke:currentColor;',
            '  stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round;pointer-events:none}',

            '#jmInstall .jm-ico{position:relative;flex:0 0 auto;width:44px;height:44px}',
            '#jmInstall .jm-ico img{display:block;width:44px;height:44px;border-radius:12px;',
            '  background:#fff;border:1px solid var(--jm-border)}',
            /* The 2px ring in the card ground punches the tick out of the app icon
               so it reads as an overlay rather than a smudge on the corner. */
            '#jmInstall .jm-ok{position:absolute;right:-4px;bottom:-4px;width:20px;height:20px;',
            '  border-radius:999px;background:var(--jm-success);color:#fff;',
            '  display:grid;place-items:center;box-shadow:0 0 0 2px var(--jm-panel)}',
            '#jmInstall .jm-ok .jm-ic{width:13px;height:13px;stroke-width:2.6}',

            '#jmInstall .jm-c{flex:1 1 auto;min-width:0}',
            /* 34px clears the close button, which is a 44px hit area pulled into
               the padding. Only these two lines need it; the actions and the step
               list sit below it and take the full width. */
            '#jmInstall .jm-t{margin:0 0 4px;padding-right:34px;font-size:16px;line-height:1.3;',
            '  font-weight:620;letter-spacing:-.01em}',
            '#jmInstall .jm-s{margin:0;padding-right:34px;color:var(--jm-dim);font-size:12.5px;',
            '  line-height:1.45}',

            '#jmInstall .jm-a{display:flex;gap:8px;margin-top:16px;flex-wrap:wrap}',
            '#jmInstall button{font:inherit;font-size:13.5px;font-weight:600;cursor:pointer;',
            '  min-height:44px;padding:0 16px;border-radius:8px;',
            '  border:1px solid var(--jm-border-strong);',
            '  background:var(--jm-panel-3);color:var(--jm-text);',
            '  -webkit-tap-highlight-color:transparent;touch-action:manipulation;',
            '  transition:background var(--jm-t-fast),border-color var(--jm-t-fast)}',
            '#jmInstall button:hover{background:var(--jm-panel-4)}',
            '#jmInstall button:active{background:var(--jm-panel-2)}',
            '#jmInstall button.jm-p{background:var(--jm-primary);border-color:var(--jm-primary);',
            '  color:#fff}',
            '#jmInstall button.jm-p:hover{background:var(--jm-primary-hover);',
            '  border-color:var(--jm-primary-hover)}',
            '#jmInstall button.jm-p:active{background:var(--jm-primary)}',
            '#jmInstall button:focus-visible{outline:2px solid var(--jm-primary);outline-offset:2px}',
            '@media(prefers-reduced-motion:reduce){#jmInstall button{transition:none}}',

            '#jmInstall .jm-x{position:absolute;top:4px;right:4px;width:44px;height:44px;',
            '  min-height:44px;padding:0;border:0;border-radius:8px;background:none;',
            '  color:var(--jm-mute);display:grid;place-items:center}',
            '#jmInstall .jm-x:hover{background:var(--jm-panel-3);color:var(--jm-text)}',
            '#jmInstall .jm-x:active{background:var(--jm-panel-2)}',

            '#jmInstall .jm-steps{margin:12px 0 0;padding-left:20px;color:var(--jm-dim);',
            '  font-size:12.5px;line-height:1.45}',
            '#jmInstall .jm-steps li{margin:4px 0}',
            '#jmInstall .jm-steps li::marker{color:var(--jm-mute)}'
        ].join('');
        document.head.appendChild(s);
    }

    var el = null;

    /* opts.ok      draws the success tick over the app icon
       opts.status  announces the card politely instead of claiming to be a dialog */
    function card(title, sub, body, opts) {
        css();
        opts = opts || {};
        if (el) el.remove();
        el = document.createElement('div');
        el.id = 'jmInstall';
        if (opts.status) {
            el.setAttribute('role', 'status');
            el.setAttribute('aria-live', 'polite');
        } else {
            el.setAttribute('role', 'dialog');
            el.setAttribute('aria-label', 'Install Jarurat Mail');
        }
        // Kept inline as well as in the stylesheet: .jm-x is positioned against
        // this element, so a card that fell back to static would fling its own
        // close button into the corner of the page.
        el.style.position = 'fixed';

        var ico = document.createElement('span');
        ico.className = 'jm-ico';
        var img = document.createElement('img');
        img.src = '/icons/icon-192.png';
        img.alt = '';
        ico.appendChild(img);
        if (opts.ok) {
            var badge = document.createElement('span');
            badge.className = 'jm-ok';
            badge.appendChild(icon('i-check'));
            ico.appendChild(badge);
        }
        el.appendChild(ico);

        var col = document.createElement('div');
        col.className = 'jm-c';

        var h = document.createElement('p');
        h.className = 'jm-t';
        h.textContent = title;
        col.appendChild(h);

        var p = document.createElement('p');
        p.className = 'jm-s';
        p.textContent = sub;
        col.appendChild(p);

        col.appendChild(body);
        el.appendChild(col);

        var x = document.createElement('button');
        x.className = 'jm-x';
        x.type = 'button';
        x.setAttribute('aria-label', opts.status ? 'Dismiss' : 'Not now');
        x.appendChild(icon('i-close'));
        x.addEventListener('click', function () {
            if (!opts.status) snooze();
            hide();
        });
        el.appendChild(x);

        document.body.appendChild(el);
        var mine = el;
        requestAnimationFrame(function () { mine.classList.add('on'); });
        return mine;
    }

    function hide() {
        if (!el) return;
        el.classList.remove('on');
        var gone = el;
        el = null;
        // The slide-out copy lives on for another 300ms and keeps the id until it
        // is removed. A card opened inside that window would be the second
        // #jmInstall in the document, which getElementById resolves the wrong way.
        gone.removeAttribute('id');
        setTimeout(function () { gone.remove(); }, 300);
    }

    var deferred = null;

    window.addEventListener('beforeinstallprompt', function (e) {
        e.preventDefault();          // suppress Chrome's own mini infobar
        deferred = e;
        if (installed() || snoozed()) return;
        showPrompt();
    });

    function showPrompt() {
        var acts = document.createElement('div');
        acts.className = 'jm-a';

        var go = document.createElement('button');
        go.className = 'jm-p';
        go.type = 'button';
        go.textContent = 'Install';
        go.addEventListener('click', function () {
            if (!deferred) { hide(); return; }
            var evt = deferred;
            deferred = null;             // prompt() is single use
            hide();
            evt.prompt();
            if (evt.userChoice && evt.userChoice.then) {
                evt.userChoice.then(function (c) {
                    if (c && c.outcome === 'dismissed') snooze();
                });
            }
        });

        var later = document.createElement('button');
        later.type = 'button';
        later.textContent = 'Not now';
        later.addEventListener('click', function () { snooze(); hide(); });

        acts.appendChild(go);
        acts.appendChild(later);

        card('Install Jarurat Mail',
             'Add it to your home screen and it opens like an app, full screen, no browser bar.',
             acts);
    }

    function showIosSteps() {
        var wrap = document.createElement('div');
        var ol = document.createElement('ol');
        ol.className = 'jm-steps';
        ['Tap the Share button at the bottom of Safari.',
         'Scroll down and tap "Add to Home Screen".',
         'Tap "Add". Jarurat Mail appears with your other apps.'
        ].forEach(function (t) {
            var li = document.createElement('li');
            li.textContent = t;
            ol.appendChild(li);
        });
        wrap.appendChild(ol);

        var acts = document.createElement('div');
        acts.className = 'jm-a';
        var ok = document.createElement('button');
        ok.className = 'jm-p';
        ok.type = 'button';
        ok.textContent = 'Got it';
        ok.addEventListener('click', function () { snooze(); hide(); });
        acts.appendChild(ok);
        wrap.appendChild(acts);

        card('Install Jarurat Mail', 'Two taps and it lives on your home screen.', wrap);
    }

    /* The install completes in a browser tab, and that tab does not change: the
       installed window opens somewhere else, or nowhere at all until the person
       taps the new icon. Vanishing the card at that moment reads as the install
       having failed, so it is replaced by an answer instead of being hidden. */
    function showInstalled(sub) {
        var acts = document.createElement('div');
        acts.className = 'jm-a';
        var done = document.createElement('button');
        done.className = 'jm-p';
        done.type = 'button';
        done.textContent = 'Done';
        done.addEventListener('click', hide);
        acts.appendChild(done);

        var mine = card('Jarurat Mail is installed', sub, acts, { ok: true, status: true });
        setTimeout(function () { if (el === mine) hide(); }, 6000);
    }

    window.addEventListener('appinstalled', function () {
        snooze();
        showInstalled('It is on your home screen now. Open it from there and it runs full screen.');
    });

    /* Is one of the host page's own dialogs on screen right now.
       Deliberately generic: this file is shared by the console, the mailbox, the
       login page and the landing page, and it must not know the class names of
       any of them. Every dialog in the product carries aria-modal, which is the
       attribute that tells assistive technology the same thing, so reading it
       here costs nothing and stays true if the markup is rewritten. */
    function modalOpen() {
        var nodes = document.querySelectorAll('[aria-modal="true"], dialog[open]');
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            // offsetParent is null for a fixed element even when it is visible, so
            // the rect is the reliable test for both kinds.
            var r = n.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) continue;
            var cs = getComputedStyle(n);
            if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;
            return true;
        }
        return false;
    }

    /* Ask the host to put its dialog away, then act on the next frame.
       Escape is the one gesture every dialog in this product already honours, so
       synthesising it needs no agreement between this file and any other. */
    function afterModalCloses(run) {
        if (!modalOpen()) { run(); return; }
        document.dispatchEvent(new KeyboardEvent('keydown', {
            key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true
        }));
        requestAnimationFrame(function () { requestAnimationFrame(run); });
    }

    /* Any element with data-jm-install re-opens the card on demand, so the
       option survives a dismissal instead of disappearing for a fortnight. */
    document.addEventListener('click', function (e) {
        var t = e.target.closest && e.target.closest('[data-jm-install]');
        if (!t) return;
        e.preventDefault();
        // The row is still in the account sheet inside the installed app, where
        // there is nothing left to install. Answering is better than a control
        // that does nothing whatsoever when it is pressed.
        if (installed()) {
            showInstalled('You are using the installed app right now.');
            return;
        }
        // The Install app row lives inside the account sheet, so this click almost
        // always arrives from an open dialog. The card now sits under that dialog,
        // so showing it without closing the sheet first would paint it where nobody
        // can see it, and the row would read as dead.
        afterModalCloses(function () {
            if (deferred) showPrompt();
            else if (isIos()) showIosSteps();
            else card('Install Jarurat Mail',
                      'Open your browser menu and choose "Install app" or "Add to Home screen".',
                      document.createElement('div'));
        });
    });

    function boot() {
        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(function () { /* http, or blocked */ });
        }
        if (installed() || snoozed()) return;
        // beforeinstallprompt does the Android case on its own. iOS Safari has no
        // event, so it is the only one that needs a timer, and it gets a beat so
        // the card is not the first thing on screen.
        //
        // The timer is unprompted, unlike every other route into this card, so it
        // must never interrupt something the person is already doing. If a dialog
        // is open when it fires, it waits and tries again rather than closing that
        // dialog out from under them.
        if (isIosSafari()) {
            var tries = 0;
            var offer = function () {
                if (installed() || snoozed()) return;
                if (modalOpen() && tries++ < 20) { setTimeout(offer, 3000); return; }
                if (!modalOpen()) showIosSteps();
            };
            setTimeout(offer, 2200);
        }
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
