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
/* Bumped to evict every cache written by the versions that served code cache
   first. Without this the old SHELL survives the update and keeps answering with
   the JavaScript the fix above exists to stop serving. */
/* v5 adds the push, pushsubscriptionchange and notificationclose handlers and
   rewrites notificationclick to route action buttons. The bump is not optional
   here and it is not housekeeping: a browser that already has v4 installed keeps
   running v4's script until the byte content of this file changes, and v4 has no
   push handler at all. Without the bump the very first push would be delivered
   to a worker that ignores it, the browser would show its own "this site has
   been updated in the background" notice instead, and the whole feature would
   look to the owner as though it had never been built. The cache name changing
   also re-fetches /js/notify.js, which is the other half of this change. */
const VERSION = 'jm-v5';
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
            // NETWORK FIRST for code, cache first for pictures.
            //
            // This served everything from the cache first and revalidated behind it,
            // which is the usual advice and was wrong here in a way that cost real
            // time. Pages are never cached, so after a deploy the browser fetched a
            // fresh mail.html and then served the PREVIOUS mail.js and style.css
            // beside it. New markup wired by old JavaScript is not a stale page, it
            // is a broken one: controls that exist in the HTML have nothing
            // listening to them, and the person looking at it reasonably reports the
            // feature as missing. That happened more than once, and the deploy was
            // fine every time.
            //
            // Going to the network first for /css/ and /js/ costs one request each
            // against a server one hop away, and it makes a deploy visible on the
            // next load rather than the one after. Icons and logos keep the old
            // behaviour: they are large, they almost never change, and a stale logo
            // has never broken anything.
            const isCode = url.pathname.startsWith('/css/') || url.pathname.startsWith('/js/');
            const fresh = isCode || req.cache === 'reload' || req.cache === 'no-cache';
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

/* ======================================================================
 * PUSH
 *
 * Nothing below this line reads or writes a cache. The rules at the top of
 * this file are untouched by any of it, deliberately: a notification path
 * that quietly started caching /api would reintroduce both of the bugs that
 * header comment exists to prevent.
 *
 * The browser has already decrypted the payload by the time this handler
 * runs, so there is no crypto here at all; event.data is plaintext JSON.
 *
 * FOUR SHAPES ARRIVE, FROM TWO SENDERS, AND BOTH SENDERS REACH THE SAME
 * SUBSCRIPTION. This application signs and sends its own pushes with its own
 * VAPID key pair (see com.jarurat.mailer.push), and it ALSO registers the same
 * browser subscription with Stalwart as a JMAP PushSubscription, so the mail
 * server can push new mail straight to the device without this application
 * being awake. One endpoint, two things pushing to it, and they do not use the
 * same envelope. Handling only one of them was the first integration mistake
 * available here and it would have shown up as new mail working and send
 * failures not, or the reverse, with no error anywhere.
 *
 * The application's own shape is flat and already decided:
 *
 *   {v:1, type, lane, title, body, tag, renotify, requireInteraction,
 *    silent, timestamp, data:{...}}
 *
 * It carries no @type. The lane is decided on the server, because a browser
 * cannot know whether a message was addressed to a person or to a shared alias
 * and working it out here would mean shipping somebody's VIP list to every
 * device. Nothing in this file second-guesses it.
 *
 * Stalwart's shapes are the RFC 8620 ones, all tagged with @type:
 *
 *   EmailPush        the good one: the messages themselves are inside the
 *                    payload, so this worker can render a complete
 *                    notification with the application server down.
 *   StateChange      "something changed", no content. We have to ask
 *                    /api/mail/poll what it was, which only works while the
 *                    session cookie is still valid; when it is not, the
 *                    notification degrades to a countless "New mail".
 *   PushVerification the RFC 8620 handshake. Not mail, and not something a
 *                    person should ever see; see verify() for why it still
 *                    has to put a notification on screen for a moment.
 *
 * A fourth shape, @type JmNotify, is ours rather than Stalwart's: it is how
 * the application pushes the two events it already knows about without any
 * mail-server involvement, a send that failed and a bounce rate crossing its
 * threshold. Its items carry an explicit kind and lane.
 * ====================================================================== */

/* Lane A is allowed to make a sound. Lane B is shown in full and silently.
   Lane C is not a notification at all, only a badge, and never reaches this
   file. The distinction is the whole design: the person gets a lot of
   notifications and very few noises. */
const LANE_A = 'A';

/* At most one sound per minute per device, across every lane and every
   message. Four hospital emails landing together produce one notification
   sound, not four. This single rule is worth more than every other rule in
   the notification design put together, and it is enforced here rather than
   on the server because only this worker can see what the device has already
   been shown. */
const SOUND_FLOOR_MS = 60000;

/* Three individual Lane A notifications, then a collapse. Chrome and Android
   both stack a fourth behind a "+n more" affordance of their own, at which
   point we have lost control of what the person actually reads. */
const LANE_A_MAX = 3;

/* The rolling Lane B notification lists this many senders. Older ones fall
   off the bottom; the count in the title still carries all of them. */
const LANE_B_LINES = 3;

const ICON = '/icons/icon-192.png';
/* Android masks the badge down to an alpha silhouette, so a full-colour 192px
   logo becomes a grey blob in the status bar. The design calls for a 96px
   single-colour /icons/badge-96.png. That asset does not exist in this repo
   and /icons is not this file's to add to, so this keeps the behaviour that
   shipped rather than pointing at a 404. */
const BADGE = '/icons/icon-192.png';

/* ---------------------------------------------------------------- storage */

/* localStorage does not exist in a worker and the push event has no page to
   ask, so the sound floor and the device identity live in IndexedDB. Every
   call is wrapped: a browser in private mode can refuse to open the database,
   and a notification that fails to appear because bookkeeping threw is a worse
   outcome than a notification that makes one sound too many. */
function idb() {
    return new Promise((resolve) => {
        let req;
        try { req = indexedDB.open('jm-notify', 1); } catch (e) { resolve(null); return; }
        req.onupgradeneeded = () => {
            try { req.result.createObjectStore('kv'); } catch (e) { /* already there */ }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => resolve(null);
        req.onblocked = () => resolve(null);
    });
}

function kvGet(key) {
    return idb().then((db) => new Promise((resolve) => {
        if (!db) { resolve(null); return; }
        try {
            const r = db.transaction('kv', 'readonly').objectStore('kv').get(key);
            r.onsuccess = () => resolve(r.result === undefined ? null : r.result);
            r.onerror = () => resolve(null);
        } catch (e) { resolve(null); }
    })).catch(() => null);
}

function kvPut(key, value) {
    return idb().then((db) => new Promise((resolve) => {
        if (!db) { resolve(false); return; }
        try {
            const tx = db.transaction('kv', 'readwrite');
            tx.objectStore('kv').put(value, key);
            tx.oncomplete = () => resolve(true);
            tx.onerror = () => resolve(false);
            tx.onabort = () => resolve(false);
        } catch (e) { resolve(false); }
    })).catch(() => false);
}

/* ------------------------------------------------------------ small tools */

function clip(text, max) {
    const s = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
    if (s.length <= max) return s;
    // Cut at a word boundary where there is one within reach, so a clipped
    // sender reads as a name rather than as a name with its last syllable
    // sawn off.
    const cut = s.slice(0, max);
    const space = cut.lastIndexOf(' ');
    // Written as an escape rather than a literal so this file stays pure ASCII.
    // It is served as text/javascript and a charset mismatch anywhere in the
    // chain would otherwise turn every clipped subject into mojibake.
    return (space > max - 12 ? cut.slice(0, space) : cut) + '\u2026';
}

/** The display name if the payload gave one, otherwise the address. */
function senderOf(item) {
    const f = item.from;
    if (!f) return 'New mail';
    if (typeof f === 'string') return f;
    if (Array.isArray(f)) return f.length ? senderOf({ from: f[0] }) : 'New mail';
    return f.name || f.email || 'New mail';
}

/**
 * True when the mailbox is open, visible and focused on this device.
 *
 * Announcing mail to somebody who is looking straight at the message is the
 * fastest way to have notifications switched off, and the page path already
 * refuses to do it. Push has to make the same check, because the server has
 * no idea which of a person's devices is in their hand.
 */
async function mailboxIsInFront() {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of windows) {
        try {
            if (new URL(c.url).pathname !== '/mail') continue;
        } catch (e) { continue; }
        if (c.visibilityState === 'visible' && c.focused) return true;
    }
    return false;
}

/** Tells every open mailbox tab to repaint its badge from the poll endpoint. */
async function nudgeClients() {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of windows) {
        try { c.postMessage({ type: 'jm-push-refresh' }); } catch (e) { /* gone */ }
    }
}

/**
 * Whether this batch may make a sound, and the claim on the floor if it may.
 *
 * Called once per push event and never per notification, so a batch of four
 * spends one allowance rather than four.
 */
async function claimSound() {
    const now = Date.now();
    const last = Number(await kvGet('lastSoundAt')) || 0;
    if (now - last < SOUND_FLOOR_MS) return false;
    await kvPut('lastSoundAt', now);
    return true;
}

/* -------------------------------------------------------------- rendering */

/**
 * A Lane A notification: one message, worth interrupting for.
 *
 * silent is OMITTED rather than set to false when this batch has the sound
 * allowance. The specified default is null, meaning "respect the device", and
 * that is what lets iOS Focus and Android Do Not Disturb do their jobs.
 * Setting false would be a claim we have no right to make.
 *
 * image is never set, at any lane, and this is a privacy rule rather than a
 * taste one. The only candidate content is the message's first inline image,
 * and for this mailbox that is a scan, a prescription or a patient
 * photograph, rendered full width on a lock screen in a waiting room.
 */
function laneAOptions(item, silent, mailbox) {
    const opts = {
        body: [clip(item.subject || '(no subject)', 120), clip(item.preview || '', 160)]
            .filter(Boolean).join('\n'),
        icon: ICON,
        badge: BADGE,
        tag: 'jm-a:' + item.id,
        renotify: true,          // legal only because tag is non-empty
        requireInteraction: false,
        // When the MAIL arrived, not when we noticed it. A phone that was
        // asleep or out of signal for an hour otherwise shows every message
        // as having landed the moment it woke up.
        timestamp: Date.parse(item.receivedAt || '') || Date.now(),
        // First-strong. Subjects arrive here in Devanagari and occasionally in
        // Urdu, and hardcoding ltr mis-renders both.
        dir: 'auto',
        // The sender's language, which we do not know. Declaring en-IN over a
        // Hindi subject makes a screen reader mispronounce it, which is worse
        // than declaring nothing.
        lang: '',
        actions: [{ action: 'archive', title: 'Archive' }, { action: 'read', title: 'Mark read' }],
        data: {
            v: 1, lane: 'A', kind: 'mail',
            id: item.id, threadId: item.threadId || '', mailbox: mailbox,
            url: '/mail?msg=' + encodeURIComponent(item.id),
            from: item.fromEmail || ''
        }
    };
    if (silent) opts.silent = true;
    return opts;
}

/**
 * The rolling Lane B notification. Exactly one per mailbox, ever.
 *
 * Four silent Lane B messages are one notification saying four, not four
 * notifications. That is not a compromise forced by the platform: four silent
 * entries in a shade are four things to dismiss and one thing to read.
 */
function laneBOptions(lines, ids, mailbox, newest) {
    return {
        body: lines.slice(0, LANE_B_LINES).join('\n'),
        icon: ICON,
        badge: BADGE,
        tag: 'jm-b:' + mailbox,
        // It may replace itself, but it never re-surfaces to the top of the
        // shade. A silent stream that keeps jumping to the top is churn
        // dressed up as information.
        renotify: false,
        silent: true,
        requireInteraction: false,
        timestamp: Date.parse((newest && newest.receivedAt) || '') || Date.now(),
        dir: 'auto',
        lang: '',
        actions: [{ action: 'archive', title: 'Archive' }, { action: 'read', title: 'Mark read' }],
        data: {
            v: 1, lane: 'B', kind: 'mail',
            id: newest ? newest.id : '', ids: ids, mailbox: mailbox,
            url: newest ? '/mail?msg=' + encodeURIComponent(newest.id) : '/mail'
        }
    };
}

/**
 * A send that failed. Ranked first in the notification design and the reason
 * push is worth building at all: a scheduled 06:00 send that the mail server
 * refuses is, today, invisible. The person believes a message went out. It
 * did not, and there is no other channel that tells them.
 */
function failOptions(item, silent, mailbox) {
    const opts = {
        body: item.body || 'The mail server refused it. Nothing was delivered.',
        icon: ICON,
        badge: BADGE,
        tag: 'jm-fail:' + (item.id || 'outbox'),
        renotify: true,
        // Chrome desktop only; Android ignores it. Correct anyway, because
        // this is the one mail event where dismissing it by accident loses the
        // only warning there is.
        requireInteraction: true,
        timestamp: Date.parse(item.at || '') || Date.now(),
        dir: 'ltr',
        lang: 'en-IN',                 // our sentence, in our language
        actions: [{ action: 'outbox', title: 'Open outbox' }],
        data: {
            v: 1, lane: 'A', kind: 'sendfail',
            id: item.id || '', mailbox: mailbox, url: item.url || '/mail'
        }
    };
    if (silent) opts.silent = true;
    return opts;
}

/**
 * A bounce or complaint rate crossing its threshold.
 *
 * One action and not two, because there is exactly one correct next step and
 * a second button would be a guess. maxActions being two is a ceiling, not a
 * quota. The fifty individual bounce events behind this number produce no
 * notifications at all; only the crossing does, once.
 */
function sesOptions(item, silent, mailbox) {
    const opts = {
        body: item.body || '',
        icon: ICON,
        badge: BADGE,
        tag: 'jm-ses:' + (item.finding || 'rate'),
        renotify: true,
        requireInteraction: true,
        timestamp: Date.now(),
        dir: 'ltr',
        lang: 'en-IN',
        actions: [{ action: 'safety', title: 'Open safety check' }],
        data: { v: 1, lane: 'A', kind: 'ses', mailbox: mailbox, url: item.url || '/app' }
    };
    if (silent) opts.silent = true;
    return opts;
}

/**
 * Turns one push payload into the smallest set of notifications that says
 * everything in it.
 *
 * Grouping decides how many notifications appear. The sound floor above
 * decides how many noises happen. The two are separate on purpose: one Lane A
 * message plus three Lane B ones is two notifications and one sound.
 */
/**
 * Shows one notification exactly as the server composed it.
 *
 * Deliberately not routed through renderBatch. That function's job is to turn a
 * list of raw messages into the fewest notifications that say everything, which
 * is a decision; this payload has had that decision made already, further
 * upstream and with more to go on. Re-deriving a tag or a lane here would
 * quietly overrule the server's rules and there would be no way to tell from
 * either side which one had won.
 */
async function renderServerNotification(payload, mailbox) {
    const inFront = await mailboxIsInFront();
    // A payload that already says silent spends no sound allowance, so a quiet
    // morning of Lane B mail does not use up the one sound a minute that the
    // hospital email arriving at 11:04 needs.
    let silent = payload.silent === true || inFront;
    if (!silent) silent = !(await claimSound());

    const data = Object.assign({ v: 1, mailbox: mailbox }, payload.data || {});
    if (!data.url) data.url = '/mail';

    const opts = {
        body: payload.body || '',
        icon: ICON,
        badge: BADGE,
        tag: payload.tag || ('jm-b:' + mailbox),
        renotify: !!payload.renotify,
        requireInteraction: !!payload.requireInteraction,
        timestamp: payload.timestamp || Date.now(),
        // The server's own sentences are English; a subject line it forwarded is
        // whatever the sender wrote. dir auto is right for both and lang is left
        // empty rather than guessed, for the reason laneAOptions gives.
        dir: 'auto',
        lang: '',
        actions: actionsFor(payload, data)
    };
    if (silent) opts.silent = true;
    opts.data = data;

    await self.registration.showNotification(payload.title || 'Jarurat Mail', opts)
        .catch(() => null);
    await nudgeClients();
}

/**
 * The buttons, chosen from what the notification is about rather than offered
 * uniformly.
 *
 * Archive and Mark read are only meaningful on a notification that stands for a
 * message this device can name. A send failure has one correct next step and a
 * bounce alert has one correct next step, and in both cases a second button
 * would be a guess. maxActions being two is a ceiling, not a quota.
 */
function actionsFor(payload, data) {
    const kind = data.kind || payload.type || '';
    if (kind.indexOf('outbox') === 0 || payload.type === 'send-failed') {
        return [{ action: 'outbox', title: 'Open outbox' }];
    }
    if (kind.indexOf('ses') === 0 || kind.indexOf('bounce') === 0 || kind.indexOf('safety') === 0) {
        return [{ action: 'safety', title: 'Open safety check' }];
    }
    if (data.id) {
        return [{ action: 'archive', title: 'Archive' }, { action: 'read', title: 'Mark read' }];
    }
    return [{ action: 'open', title: 'Open inbox' }];
}

async function renderBatch(items, mailbox) {
    const reg = self.registration;
    if (!items.length) return;

    const inFront = await mailboxIsInFront();
    // Everything is silent when the person is looking at the mailbox. It is
    // not suppressed outright, because a push handler that shows nothing at
    // all spends the userVisibleOnly budget and earns the browser's own
    // "this site has been updated in the background" notice instead, which
    // says less and cannot be tapped anywhere useful.
    const loud = inFront ? false : await claimSound();

    const laneA = [];
    const laneB = [];
    for (const item of items) {
        if (item.kind === 'sendfail' || item.kind === 'ses' || item.lane === LANE_A) laneA.push(item);
        else laneB.push(item);
    }

    const shown = [];

    // --- Lane A ---------------------------------------------------------
    const existingA = reg.getNotifications
        ? (await reg.getNotifications()).filter((n) => n.tag && n.tag.indexOf('jm-a:') === 0)
        : [];
    const mailA = laneA.filter((i) => i.kind !== 'sendfail' && i.kind !== 'ses');
    const alertA = laneA.filter((i) => i.kind === 'sendfail' || i.kind === 'ses');

    for (const item of alertA) {
        // A failure and a bounce alert are never collapsed into a group with
        // ordinary mail. Each is a distinct thing to act on and each has its
        // own single correct next step.
        const opts = item.kind === 'sendfail'
            ? failOptions(item, !loud, mailbox)
            : sesOptions(item, !loud, mailbox);
        shown.push(reg.showNotification(item.title || 'Jarurat Mail', opts));
    }

    if (mailA.length) {
        if (existingA.length + mailA.length > LANE_A_MAX) {
            // Past three, individual notifications stop being readable and the
            // platform starts hiding them behind its own summary. One
            // notification we control beats four the browser folds up.
            const senders = [];
            for (const n of existingA) senders.push(clip(n.title || '', 40));
            for (const item of mailA) senders.push(clip(senderOf(item), 40));
            for (const n of existingA) { try { n.close(); } catch (e) { /* already gone */ } }
            const opts = {
                body: senders.slice(0, 4).join('\n'),
                icon: ICON,
                badge: BADGE,
                tag: 'jm-a:' + mailbox,
                renotify: true,
                requireInteraction: false,
                timestamp: Date.now(),
                dir: 'auto',
                lang: '',
                // Neither Archive nor Mark read is meaningful across four
                // different messages from four different people, so the group
                // offers the only action that is.
                actions: [{ action: 'open', title: 'Open inbox' }],
                data: { v: 1, lane: 'A', kind: 'group', mailbox: mailbox, url: '/mail' }
            };
            if (!loud) opts.silent = true;
            shown.push(reg.showNotification(senders.length + ' messages need you', opts));
        } else {
            for (const item of mailA) {
                shown.push(reg.showNotification(clip(senderOf(item), 40),
                    laneAOptions(item, !loud, mailbox)));
            }
        }
    }

    // --- Lane B ---------------------------------------------------------
    if (laneB.length) {
        // The rolling notification's own data is the counter. Reading it back
        // off the notification rather than out of IndexedDB means dismissing
        // it really does reset the count, which is exactly what a person
        // dismissing it is saying.
        let lines = [];
        let ids = [];
        if (reg.getNotifications) {
            const prior = (await reg.getNotifications({ tag: 'jm-b:' + mailbox }))[0];
            if (prior && prior.data) {
                lines = Array.isArray(prior.data.lines) ? prior.data.lines.slice() : [];
                ids = Array.isArray(prior.data.ids) ? prior.data.ids.slice() : [];
            }
        }
        // Forward through the batch, unshifting each one, so the LAST message in
        // the payload ends up at the top of the list. Walking the batch backwards
        // reads as the obvious way to build a newest-first list and produces the
        // opposite: a three-message payload came out with its middle message
        // listed first, which is a lie about which mail is new.
        for (let i = 0; i < laneB.length; i++) {
            const item = laneB[i];
            lines.unshift(clip(senderOf(item), 28) + ': ' + clip(item.subject || '(no subject)', 60));
            ids.unshift(item.id);
        }
        const newest = laneB[laneB.length - 1];
        const opts = laneBOptions(lines, ids, mailbox, newest);
        opts.data.lines = lines.slice(0, LANE_B_LINES);
        let title;
        if (ids.length > 1) {
            title = ids.length + ' new messages';
        } else {
            title = clip(senderOf(newest), 40);
            opts.body = clip(newest.subject || '(no subject)', 120);
        }
        shown.push(reg.showNotification(title, opts));
    }

    await Promise.all(shown.map((p) => Promise.resolve(p).catch(() => null)));
    await nudgeClients();
}

/**
 * A StateChange carries no content, so the only way to say who wrote and what
 * about is to ask. This is the one place in this worker that touches /api,
 * and it is a plain network fetch with no cache read and no cache write, so
 * the deny list at the top of the file is respected rather than bypassed.
 */
async function fromPoll() {
    try {
        const res = await fetch('/api/mail/poll', {
            credentials: 'include',
            cache: 'no-store',
            headers: { 'Accept': 'application/json' }
        });
        if (!res.ok) return null;
        return await res.json();
    } catch (e) { return null; }
}

/**
 * The RFC 8620 verification handshake, which arrives as a real push.
 *
 * It is not mail and there is nothing here a person should read, but a push
 * handler that resolves without showing anything is what browsers punish, so
 * one notification goes up and comes down again a moment later. It is tagged,
 * so a retry replaces it rather than stacking.
 */
async function verify(payload) {
    const reg = self.registration;
    await reg.showNotification('Setting up notifications', {
        body: 'Jarurat Mail is finishing setup on this device.',
        icon: ICON, badge: BADGE, tag: 'jm-setup', silent: true, renotify: false,
        data: { v: 1, kind: 'setup', url: '/mail' }
    }).catch(() => null);
    try {
        await apiPostJson('/api/mail/push/seen', {
            deviceClientId: (await kvGet('deviceClientId')) || '',
            verificationCode: payload.verificationCode || ''
        });
    } catch (e) { /* the server verifies itself out of band; this is a shortcut, not the path */ }
    await new Promise((r) => setTimeout(r, 1500));
    const up = await reg.getNotifications({ tag: 'jm-setup' });
    for (const n of up) { try { n.close(); } catch (e) { /* gone */ } }
}

/**
 * Tells the server that a push really did arrive on this device.
 *
 * This matters more than it looks. The subscription is verified server side
 * without the push ever being delivered, because the verification code can be
 * read straight back off PushSubscription/get, so "verified" is evidence of
 * nothing. This POST is the only real proof, and it is what lets the page stop
 * paying for a poll it no longer needs.
 */
async function reportSeen() {
    const id = await kvGet('deviceClientId');
    if (!id) return;
    if (await kvGet('pushSeen')) return;      // once is enough; this is a fact, not a heartbeat
    try {
        const res = await apiPostJson('/api/mail/push/seen', { deviceClientId: id });
        if (res.ok) await kvPut('pushSeen', true);
    } catch (e) { /* offline; the next push tries again */ }
}

self.addEventListener('push', (event) => {
    event.waitUntil((async () => {
        let payload = null;
        try { payload = event.data ? event.data.json() : null; } catch (e) { payload = null; }

        const type = payload && payload['@type'];

        if (type === 'PushVerification') { await verify(payload); return; }

        await reportSeen();

        const mailbox = (payload && payload.mailbox) || (await kvGet('mailbox')) || 'inbox';

        /* The application's own notification, arriving with every decision
           already made: which lane, what it says, whether it may make a sound.
           Rendered as sent, with two exceptions that only this side can know
           about, and both of them can only ever make it quieter:

             - the sound floor, because the server has no idea what this device
               was shown thirty seconds ago by some other trigger, and
             - the person having the mailbox open in front of them, because the
               server has no idea which of their devices is in their hand.

           A silent:true from the server is never overridden into a sound. */
        if (payload && payload.v === 1 && payload.type && payload.lane && !type) {
            await renderServerNotification(payload, mailbox);
            return;
        }

        if (type === 'EmailPush' && Array.isArray(payload.emails)) {
            await renderBatch(payload.emails.map((e) => ({
                id: e.id,
                threadId: e.threadId,
                subject: e.subject,
                preview: e.preview,
                receivedAt: e.receivedAt,
                from: e.from,
                fromEmail: (Array.isArray(e.from) && e.from[0] && e.from[0].email) || '',
                // The server classifies. The VIP list and the saved searches
                // live in mailbox_settings, and shipping them to a browser to
                // be evaluated would put a person's VIP list in a payload.
                lane: e.lane || LANE_A
            })), mailbox);
            return;
        }

        if (type === 'JmNotify' && Array.isArray(payload.items)) {
            await renderBatch(payload.items, mailbox);
            return;
        }

        // StateChange, or anything we do not recognise. Ask the poll endpoint
        // what happened. When the session has expired there is no answer to be
        // had, and a notification saying only that mail arrived is still a true
        // statement and still worth a tap.
        const data = await fromPoll();
        const newest = data && data.newest;
        if (newest && newest.id && !newest.seen) {
            await renderBatch([{
                id: newest.id,
                subject: newest.subject,
                receivedAt: newest.receivedAt,
                from: newest.from,
                lane: newest.lane || LANE_A
            }], (data && data.mailbox) || mailbox);
            return;
        }
        if (data) {
            // Something changed, but not into an unread message: mail read on
            // another device, or a deletion. The tabs repaint and nothing is
            // announced, which is the correct answer to a non-event. One
            // notification still has to appear, so it is the silent rolling
            // one, which replaces itself rather than adding to the pile.
            await nudgeClients();
        }
        await self.registration.showNotification('New mail', {
            body: data ? 'Your mailbox was updated.' : 'Open Jarurat Mail to read it.',
            icon: ICON, badge: BADGE, tag: 'jm-b:' + mailbox, silent: true, renotify: false,
            timestamp: Date.now(),
            data: { v: 1, lane: 'B', kind: 'mail', mailbox: mailbox, url: '/mail' }
        });
    })());
});

/**
 * The browser rotates a push subscription on its own schedule and without
 * asking, and the old endpoint stops working the moment it does. This event is
 * the only warning there is, and a subscription that is not replaced here goes
 * silent permanently with nothing on screen to say so.
 *
 * The old subscription's applicationServerKey is reused rather than re-fetched,
 * because event.oldSubscription carries it and a fetch would fail on a device
 * that happens to be offline at the moment the rotation happens.
 */
self.addEventListener('pushsubscriptionchange', (event) => {
    event.waitUntil((async () => {
        const old = event.oldSubscription || null;
        let key = old && old.options && old.options.applicationServerKey;
        if (!key) key = await kvGet('applicationServerKey');
        if (!key) return;
        let fresh = event.newSubscription || null;
        if (!fresh) {
            try {
                fresh = await self.registration.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: key
                });
            } catch (e) { return; }
        }
        const json = fresh.toJSON();
        // A rotated endpoint has not proved itself, whatever the old one did,
        // so the page goes back to polling until a push lands on the new one.
        await kvPut('pushSeen', false);
        try {
            await apiPostJson('/api/mail/push/subscribe', {
                deviceClientId: (await kvGet('deviceClientId')) || '',
                endpoint: json.endpoint,
                keys: json.keys
            });
        } catch (e) { /* the page re-registers on its next load; see notify.js */ }
    })());
});

/* ======================================================================
 * CLICKS
 * ====================================================================== */

/* The CSRF token is a non-HttpOnly cookie, which a page reads off
   document.cookie. A worker has no document, so there are three routes and all
   three are needed: the token the page baked into the notification when the
   page created it, the Cookie Store API where the engine has one (Chromium),
   and an open tab asked over a MessageChannel where it does not (Firefox). A
   notification created by a push and clicked with every tab shut has only the
   second route, which is why the action buttons fall back to opening the
   message rather than silently failing on an engine that has none of them. */

/* The push endpoints post JSON rather than a form, so apiPost's URLSearchParams body
 * is the wrong shape for them, and all three were written with a bare fetch and no
 * CSRF header at all. Every one of them is a 403 in production: /api/mail/** sits on
 * the console chain, which has CookieCsrfTokenRepository enabled and exempts only the
 * one-click unsubscribe and the SNS webhook.
 *
 * The consequences were both invisible and total. pushSeen could never be set, so the
 * app could never conclude push was working, so the poll never stood down and the
 * settings screen could never say push was proved. And a browser-rotated subscription
 * was never re-registered, so push died permanently on that device with nothing on
 * screen to say so, which is the exact failure the pushsubscriptionchange handler
 * exists to prevent.
 *
 * csrfToken() reads cookieStore first and only falls back to asking a window, so this
 * still works when the app is closed, which is when a push actually arrives. */
async function apiPostJson(path, payload) {
    const token = await csrfToken(null);
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['X-XSRF-TOKEN'] = token;
    return fetch(path, {
        method: 'POST',
        credentials: 'include',
        headers: headers,
        body: JSON.stringify(payload)
    });
}

async function csrfToken(data) {
    if (data && data.csrf) return data.csrf;
    try {
        if (self.cookieStore) {
            const c = await self.cookieStore.get('XSRF-TOKEN');
            if (c && c.value) return decodeURIComponent(c.value);
        }
    } catch (e) { /* not available here */ }
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of windows) {
        const answer = await new Promise((resolve) => {
            let done = false;
            const ch = new MessageChannel();
            ch.port1.onmessage = (e) => { done = true; resolve(e.data && e.data.csrf); };
            try { c.postMessage({ type: 'jm-csrf' }, [ch.port2]); } catch (e2) { resolve(null); return; }
            setTimeout(() => { if (!done) resolve(null); }, 400);
        });
        if (answer) return answer;
    }
    return null;
}

async function apiPost(path, params, data) {
    const token = await csrfToken(data);
    if (!token) return { ok: false, reason: 'csrf' };
    const body = new URLSearchParams();
    for (const k of Object.keys(params)) body.append(k, params[k]);
    try {
        const res = await fetch(path, {
            method: 'POST',
            credentials: 'include',
            headers: {
                'X-XSRF-TOKEN': token,
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: body.toString()
        });
        return { ok: res.ok, reason: res.ok ? '' : String(res.status) };
    } catch (e) { return { ok: false, reason: 'offline' }; }
}

/** Archive is a folder move, so the folder's id has to be looked up first. */
async function archiveFolderId() {
    try {
        const res = await fetch('/api/mail/folders', {
            credentials: 'include', cache: 'no-store', headers: { 'Accept': 'application/json' }
        });
        if (!res.ok) return null;
        const data = await res.json();
        const list = Array.isArray(data) ? data : (data.folders || []);
        for (const f of list) if (f && f.role === 'archive') return f.id;
        return null;
    } catch (e) { return null; }
}

/**
 * Puts a failed action back on screen instead of letting the notification
 * vanish and leaving the person believing it worked.
 *
 * This is why close() is no longer the first line of the handler for an action
 * click: an archive that 403s because this mailbox has no MAIL_SEND, or that
 * never left the device because the phone is in a lift, has to say so. A body
 * tap still closes first, because a body tap has nothing to undo.
 */
function restore(notification, message) {
    return self.registration.showNotification(notification.title, {
        body: message,
        icon: ICON,
        badge: BADGE,
        tag: notification.tag,
        renotify: false,
        silent: true,
        timestamp: notification.timestamp || Date.now(),
        data: notification.data || {}
    }).catch(() => null);
}

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
 * The message now carries guarded:true. That is the tab's instruction not to throw
 * away typed text: if the composer is open with something in it, notify.js asks
 * before it swaps the pane rather than obeying. A notification click is allowed to
 * decline to navigate, and this is the only state in which it is, because losing
 * text somebody typed to a background event is the failure people never report and
 * never forgive.
 *
 * This handler reads no cache and writes no cache. The rules at the top of this
 * file are untouched by it. */
self.addEventListener('notificationclick', (event) => {
    const notification = event.notification;
    const data = notification.data || {};
    const id = data.id || '';
    const url = data.url || '/mail';
    const action = event.action || '';

    /** Focus an open /mail tab and hand it the id, or open one. */
    async function land(openId) {
        // includeUncontrolled matters: a tab that was already loaded when this
        // worker activated is not controlled by it, and that is the commonest tab
        // there is. Without the flag the reuse path would almost never be taken.
        const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });

        let target = null;
        for (const client of windows) {
            if (new URL(client.url).pathname === '/mail') { target = client; break; }
        }

        if (target) {
            // The message goes first and the focus second, which is a reversal of
            // what this handler used to do and is deliberate.
            //
            // focus() can reject. A browser is allowed to decline to hand focus to
            // a background client, and it does: Chromium answers InvalidAccessError,
            // "Not allowed to focus a window", whenever the click did not carry user
            // activation. That rejection used to go unhandled inside waitUntil, and
            // the postMessage below it never ran, so the tap did nothing at all and
            // read as a dead notification. Posting first means the tab is holding
            // the right message whatever focus decides.
            //
            // The rejection then still falls through to a new window, because the
            // person has to SEE something happen; a tab that quietly became correct
            // behind another window is not an answer to a tap. That risks a second
            // copy of the app on the one path where focus is refused, and it is the
            // lesser of the two, because guarded:true means the tab it left behind
            // cannot have thrown away anything that was typed into it.
            if (openId) {
                target.postMessage({ type: 'jm-open-mail', id: openId, guarded: true });
            } else if (url && url !== '/mail') {
                // A notification with no message id but a destination, such as a
                // failed send pointing at its outbox row. Focusing the tab and
                // leaving it where it was would consume the tap and show nothing,
                // so the tab is navigated. navigate() throws on a client this
                // worker does not control, which is the commonest kind of tab, and
                // the catch below turns that into a new window rather than nothing.
                try { if (target.navigate) await target.navigate(url); } catch (e) { /* uncontrolled */ }
            }
            try {
                if (target.focus) await target.focus();
                return;
            } catch (e) { /* refused; fall through to openWindow */ }
        }

        if (self.clients.openWindow) await self.clients.openWindow(url);
    }

    if (!action) {
        notification.close();
        event.waitUntil(land(id));
        return;
    }

    event.waitUntil((async () => {
        if (action === 'open' || action === 'outbox' || action === 'safety') {
            notification.close();
            if (action !== 'safety') {
                const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
                for (const client of windows) {
                    if (new URL(client.url).pathname !== '/mail') continue;
                    try { if (client.focus) await client.focus(); return; } catch (e) { break; }
                }
            }
            if (self.clients.openWindow) await self.clients.openWindow(url);
            return;
        }

        if (action === 'read') {
            // A collapsed Lane B notification stands for several messages, so
            // clearing it clears all of them rather than only the newest.
            const ids = Array.isArray(data.ids) && data.ids.length ? data.ids : (id ? [id] : []);
            if (!ids.length) { notification.close(); await land(id); return; }
            let failed = '';
            for (const one of ids) {
                const r = await apiPost('/api/mail/read', { id: one, value: 'true' }, data);
                if (!r.ok) { failed = r.reason; break; }
            }
            notification.close();
            if (failed) {
                await restore(notification, failed === 'csrf'
                    ? 'Open Jarurat Mail to mark this read.'
                    : 'Could not mark it read. Tap to open.');
            } else {
                await nudgeClients();
            }
            return;
        }

        if (action === 'archive') {
            const folder = await archiveFolderId();
            if (!folder) {
                notification.close();
                await restore(notification, 'This mailbox has no archive folder. Tap to open.');
                return;
            }
            const ids = Array.isArray(data.ids) && data.ids.length ? data.ids : (id ? [id] : []);
            let failed = '';
            for (const one of ids) {
                const r = await apiPost('/api/mail/move', { id: one, folder: folder }, data);
                if (!r.ok) { failed = r.reason; break; }
            }
            notification.close();
            if (failed) {
                await restore(notification, failed === 'csrf'
                    ? 'Open Jarurat Mail to archive this.'
                    : 'Could not archive it. Tap to open.');
            } else {
                await nudgeClients();
            }
            return;
        }

        notification.close();
        await land(id);
    })());
});

/**
 * A dismissed rolling notification has had its count reset by the person
 * dismissing it, and that is the whole of the bookkeeping: the counter lives in
 * the notification's own data, so closing it is what clears it. Nothing has to
 * be written here.
 *
 * The handler still exists because the tabs should repaint. Clearing the shade
 * is often the moment somebody goes back to the app, and a badge that is one
 * poll out of date at that moment is the one they will notice.
 */
self.addEventListener('notificationclose', (event) => {
    const data = event.notification.data || {};
    if (data.kind === 'setup') return;
    event.waitUntil(nudgeClients());
});

self.addEventListener('message', (event) => {
    if (event.data === 'skipWaiting') { self.skipWaiting(); return; }
    const msg = event.data;
    if (!msg || typeof msg !== 'object') return;
    // The page owns the identity of this device and the mailbox pinned to the
    // session. This worker outlives both the page and the session, so it keeps a
    // copy for the push handler and for pushsubscriptionchange, which fires with
    // no page anywhere. Nothing secret is kept here: an opaque device id, the
    // mailbox address that is already in every notification, and the server's
    // own public VAPID key.
    if (msg.type === 'jm-push-state') {
        event.waitUntil((async () => {
            if (msg.deviceClientId) await kvPut('deviceClientId', msg.deviceClientId);
            if (msg.mailbox) await kvPut('mailbox', msg.mailbox);
            if (msg.applicationServerKey) await kvPut('applicationServerKey', msg.applicationServerKey);
            if (msg.pushSeen === false) await kvPut('pushSeen', false);
            if (msg.pushSeen === true) await kvPut('pushSeen', true);
        })());
    }
});
