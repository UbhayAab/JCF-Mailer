/* Jarurat Mail service worker.
 *
 * This app is session authenticated and every page can redirect to /login, so the
 * one thing this worker must never do is cache HTML or anything under /api. A
 * cached page would either show one person's mailbox to the next person on the
 * device or keep serving a logged-out shell after a real sign in. Stale mail is
 * worse than no mail: a message that was deleted, moved or read on another device
 * would still be sitting in the list, and a person acting on it acts on something
 * that no longer exists. Both have happened to other people; neither is
 * recoverable by the user.
 *
 * So the rule is narrow on purpose:
 *   - /api/** and anything else on the deny list -> never touched, not even to
 *     read from the cache. This is checked first, before any other branch, so a
 *     later edit to isShellAsset cannot accidentally widen it.
 *   - versioned static assets (css, js, icons, logos, manifest) -> stale-while-
 *     revalidate, with a network-first override when the user asked for fresh.
 *   - navigations -> network, falling back to the offline card only when the
 *     network actually fails. A redirect or a 401 is a real answer and passes
 *     through untouched.
 *   - everything else -> not handled at all, straight to the network.
 */

/* Bumping VERSION is what ships a CSS or JS change to an installed app: the cache
   name changes, so the new worker starts from an empty cache and every asset is
   fetched fresh once. No template references an asset with a ?v= query, which
   means this constant is the ONLY deploy mechanism the app has. Ship a change to
   anything under /css or /js and forget to bump it, and installed phones keep
   serving the previous copy until the browser happens to revalidate. */
const VERSION = 'jm-v3';
const SHELL = 'jm-shell-' + VERSION;

const PRECACHE = [
    '/css/style.css',
    '/js/mail.js',
    '/js/notify.js',
    '/js/pwa.js',
    '/icons/icon-192.png',
    '/icons/icon-512.png',
    '/logo.png',
    '/manifest.webmanifest'
];

const OFFLINE_URL = '/offline.html';

self.addEventListener('install', (event) => {
    event.waitUntil((async () => {
        const cache = await caches.open(SHELL);
        // One failed asset must not fail the whole install, which would leave the
        // app permanently uninstallable. addAll is all-or-nothing; this is not.
        await Promise.all(PRECACHE.concat([OFFLINE_URL]).map(
            (url) => cache.add(new Request(url, { cache: 'reload' })).catch(() => null)
        ));
        await self.skipWaiting();
    })());
});

self.addEventListener('activate', (event) => {
    event.waitUntil((async () => {
        const names = await caches.keys();
        // Scoped to our own prefix rather than "everything that is not SHELL", so
        // this worker cannot delete a cache some other feature on this origin owns.
        await Promise.all(names
            .filter((n) => n.startsWith('jm-') && n !== SHELL)
            .map((n) => caches.delete(n)));

        // Without this the browser holds the navigation request until the worker
        // has booted and reached the fetch handler, which on a cold start is the
        // slowest part of opening the installed app. With it the request is
        // already in flight by the time the handler runs.
        if (self.registration.navigationPreload) {
            await self.registration.navigationPreload.enable().catch(() => null);
        }
        await self.clients.claim();
    })());
});

/* Requests that must reach the network every single time, with no cache read and
   no cache write. Checked before anything else in the fetch handler. */
function isNeverCached(url) {
    return url.pathname === '/api' || url.pathname.startsWith('/api/');
}

/** Assets that are safe to cache: same origin, GET, and not a page or an API call. */
function isShellAsset(url) {
    return url.pathname.startsWith('/css/')
        || url.pathname.startsWith('/js/')
        || url.pathname.startsWith('/icons/')
        || url.pathname === '/logo.png'
        || url.pathname === '/logo1.png'
        || url.pathname === '/favicon.ico'
        || url.pathname === '/apple-touch-icon.png'
        || url.pathname === '/manifest.webmanifest';
}

/* 206 passes res.ok, and cache.put throws on a partial response. Opaque and
   error responses have no useful body to keep either. */
function storable(res) {
    return res && res.status === 200 && res.type === 'basic';
}

self.addEventListener('fetch', (event) => {
    const req = event.request;
    if (req.method !== 'GET') return;

    const url = new URL(req.url);
    if (url.origin !== self.location.origin) return;
    if (isNeverCached(url)) return;

    if (isShellAsset(url)) {
        event.respondWith((async () => {
            const cache = await caches.open(SHELL);

            const live = fetch(req).then((res) => {
                if (storable(res)) cache.put(req, res.clone());
                return res;
            }).catch(() => null);

            // A hard reload is the one escape hatch a person has when the app is
            // serving something stale, so honour it: reloadable requests arrive
            // with cache 'reload' or 'no-cache' and must not be answered from the
            // cache we are trying to bypass.
            const fresh = req.cache === 'reload' || req.cache === 'no-cache';
            if (fresh) {
                const res = await live;
                return res || (await cache.match(req)) || Response.error();
            }

            const hit = await cache.match(req);
            if (hit) {
                // respondWith settles the moment the cached copy is returned, and
                // the browser is then free to kill the worker. Without this the
                // revalidation write is routinely lost and the cache never moves.
                event.waitUntil(live);
                return hit;
            }
            return (await live) || Response.error();
        })());
        return;
    }

    if (req.mode === 'navigate') {
        event.respondWith((async () => {
            try {
                // No cache write here, deliberately. See the header comment.
                const preload = event.preloadResponse ? await event.preloadResponse : null;
                if (preload) return preload;
                return await fetch(req);
            } catch (e) {
                const cache = await caches.open(SHELL);
                const offline = await cache.match(OFFLINE_URL);
                // Returned with its own headers intact, including the CSP the
                // server set, rather than rebuilt as a 503: rebuilding drops that
                // header and the card's inline script stops being governed by it.
                return offline || new Response(
                    '<h1>Offline</h1><p>Jarurat Mail needs a connection.</p>',
                    { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
            }
        })());
    }

    // Everything else is left alone.
});

/* A notification about new mail is only worth showing if tapping it lands on that
 * message, in the tab the person already has open.
 *
 * The default behaviour of a notification is nothing at all: the browser closes it
 * and that is the end of it. The behaviour people expect from every other mail
 * client is the one below. The part that matters is the reuse. Calling openWindow
 * unconditionally is the obvious version and it is wrong: somebody who has had the
 * mailbox open all afternoon taps the notification and gets a SECOND copy of the
 * app, with its own session state, its own half-written reply nowhere in sight, and
 * the tab they were actually using still sitting behind it. So an existing /mail
 * window is focused and told which message to open, and a new window is only opened
 * when there is genuinely nothing to focus.
 *
 * Only /mail windows are candidates. A console tab at /app has no reading pane and
 * no listener for this message, so focusing it would consume the tap and show the
 * person nothing; that case wants a new window like any other.
 *
 * This handler reads no cache and writes no cache. The rules at the top of this
 * file are untouched by it. */
self.addEventListener('notificationclick', (event) => {
    event.notification.close();

    const data = event.notification.data || {};
    const id = data.id || '';
    const url = data.url || '/mail';

    event.waitUntil((async () => {
        // includeUncontrolled matters: a tab that was already loaded when this
        // worker activated is not controlled by it, and that is the commonest tab
        // there is. Without the flag the reuse path would almost never be taken.
        const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });

        let target = null;
        for (const client of windows) {
            if (new URL(client.url).pathname === '/mail') { target = client; break; }
        }

        if (target) {
            // focus() before postMessage, so the tab is already in front when it
            // starts swapping the reading pane. The message is what notify.js turns
            // into an openMessage call; a client that has no listener simply ignores
            // it and the person still lands on their mailbox.
            if (target.focus) await target.focus();
            target.postMessage({ type: 'jm-open-mail', id: id });
            return;
        }

        if (self.clients.openWindow) await self.clients.openWindow(url);
    })());
});

self.addEventListener('message', (event) => {
    if (event.data === 'skipWaiting') self.skipWaiting();
});
