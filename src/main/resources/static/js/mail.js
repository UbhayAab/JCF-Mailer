/* =========================================================================
   Jarurat Mail - webmail screen
   Talks only to /api/mail/**. Message bodies arrive already sanitised and
   already wrapped in a standalone document; this file never assembles sender
   HTML into markup of its own. See MailHtmlSanitizer for the reasoning.

   Below 900px this is a phone app: a header of its own, one scrolling list, a
   fixed tab bar, and a reader that slides in over the list. That shell is a
   small state machine whose only storage is the history stack, because in
   display:standalone the phone's own back gesture is the only back there is.
   ========================================================================= */

const $ = id => document.getElementById(id);

function esc(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function can(permission) { return PERMS.indexOf(permission) >= 0; }

/** Every icon on this page is a symbol we ship, never a font character. */
function icon(id, cls) {
  return '<svg class="ic' + (cls ? ' ' + cls : '') + '" aria-hidden="true"><use href="#'
    + id + '"/></svg>';
}

function toast(message, bad) {
  const el = document.createElement('div');
  el.className = 'toast' + (bad ? ' bad' : '');
  el.textContent = message;
  $('toasts').appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, 4200);
  setTimeout(() => el.remove(), 4600);
}

/* ---------- transport ---------- */

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/**
 * The mailbox is locked, which is not the same as being signed out.
 * 401 means the console session died and the browser belongs at /login.
 * 409 with locked:true means the console session is fine and only the mail
 * server still wants its password, so the answer is a prompt, not a redirect.
 */
class Locked extends Error {}

function readBody(res) {
  return res.json().catch(() => null);   // an error page is not always json
}

function raise(res, payload) {
  if (res.status === 409 && payload && payload.locked) {
    throw new Locked(payload.error || 'Your mailbox is not open on this device.');
  }
  throw new Error((payload && payload.error) || 'Request failed');
}

async function api(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');
  const payload = await readBody(res);
  if (!res.ok) raise(res, payload);
  return payload;
}

async function post(url, params) {
  const body = new URLSearchParams();
  Object.keys(params || {}).forEach(k => {
    if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]);
  });
  const res = await fetch(url, { method: 'POST', body, headers: { 'X-XSRF-TOKEN': csrfToken() } });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');
  const payload = await readBody(res);
  if (!res.ok) raise(res, payload);
  return payload || {};
}

/**
 * True when the failure was handled by putting the unlock prompt on screen.
 * During boot it answers true without prompting, because status and folders are
 * now fired together and a 409 from folders would otherwise race the status
 * answer into two unlock prompts stacked on each other.
 */
function handled(e) {
  if (!(e instanceof Locked)) return false;
  // S.restoring joins S.booting here for the same reason. While the device is
  // handing the mailbox credential back, every mail endpoint answers 409, and a
  // 409 that is about to stop being true must not put a password prompt up.
  if (S.booting || S.restoring) return true;
  openUnlock(e.message);
  return true;
}

/* ---------- state ---------- */

const S = {
  mailbox: '',
  folders: [],
  folderId: null,
  folderName: 'Inbox',
  folderRole: '',
  messages: [],
  total: 0,
  offset: 0,
  limit: 40,
  selected: null,
  query: '',
  booting: true,
  restoring: false,    // the device is handing the mailbox credential back right now
  reader: null,        // the message object the reader is showing
  readerFor: null,     // the id it was asked for, so a slow answer cannot win
  composeSeed: '',
  listTop: 0,          // the list offset, held while the reader is over it
  imagesFor: null,     // the one message id the reader is currently showing images for
  files: [],           // File objects staged on the compose sheet, not yet uploaded
  sending: null,       // the live XMLHttpRequest, so a second Send cannot start another
  // Filled from /api/mail/status so there is one number and the server owns it.
  // These are the fallbacks for the moment before that answer arrives.
  attachLimit: 17825792,
  attachMaxFiles: 20
};

/* Extensions the send endpoint refuses. Kept here as well as on the server so the
   sender is told at the moment they choose the file rather than after uploading it
   over mobile data, which is the difference between a correction and a wasted
   minute. The server list is the one that decides; this one only saves the trip,
   and the two are allowed to drift apart in the safe direction because anything
   this misses is still refused there. Mirrors Attachment.REFUSED_EXTENSIONS. */
const REFUSED_EXT = new Set([
  'exe', 'scr', 'bat', 'cmd', 'com', 'pif', 'js', 'vbs', 'jar',
  'jse', 'vbe', 'ws', 'wsf', 'wsh', 'wsc', 'sct',
  'ps1', 'ps1xml', 'psc1', 'msh', 'msi', 'msp', 'mst',
  'hta', 'cpl', 'msc', 'lnk', 'scf', 'reg', 'chm', 'hlp',
  'ade', 'adp', 'ins', 'isp', 'its', 'ksh', 'csh', 'sh',
  'dll', 'ocx', 'sys', 'drv', 'gadget', 'application', 'appref-ms', 'iso'
]);

const FOLDER_ICON = {
  inbox: 'i-inbox', sent: 'i-send', drafts: 'i-draft',
  junk: 'i-spam', trash: 'i-trash', archive: 'i-archive'
};

/* Copy for a folder that has nothing in it. Section 9 asks every empty state to
   name what would be here and offer the action that puts something here, which
   is a different sentence for every folder. */
const EMPTY_FOLDER = {
  inbox:   { icon: 'i-inbox',   line: 'No mail in your inbox.',              act: 'refresh' },
  sent:    { icon: 'i-send',    line: 'You have not sent anything yet.',     act: 'compose' },
  drafts:  { icon: 'i-draft',   line: 'No drafts waiting here.',             act: 'compose' },
  junk:    { icon: 'i-spam',    line: 'No spam. Nothing to look at.',        act: 'refresh' },
  trash:   { icon: 'i-trash',   line: 'The bin is empty.',                   act: 'refresh' },
  archive: { icon: 'i-archive', line: 'Nothing archived yet.',               act: 'refresh' }
};

/* ---------- avatars ---------- */

/* Derived, not assigned, so the same sender is the same colour in every folder,
   on every device and after every reload, with no state stored anywhere. This
   is FNV-1a, and Math.imul is not optional: a plain multiply leaves the double
   past 2^53 and the low bits stop moving, which collapses the whole hash into a
   handful of buckets and makes half an inbox the same colour. */
function avatarSlot(address) {
  const s = String(address || '').toLowerCase();
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return (h >>> 0) & 7;
}

/* Array.from and not [0]: a display name starting outside the basic plane would
   otherwise be sliced through a surrogate pair and paint a replacement box. */
function avatarInitial(display, email) {
  const chars = Array.from(String(display || email || '').trim());
  for (let i = 0; i < chars.length; i++) {
    if (/[\p{L}\p{N}]/u.test(chars[i])) return chars[i].toUpperCase();
  }
  return '?';
}

function paintAvatar(el, address, display) {
  if (!el) return;
  el.setAttribute('data-c', String(avatarSlot(address)));
  el.textContent = avatarInitial(display, address);
}

/* ---------- time ---------- */

function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d)) return '';
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
  const days = Math.floor((now - d) / 86400000);
  if (days < 1) return 'Yesterday';
  if (days < 7) return d.toLocaleDateString('en-IN', { weekday: 'short' });
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

function fullWhen(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? '' : d.toLocaleString('en-IN', {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false
  });
}

function bytes(n) {
  if (!n) return '';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return Math.round(n / 1024) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

/* =========================================================================
   The shell state machine

   One object, one reconciler, one pusher, one closer. applyState is the only
   thing that touches the DOM for a pane or an overlay, go() is the only thing
   that pushes, and everything that means "back" calls history.back(). Splitting
   that rule is how a back button starts needing two taps: a handler that
   removes the class AND pops makes popstate close a second time and eat the
   entry underneath.

   It is absolute rather than incremental because Android back can land on any
   state from any other. With the reader open and compose over it the stack is
   [list, reader, compose], and a handler that closes "the topmost thing" has no
   way to know what it is now in.
   ========================================================================= */

const BASE = { pane: 'list', overlay: null, id: null, pushed: false };
let UI = Object.assign({}, BASE);

const mqPhone  = window.matchMedia('(max-width:899.98px)');
/* Evaluated at call time and never cached: rotating the phone changes it. */
const onePane  = () => window.matchMedia('(max-width:1100px)').matches;

const FOCUS = { saved: null };
let afterPop = null;
let popGuard = false;

function inert(el, on) { if (el) el.inert = !!on; }

function applyState(next) {
  const prev = UI;
  UI = Object.assign({}, BASE, next || {});

  const reading = UI.pane === 'reader';
  const modal = !!UI.overlay && UI.overlay !== 'search';
  const phone = mqPhone.matches;

  // Measured, not assumed: a scroll box that stops being scrollable clamps its
  // own scrollTop to zero, so the offset does NOT survive overflow:hidden on its
  // own. It is read here on the way in and put back on the way out, which is
  // what makes Back land on the row that was tapped.
  if (reading && prev.pane !== 'reader') S.listTop = $('list').scrollTop;

  document.body.dataset.pane = UI.pane;
  document.body.classList.toggle('searching', UI.overlay === 'search');
  // The 900 to 1100 block still swaps panes with display:none on .reading, and
  // it is deliberately untouched, so the class has to keep being set.
  $('mailGrid').classList.toggle('reading', reading);

  $('foldersSheet').classList.toggle('open', UI.overlay === 'folders' || UI.overlay === 'move');
  $('accountSheet').classList.toggle('open', UI.overlay === 'account');
  $('devicesSheet').classList.toggle('open', UI.overlay === 'devices');
  $('moreSheet').classList.toggle('open', UI.overlay === 'more');
  $('composeSheet').classList.toggle('open', UI.overlay === 'compose');
  /* Fetched on the transition rather than by whichever control opened the sheet,
     because a forward gesture back into this state pushes no control at all and
     the sheet would come up permanently empty. Entering is also the only moment
     the list is worth re-reading: "last used" is a clock, and a list cached from
     ten minutes ago is a list that quietly lies about it. */
  if (UI.overlay === 'devices' && prev.overlay !== 'devices') loadDevices();
  // Closing the composer files what is in it. popstate cannot be cancelled and a
  // save cannot be awaited from there, so the close is allowed to happen and the
  // request follows it; a failure leaves the composer's own state in memory and
  // says so on the sheet, which is what reopening restores from.
  if (prev.overlay === 'compose' && UI.overlay !== 'compose') {
    closeLinkRow(false);
    acClose();
    if (composeHasContent() && composeDirty()) saveDraft('close');
    dockToKeyboard();
  }
  $('foldersSheetTitle').textContent = UI.overlay === 'move' ? 'Move to folder' : 'Folders';

  // visibility:hidden is the portable half for older Android WebView; inert is
  // the half that keeps a parked pane out of the tab order everywhere else.
  inert(document.querySelector('.app'), modal);
  inert($('tabbar'), modal);
  inert($('reader'), phone && !reading);
  inert($('list'), phone && reading);
  $('reader').setAttribute('aria-hidden', (phone && !reading) ? 'true' : 'false');

  // Devices is reached through the You sheet and is a screen inside it, so the
  // You tab stays lit rather than the bar going blank on a state the person
  // walked into from there. Move does the same for Folders.
  const TAB_FOR = { move: 'folders', devices: 'you' };
  const active = reading ? '' : (TAB_FOR[UI.overlay] || UI.overlay || 'inbox');
  const tabs = $('tabbar').querySelectorAll('.tab');
  for (let i = 0; i < tabs.length; i++) {
    if (tabs[i].dataset.tab === active) tabs[i].setAttribute('aria-current', 'page');
    else tabs[i].removeAttribute('aria-current');
  }

  if (!reading && prev.pane === 'reader' && S.listTop) {
    $('list').scrollTop = S.listTop;
    S.listTop = 0;
  }

  // Closing search is the one transition that owns some app state as well as
  // some chrome: a query left in S would keep filtering a list whose search
  // field is no longer on screen.
  if (prev.overlay === 'search' && UI.overlay !== 'search' && S.query) {
    S.query = '';
    $('q').value = '';
    setTitles(S.folderName);
    loadMessages(true);
  }
  syncFocus(prev);
}

function focusSoon(el) {
  if (!el) return;
  requestAnimationFrame(() => { try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); } });
}

/* Records where focus was on the way into a layer and puts it back on the way
   out, or Back strands the focus ring behind a sheet nobody can see. */
function syncFocus(prev) {
  const wasLayer = prev.pane === 'reader' || !!prev.overlay;
  const isLayer = UI.pane === 'reader' || !!UI.overlay;
  if (isLayer && !wasLayer) FOCUS.saved = document.activeElement;
  if (prev.pane === UI.pane && prev.overlay === UI.overlay) return;

  if (UI.overlay === 'compose') focusSoon($('cTo'));
  else if (UI.overlay === 'search') focusSoon($('q'));
  else if (UI.overlay === 'folders' || UI.overlay === 'move') focusSoon($('sheetFolders').querySelector('.fold'));
  else if (UI.overlay === 'account') focusSoon($('shStudio'));
  else if (UI.overlay === 'devices') focusSoon($('devicesSheet').querySelector('[data-close]'));
  else if (UI.overlay === 'more') focusSoon($('moreSheet').querySelector('.mrow'));
  else if (UI.pane === 'reader') focusSoon($('reader').querySelector('[data-act="back"]'));
  else if (wasLayer && FOCUS.saved && document.contains(FOCUS.saved)) {
    focusSoon(FOCUS.saved);
    FOCUS.saved = null;
  }
}

function go(patch) {
  const next = Object.assign({}, UI, patch);
  history.pushState({ jm: next }, '');
  applyState(next);
}

/** Same reconciler, no new entry. For a state that must not add a back step. */
function replace(patch) {
  const next = Object.assign({}, UI, patch);
  history.replaceState({ jm: next }, '');
  applyState(next);
}

/**
 * Run something once the pop has actually landed. Choosing a folder in a sheet
 * has to close the sheet and then act, and doing both in one path would mutate
 * and pop at the same time, which is exactly the bug this machine exists to
 * avoid. The work is queued for the popstate instead.
 */
function popThen(fn) { afterPop = fn; history.back(); }

/** An overlay opened while another is already up replaces it rather than stacking. */
function goOverlay(name) {
  if (UI.overlay === name) return;
  if (UI.overlay) replace({ overlay: name });
  else go({ overlay: name });
}

window.addEventListener('popstate', function (e) {
  const s = (e.state && e.state.jm) || BASE;

  // There used to be a confirm here asking whether to throw the message away.
  // It is gone, because closing the composer now files a draft instead of
  // discarding one, and a question whose safe answer is always the same is a
  // question people learn to click through. Discard is a deliberate button on
  // the sheet. The save itself is fired from applyState, on the transition.

  applyState(s);
  const run = afterPop;
  afterPop = null;
  if (run) run();
  // Forward navigation back into a message the reader is not showing any more.
  if (s.pane === 'reader' && s.id && S.readerFor !== s.id) openMessage(s.id, { push: false });
});

/**
 * #q and #btnSend are single nodes that move between two slots rather than two
 * mirrored copies. appendChild on a live node keeps its listeners, its value
 * and its disabled state, so one rotation mid compose loses neither the draft
 * nor the pending state of the send button.
 */
function placeChrome() {
  const phone = mqPhone.matches;
  (phone ? $('qPhone') : $('qDesk')).appendChild($('q'));
  (phone ? $('sendHead') : $('sendFoot')).appendChild($('btnSend'));
  // Both slots are inside .sheet-h. On a phone the compose footer is not on
  // screen at all, so an address parked there would be an address nobody sees;
  // under the title it is a caption of the thing it belongs to.
  (phone ? $('fromPhone') : $('fromHead')).appendChild($('composeFrom'));
  // The format bar is the third node that moves rather than being mirrored. Over
  // the editable on a laptop; docked above the software keyboard on a phone, for
  // the reason dockToKeyboard sets out at length.
  (phone ? $('fmtDock') : $('fmtDeck')).appendChild($('fmtGroup'));
  measureToolbar();
}

mqPhone.addEventListener('change', () => { placeChrome(); applyState(UI); dockToKeyboard(); });

/* ---------- the three states every pane owes ---------- */

function errState(message, retry) {
  return '<div class="errstate" role="alert">'
    + icon('i-warn', 'ic-32')
    + '<span class="head">Could not load this</span>'
    + '<span class="why">' + esc(message || 'The mail server did not answer.') + '</span>'
    + (retry ? '<button class="btn sm" type="button" data-retry="' + esc(retry) + '">Try again</button>' : '')
    + '</div>';
}

function emptyState(sprite, line, action, label) {
  return '<div class="empty">' + icon(sprite, 'ic-32')
    + '<span class="line">' + esc(line) + '</span>'
    + (action ? '<button class="btn sm" type="button" data-do="' + esc(action) + '">'
        + esc(label) + '</button>' : '')
    + '</div>';
}

function loadState(label) {
  return '<div class="loadstate"><span class="spin" aria-hidden="true"></span>'
    + '<span>' + esc(label || 'Loading') + '</span></div>';
}

/* Ten rows at the real row geometry, so nothing shifts when the data lands.
   A spinner is for a control; a list gets skeleton rows. */
function skeleton() {
  let out = '<div class="skel" aria-hidden="true">';
  for (let i = 0; i < 10; i++) {
    out += '<div class="skrow"><span class="sk sk-av"></span><span class="skb">'
      + '<span class="sk sk-1"></span><span class="sk sk-2"></span><span class="sk sk-3"></span></span></div>';
  }
  return out + '</div>';
}

/* One delegated handler, because these blocks are written into three different
   panes and each is replaced whenever the pane reloads. */
document.addEventListener('click', function (e) {
  const t = e.target.closest && e.target.closest('[data-retry],[data-do]');
  if (!t) return;
  const retry = t.getAttribute('data-retry');
  if (retry === 'folders') { loadFolders(); return; }
  if (retry === 'messages') { loadMessages(true); return; }
  if (retry === 'devices') { loadDevices(); return; }
  const what = t.getAttribute('data-do');
  if (what === 'refresh') refreshAll();
  else if (what === 'compose') openCompose();
  else if (what === 'clear-search') { $('q').value = ''; S.query = ''; setTitles(S.folderName); loadMessages(true); }
});

/* ---------- folders ---------- */

async function loadFolders() {
  let data;
  try {
    data = await api('/api/mail/folders');
  } catch (e) {
    if (handled(e)) return;
    $('folders').innerHTML = errState(e.message, 'folders');
    $('list').innerHTML = errState(e.message, 'folders');
    $('list').setAttribute('aria-busy', 'false');
    setReader(errState('The mailbox could not be opened.', 'folders'));
    return;
  }
  applyFolders(data);
}

function applyFolders(data) {
  S.folders = data.folders || [];
  if (data.mailbox) {
    S.mailbox = data.mailbox;
    identify(data.mailbox);
  }
  renderFolders();

  const inbox = S.folders.find(f => f.role === 'inbox') || S.folders[0];
  if (inbox && !S.folderId) selectFolder(inbox.id, inbox.name, inbox.role);
  else if (S.folderId) loadMessages(true);
}

/** The signed in address, in all four places that show it. */
function identify(address) {
  $('railEmail').textContent = address;
  $('sheetEmail').textContent = address;
  $('composeFrom').textContent = address;
  paintAvatar($('railAvatar'), address, address);
  paintAvatar($('pheadAvatar'), address, address);
  paintAvatar($('sheetAvatar'), address, address);
}

/**
 * The row carries both numbers, because one numeral that silently means unread
 * on the inbox and total everywhere else is not something a reader can decode
 * from two greys at 11px. Unread is a filled chip, total is a plain numeral,
 * and the button's own label says which is which out loud for anyone who is
 * not reading the difference off the pixels.
 */
function folderRow(f) {
  const unread = f.unread > 0
    ? '<span class="fu">' + (f.unread > 99 ? '99+' : f.unread) + '</span>' : '';
  const total = f.total
    ? '<span class="ct">' + esc(String(f.total)) + '</span>' : '';
  const label = f.name
    + (f.unread > 0 ? ', ' + f.unread + ' unread' : '')
    + (f.total ? ', ' + f.total + ' message' + (f.total === 1 ? '' : 's') : '');
  return '<button type="button" class="fold' + (f.id === S.folderId ? ' on' : '') + '"'
    + ' data-id="' + esc(f.id) + '" data-name="' + esc(f.name) + '" data-role="' + esc(f.role) + '"'
    + ' aria-label="' + esc(label) + '">'
    + icon(FOLDER_ICON[f.role] || 'i-folder')
    + '<span class="fname">' + esc(f.name) + '</span>'
    + unread + total + '</button>';
}

function renderFolders() {
  const unread = S.folders.reduce((sum, f) => sum + (f.role === 'inbox' ? f.unread : 0), 0);
  const badge = $('tabUnread');
  badge.textContent = unread > 99 ? '99+' : String(unread);
  badge.hidden = unread <= 0;

  const rows = S.folders.map(folderRow).join('');
  $('folders').innerHTML = rows;
  $('sheetFolders').innerHTML = rows;
  const archive = S.folders.find(f => f.role === 'archive');
  const canArchive = !!archive && archive.id !== S.folderId && can('MAIL_SEND');
  $('rArchive').disabled = !canArchive;
  $('moreArchive').disabled = !canArchive;
}

function setTitles(text) {
  $('paneTitle').textContent = text;
  $('pheadTitle').textContent = text;
  $('rbarTitle').textContent = text;
}

function selectFolder(id, name, role) {
  S.folderId = id;
  S.folderName = name;
  S.folderRole = role || '';
  S.query = '';
  $('q').value = '';
  S.offset = 0;
  S.selected = null;
  S.reader = null;
  S.readerFor = null;
  setTitles(name);
  renderFolders();
  loadMessages(true);
  emptyReader();
  if (UI.pane === 'reader') leaveReader();
}

/* ---------- message list ---------- */

async function loadMessages(reset) {
  const box = $('list');
  if (reset) {
    S.offset = 0;
    S.messages = [];
    box.innerHTML = skeleton();
    box.setAttribute('aria-busy', 'true');
  }
  let data;
  try {
    data = S.query
      ? await api('/api/mail/search?q=' + encodeURIComponent(S.query)
          + '&offset=' + S.offset + '&limit=' + S.limit)
      : await api('/api/mail/messages?folder=' + encodeURIComponent(S.folderId)
          + '&role=' + encodeURIComponent(S.folderRole)
          + '&offset=' + S.offset + '&limit=' + S.limit);
  } catch (e) {
    box.setAttribute('aria-busy', 'false');
    if (handled(e)) return;
    box.innerHTML = errState(e.message, 'messages');
    return;
  }
  S.messages = S.messages.concat(data.messages || []);
  S.total = data.total || 0;
  box.setAttribute('aria-busy', 'false');
  renderList();
}

function rowMarks(m) {
  if (!m.hasAttachment && !m.flagged) return '';
  return '<span class="marks" aria-hidden="true">'
    + (m.hasAttachment ? icon('i-attach', 'ic-sm') : '')
    + (m.flagged ? icon('i-star-on', 'ic-sm star') : '')
    + '</span>';
}

/* Every child of the row is a span. A <button> takes phrasing content, and the
   row this replaced put three <div>s inside one. */
function messageRow(m) {
  const who = m.from.display || m.from.email;
  return '<button type="button" class="msg' + (m.seen ? '' : ' unread')
    + (m.id === S.selected ? ' on' : '') + '" data-id="' + esc(m.id) + '">'
    + '<span class="av" data-c="' + avatarSlot(m.from.email || who) + '" aria-hidden="true">'
    + esc(avatarInitial(m.from.display, m.from.email)) + '</span>'
    + '<span class="txt">'
    + '<span class="r1"><span class="from">' + esc(who) + '</span>'
    + '<span class="when">' + esc(when(m.receivedAt)) + '</span></span>'
    + '<span class="subj">' + esc(m.subject || '(no subject)') + '</span>'
    + '<span class="prev">' + esc(m.preview) + '</span></span>'
    + rowMarks(m) + '</button>';
}

function renderList() {
  const box = $('list');
  // innerHTML clamps scrollTop to zero while the box is empty, and Back landing
  // at the top of a folder instead of on the row you tapped is the whole
  // difference between a real back and a reload.
  const keep = box.scrollTop;

  if (!S.messages.length) {
    box.innerHTML = S.query
      ? emptyState('i-search', 'Nothing matched that search.', 'clear-search', 'Clear search')
      : folderEmptyState();
    return;
  }

  const rows = S.messages.map(messageRow).join('');
  const more = S.messages.length < S.total
    ? '<div class="listfoot"><button class="btn sm" type="button" id="btnMore">Load more</button>'
      + '<span>' + S.messages.length + ' of ' + S.total + '</span></div>'
    : '<div class="listfoot"><span>' + S.messages.length + ' message'
      + (S.messages.length === 1 ? '' : 's') + '</span></div>';

  box.innerHTML = rows + more;
  box.scrollTop = keep;

  const btn = $('btnMore');
  if (btn) btn.addEventListener('click', () => {
    btn.disabled = true;
    btn.textContent = 'Loading';
    S.offset += S.limit;
    loadMessages(false);
  });
}

function folderEmptyState() {
  const shape = EMPTY_FOLDER[S.folderRole] || { icon: 'i-mail', line: 'Nothing in this folder.', act: 'refresh' };
  const compose = shape.act === 'compose' && can('MAIL_SEND');
  return emptyState(shape.icon, shape.line,
    compose ? 'compose' : 'refresh',
    compose ? 'Write a message' : 'Check again');
}

/* Selection is one class on two rows, not a re-render. Rebuilding the whole
   list to move a highlight throws the scroll offset away. */
function selectRow(id) {
  const box = $('list');
  const was = box.querySelector('.msg.on');
  if (was) was.classList.remove('on');
  const now = box.querySelector('.msg[data-id="' + (window.CSS && CSS.escape ? CSS.escape(id) : id) + '"]');
  if (now) now.classList.add('on');
}

function setRowSeen(id, seen) {
  const box = $('list');
  const el = box.querySelector('.msg[data-id="' + (window.CSS && CSS.escape ? CSS.escape(id) : id) + '"]');
  if (el) el.classList.toggle('unread', !seen);
}

/* ---------- reader ---------- */

function setReader(html) {
  $('rbody').innerHTML = html;
  setReaderChrome(false);
}

/**
 * Section 9: an empty state names what would be here and carries the action
 * that puts something here. On a reader with nothing in it that action is
 * writing a message, which is also the only thing this pane can do without one.
 * Pass false where composing is not available, not merely unhelpful.
 */
function emptyReader(line, offerCompose) {
  const compose = offerCompose !== false && can('MAIL_SEND');
  setReader(emptyState('i-mail-open', line || 'Pick a message to read it.',
    compose ? 'compose' : '', compose ? 'Write a message' : ''));
}

/** The toolbar only offers what this session and this message actually allow. */
function setReaderChrome(hasMessage) {
  const send = can('MAIL_SEND');
  // A toolbar of seven greyed controls is the first thing in an empty pane and
  // it belongs to a message, so with no message it is not drawn at all. The
  // class is read only where both panes are on screen; below that width this
  // bar carries the Back button and has to stay.
  $('reader').classList.toggle('nomsg', !hasMessage);
  const ids = ['rReply', 'rReplyAll', 'rForward', 'rStar', 'rMove', 'rDelete', 'rMore'];
  ids.forEach(id => { $(id).disabled = !hasMessage; });
  ['rReply', 'rReplyAll', 'rForward', 'rMove', 'rDelete'].forEach(id => {
    if (!send) $(id).style.display = 'none';
  });
  const archive = S.folders.find(f => f.role === 'archive');
  const canArchive = hasMessage && send && !!archive && archive.id !== S.folderId;
  $('rArchive').disabled = !canArchive;
  $('moreArchive').disabled = !canArchive;
  $('rFab').disabled = !hasMessage || !send;
  $('rFab').style.display = send ? '' : 'none';
  // An empty pane has no sender, so the strip the bar carries on a phone goes
  // with the message it belongs to rather than outliving it.
  if (!hasMessage) { $('rbarWho').textContent = ''; $('reader').classList.remove('scrolled'); }
}

/**
 * On a phone the head scrolls away with the letter, which is the point: it is
 * part of the message, not part of the chrome. That leaves a letter with nobody's
 * name on it, so the toolbar picks the sender up at the moment the sender line
 * leaves the top of the scroller.
 *
 * An observer and not a scroll handler. This is the most used screen in the
 * product and the swipe that scrolls a message is the gesture it is used for, so
 * a listener here would run on every frame of it to answer a question that
 * changes twice per message. The observer answers exactly when the answer
 * changes and costs nothing in between.
 *
 * The head is rebuilt on every open, twice where a message is opened from a row
 * we already hold, so the old observer is dropped each time rather than
 * accumulating one per message read.
 */
let headWatch = null;
function watchHead() {
  if (headWatch) { headWatch.disconnect(); headWatch = null; }
  const root = $('rbody');
  $('reader').classList.remove('scrolled');
  root.scrollTop = 0;                 // a new message starts at its own subject
  // The name itself is watched and not the head or the meta block around it,
  // because the strip is a replacement for exactly that line and nothing else.
  // It also has to be a line the pane can actually scroll past: on a message
  // with no attachments and no blocked images the head is 181px in a 181px
  // scroll, so a rule that waited for the whole head to clear the top would
  // never fire on the plainest mail in the folder.
  const mark = root.querySelector('#rheadWho');
  if (!mark || !window.IntersectionObserver) return;
  headWatch = new IntersectionObserver(entries => {
    const e = entries[entries.length - 1];
    // The top edge is the whole test. A sender line still below the fold is
    // also "not intersecting", and reading that as scrolled would raise the
    // strip over the head it duplicates.
    const gone = !e.isIntersecting && !!e.rootBounds
      && e.boundingClientRect.top < e.rootBounds.top;
    $('reader').classList.toggle('scrolled', gone);
  }, { root: root, threshold: 0 });
  headWatch.observe(mark);
}

function starChrome(flagged) {
  const btn = $('rStar');
  btn.querySelector('use').setAttribute('href', flagged ? '#i-star-on' : '#i-star');
  btn.setAttribute('aria-label', flagged ? 'Remove star' : 'Star this message');
  btn.classList.toggle('on', !!flagged);
}

/**
 * The head is drawn from the row already in S.messages before the request goes
 * out, so the sender and the subject are on screen in the same frame as the tap
 * and only the body is waiting on the network.
 */
function readerHead(m, full) {
  const who = m.from.display || m.from.email;
  // The bar's copy of the sender is written here rather than at the call sites,
  // because this is the one function that decides what the sender is called and
  // the two have to agree for the strip to read as the same line moving up.
  $('rbarWho').textContent = who;
  const recipients = (m.to || []).map(a => esc(a.email)).join(', ');
  const meta = full
    ? esc(m.from.email) + (recipients ? ' to ' + recipients : '') + ' · ' + esc(fullWhen(m.receivedAt))
    : esc(m.from.email || '') + ' · ' + esc(fullWhen(m.receivedAt));

  return '<div class="rhead">'
    + '<h2>' + esc(m.subject || '(no subject)') + '</h2>'
    + '<div class="rmeta">'
    + '<span class="avatar av-lg" data-c="' + avatarSlot(m.from.email || who) + '" aria-hidden="true">'
    + esc(avatarInitial(m.from.display, m.from.email)) + '</span>'
    + '<div class="who"><b id="rheadWho">' + esc(who) + '</b>'
    + '<div class="muted" style="font-size:12.5px">' + meta + '</div></div>'
    + '</div>'
    + (full && m.blockedImages > 0
        ? '<div class="banner">' + icon('i-warn', 'ic-sm')
          + '<span class="bmsg">' + m.blockedImages + ' remote image'
          + (m.blockedImages === 1 ? '' : 's') + ' blocked. Loading them tells the '
          + 'sender you opened this.</span>'
          + '<button class="btn sm" type="button" data-act="images">Show images</button></div>'
        : '')
    + (full ? attachmentList(m) : '')
    + '</div>';
}

function attachmentList(m) {
  const files = m.attachments || [];
  if (!files.length) return '';
  // The href carries the message id as well as the blob id, because the server
  // re-reads the message to prove this blob really belongs to it.
  return '<div class="atts">' + files.map(a =>
    '<a class="att" href="/api/mail/attachment?id=' + encodeURIComponent(m.id)
    + '&blobId=' + encodeURIComponent(a.blobId || '') + '" download="' + esc(a.name || 'attachment') + '">'
    + icon('i-attach', 'ic-sm')
    + '<span class="nm">' + esc(a.name || 'attachment') + '</span>'
    + (a.size ? '<span class="muted">' + esc(bytes(a.size)) + '</span>' : '')
    + icon('i-download', 'ic-sm') + '</a>').join('') + '</div>';
}

async function openMessage(id, opts) {
  opts = opts || {};
  /* A draft is a message you are still writing, so the row opens the composer and
     not the reader. Reading your own unfinished letter in a sandboxed frame with
     a Reply button under it is the wrong screen for it in every mail client
     there has ever been. */
  if (S.folderRole === 'drafts' && can('MAIL_SEND') && !opts.read) { resumeDraft(id); return; }
  const push = opts.push !== false;
  const wasOpen = document.body.dataset.pane === 'reader';

  S.selected = id;
  S.readerFor = id;
  selectRow(id);

  const row = S.messages.find(x => x.id === id);
  if (row) {
    $('rbody').innerHTML = readerHead(row, false) + loadState('Opening');
    starChrome(row.flagged);
    setReaderChrome(true);
    watchHead();
  } else {
    setReader(loadState('Opening'));
  }

  if (push && onePane()) go({ pane: 'reader', id: id, pushed: true });
  else replace({ pane: 'reader', id: id, pushed: wasOpen ? UI.pushed : false });

  let m;
  try {
    m = await api('/api/mail/message?id=' + encodeURIComponent(id)
      + '&images=' + (opts.images ? 'true' : 'false') + '&theme=' + encodeURIComponent(theme()));
  } catch (e) {
    if (S.readerFor !== id) return;
    if (handled(e)) return;
    setReader(errState(e.message, null));
    return;
  }
  if (S.readerFor !== id) return;     // a second tap already won the pane
  S.imagesFor = opts.images ? id : null;
  S.reader = m;
  renderReader(m, wasOpen);

  // Marking read is a separate call so that opening a message stays a plain GET.
  if (row && !row.seen) {
    try {
      await post('/api/mail/read', { id: id, value: true });
      row.seen = true;
      setRowSeen(id, true);
      loadFolderCounts();
    } catch (e) { /* the body is on screen; a failed receipt is not worth a toast */ }
  }
}

function renderReader(m, wasOpen) {
  $('rbody').innerHTML = readerHead(m, true);
  starChrome(m.flagged);
  setReaderChrome(true);
  watchHead();

  // A srcdoc frame first laid out inside a visibility:hidden subtree sometimes
  // never paints on WebKit, so the mount waits for the pane to arrive.
  // transitionend does not fire when the element was already where it is going
  // and is absent altogether under reduced motion, hence the race.
  if (!mqPhone.matches || wasOpen) { mountBody($('rbody'), m.bodyHtml); return; }
  let done = false;
  const mount = () => {
    if (done) return;
    done = true;
    $('reader').removeEventListener('transitionend', onEnd);
    if (S.reader === m) mountBody($('rbody'), m.bodyHtml);
  };
  const onEnd = ev => { if (ev.propertyName === 'transform') mount(); };
  $('reader').addEventListener('transitionend', onEnd);
  setTimeout(mount, 260);
}

/**
 * Layer 2 of the four described in MailHtmlSanitizer.
 *
 * The sandbox attribute carries NO allow-scripts, so nothing in a message can
 * execute, and NO allow-same-origin, so the frame runs on an opaque origin and
 * cannot reach our cookies, our DOM or our API even if it somehow ran code. Forms
 * are absent from the list too, so a phishing login box has nowhere to post.
 * allow-popups and allow-popups-to-escape-sandbox are present for one reason only:
 * a genuine link in the message should open in an ordinary tab.
 *
 * srcdoc is assigned as a DOM PROPERTY. It is never concatenated into an HTML
 * string, because srcdoc is itself an attribute and building it as text would need
 * a second, separate round of escaping that is easy to get wrong and impossible to
 * notice when it is wrong.
 *
 * The wrapper is not decoration. Running with scripting off, the frame can never
 * report its content height, and iOS Safari sizes it to that content and ignores
 * the CSS height. A long message then stretches the pane rather than scrolling
 * inside it, and the toolbar goes off the top of the screen with nothing left to
 * bring it back. The wrapper scrolls and the frame keeps its own height.
 */
/**
 * Grows the message frame to its own content, so the pane has ONE scroller.
 *
 * The frame reports its height by postMessage. It is an opaque origin, so the event's
 * origin is the string "null" and is worth nothing as a check; identity is established
 * by comparing event.source against this frame's contentWindow, which no other
 * document can forge. The number is clamped, because a message that claims to be
 * 900,000 pixels tall would otherwise be allowed to build a scrollbar out of nothing.
 *
 * The listener is removed when the frame goes, since a reader that is opened and
 * closed forty times in a session would otherwise leave forty listeners behind, each
 * holding a dead frame.
 */
function listenForHeight(frame, wrap) {
  const MAX = 60000;
  function onMessage(e) {
    if (!frame.isConnected) { window.removeEventListener('message', onMessage); return; }
    if (e.source !== frame.contentWindow) return;
    const h = e.data && e.data.jmHeight;
    if (typeof h !== 'number' || !isFinite(h) || h <= 0) return;
    const px = Math.min(Math.round(h), MAX);
    frame.style.height = px + 'px';
    // Once the frame carries its own height the wrapper has nothing left to scroll,
    // and two nested scrollers on one pane is the thing being removed here.
    wrap.classList.add('sized');
  }
  window.addEventListener('message', onMessage);
}

function mountBody(container, doc) {
  const old = container.querySelector('.rwrap');
  if (old) old.remove();            // two 400KB srcdoc documents per open is a real leak
  const wrap = document.createElement('div');
  wrap.className = 'rwrap';
  const frame = document.createElement('iframe');
  frame.className = 'rframe';
  // allow-scripts is here for exactly one script: the height reporter the sanitiser
  // appends, which the frame's own CSP pins to a sha256 so nothing else can run.
  // allow-same-origin is deliberately still absent, so the frame has an opaque origin
  // and cannot read our cookies, our DOM or our API whatever runs inside it. See
  // MailHtmlSanitizer.HEIGHT_REPORTER for why the height is worth this.
  frame.setAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox allow-scripts');
  frame.setAttribute('referrerpolicy', 'no-referrer');
  frame.setAttribute('title', 'Message body');
  frame.setAttribute('scrolling', 'no');
  wrap.appendChild(frame);
  container.appendChild(wrap);
  listenForHeight(frame, wrap);
  frame.srcdoc = doc;
}

/**
 * One delegated listener, bound once. Reading the action off e.target directly
 * stops working the moment a button holds an <svg> instead of a word, because
 * the target is then the svg or a node inside the <use> shadow tree. closest is
 * the half of the fix that lives here; pointer-events:none on .ic is the other.
 * Binding once also removes a listener leak on every re-render of the head.
 */
$('reader').addEventListener('click', function (e) {
  const b = e.target.closest('[data-act]');
  if (!b || b.disabled) return;
  const act = b.getAttribute('data-act');
  if (act === 'back') { leaveReader(); return; }
  if (act === 'more') { goOverlay('more'); return; }
  const m = S.reader;
  if (!m) return;
  if (act === 'images') openMessage(m.id, { images: true, push: false });
  else if (act === 'flag') toggleFlag(m.id, !m.flagged);
  else if (act === 'delete') removeMessage(m.id);
  else if (act === 'reply') replyTo(m, false);
  else if (act === 'reply-all') replyTo(m, true);
  else if (act === 'forward') forwardOf(m);
  else if (act === 'archive') archiveMessage(m.id);
  else if (act === 'move') goOverlay('move');
});

/**
 * The only way out of the reader. It pops when the reader put an entry on the
 * stack and replaces when it did not, which is what keeps Back one press after
 * a star, a move or a delete rather than two, and stops a desktop close from
 * walking the browser off the page.
 */
function leaveReader() {
  S.reader = null;
  S.readerFor = null;
  if (UI.pane !== 'reader') return;
  if (UI.pushed) history.back();
  else replace({ pane: 'list', id: null, pushed: false });
}

function theme() {
  const explicit = document.documentElement.getAttribute('data-theme');
  if (explicit) return explicit;
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/* ---------- actions ---------- */

async function toggleFlag(id, value) {
  const btn = $('rStar');
  btn.disabled = true;
  try {
    await post('/api/mail/flag', { id: id, value: value });
    const row = S.messages.find(x => x.id === id);
    if (row) row.flagged = value;
    if (S.reader && S.reader.id === id) S.reader.flagged = value;
    starChrome(value);
    renderList();
    // push:false, because the reader is already open and its entry is already
    // on the stack. Pushing here is how Back starts needing two presses.
    openMessage(id, { images: S.imagesFor === id, push: false });
  } catch (e) { if (!handled(e)) toast(e.message, true); }
  finally { btn.disabled = false; }
}

function dropMessage(id) {
  S.messages = S.messages.filter(x => x.id !== id);
  S.total = Math.max(0, S.total - 1);
  S.selected = null;
  renderList();
  loadFolderCounts();
}

async function moveMessage(id, folderId) {
  try {
    await post('/api/mail/move', { id: id, folder: folderId });
    dropMessage(id);
    emptyReader('Message moved.');
    leaveReader();
    toast('Moved.');
  } catch (e) { if (!handled(e)) toast(e.message, true); }
}

function archiveMessage(id) {
  const archive = S.folders.find(f => f.role === 'archive');
  if (!archive) { toast('This mailbox has no archive folder.', true); return; }
  moveMessage(id, archive.id);
}

async function removeMessage(id) {
  if (!window.confirm('Delete this message?')) return;
  try {
    await post('/api/mail/delete', { id: id });
    dropMessage(id);
    emptyReader('Message deleted.');
    leaveReader();
    toast('Deleted.');
  } catch (e) { if (!handled(e)) toast(e.message, true); }
}

async function markUnread(id) {
  try {
    await post('/api/mail/read', { id: id, value: false });
    const row = S.messages.find(x => x.id === id);
    if (row) row.seen = false;
    setRowSeen(id, false);
    loadFolderCounts();
    leaveReader();
    toast('Marked as unread.');
  } catch (e) { if (!handled(e)) toast(e.message, true); }
}

async function loadFolderCounts() {
  try {
    const data = await api('/api/mail/folders');
    S.folders = data.folders || [];
    renderFolders();
  } catch (e) { /* the counts are decoration, not the point of the screen */ }
}

function refreshAll() {
  const btn = $('pbRefresh');
  btn.classList.add('busy');
  $('btnRefresh').disabled = true;
  Promise.resolve(loadFolders())
    .catch(() => {})
    .finally(() => { btn.classList.remove('busy'); $('btnRefresh').disabled = false; });
}

/* ---------- opening the mailbox ---------- */

/**
 * The password typed here goes straight into one POST and is wiped from the field
 * as soon as that POST returns. It is never put in S, never in localStorage, and
 * never in a URL, so it cannot end up in a bookmark, a back button or a proxy log.
 *
 * This sheet is deliberately outside the state machine. It has no dismiss, a 409
 * can arrive at any moment including with the reader open, and a back gesture
 * must never be able to pop it.
 */
function openUnlock(reason) {
  /* The single gate, and it is a refusal rather than a delay. While a device
     restore is in flight the mailbox is about to open on its own, and a sheet
     that appears and disappears half a second later is worse than one that never
     appeared: it is on screen long enough to be read, focused and typed into,
     and it teaches people that the app asks twice. Every caller goes through
     here, so there is one place this can be true. */
  if (S.restoring) return;
  if (reason) $('unlockWhy').textContent = reason;
  $('unlockSheet').classList.add('open');
  ($('uAddress').value ? $('uPassword') : $('uAddress')).focus();
}

/* The footer line has to follow the box above it. "Kept for this visit only" is
   true with the box clear and a plain untruth with it ticked, and a promise the
   screen breaks is worse than no promise. */
function syncUnlockKeep() {
  const keep = $('uRemember') && $('uRemember').checked;
  $('unlockKeep').textContent = keep
    ? 'Kept on this device until you sign it out under Devices'
    : 'Kept for this visit only, never saved';
}

/* =========================================================================
   Restoring the mailbox from the device

   This is the whole of "the second password prompt has to stop appearing".

   The mailbox credential does not come back with the page. It is restored on a
   thread that outlives the redirect, exactly as MailboxAccess.openIfUnset already
   describes for the login path, so /api/mail/status can honestly answer "not
   open" for a few hundred milliseconds on a visit that is in fact about to
   succeed. Prompting on that first answer is the defect: the sheet goes up, the
   restore lands, and the sheet is taken away again from under somebody who has
   already started typing.

   So while the server says restoring, the list shows its skeleton, status is
   asked again on a short cycle, and openUnlock is locked out rather than merely
   deferred. If the restore does not land inside RESTORE_LIMIT_MS, or the server
   stops claiming one, the prompt appears and that is the honest outcome.

   A server that knows nothing of device sessions omits `restoring`, this returns
   false on the first line, and the boot behaves exactly as it does today.
   ========================================================================= */

const RESTORE_EVERY_MS = 400;
const RESTORE_LIMIT_MS = 8000;

async function restoreFromDevice(status) {
  if (!status || !status.restoring) return false;

  S.restoring = true;
  $('list').innerHTML = skeleton();
  $('list').setAttribute('aria-busy', 'true');
  const until = Date.now() + RESTORE_LIMIT_MS;
  try {
    while (Date.now() < until) {
      await new Promise(done => setTimeout(done, RESTORE_EVERY_MS));
      let next;
      try {
        next = await api('/api/mail/status');
      } catch (e) {
        return false;                  // a dead status call is not a restore
      }
      if (next.unlocked) return true;
      if (!next.restoring) return false;
    }
    return false;
  } finally {
    // Cleared before anything can prompt, so the refusal in openUnlock lasts
    // exactly as long as the restore and not one call longer.
    S.restoring = false;
  }
}

async function unlockMailbox() {
  const btn = $('btnUnlock');
  const address = $('uAddress').value.trim();
  const password = $('uPassword').value;
  if (!address || !password) { toast('Both fields are needed.', true); return; }

  btn.disabled = true;
  btn.textContent = 'Opening';
  try {
    /* The second half of the assumed device-session contract. remember=true asks
       the server to seal this mailbox credential against the device token minted
       at sign in, which is the only thing that stops this same sheet appearing
       again tomorrow for somebody whose console and mailbox passwords differ. A
       server that does not know the parameter ignores it and nothing here
       changes. */
    await post('/api/mail/unlock', {
      address: address,
      password: password,
      remember: $('uRemember') && $('uRemember').checked ? 'true' : 'false'
    });
    $('uPassword').value = '';
    $('unlockSheet').classList.remove('open');
    S.folderId = null;
    await loadFolders();
  } catch (e) {
    $('uPassword').value = '';
    // A rejected password comes back as 409 locked, which is exactly the state the
    // prompt is already in, so it is a message rather than a fresh prompt.
    toast(e instanceof Locked ? 'That address or password was not accepted.' : e.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Open mailbox';
  }
}

/* Part of the assumed device-session contract, and the part most likely to be
   missed: POST /api/mail/lock has to drop the mailbox credential held against
   this device's token as well as the one in the session. Without that, closing
   the mailbox is undone by the next reload and the button is a lie. */
async function lockMailbox() {
  try { await post('/api/mail/lock', {}); } catch (e) { /* closing can only fail into closed */ }
  S.folders = [];
  S.messages = [];
  S.folderId = null;
  S.selected = null;
  S.reader = null;
  S.readerFor = null;
  $('folders').innerHTML = '';
  $('sheetFolders').innerHTML = '';
  $('list').innerHTML = emptyState('i-lock', 'Your mailbox is closed on this device.');
  $('list').setAttribute('aria-busy', 'false');
  // No compose action on this one: writing a message is exactly what a closed
  // mailbox cannot do, and an empty state must not offer it.
  emptyReader('Your mailbox is closed on this device.', false);
  $('tabUnread').hidden = true;
  openUnlock('Your mailbox is closed on this device. Enter your email password to open it again.');
}

/* =========================================================================
   Devices

   A sign-in that outlives the browser session is only defensible if the person
   can see every device holding one and end any of them, so this screen is part
   of that feature rather than a follow-up to it.

   THE CONTRACT THIS CODES AGAINST HAS NOT LANDED YET. Nothing server side
   answers these paths at the time of writing, so what follows is the assumed
   shape, stated here so the agent that builds it has something exact to meet:

     GET  /api/mail/devices
          -> { enabled: true, devices: [ { id, name, platform, current,
                                           createdAt, lastSeenAt, ip, mailbox } ] }
     POST /api/mail/devices/revoke       id=<id>   -> { ok, self }
     POST /api/mail/devices/revoke-all             -> { ok, signedOut, self }

   `current` marks the device being used right now. `mailbox` is true when the
   token can reopen the mailbox without the email password, which is the half a
   person actually cares about and the half that costs them if the device is
   lost. `self` in a revoke answer means the current device was among those
   ended, and the browser belongs at /login.

   /api/mail/** is the prefix SecurityConfig leaves open to a session bought with
   a mailbox password alone, so that is the path tried first; /api/devices is
   tried after it in case the console builds only that one, exactly the way
   session.js discovers its own endpoint. Whichever answers is remembered.
   ========================================================================= */

const DEVICE_PATHS = ['/api/mail/devices', '/api/devices'];

const DEV = {
  base: null,        // the path that answered, discovered once
  list: null,        // the last good answer, or null
  loading: false,
  error: null,       // a message when the last attempt failed
  missing: false,    // nothing answered at all: the feature is not on this server
  confirming: null,  // the id whose confirm line is open, or 'all', or null
  busy: null         // the id currently being revoked, or 'all'
};

async function deviceCall(suffix, params) {
  const paths = DEV.base ? [DEV.base] : DEVICE_PATHS;
  for (let i = 0; i < paths.length; i++) {
    const url = paths[i] + (suffix || '');
    const res = params
      ? await fetch(url, { method: 'POST', body: new URLSearchParams(params),
                           headers: { 'X-XSRF-TOKEN': csrfToken() } })
      : await fetch(url, { headers: { Accept: 'application/json' } });
    if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
    // A 404 means this path is not the one; a 403 means this session is not
    // allowed to ask it. Both are reasons to try the other path rather than to
    // report a failure, and if neither answers the screen says so plainly.
    if (res.status === 404 || res.status === 403) continue;
    DEV.base = paths[i];
    const payload = await readBody(res);
    if (!res.ok) throw new Error((payload && payload.error) || 'That did not work.');
    return payload || {};
  }
  const gone = new Error('Signed-in devices are not available on this server yet.');
  gone.missing = true;
  throw gone;
}

async function loadDevices() {
  DEV.loading = true;
  DEV.error = null;
  DEV.missing = false;
  DEV.confirming = null;
  DEV.busy = null;
  renderDevices();
  try {
    const data = await deviceCall('');
    DEV.list = (data && data.devices) || [];
  } catch (e) {
    DEV.error = e.message;
    DEV.missing = !!e.missing;
  } finally {
    DEV.loading = false;
    renderDevices();
  }
}

/* Two sprites and no third: there is no laptop symbol in the set, and inventing
   one from a text character is exactly what section 6 forbids. A browser on
   something that is not a phone is a browser, so it gets the globe. */
function deviceSprite(d) {
  const s = ((d.platform || '') + ' ' + (d.name || '')).toLowerCase();
  return /iphone|ipad|android|phone|mobile|ios/.test(s) ? 'i-phone' : 'i-globe';
}

/* Deliberately coarse. "Last used 4 minutes ago" is what somebody needs to answer
   "is that me, or is that the phone I left in the auto", and a precise timestamp
   for something two weeks old only makes that harder to read at a glance. */
function lastUsed(iso) {
  const d = iso ? new Date(iso) : null;
  if (!d || isNaN(d)) return 'Last used at an unknown time';
  const mins = Math.floor((Date.now() - d.getTime()) / 60000);
  if (mins < 2) return 'In use now';
  if (mins < 60) return 'Last used ' + mins + ' minutes ago';
  const hours = Math.floor(mins / 60);
  if (hours < 24) return 'Last used ' + hours + (hours === 1 ? ' hour ago' : ' hours ago');
  const days = Math.floor(hours / 24);
  if (days < 30) return 'Last used ' + days + (days === 1 ? ' day ago' : ' days ago');
  return 'Last used ' + fullWhen(iso);
}

function deviceRow(d) {
  const current = !!d.current;
  const confirming = DEV.confirming === d.id;
  const busy = DEV.busy === d.id;
  const name = d.name || d.platform || 'A signed-in browser';
  const where = [d.platform && d.platform !== d.name ? d.platform : '', d.ip || '']
    .filter(Boolean).join(' · ');

  // The confirm copy is different for the device you are holding, because the
  // consequence is different: everything else keeps working, this one does not.
  const why = current
    ? 'This signs you out here and returns you to the sign-in page.'
    : (d.mailbox
        ? 'That device will need both passwords again before it can read this mailbox.'
        : 'That device will need to sign in again.');

  return '<div class="drow' + (current ? ' is-current' : '') + (confirming ? ' confirming' : '')
    + (busy ? ' going' : '') + '" data-drow="' + esc(d.id) + '">'
    + icon(deviceSprite(d))
    + '<span class="dmeta">'
    + '<span class="dname"><span class="nm">' + esc(name) + '</span>'
    + (current ? '<span class="pill ok">This device</span>' : '')
    + '</span>'
    // A device that signs in but still asks for the email password is a smaller
    // exposure than one that does not, and that belongs on the same line as the
    // clock rather than in a pill: a second pill competes with the device name
    // for the width the name needs, and at 390px the name is what identifies it.
    + '<span class="dwhen">' + esc(lastUsed(d.lastSeenAt))
    + (d.mailbox === false ? ' · asks for the mail password' : '') + '</span>'
    + (where ? '<span class="dwhere">' + esc(where) + '</span>' : '')
    + '</span>'
    + '<button class="dsign" type="button" data-dsign="' + esc(d.id) + '">Sign out</button>'
    + '<span class="dconfirm">'
    + '<span class="why">' + esc(why) + '</span>'
    + '<button class="btn" type="button" data-dcancel>Keep it</button>'
    + '<button class="btn dgo" type="button" data-dconfirm="' + esc(d.id) + '"' + (busy ? ' disabled' : '')
    + '>' + (busy ? 'Signing out' : 'Sign out') + '</button>'
    + '</span>'
    + '</div>';
}

/* Skeleton rows at the real row geometry, so nothing jumps when the answer lands.
   Section 9: a list gets skeletons, a spinner is only ever for a control. */
function deviceSkeleton() {
  let out = '<div class="dlist" aria-hidden="true">';
  for (let i = 0; i < 3; i++) {
    out += '<div class="drow"><span class="sk sk-av" style="width:18px;height:18px"></span>'
      + '<span class="dmeta"><span class="sk sk-1" style="width:56%;margin-bottom:6px"></span>'
      + '<span class="sk sk-3" style="width:38%"></span></span>'
      + '<span class="sk sk-2" style="width:52px"></span></div>';
  }
  return out + '</div>';
}

function renderDevices() {
  const body = $('devicesBody');
  if (!body) return;

  if (DEV.loading && !DEV.list) {
    body.setAttribute('aria-busy', 'true');
    body.innerHTML = deviceSkeleton();
    return;
  }
  body.setAttribute('aria-busy', 'false');

  if (DEV.error && !DEV.list) {
    // The feature simply not being on this server is not a fault and must not be
    // dressed as one: no Retry, because retrying cannot make it exist.
    body.innerHTML = DEV.missing
      ? emptyState('i-shield', DEV.error)
      : errState(DEV.error, 'devices');
    return;
  }

  const list = DEV.list || [];
  if (!list.length) {
    body.innerHTML = emptyState('i-shield',
      'No device is kept signed in. Every visit will ask for your password.');
    return;
  }

  const others = list.filter(d => !d.current).length;
  const allConfirming = DEV.confirming === 'all';
  const allBusy = DEV.busy === 'all';

  body.innerHTML =
    '<p class="dnote">These devices stay signed in without asking again. Sign out '
    + 'anything you do not recognise, or a device you no longer have.</p>'
    + '<div class="dlist">' + list.map(deviceRow).join('') + '</div>'
    + (others
        ? '<div class="dall">'
          + (allConfirming
              ? '<p class="why">' + others + (others === 1 ? ' other device' : ' other devices')
                + ' will need to sign in again. This device stays signed in.</p>'
                + '<button class="btn dgo" type="button" data-dallconfirm' + (allBusy ? ' disabled' : '')
                + '>' + (allBusy ? 'Signing out' : 'Yes, sign them out') + '</button>'
                + '<button class="btn" type="button" data-dcancel>Cancel</button>'
              : '<p class="why">Lost a phone, or signed in on a computer you do not own?</p>'
                + '<button class="btn" type="button" data-dall>Sign out '
                + others + (others === 1 ? ' other device' : ' other devices') + '</button>')
          + '</div>'
        : '');
}

async function revokeDevice(id) {
  DEV.busy = id;
  renderDevices();
  try {
    const r = await deviceCall('/revoke', { id: id });
    // Ending the session you are sitting in means the next request is a 401
    // anyway. Going there deliberately is the difference between an app that
    // did what you asked and one that appears to hang.
    if (r && r.self) { location.href = '/login?loggedOut'; return; }
    toast('That device was signed out.');
    await loadDevices();
  } catch (e) {
    DEV.busy = null;
    DEV.confirming = null;
    renderDevices();
    toast(e.message, true);
  }
}

async function revokeOtherDevices() {
  DEV.busy = 'all';
  renderDevices();
  try {
    const r = await deviceCall('/revoke-all', {});
    if (r && r.self) { location.href = '/login?loggedOut'; return; }
    const n = r && typeof r.signedOut === 'number' ? r.signedOut : 0;
    toast(n === 1 ? 'One device was signed out.' : n + ' devices were signed out.');
    await loadDevices();
  } catch (e) {
    DEV.busy = null;
    DEV.confirming = null;
    renderDevices();
    toast(e.message, true);
  }
}

/* One delegated listener on the sheet, bound once. The body is replaced on every
   render, so a listener per control would leak one set per render and the sprite
   inside a button is what e.target reports, which is why every branch matches
   with closest(). */
$('devicesSheet').addEventListener('click', e => {
  const t = e.target.closest
    && e.target.closest('[data-dsign],[data-dconfirm],[data-dcancel],[data-dall],[data-dallconfirm]');
  if (!t) return;
  if (t.hasAttribute('data-dcancel')) { DEV.confirming = null; renderDevices(); return; }
  if (t.hasAttribute('data-dall')) { DEV.confirming = 'all'; renderDevices(); return; }
  if (t.hasAttribute('data-dallconfirm')) { revokeOtherDevices(); return; }
  const sign = t.getAttribute('data-dsign');
  if (sign) { DEV.confirming = sign; renderDevices(); return; }
  const confirmed = t.getAttribute('data-dconfirm');
  if (confirmed) revokeDevice(confirmed);
});

/* ---------- compose ---------- */

/* =========================================================================
   Addresses

   One scanner, used by the chip fields, by paste and by the reply builder, so
   there is exactly one answer in this file to the question of where one
   recipient ends and the next begins. It has to be a scanner rather than a
   split, because a display name is allowed to hold the separator: split on a
   comma and "Sharma, Priya" <priya@jarurat.care> becomes two recipients, one of
   which is the word Sharma.
   ========================================================================= */

/* Deliberately narrower than RFC 5322 allows. The job here is to tell somebody
   they have mistyped before they send, so it has to refuse what is almost
   certainly a slip: no spaces, one at sign, a dotted domain with no leading or
   trailing hyphen in any label. Quoted local parts and address literals are
   legal and are refused, and that is the trade: the server is still the one
   that decides, and it will accept anything this is wrong about. */
const ADDR_RE = /^[^\s@<>,;"]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/;

function addressOk(email) {
  return ADDR_RE.test(String(email || '').trim()) && String(email).length <= 254;
}

/** One token to a recipient. Accepts "Name <a@b>", <a@b>, mailto:a@b and a@b. */
function parseAddress(token) {
  let raw = String(token || '').trim();
  let name = '';
  const lt = raw.lastIndexOf('<');
  const gt = raw.lastIndexOf('>');
  if (lt >= 0 && gt > lt) {
    name = raw.slice(0, lt).trim();
    raw = raw.slice(lt + 1, gt).trim();
  }
  name = name.replace(/^"(.*)"$/, '$1').trim();
  const email = raw.replace(/^mailto:/i, '').trim();
  return { name: name, email: email, ok: addressOk(email) };
}

/**
 * A whole pasted list to recipients.
 *
 * Commas, semicolons and newlines separate, but only outside quotes and outside
 * angle brackets. Whitespace does not separate, because a display name is mostly
 * whitespace; the one exception is a run of bare addresses with nothing else in
 * it, which is what a copy out of a spreadsheet column looks like, and that is
 * detected after the fact rather than guessed at during the scan.
 */
function splitAddresses(raw) {
  const tokens = [];
  let buf = '', quoted = false, angled = false;
  const flush = () => { const t = buf.trim(); buf = ''; if (t) tokens.push(t); };
  const src = String(raw || '');
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (ch === '"') { quoted = !quoted; buf += ch; continue; }
    if (!quoted && ch === '<') { angled = true; buf += ch; continue; }
    if (!quoted && ch === '>') { angled = false; buf += ch; continue; }
    if (!quoted && !angled && (ch === ',' || ch === ';' || ch === '\n' || ch === '\r')) { flush(); continue; }
    buf += ch;
  }
  flush();

  const out = [];
  tokens.forEach(t => {
    // Only split on whitespace when every piece is itself an address, so a name
    // that happens to contain no angle brackets is never torn in half.
    if (!/[<>"]/.test(t) && /\s/.test(t)) {
      const pieces = t.split(/\s+/).filter(Boolean);
      if (pieces.length > 1 && pieces.every(addressOk)) {
        pieces.forEach(p => out.push(parseAddress(p)));
        return;
      }
    }
    out.push(parseAddress(t));
  });
  return out.filter(a => a.email);
}

/* =========================================================================
   Recipient chip fields

   Three of these, one per header line. The typing box keeps the id it always
   had, which is what lets label[for], syncFocus and every existing listener go
   on working; the chips are its siblings inside the bordered box around it.

   An address that will not parse is committed as a chip wearing the refusal
   rather than being rejected at the keystroke, because refusing mid-typing
   fights the person while they are still typing, and dropping it silently is
   how a message goes to four of five recipients and nobody finds out.
   ========================================================================= */

function ChipField(inputId, boxId, kind) {
  this.input = $(inputId);
  this.box = $(boxId);
  this.kind = kind;
  this.items = [];
  this.wire();
}

ChipField.prototype.wire = function () {
  const self = this;

  // The whole box is the target, because a 24px chip row inside a 44px field
  // leaves most of the box as dead space that ought to focus the input.
  this.box.addEventListener('mousedown', e => {
    if (e.target.closest('button')) return;
    if (e.target === self.input) return;
    e.preventDefault();
    self.input.focus();
  });

  this.box.addEventListener('click', e => {
    const rm = e.target.closest('[data-rmchip]');
    if (rm) { self.removeAt(Number(rm.dataset.rmchip)); self.input.focus(); return; }
    // Clicking the name puts the address back in the box to be corrected, which
    // is the only repair a chip needs and the one people reach for on a typo.
    const edit = e.target.closest('[data-editchip]');
    if (edit) {
      const i = Number(edit.dataset.editchip);
      const it = self.items[i];
      self.commit();
      self.removeAt(i);
      self.input.value = it.name ? it.name + ' <' + it.email + '>' : it.email;
      self.input.focus();
      acAsk(self);
    }
  });

  this.input.addEventListener('keydown', e => {
    if (acKey(e, self)) return;               // the menu gets first refusal
    if (e.key === ',' || e.key === ';' || e.key === 'Enter') {
      // Enter with an empty box is a submit gesture on every other form on this
      // page, so it is left alone rather than swallowed.
      if (!self.input.value.trim()) return;
      e.preventDefault();
      self.commit();
      return;
    }
    if (e.key === 'Tab' && self.input.value.trim()) { self.commit(); return; }
    if (e.key === 'Backspace' && !self.input.value && self.items.length) {
      e.preventDefault();
      const last = self.box.querySelector('.chip:nth-last-of-type(1)');
      if (last && !last.classList.contains('armed')) { last.classList.add('armed'); return; }
      self.removeAt(self.items.length - 1);
    }
  });

  this.input.addEventListener('input', () => {
    self.box.querySelectorAll('.chip.armed').forEach(c => c.classList.remove('armed'));
    acAsk(self);
  });

  // preventDefault and read the clipboard ourselves, or a pasted column of forty
  // addresses lands as one 900 character string in a box 130px wide.
  this.input.addEventListener('paste', e => {
    const text = e.clipboardData && e.clipboardData.getData('text/plain');
    if (!text) return;
    e.preventDefault();
    self.commit(self.input.value + text + ',');
  });

  this.input.addEventListener('focus', () => {
    self.box.classList.add('focus');
    acAsk(self);
  });

  // Deferred, because a click on a menu row blurs the input before the row's own
  // click handler runs, and committing here would take the menu away first.
  this.input.addEventListener('blur', () => {
    setTimeout(() => {
      if (document.activeElement === self.input) return;
      self.box.classList.remove('focus');
      self.box.querySelectorAll('.chip.armed').forEach(c => c.classList.remove('armed'));
      self.commit();
      if (AC.field === self) acClose();
    }, 140);
  });
};

/** Everything in the typing box becomes chips. Called on every commit gesture. */
ChipField.prototype.commit = function (text) {
  const raw = text === undefined ? this.input.value : text;
  if (!String(raw).trim()) return;
  this.input.value = '';
  splitAddresses(raw).forEach(a => this.add(a));
  this.render();
  onComposeInput();
};

ChipField.prototype.add = function (addr) {
  if (!addr || !addr.email) return;
  const key = addr.email.toLowerCase();
  // Folded across all three fields, not only this one. The same address in To
  // and in Bcc is a person who gets two copies and can see one of them.
  if (CHIPS.some(f => f.items.some(x => x.email.toLowerCase() === key))) return;
  this.items.push(addr);
};

ChipField.prototype.removeAt = function (i) {
  this.items.splice(i, 1);
  this.render();
  onComposeInput();
};

ChipField.prototype.set = function (list) {
  this.items = [];
  (list || []).forEach(a => {
    const one = typeof a === 'string' ? parseAddress(a) : { name: a.name || '', email: a.email || '', ok: addressOk(a.email) };
    this.add(one);
  });
  this.input.value = '';
  this.render();
};

ChipField.prototype.render = function () {
  const self = this;
  // Rebuilt rather than patched. A recipient list is at most a few dozen nodes,
  // and a diff here would be more code than the thing it is optimising.
  this.box.querySelectorAll('.chip').forEach(c => c.remove());
  const frag = document.createDocumentFragment();
  this.items.forEach((a, i) => {
    const chip = document.createElement('span');
    chip.className = 'chip' + (a.ok ? '' : ' bad');
    chip.setAttribute('data-addr', a.email);
    if (!a.ok) chip.title = a.email + ' is not an email address.';
    const label = document.createElement('button');
    label.type = 'button';
    label.className = 'ct';
    label.setAttribute('data-editchip', String(i));
    // textContent and never innerHTML: the display name came off the wire.
    label.textContent = a.name || a.email;
    label.title = a.name ? a.name + ' <' + a.email + '>' : a.email;
    const x = document.createElement('button');
    x.type = 'button';
    x.className = 'x';
    x.setAttribute('data-rmchip', String(i));
    // The field is in the name, because a screen reader hearing eight identical
    // "Remove" buttons on one sheet cannot tell a To from a Bcc.
    x.setAttribute('aria-label', 'Remove ' + a.email + ' from ' + self.title());
    x.innerHTML = icon('i-close');
    chip.appendChild(label);
    chip.appendChild(x);
    frag.appendChild(chip);
  });
  this.box.insertBefore(frag, this.input);
  self.input.placeholder = self.items.length ? '' : self.input.dataset.ph || self.input.placeholder;
};

/** What this field is called out loud, for the labels on its own controls. */
ChipField.prototype.title = function () {
  return this.kind === 'to' ? 'To' : this.kind === 'cc' ? 'Cc' : 'Bcc';
};

ChipField.prototype.emails = function () { return this.items.map(a => a.email); };
/* Bare addresses, comma joined. The send endpoint splits on comma and semicolon
   and parses nothing else, so a display name travelling with one would arrive as
   part of the address. The names are the composer's own, for the person typing. */
ChipField.prototype.serialise = function () { return this.emails().join(', '); };
ChipField.prototype.bad = function () { return this.items.filter(a => !a.ok); };

let CHIPS = [];
let cTo = null, cCc = null, cBcc = null;

/* =========================================================================
   Recipient autocomplete

   Against the contract in ContactSuggestApi: always 200, never an unlock sheet,
   an echoed q so a slow early answer cannot land on top of a fast later one,
   and an empty first answer that means "still warming" rather than "no
   contacts". One menu node moves between the three fields.
   ========================================================================= */

const AC = { field: null, rows: [], index: -1, timer: null, seq: 0, warm: false };

function acClose() {
  const menu = $('acMenu');
  menu.hidden = true;
  menu.innerHTML = '';
  if (AC.field) AC.field.input.setAttribute('aria-expanded', 'false');
  AC.field = null;
  AC.rows = [];
  AC.index = -1;
}

/** Fired once when the composer opens: the first call warms the address book. */
function acWarm() {
  if (AC.warm) return;
  AC.warm = true;
  fetch('/api/mail/contacts?q=&limit=1', { headers: { Accept: 'application/json' } })
    .catch(() => { AC.warm = false; });
}

function acAsk(field) {
  clearTimeout(AC.timer);
  const q = field.input.value.trim();
  // A prefix with a separator still in it is on its way to being committed, not
  // on its way to being searched.
  if (/[,;]/.test(q)) { acClose(); return; }
  AC.timer = setTimeout(() => acFetch(field, q), 120);
}

async function acFetch(field, q) {
  const seq = ++AC.seq;
  let data;
  try {
    data = await api('/api/mail/contacts?q=' + encodeURIComponent(q) + '&limit=8');
  } catch (e) {
    // This endpoint promises never to fail. If it does anyway, an autocomplete
    // is the last thing that should put an unlock sheet over somebody's typing.
    acClose();
    return;
  }
  if (seq !== AC.seq) return;                       // overtaken by later typing
  if (document.activeElement !== field.input) return;
  if (data.locked) { acClose(); return; }
  // The server echoes the query it understood, which is the second guard: a slow
  // answer to "pri" must not paint under a box that now reads "priyanka".
  if ((data.q || '') !== q) return;

  const taken = new Set();
  CHIPS.forEach(f => f.items.forEach(a => taken.add(a.email.toLowerCase())));
  AC.rows = (data.contacts || []).filter(c => !taken.has(String(c.email).toLowerCase()));
  if (!AC.rows.length) { acClose(); return; }

  AC.field = field;
  AC.index = -1;
  const menu = $('acMenu');
  field.box.appendChild(menu);
  menu.innerHTML = '';
  AC.rows.forEach((c, i) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'acrow';
    row.setAttribute('role', 'option');
    row.setAttribute('aria-selected', 'false');
    row.dataset.i = String(i);
    const av = document.createElement('span');
    av.className = 'av';
    av.setAttribute('aria-hidden', 'true');
    paintAvatar(av, c.email, c.name);
    const tx = document.createElement('span');
    tx.className = 'tx';
    const nm = document.createElement('span');
    nm.className = 'nm';
    // The contract says name is not escaped, so it goes in as text and never as
    // markup. This is the one place a contact's own string reaches the DOM.
    nm.textContent = c.name || c.email;
    tx.appendChild(nm);
    if (c.name) {
      const em = document.createElement('span');
      em.className = 'em';
      em.textContent = c.email;
      tx.appendChild(em);
    }
    row.appendChild(av);
    row.appendChild(tx);
    if (c.lastSeen) {
      const ago = document.createElement('span');
      ago.className = 'ago';
      ago.textContent = when(c.lastSeen);
      row.appendChild(ago);
    }
    menu.appendChild(row);
  });
  menu.hidden = false;
  field.input.setAttribute('aria-expanded', 'true');
}

function acHighlight(next) {
  const rows = $('acMenu').querySelectorAll('.acrow');
  if (!rows.length) return;
  if (AC.index >= 0 && rows[AC.index]) rows[AC.index].setAttribute('aria-selected', 'false');
  AC.index = (next + rows.length) % rows.length;
  rows[AC.index].setAttribute('aria-selected', 'true');
  rows[AC.index].scrollIntoView({ block: 'nearest' });
}

function acTake(i) {
  const c = AC.rows[i];
  const field = AC.field;
  if (!c || !field) return;
  field.input.value = '';
  field.add({ name: c.name || '', email: c.email, ok: addressOk(c.email) });
  field.render();
  acClose();
  field.input.focus();
  onComposeInput();
}

/** True when the menu consumed the key, so the field's own handler stands down. */
function acKey(e, field) {
  if (AC.field !== field || $('acMenu').hidden) return false;
  if (e.key === 'ArrowDown') { e.preventDefault(); acHighlight(AC.index + 1); return true; }
  if (e.key === 'ArrowUp') { e.preventDefault(); acHighlight(AC.index - 1); return true; }
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); acClose(); return true; }
  if ((e.key === 'Enter' || e.key === 'Tab') && AC.index >= 0) {
    e.preventDefault();
    acTake(AC.index);
    return true;
  }
  return false;
}

$('acMenu').addEventListener('mousedown', e => e.preventDefault());   // keep the caret
$('acMenu').addEventListener('click', e => {
  const row = e.target.closest('.acrow');
  if (row) acTake(Number(row.dataset.i));
});

/* =========================================================================
   The rich text body

   execCommand for the six commands every engine implements, because it is the
   only formatting path that keeps the native undo stack and the IME's own
   behaviour, and a replacement built on Range surgery means owning a document
   model, which is the library this app is not allowed to have.

   What the engines disagree about is the markup they emit, and that is answered
   on the way out rather than on the way in: nothing here trusts editor.innerHTML.
   The serialiser below rebuilds the whole subtree through one allowlist, so
   Gecko's <span style="font-weight:bold"> and WebKit's <b> both leave as
   <strong> and the recipient cannot tell which browser wrote the letter.

   None of this is a security control. It is a quality control. The security
   control is the server, which re-cleans every byte because what a browser sent
   is evidence of intent and never evidence of safety.
   ========================================================================= */

/* Blocks kept, and what they become. div and p are the same thing here on
   purpose: Safari emits div on Enter whatever defaultParagraphSeparator says, so
   a serialiser that treated them differently would produce different mail from
   the same keystrokes on the same page. */
const OUT_BLOCK = {
  P: 'p', DIV: 'p', BLOCKQUOTE: 'blockquote', UL: 'ul', OL: 'ol', LI: 'li',
  HR: 'hr', PRE: 'pre',
  H1: 'h3', H2: 'h3', H3: 'h3', H4: 'h4', H5: 'h4', H6: 'h4',
  TABLE: 'table', THEAD: 'thead', TBODY: 'tbody', TFOOT: 'tbody',
  TR: 'tr', TD: 'td', TH: 'th'
};

const OUT_INLINE = {
  B: 'strong', STRONG: 'strong', I: 'em', EM: 'em', U: 'u',
  S: 's', STRIKE: 's', DEL: 's', A: 'a', BR: 'br', CODE: 'code',
  SUB: 'sub', SUP: 'sup', SMALL: 'small'
};

/* Inline styles, because Outlook renders through the Word engine, drops <style>
   and <head> entirely, and has defaults for list indent and blockquote that are
   wrong. There is no head stylesheet in a mail to fix them in, so every one of
   these has to travel on the element. px and % only: calc() reaches 7% of the
   client field and custom properties 6%. */
const OUT_STYLE = {
  p: 'margin:0 0 12px 0',
  ul: 'margin:0 0 12px 20px;padding:0',
  ol: 'margin:0 0 12px 20px;padding:0',
  blockquote: 'margin:0 0 12px 12px;padding:0 0 0 12px;border-left:2px solid #dde5e8;color:#5a6b76',
  a: 'color:#00697f',
  hr: 'border:0;border-top:1px solid #dde5e8;margin:16px 0',
  h3: 'margin:0 0 8px 0;font-size:17px;line-height:1.35',
  h4: 'margin:0 0 8px 0;font-size:15px;line-height:1.35',
  pre: 'margin:0 0 12px 0;white-space:pre-wrap;font-family:Consolas,Monaco,monospace;font-size:13px',
  table: 'border-collapse:collapse;max-width:100%',
  td: 'padding:4px 8px;vertical-align:top',
  th: 'padding:4px 8px;vertical-align:top;text-align:left'
};

const BLOCK_TAGS = new Set(['p', 'blockquote', 'ul', 'ol', 'li', 'hr', 'pre', 'h3', 'h4',
  'table', 'thead', 'tbody', 'tr', 'td', 'th']);
const VOID_TAGS = new Set(['br', 'hr']);
const OUT_SCHEMES = /^(https?:|mailto:|tel:)/i;

/** True when an inline element is really a wrapper around block content. */
function hasBlockChild(el) {
  for (let n = el.firstElementChild; n; n = n.nextElementSibling) {
    const t = OUT_BLOCK[n.tagName];
    if (t && BLOCK_TAGS.has(t)) return true;
    if (hasBlockChild(n)) return true;
  }
  return false;
}

function escAttr(s) {
  return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** A link the composer will emit, or '' for one it will not. */
function outHref(raw) {
  let v = String(raw || '').trim().replace(/[\u0000-\u0020\u007f]/g, '');
  if (!v) return '';
  // A bare domain is what people actually type into a link box. Refusing it and
  // saying "start it with https://" teaches them to type nothing at all, so a
  // token with a dotted host and no scheme of its own gets one. An at sign
  // before the first slash is a mail address rather than a host.
  if (!/^[a-z][a-z0-9+.-]*:/i.test(v)) {
    const host = v.split(/[\/?#]/)[0];
    if (host.indexOf('@') > 0) v = 'mailto:' + v;
    else if (/^[^\s@]+\.[a-z]{2,}$/i.test(host)) v = 'https://' + v;
  }
  return OUT_SCHEMES.test(v) ? v : '';
}

/**
 * Only the inline style attribute is read here, never the computed style.
 *
 * Computed style is inherited, so a span inside a strong reports weight 700 and
 * the walk would wrap it a second time; and it needs a defaultView, which the
 * inert document the paste path parses into does not have. Gecko's execCommand
 * writes the declaration onto the element itself, which is exactly what this
 * sees, so nothing is lost by looking no further.
 */
function inlineLook(el) {
  const st = el.getAttribute && el.getAttribute('style') ? el.style : null;
  if (!st) return { bold: false, italic: false, underline: false };
  const w = String(st.fontWeight || '');
  const dec = String(st.textDecorationLine || st.textDecoration || '');
  return {
    bold: w === 'bold' || w === 'bolder' || (parseInt(w, 10) >= 600),
    italic: st.fontStyle === 'italic' || st.fontStyle === 'oblique',
    underline: dec.indexOf('underline') >= 0
  };
}

/**
 * The one walk, producing the HTML part and the text part together.
 *
 * Together and not in two passes, because the two are alternatives of the same
 * message and a recipient whose client refuses HTML must be reading the same
 * letter. Two traversals with two sets of rules is two things to keep in
 * agreement, and they would stop agreeing on the first edge case.
 */
function walkOut(root) {
  const html = [];
  const text = [];
  let listDepth = 0;
  const counters = [];

  function pushText(s) { text.push(s); }

  function line() {
    // Blocks end a line, and two blank lines never become three, because a text
    // alternative full of vertical whitespace reads as a broken conversion.
    // The last chunk is enough to decide it, and joining the whole array on
    // every block would make a long paste quadratic in its own length.
    let tail = '';
    for (let i = text.length - 1; i >= 0 && !tail; i--) tail = text[i];
    if (!tail) return;
    if (/\n\n$/.test(tail)) return;
    text.push(/\n$/.test(tail) ? '\n' : '\n\n');
  }

  function inlineWrap(el, depth) {
    const look = inlineLook(el);
    let open = '', close = '';
    if (look.bold) { open += '<strong>'; close = '</strong>' + close; }
    if (look.italic) { open += '<em>'; close = '</em>' + close; }
    if (look.underline) { open += '<u>'; close = '</u>' + close; }
    if (open) html.push(open);
    kids(el, depth);
    if (close) html.push(close);
  }

  function kids(el, depth) {
    for (let n = el.firstChild; n; n = n.nextSibling) emit(n, depth + 1);
  }

  function emit(node, depth) {
    if (depth > 40) return;                       // a paste can be arbitrarily nested
    if (node.nodeType === 3) {
      const t = node.nodeValue;
      if (!t) return;
      html.push(esc(t));
      // A non breaking space is a space in a text part. Left as it is it
      // renders as a stray box in half the terminal mail readers there are.
      pushText(t.replace(/\u00a0/g, ' '));
      return;
    }
    if (node.nodeType !== 1) return;              // comments and the rest go

    const tag = node.tagName;

    // Word and Google Docs paste elements in their own namespaces, a stylesheet,
    // and a wrapper that would bold the entire paste. None of that survives.
    if (tag.indexOf(':') >= 0) return;
    if (tag === 'STYLE' || tag === 'SCRIPT' || tag === 'XML' || tag === 'META'
        || tag === 'LINK' || tag === 'TITLE' || tag === 'HEAD') return;
    // An image is dropped rather than kept, because MailService marks every part
    // as an attachment and never emits a cid, so an inline image would arrive
    // broken; and a data: image is blocked by Outlook desktop and eats the 102KB
    // Gmail clips at. This comes back the day the send path grows multipart/related.
    if (tag === 'IMG') return;

    if (tag === 'SPAN' || tag === 'FONT') {
      const look = inlineLook(node);
      if (hasBlockChild(node)) { kids(node, depth); return; }
      if (!look.bold && !look.italic && !look.underline) { kids(node, depth); return; }
      inlineWrap(node, depth);
      return;
    }
    // The wrapper Google Docs puts around everything it copies. Kept as a bold
    // element it would bold the whole paste.
    if (tag === 'B' && /font-weight:\s*normal/i.test(node.getAttribute('style') || '')) {
      kids(node, depth);
      return;
    }

    const inline = OUT_INLINE[tag];
    if (inline === 'br') { html.push('<br>'); pushText('\n'); return; }
    if (inline === 'a') {
      const href = outHref(node.getAttribute('href'));
      if (!href) { kids(node, depth); return; }
      html.push('<a href="' + escAttr(href) + '" style="' + OUT_STYLE.a + '">');
      const before = text.length;
      kids(node, depth);
      html.push('</a>');
      // A link whose text is already the address does not need it twice; one
      // whose text is a word gets the address after it, because a text part with
      // no URLs in it is a message whose links have been deleted.
      const label = text.slice(before).join('').trim();
      if (label && label !== href && href.indexOf(label) < 0) pushText(' <' + href + '>');
      return;
    }
    if (inline) {
      // A bold that wraps whole paragraphs is unwrapped rather than emitted,
      // because <strong><p>...</p></strong> is invalid and every client repairs
      // it differently. Word and Docs both produce exactly that shape.
      if (hasBlockChild(node)) { kids(node, depth); return; }
      html.push('<' + inline + '>');
      kids(node, depth);
      html.push('</' + inline + '>');
      return;
    }

    const block = OUT_BLOCK[tag];
    if (!block) { kids(node, depth); return; }    // unwrapped, never dropped

    if (block === 'hr') { html.push('<hr style="' + OUT_STYLE.hr + '">'); line(); pushText('----\n\n'); return; }

    const style = OUT_STYLE[block];
    html.push('<' + block + (style ? ' style="' + style + '"' : '') + '>');

    if (block === 'ul' || block === 'ol') {
      listDepth++;
      counters.push(0);
      line();
      kids(node, depth);
      counters.pop();
      listDepth--;
    } else if (block === 'li') {
      const ordered = node.parentNode && node.parentNode.tagName === 'OL';
      if (ordered && counters.length) counters[counters.length - 1]++;
      pushText('  '.repeat(Math.max(0, listDepth - 1))
        + (ordered ? (counters[counters.length - 1] || 1) + '. ' : '- '));
      kids(node, depth);
      pushText('\n');
    } else if (block === 'blockquote') {
      const before = text.length;
      kids(node, depth);
      // Prefixed after the fact, because a quote's own paragraphs have to be
      // marked line by line and the walk only knows where they are once it is out.
      const inner = text.splice(before, text.length - before).join('');
      pushText(inner.replace(/\n$/, '').split('\n').map(l => '> ' + l).join('\n') + '\n\n');
    } else {
      kids(node, depth);
      line();
    }

    if (!VOID_TAGS.has(block)) html.push('</' + block + '>');
  }

  kids(root, 0);

  let outHtml = html.join('');
  // An editable that has only ever held the engine's own placeholder break
  // serialises to nothing, which is what makes the placeholder rule work.
  if (!/[^\s]/.test(outHtml.replace(/<[^>]*>/g, ''))
      && outHtml.indexOf('<hr') < 0 && outHtml.indexOf('<table') < 0) outHtml = '';
  return { html: outHtml, text: text.join('').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim() };
}

/**
 * Pasted or quoted markup to markup this composer owns.
 *
 * createHTMLDocument gives a document with no browsing context, so assigning to
 * its innerHTML builds the tree without running a script, without fetching an
 * image and without a tracking pixel reaching whoever wrote it. Parsing pasted
 * markup into the live document, even into a detached node, is the shortcut that
 * turns a paste into a network request.
 */
function cleanForeignHtml(html) {
  const doc = document.implementation.createHTMLDocument('');
  try {
    // The Sanitizer API is deliberately NOT used as a first pass here, and that is
    // a change of mind against measurement rather than an oversight. Driven at
    // 390 and 1440 in this Chromium, body.setHTML strips the style attribute
    // outright, which takes the bold off every run Word and Gecko express as
    // <span style="font-weight:bold"> and takes the marker off the
    // <b style="font-weight:normal"> wrapper Google Docs puts around a whole
    // paste. Pasting a formatted paragraph came back unformatted and the Docs
    // wrapper came back bolding everything. The walk below is the filter, it
    // reads the same attribute, and running a pre-pass that deletes its evidence
    // is worse than not running one.
    doc.body.innerHTML = String(html || '');
  } catch (e) {
    doc.body.textContent = String(html || '');
  }
  return walkOut(doc.body);
}

const EDITOR = {
  node: null,
  savedRange: null
};

function editorNode() { return EDITOR.node || (EDITOR.node = $('cEditor')); }

/**
 * Issued on every focus rather than once at boot, because both flags are
 * document scoped in some engines and element scoped in others, and an editor
 * refocused after the reader pane took focus would otherwise emit spans where it
 * emitted tags a minute earlier. Both are no-ops where they are unsupported, and
 * Safari's Enter yields a div whatever is asked, which is why the serialiser
 * treats div and p as the same block.
 */
function pinCommandOutput() {
  try {
    document.execCommand('styleWithCSS', false, false);
    document.execCommand('defaultParagraphSeparator', false, 'p');
  } catch (e) { /* older engines throw rather than ignore; harmless */ }
}

function editorValue() { return walkOut(editorNode()); }

function setEditorHtml(html) {
  const ed = editorNode();
  // Already rebuilt through the allowlist above, so what is assigned here is
  // this file's own markup and not anybody else's.
  ed.innerHTML = html || '';
  markEditorEmpty();
}

function markEditorEmpty() {
  const ed = editorNode();
  const v = walkOut(ed);
  ed.setAttribute('data-empty', v.html ? 'false' : 'true');
}

/**
 * Read from the caret's ancestors and from computed style rather than from
 * queryCommandState, which reports the wrong answer whenever a stylesheet has
 * set a numeric font-weight on the editable, and which is deprecated alongside
 * the commands it describes. Computed style is what the person can see, and what
 * they can see is what the button has to agree with.
 */
function stateAt() {
  const ed = editorNode();
  const sel = window.getSelection();
  const out = { bold: false, italic: false, underline: false, link: false,
                insertUnorderedList: false, insertOrderedList: false, quote: false };
  if (!sel || !sel.rangeCount) return out;
  let node = sel.getRangeAt(0).startContainer;
  if (node.nodeType === 3) node = node.parentNode;
  if (!node || !ed.contains(node)) return out;
  const cs = window.getComputedStyle(node);
  out.bold = parseInt(cs.fontWeight, 10) >= 600;
  out.italic = cs.fontStyle === 'italic' || cs.fontStyle === 'oblique';
  out.underline = String(cs.textDecorationLine || cs.textDecoration || '').indexOf('underline') >= 0;
  out.link = !!node.closest('a');
  out.insertUnorderedList = !!node.closest('ul');
  out.insertOrderedList = !!node.closest('ol');
  out.quote = !!node.closest('blockquote');
  return out;
}

function paintToolbar() {
  const st = stateAt();
  $('fmtBar').querySelectorAll('.fx[aria-pressed]').forEach(b => {
    const on = !!st[b.dataset.cmd];
    b.setAttribute('aria-pressed', on ? 'true' : 'false');
  });
  const link = $('fmtBar').querySelector('[data-cmd="link"]');
  // The label and not the contents. This button holds a sprite symbol now, and
  // writing text into it would take the symbol out with it. There is no unlink
  // glyph in fragments/icons.html, so the pressed state and the name carry the
  // difference between what it will do and what it did.
  if (link) {
    const name = st.link ? 'Remove link' : 'Link';
    link.setAttribute('aria-label', name);
    link.setAttribute('title', name);
  }
}

/* selectionchange is a document level event that fires on every caret move, so
   the read is deferred to a frame rather than run once per event. */
let paintQueued = false;
document.addEventListener('selectionchange', () => {
  if (UI.overlay !== 'compose') return;
  if (paintQueued) return;
  paintQueued = true;
  requestAnimationFrame(() => {
    paintQueued = false;
    paintToolbar();
    keepCaretVisible();
  });
});

/**
 * Pulls the caret above the docked format bar.
 *
 * The editable is its own scroll container, so block:'nearest' scrolls exactly
 * it and nothing else on the page moves. Without this the caret walks down
 * behind the bar as the message grows and the person is typing blind.
 */
function keepCaretVisible() {
  const ed = editorNode();
  if (document.activeElement !== ed) return;
  const sel = window.getSelection();
  if (!sel || !sel.rangeCount) return;
  let node = sel.getRangeAt(0).startContainer;
  if (node.nodeType === 3) node = node.parentNode;
  if (!node || !ed.contains(node) || !node.scrollIntoView) return;
  try { node.scrollIntoView({ block: 'nearest' }); } catch (e) { /* older engines */ }
}

function saveRange() {
  const sel = window.getSelection();
  if (sel && sel.rangeCount && editorNode().contains(sel.getRangeAt(0).startContainer)) {
    EDITOR.savedRange = sel.getRangeAt(0).cloneRange();
  }
}

function restoreRange() {
  const ed = editorNode();
  ed.focus();
  if (!EDITOR.savedRange) return;
  const sel = window.getSelection();
  sel.removeAllRanges();
  sel.addRange(EDITOR.savedRange);
}

function insertHtmlAtCaret(fragmentHtml) {
  if (!fragmentHtml) return;
  // insertHTML rather than Range surgery, for the one property execCommand still
  // has that nothing replaces: the paste lands on the undo stack, so Ctrl+Z takes
  // it back out. The Range path below is correct and loses that, which is why it
  // is the fallback and not the default.
  let done = false;
  try { done = document.execCommand('insertHTML', false, fragmentHtml); } catch (e) { done = false; }
  if (!done) {
    const sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    const range = sel.getRangeAt(0);
    range.deleteContents();
    const tpl = document.createElement('template');
    tpl.innerHTML = fragmentHtml;
    const frag = tpl.content;
    const last = frag.lastChild;
    range.insertNode(frag);
    if (last) {
      range.setStartAfter(last);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
    }
  }
  markEditorEmpty();
  onComposeInput();
}

function runCommand(cmd) {
  const ed = editorNode();
  ed.focus();
  pinCommandOutput();
  if (cmd === 'quote') {
    // formatBlock is the only way to reach a blockquote without a document model,
    // and the argument needs the angle brackets on the engines that predate the
    // bare form.
    try { document.execCommand('formatBlock', false, '<blockquote>'); }
    catch (e) { document.execCommand('formatBlock', false, 'blockquote'); }
  } else if (cmd === 'removeFormat') {
    document.execCommand('removeFormat', false, null);
    document.execCommand('unlink', false, null);
  } else {
    document.execCommand(cmd, false, null);
  }
  markEditorEmpty();
  paintToolbar();
  onComposeInput();
}

function openLinkRow() {
  const st = stateAt();
  if (st.link) { runCommand('unlink'); return; }
  saveRange();
  const row = $('linkRow');
  row.hidden = false;
  const box = $('linkUrl');
  box.value = '';
  focusSoon(box);
}

function closeLinkRow(refocus) {
  $('linkRow').hidden = true;
  if (refocus) restoreRange();
}

function applyLink() {
  const href = outHref($('linkUrl').value);
  if (!href) {
    toast('That does not look like a web address. Start it with https:// .', true);
    return;
  }
  restoreRange();
  $('linkRow').hidden = true;
  const sel = window.getSelection();
  const collapsed = !sel || !sel.rangeCount || sel.getRangeAt(0).collapsed;
  if (collapsed) {
    // Nothing selected, so the address becomes its own label. Inserting a bare
    // createLink on a collapsed caret does nothing at all in every engine.
    insertHtmlAtCaret('<a href="' + escAttr(href) + '">' + esc(href) + '</a>');
  } else {
    document.execCommand('createLink', false, href);
  }
  markEditorEmpty();
  paintToolbar();
  onComposeInput();
}

/* mousedown and not click. A toolbar button that takes focus kills the selection
   before the command runs, which works on the first press and then silently
   formats nothing for the rest of the session. */
$('fmtBar').addEventListener('mousedown', e => { if (e.target.closest('button')) e.preventDefault(); });
$('fmtBar').addEventListener('click', e => {
  const btn = e.target.closest('button[data-cmd]');
  if (!btn) return;
  const cmd = btn.dataset.cmd;
  if (cmd === 'link') { openLinkRow(); return; }
  runCommand(cmd);
});
$('linkAdd').addEventListener('click', applyLink);
$('linkCancel').addEventListener('click', () => closeLinkRow(true));
$('linkUrl').addEventListener('keydown', e => {
  if (e.key === 'Enter') { e.preventDefault(); applyLink(); }
  else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeLinkRow(true); }
});

/* The clipboard's text/html is raw: getData does not sanitise, only the async
   clipboard read does, and this is the path a Ctrl+V takes, so nothing here may
   assume the browser already looked. Shift is the universal paste-without-
   formatting gesture and is honoured, because the alternative is people pasting a
   web page and then hunting through a menu for the way to undo it. */
editorNode().addEventListener('paste', e => {
  const dt = e.clipboardData;
  if (!dt) return;
  e.preventDefault();
  const html = dt.getData('text/html');
  const text = dt.getData('text/plain');
  if (e.shiftKey || !html) {
    insertHtmlAtCaret(esc(text).replace(/\r?\n/g, '<br>'));
    return;
  }
  const clean = cleanForeignHtml(html);
  insertHtmlAtCaret(clean.html || esc(text).replace(/\r?\n/g, '<br>'));
});

/* Drop is the same vector arriving through a different door, and it is the one
   people forget. Files dropped on the editable are left to the sheet's own drop
   handler, which stages them as attachments. */
editorNode().addEventListener('drop', e => {
  const dt = e.dataTransfer;
  if (!dt) return;
  if (Array.from(dt.types || []).indexOf('Files') >= 0) return;
  e.preventDefault();
  const html = dt.getData('text/html');
  const clean = html ? cleanForeignHtml(html) : null;
  insertHtmlAtCaret((clean && clean.html) || esc(dt.getData('text/plain')).replace(/\r?\n/g, '<br>'));
});

editorNode().addEventListener('input', () => { markEditorEmpty(); onComposeInput(); });
/**
 * Puts the message field at the top of the sheet on a phone.
 *
 * The sheet is 844px tall and the software keyboard takes 300 of them, so the
 * recipients, the subject and the attach row together leave the writing area a
 * slot about a hundred pixels deep, which is four lines. Somebody who has just
 * put the caret in the body is not looking at any of those rows, so they are
 * scrolled off the top and the letter gets the whole remaining screen. One swipe
 * down brings every one of them back, and nothing is hidden that was not.
 *
 * Called from the focus AND from dockToKeyboard, which is not belt and braces:
 * at the moment of focus the sheet has not shrunk yet and there is nothing to
 * scroll, and the shrink arrives a frame or two later with the keyboard.
 */
function revealWritingArea() {
  if (!mqPhone.matches) return;
  if (document.activeElement !== editorNode()) return;
  const field = document.querySelector('#composeSheet .field-msg');
  const holder = $('composeBody');
  if (!field || !holder) return;
  // Written straight onto scrollTop rather than through scrollIntoView. The two
  // rectangles are read first, which forces the layout the keyboard has just
  // changed to settle; scrollIntoView issued in the same frame as the resize was
  // measured doing nothing at all, and it can also scroll the window, which on a
  // fixed sheet is a move nothing on screen accounts for.
  const delta = field.getBoundingClientRect().top - holder.getBoundingClientRect().top;
  if (delta > 1) holder.scrollTop += delta;
}

editorNode().addEventListener('focus', () => {
  pinCommandOutput();
  requestAnimationFrame(revealWritingArea);
});

/* =========================================================================
   The format bar over the software keyboard

   The phone is the primary surface and the toolbar is the control most likely
   to be got wrong on it: parked at the bottom of the sheet it disappears under
   the keyboard, and floated over the text it hides the words being formatted.

   It is docked instead, in the flow, immediately above the keyboard's own top
   edge. The keyboard's height is not something a page can be told directly; the
   only report of it is visualViewport, and only on iOS, where the layout
   viewport does not shrink at all. Android Chrome shrinks the layout viewport
   instead, which makes the number below zero and puts the bar at the bottom of
   an already shortened screen, which is the same place.
   ========================================================================= */

function dockToKeyboard() {
  const dock = $('fmtDock');
  if (!dock) return;
  const vv = window.visualViewport;
  if (!vv || !mqPhone.matches || UI.overlay !== 'compose') {
    dock.style.marginBottom = '';
    dock.removeAttribute('data-kb');
    return;
  }
  // offsetTop matters: iOS scrolls the visual viewport up inside the layout one
  // rather than resizing the page, so the covered band is what is left over
  // after both the height difference and that offset are taken out.
  const covered = window.innerHeight - vv.height - vv.offsetTop;
  // A threshold, because the address bar collapsing is also a visual viewport
  // change and is not a keyboard. No keyboard is under 120px on a phone.
  const kb = covered > 120 ? Math.round(covered) : 0;
  dock.style.marginBottom = kb ? kb + 'px' : '';
  if (kb) dock.setAttribute('data-kb', '1'); else dock.removeAttribute('data-kb');
  // Next frame, not this one. The margin above has only just been written, so
  // the sheet body is still its old height and has nothing to scroll yet; a
  // reveal issued here measures the layout the keyboard has already replaced.
  if (kb) requestAnimationFrame(() => { revealWritingArea(); keepCaretVisible(); });
}

/* The fade at the right edge is there to say the bar scrolls, so it has to come
   off when it does not, or a bar that fits ends in a gradient for no reason. */
function measureToolbar() {
  const bar = $('fmtBar');
  if (!bar) return;
  bar.classList.toggle('fits', bar.scrollWidth <= bar.clientWidth + 1);
}
window.addEventListener('resize', measureToolbar);

if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', dockToKeyboard);
  window.visualViewport.addEventListener('scroll', dockToKeyboard);
}

/* =========================================================================
   Compose state
   ========================================================================= */

/**
 * Everything the composer is currently about, beyond the fields themselves.
 *
 * replyTo and forwardOf are parent message ids and never header values. The
 * server derives In-Reply-To and References from the parent it loads itself,
 * because a browser that can name the header is a browser that can forge one
 * into somebody else's thread.
 */
const COMPOSE = { replyTo: null, forwardOf: null, keep: [] };

/* The draft this composer is attached to. id is the Drafts mailbox message id
   the server answered with, and it is the only durable state; everything else
   here exists to keep the saving line honest. */
const DRAFT = { id: null, timer: null, saving: false, savedAt: null, seed: '', failed: false, queued: false };

const DRAFT_DEBOUNCE = 3000;

function composeFingerprint() {
  /* The files are part of it. Attaching a report and then closing the sheet used
     to be a silent loss, because the four text boxes were unchanged and the
     discard question never fired. */
  return JSON.stringify([
    cTo.serialise(), cCc.serialise(), cBcc.serialise(),
    $('cSubject').value, editorValue().html,
    S.files.map(f => f.name + ':' + f.size),
    COMPOSE.keep.map(k => k.blobId)
  ]);
}

/* Dirty means changed since it was opened, not merely non-empty: a reply is
   pre-filled with a quote, and asking to discard something nobody typed is the
   fastest way to teach people to ignore the question. */
function composeDirty() { return composeFingerprint() !== S.composeSeed; }

/** True when there is anything at all worth keeping. */
function composeHasContent() {
  return !!(cTo.items.length || cCc.items.length || cBcc.items.length
    || $('cSubject').value.trim() || editorValue().html || S.files.length);
}

/* One entry point for every edit, so the draft timer, the placeholder and the
   Send button cannot get out of step with each other by being updated in some
   places and not in others. */
function onComposeInput() {
  markEditorEmpty();
  scheduleDraft();
  // Discard has to appear as soon as there is something to discard, rather than
  // three seconds later when the first save happens to land.
  renderDraftState(DRAFT.savedAt ? 'pending' : undefined);
}

function clearCompose() {
  cTo.set([]);
  cCc.set([]);
  cBcc.set([]);
  $('cSubject').value = '';
  setEditorHtml('');
  S.files = [];
  COMPOSE.replyTo = null;
  COMPOSE.forwardOf = null;
  COMPOSE.keep = [];
  DRAFT.id = null;
  DRAFT.savedAt = null;
  DRAFT.failed = false;
  DRAFT.seed = '';
  clearTimeout(DRAFT.timer);
  showCcBcc(false);
  renderFiles();
  renderDraftState();
  $('composeTitle').textContent = 'New message';
  S.composeSeed = composeFingerprint();
}

function showCcBcc(on) {
  $('fldCc').hidden = !on;
  $('fldBcc').hidden = !on;
  $('btnCcBcc').setAttribute('aria-expanded', on ? 'true' : 'false');
  $('btnCcBcc').hidden = !!on;
}

/**
 * Opens the composer.
 *
 * With no arguments it reopens whatever is already in the sheet rather than
 * wiping it, because closing the composer now saves a draft instead of asking
 * whether to throw one away, and a Compose button that silently discarded the
 * thing it had just saved would be the worst of both. A new message is what you
 * get after a send or after Discard.
 */
function openCompose(seed) {
  if (!can('MAIL_SEND')) return;
  if (seed) {
    // A reply started on top of an unfinished message files that message rather
    // than throwing it away, because Reply is not a discard gesture and nobody
    // expects it to be one.
    if (composeHasContent() && composeDirty()) saveDraft('replaced');
    clearCompose();
    cTo.set(seed.to || []);
    cCc.set(seed.cc || []);
    cBcc.set(seed.bcc || []);
    $('cSubject').value = seed.subject || '';
    // A reply and a forward bring their own body and have already placed the
    // signature above the quote. Only a blank composer needs one added here, and
    // seed.html being empty is exactly what "blank" means.
    setEditorHtml(seed.html || signatureBlock('new'));
    COMPOSE.replyTo = seed.replyTo || null;
    COMPOSE.forwardOf = seed.forwardOf || null;
    COMPOSE.keep = seed.keep || [];
    DRAFT.id = seed.draftId || null;
    if (seed.draftId) {
      DRAFT.savedAt = seed.savedAt ? new Date(seed.savedAt) : null;
      $('composeTitle').textContent = 'Draft';
    }
    if ((seed.cc && seed.cc.length) || (seed.bcc && seed.bcc.length)) showCcBcc(true);
    renderFiles();
    renderDraftState();
    S.composeSeed = composeFingerprint();
    DRAFT.seed = S.composeSeed;
  }
  goOverlay('compose');
  acWarm();
  requestAnimationFrame(() => { dockToKeyboard(); measureToolbar(); });
}

/* =========================================================================
   Drafts

   The Drafts mailbox is the store. A Postgres table for this would be a second
   copy of a thing the mail server already owns, and it would disagree with the
   phone's IMAP client the first time somebody deleted a draft there.

   Attachments are deliberately NOT part of an autosave. Staging a file uploads
   nothing until send, and moving that upload to attach time is a change to the
   send endpoint that this file does not own. Until it lands the saving line says
   so out loud rather than implying files are safe.
   ========================================================================= */

function scheduleDraft() {
  if (!can('MAIL_SEND')) return;
  clearTimeout(DRAFT.timer);
  if (DRAFT.savedAt || DRAFT.failed) renderDraftState('pending');
  DRAFT.timer = setTimeout(() => saveDraft('timer'), DRAFT_DEBOUNCE);
}

function draftFields() {
  const v = editorValue();
  return {
    // id, not draftId. POST /draft names it id and POST /send names it draftId,
    // and they are two different endpoints rather than one shape used twice.
    id: DRAFT.id || '',
    to: cTo.serialise(),
    cc: cCc.serialise(),
    bcc: cBcc.serialise(),
    subject: $('cSubject').value,
    body: v.text,
    html: v.html,
    replyTo: COMPOSE.replyTo || '',
    forwardOf: COMPOSE.forwardOf || ''
  };
}

async function saveDraft(reason) {
  clearTimeout(DRAFT.timer);
  if (!can('MAIL_SEND')) return;
  if (S.sending) return;                       // a send is about to destroy it anyway
  if (!composeHasContent()) return;
  const print = composeFingerprint();
  if (print === DRAFT.seed && DRAFT.id) return;   // nothing has changed since the last save
  if (DRAFT.saving) { DRAFT.queued = true; return; }

  DRAFT.saving = true;
  renderDraftState('saving');
  try {
    const r = await post('/api/mail/draft', draftFields());
    DRAFT.id = r.id || DRAFT.id;
    DRAFT.savedAt = r.savedAt ? new Date(r.savedAt) : new Date();
    DRAFT.seed = print;
    DRAFT.failed = false;
    if ($('composeTitle').textContent === 'New message') $('composeTitle').textContent = 'Draft';
  } catch (e) {
    // A locked mailbox mid-autosave must not throw the unlock sheet over
    // somebody's typing. The line says it failed and the next edit tries again.
    DRAFT.failed = true;
  } finally {
    DRAFT.saving = false;
    renderDraftState();
    if (DRAFT.queued) { DRAFT.queued = false; saveDraft('queued'); }
  }
}

/**
 * The last save, for a tab that is going away.
 *
 * fetch with keepalive and XHR both lose the race against a closing document,
 * and beforeunload cannot await anything. sendBeacon is the only request a
 * browser promises to finish, and it cannot set a header, so the CSRF token
 * travels as the _csrf parameter Spring Security reads by default. The blob
 * carries the form content type explicitly, because a URLSearchParams handed
 * straight to sendBeacon goes out as text/plain and no form binder would see it.
 */
function beaconDraft() {
  if (!can('MAIL_SEND') || UI.overlay !== 'compose') return;
  if (!composeHasContent()) return;
  if (composeFingerprint() === DRAFT.seed && DRAFT.id) return;
  if (!navigator.sendBeacon) return;
  const params = new URLSearchParams(draftFields());
  // forwardOf is not a parameter POST /draft takes, and a URLSearchParams built
  // from the same object would carry it into a beacon nobody can see fail.
  params.delete('forwardOf');
  params.append('_csrf', csrfToken());
  try {
    navigator.sendBeacon('/api/mail/draft',
      new Blob([params.toString()], { type: 'application/x-www-form-urlencoded' }));
  } catch (e) { /* nothing left to do from a page that is closing */ }
}

window.addEventListener('pagehide', beaconDraft);
document.addEventListener('visibilitychange', () => {
  // hidden is the state a phone reaches when the app is switched away from, and
  // on iOS it is frequently the last state a page is ever in.
  if (document.visibilityState === 'hidden') beaconDraft();
});

function renderDraftState(phase) {
  const el = $('draftState');
  const wrap = $('fmtGroup');
  let text = '';
  let bad = false;
  if (phase === 'saving') text = 'Saving';
  else if (DRAFT.failed) { text = 'Not saved. It will try again as you type.'; bad = true; }
  else if (phase === 'pending' && DRAFT.savedAt) text = 'Saved ' + hhmm(DRAFT.savedAt) + ', editing';
  else if (DRAFT.savedAt) text = 'Saved to Drafts ' + hhmm(DRAFT.savedAt);
  // Said out loud rather than implied, because a person who has watched a
  // saving line appear will reasonably assume the file went with it.
  if (text && S.files.length) text += '. Attachments are not saved yet.';
  el.textContent = text;
  el.classList.toggle('bad', bad);
  const show = !!text || (!!DRAFT.id || composeHasContent());
  wrap.setAttribute('data-draft', show ? '1' : '0');
  $('btnDiscard').hidden = !(DRAFT.id || composeHasContent());
}

function hhmm(d) {
  return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

async function discardDraft() {
  if (!window.confirm('Throw this message away?')) return;
  const id = DRAFT.id;
  clearCompose();
  if (UI.overlay === 'compose') history.back();
  if (id) {
    try { await post('/api/mail/draft/delete', { id: id }); }
    catch (e) { if (!handled(e)) toast(e.message, true); }
  }
}

/**
 * A row in the Drafts folder opens the composer, not the reader.
 *
 * The body comes back as the reader's standalone document, which is already
 * sanitised, so it is parsed into an inert document and rebuilt through the same
 * walk everything else goes through rather than being trusted as markup.
 */
async function resumeDraft(id) {
  let m;
  try {
    m = await api('/api/mail/draft?id=' + encodeURIComponent(id));
  } catch (e) {
    if (!handled(e)) toast(e.message, true);
    return;
  }
  // GET /draft and not GET /message. The two answer different questions: /message
  // returns a standalone sanitised document for the reader's iframe, which is not
  // something a contenteditable can hold, and it does not carry Bcc at all. This
  // one returns editable HTML and every header the composer had. It is still run
  // through the walk here, because a draft in that folder may have been written by
  // any client holding the mailbox password.
  const body = cleanForeignHtml(m.html || '');
  openCompose({
    to: (m.to || []).map(a => ({ name: a.name, email: a.email })),
    cc: (m.cc || []).map(a => ({ name: a.name, email: a.email })),
    bcc: (m.bcc || []).map(a => ({ name: a.name, email: a.email })),
    subject: m.subject || '',
    html: body.html,
    draftId: id,
    savedAt: null,
    keep: (m.attachments || []).map(a => ({ blobId: a.blobId, name: a.name, size: a.size }))
  });
}

/** The <body> of a reader document, as a string, without parsing it here. */
function bodyFragmentOf(doc) {
  const s = String(doc || '');
  const open = s.indexOf('<body>');
  const close = s.lastIndexOf('</body>');
  return open >= 0 && close > open ? s.slice(open + 6, close) : s;
}

/* ---------- attachments on the compose sheet ---------- */

/** Lowercase extension without its dot, matching Attachment.refusedExtension. */
function extensionOf(name) {
  const base = String(name || '').replace(/[. ]+$/, '');
  const dot = base.lastIndexOf('.');
  return dot < 0 || dot === base.length - 1 ? '' : base.slice(dot + 1).toLowerCase();
}

/** The sentence for why this file cannot go, or '' when it can. */
function fileRefusal(file) {
  const ext = extensionOf(file.name);
  if (REFUSED_EXT.has(ext)) {
    return 'A .' + ext + ' file runs as a program when it is opened, so it cannot be emailed. '
      + 'Put it in a shared folder and send the link.';
  }
  return '';
}

function stagedBytes() {
  return S.files.reduce((sum, f) => sum + (f.size || 0), 0);
}

/* Base64 is four bytes out for every three in, plus a line break every 76
   characters. The same 1.37 the server uses, so the number the sender is watching
   is the number the server will judge them by. */
function encodedBytes(raw) { return Math.round(raw * 1.37); }

/**
 * Adds files, refusing the ones that cannot go and saying which and why.
 *
 * Duplicates are dropped on name and size together, because picking the same file
 * twice is a slip and attaching two genuinely different files that share a name is
 * something people do do.
 */
function addFiles(list) {
  const incoming = Array.from(list || []);
  if (!incoming.length) return;

  const refused = [];
  let added = 0;
  incoming.forEach(file => {
    const why = fileRefusal(file);
    if (why) { refused.push(file.name + ': ' + why); return; }
    // The server drops empty parts, so an empty file staged here would look
    // attached on this screen and be absent from the message that arrives.
    if (!file.size) { refused.push(file.name + ' is empty, so there is nothing to attach.'); return; }
    if (S.files.length + 1 > S.attachMaxFiles) {
      refused.push('Only ' + S.attachMaxFiles + ' files fit on one message.');
      return;
    }
    if (S.files.some(f => f.name === file.name && f.size === file.size)) return;
    S.files.push(file);
    added++;
  });

  renderFiles();
  if (refused.length) toast(refused[0], true);
  else if (added && stagedBytes() > S.attachLimit) {
    toast('That is over the ' + bytes(S.attachLimit) + ' a message can carry. Take one off to send.', true);
  }
}

function removeFile(index) {
  S.files.splice(index, 1);
  renderFiles();
}

/**
 * Draws the staged list and the running total.
 *
 * The over-limit state disables Send rather than letting the sender find out from
 * the server after a two minute upload. The row itself is never silently dropped:
 * a file that vanishes on its own reads as a bug, and the sender needs to know
 * which one to take off.
 */
function renderFiles() {
  const total = stagedBytes();
  const over = total > S.attachLimit;

  /* A forwarded file is drawn in the same list as a staged one, because to the
     person writing the message they are the same thing: something that will go
     out with it and can be taken back off. They differ only in where the bytes
     are, which is why the kept ones do not count towards the upload budget. */
  const kept = COMPOSE.keep.map((k, i) =>
    '<li class="frow kept">'
    + icon('i-attach', 'ic-sm')
    + '<span class="nm">' + esc(k.name || 'attachment') + '</span>'
    + '<span class="sz">' + esc(bytes(k.size) || '') + ' forwarded</span>'
    + '<button class="pib rm" type="button" data-rmkeep="' + i + '" aria-label="Do not forward '
    + esc(k.name || 'this file') + '">' + icon('i-close') + '</button></li>').join('');

  $('fileList').innerHTML = kept + S.files.map((f, i) =>
    '<li class="frow">'
    + icon('i-attach', 'ic-sm')
    + '<span class="nm">' + esc(f.name) + '</span>'
    + '<span class="sz">' + esc(bytes(f.size) || '0 KB') + '</span>'
    + '<button class="pib rm" type="button" data-rm="' + i + '" aria-label="Remove ' + esc(f.name) + '">'
    + icon('i-close') + '</button></li>').join('');

  const budget = $('attachBudget');
  budget.classList.toggle('over', over);
  if (!S.files.length) {
    budget.textContent = 'Up to ' + bytes(S.attachLimit);
  } else {
    budget.textContent = bytes(total) + ' of ' + bytes(S.attachLimit)
      + ' (about ' + bytes(encodedBytes(total)) + ' sent)';
  }

  // Only the over-limit case disables Send. An in-flight send disables it too,
  // and that is set and cleared by sendMessage rather than here.
  if (!S.sending) $('btnSend').disabled = over;
  renderDraftState();
}

/** Send has one label node and one fill node, and neither is the button itself. */
function sendChrome(label, fraction, busy) {
  $('sendLabel').textContent = label;
  $('sendFill').style.width = (fraction === null ? 0 : Math.round(fraction * 100)) + '%';
  $('btnSend').disabled = !!busy;
  // Disabled and busy are the same attribute here but not the same state to look
  // at: over the limit is a refusal and should look inert, a send in flight is
  // work and should look alive.
  $('btnSend').classList.toggle('sending', !!busy);
}

/**
 * A multipart send with real upload progress.
 *
 * XMLHttpRequest and not fetch, and this is the only reason: fetch reports nothing
 * at all about how much of a request body has gone out, and a 20MB attachment on
 * Indian mobile data is half a minute in which a button reading "Sending" is
 * indistinguishable from a button that has hung. XHR's upload.progress is the only
 * way a browser will tell us, so the one request in this file that carries bytes
 * uses it and every other request stays on fetch.
 *
 * Resolves and rejects with the same shapes post() does, Locked included, so the
 * caller cannot tell which transport it got.
 */
function upload(url, form, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    S.sending = xhr;
    xhr.open('POST', url);
    xhr.setRequestHeader('X-XSRF-TOKEN', csrfToken());
    // Not multipart/form-data by hand: the browser has to append the boundary it
    // generated, and setting the header ourselves would strip it.
    xhr.upload.addEventListener('progress', e => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
    });
    xhr.addEventListener('load', () => {
      let payload = null;
      try { payload = JSON.parse(xhr.responseText); } catch (e) { payload = null; }
      if (xhr.status === 401) { location.href = '/login'; reject(new Error('signed out')); return; }
      if (xhr.status === 403) { reject(new Error('Your role does not allow that.')); return; }
      if (xhr.status >= 200 && xhr.status < 300) { resolve(payload || {}); return; }
      if (xhr.status === 409 && payload && payload.locked) {
        reject(new Locked(payload.error || 'Your mailbox is not open on this device.'));
        return;
      }
      // Measured on the running app: a request over spring.servlet.multipart's own
      // 25MiB ceiling is turned down by the container before any of our code runs,
      // as a 413 with no body at all, so there is no sentence to show and the
      // generic one below would read "Request failed" on the one failure where the
      // sender most needs to know what to change. Our own limit is well under that
      // and answers properly, so this only fires for a caller that went around the
      // compose sheet, but a bare "Request failed" is not worth shipping either.
      if (xhr.status === 413 && !(payload && payload.error)) {
        reject(new Error('Those files are larger than this server accepts in one request. '
          + 'Attach less than ' + bytes(S.attachLimit) + '.'));
        return;
      }
      reject(new Error((payload && payload.error) || 'Request failed'));
    });
    // A dropped connection mid upload fires error, a navigation fires abort and a
    // stalled one fires timeout. All three mean the same thing to the sender, which
    // is that nothing was sent and the message is still here to try again.
    xhr.addEventListener('error', () => reject(new Error(
      'The connection dropped while the files were going up. Nothing was sent. Try again.')));
    xhr.addEventListener('abort', () => reject(new Error('Sending was stopped. Nothing was sent.')));
    xhr.addEventListener('timeout', () => reject(new Error(
      'The upload timed out. Nothing was sent. Try again on a better connection.')));
    xhr.send(form);
  });
}

/* =========================================================================
   Reply, reply all and forward

   The quoted body is the real one. It used to be the literal string
   "> (original message)", which meant every reply this app has ever sent quoted
   nothing at all; and no In-Reply-To or References header was written, so every
   one of them opened a new thread in the recipient's client. Both are defects
   rather than missing features.

   The quote is built from the reader document the server already sanitised,
   parsed into an inert document and rebuilt through the same allowlist as a
   paste. The headers are NOT built here: the parent's id goes up and the server
   loads it and derives them, because a browser that can name a Message-ID is a
   browser that can drop a forged reply into somebody else's thread.
   ========================================================================= */

function attributionOf(m) {
  return 'On ' + fullWhen(m.receivedAt) + ', ' + (m.from.display || m.from.email) + ' wrote:';
}

/** The quoted original, as the markup the composer will hold. */
/**
 * The mailbox's signature, as a block to sit above a quote or below a blank line.
 *
 * Reads through the MailSettings module, which is a separate script, so every call
 * has to survive that script being absent: it was built and left out of this page
 * for a while, and a hard reference here would have taken the composer down with it
 * rather than simply producing no signature. The separator is the standard "-- ",
 * which other clients recognise and collapse.
 *
 * @param kind 'new' or 'reply'.
 */
function signatureBlock(kind) {
  var api = window.MailSettings;
  if (!api || typeof api.signatureFor !== 'function') return '';
  var sig = api.signatureFor(kind);
  if (!sig) return '';
  return '<p><br></p><div class="jsig" data-sig="1">-- <br>' + sig + '</div>';
}

function quoteBlockOf(m) {
  const inner = cleanForeignHtml(bodyFragmentOf(m.bodyHtml));
  return '<p><br></p><p>' + esc(attributionOf(m)) + '</p>'
    + '<blockquote class="jq">' + (inner.html || '<p></p>') + '</blockquote>';
}

function replyTo(m, all) {
  const subject = /^re:/i.test(m.subject || '') ? m.subject : 'Re: ' + (m.subject || '');
  const mine = String(S.mailbox || ME.email || '').toLowerCase();
  const from = (m.from.email || '').toLowerCase();
  let cc = [];
  if (all) {
    const seen = new Set([mine, from]);
    [].concat(m.to || [], m.cc || []).forEach(a => {
      const e = String(a.email || '').toLowerCase();
      if (!e || seen.has(e)) return;
      seen.add(e);
      cc.push({ name: a.name || '', email: a.email });
    });
  }
  openCompose({
    to: [{ name: m.from.name || '', email: m.from.email }],
    cc: cc,
    subject: subject,
    // Above the quote, which is where every client puts it and where the reader
    // expects to find who wrote to them. MailSettings degrades to an empty string
    // when it is not on the page or has not answered yet, so a signature never
    // delays the composer opening.
    html: signatureBlock('reply') + quoteBlockOf(m),
    replyTo: m.id
  });
  // The caret belongs at the top, above the quote, which is where every client
  // puts it and where the reply is actually written.
  focusSoon(editorNode());
  requestAnimationFrame(caretToStart);
}

function forwardOf(m) {
  const subject = /^fwd:/i.test(m.subject || '') ? m.subject : 'Fwd: ' + (m.subject || '');
  const rows = [
    ['From', m.from.display || m.from.email],
    ['Date', fullWhen(m.receivedAt)],
    ['Subject', m.subject || '(no subject)'],
    ['To', (m.to || []).map(a => a.display || a.email).join(', ')]
  ].filter(r => r[1]);
  const head = '<p><br></p><p>' + esc('---------- Forwarded message ----------') + '</p><p>'
    + rows.map(r => '<strong>' + esc(r[0]) + ':</strong> ' + esc(r[1])).join('<br>')
    + '</p>';
  const inner = cleanForeignHtml(bodyFragmentOf(m.bodyHtml));
  openCompose({
    subject: subject,
    html: head + '<blockquote class="jq">' + (inner.html || '<p></p>') + '</blockquote>',
    forwardOf: m.id,
    // The parent's blobs, named rather than downloaded and re-uploaded. They are
    // blobs in the same account, so the send can reference them directly; this
    // list is what tells it which ones survived the writer's second thoughts.
    keep: (m.attachments || []).map(a => ({ blobId: a.blobId, name: a.name, size: a.size }))
  });
  focusSoon(editorNode());
  requestAnimationFrame(caretToStart);
}

function caretToStart() {
  const ed = editorNode();
  if (document.activeElement !== ed) return;
  const range = document.createRange();
  range.setStart(ed, 0);
  range.collapse(true);
  const sel = window.getSelection();
  sel.removeAllRanges();
  sel.addRange(range);
  ed.scrollTop = 0;
}

async function sendMessage() {
  if (S.sending) return;                       // a second tap must not start a second send

  // Refused here rather than by the server, because a mistyped address that only
  // fails after a two minute upload is a mistyped address nobody can correct.
  const wrong = [].concat(cTo.bad(), cCc.bad(), cBcc.bad());
  if (wrong.length) {
    toast(wrong[0].email + ' is not an email address. Fix it or take it off before sending.', true);
    const owner = CHIPS.find(f => f.bad().length);
    if (owner) owner.input.focus();
    return;
  }
  if (!cTo.items.length && !cCc.items.length && !cBcc.items.length) {
    toast('Add at least one recipient.', true);
    cTo.input.focus();
    return;
  }

  const total = stagedBytes();
  if (total > S.attachLimit) {
    toast('Those files come to ' + bytes(total) + ', about ' + bytes(encodedBytes(total))
      + ' once encoded for email, over the ' + bytes(S.attachLimit)
      + ' a message can carry. Take one off.', true);
    return;
  }

  const written = editorValue();
  const fields = {
    to: cTo.serialise(),
    cc: cCc.serialise(),
    bcc: cBcc.serialise(),
    subject: $('cSubject').value,
    // Both parts, always. A text/plain alternative is what makes the message
    // render in a client that refuses HTML, and its absence is one of the
    // cheapest things a spam filter scores against.
    body: written.text,
    html: written.html,
    replyTo: COMPOSE.replyTo || '',
    forwardOf: COMPOSE.forwardOf || '',
    keepAttachments: COMPOSE.keep.map(k => k.blobId).join(','),
    draftId: DRAFT.id || ''
  };

  clearTimeout(DRAFT.timer);
  sendChrome(S.files.length ? 'Uploading' : 'Sending', 0, true);
  try {
    let result;
    if (!S.files.length) {
      // No files, so nothing has changed about this request: the same urlencoded
      // form post through the same helper it has always used. The multipart path
      // below exists only when there is something to carry.
      result = await post('/api/mail/send', fields);
    } else {
      const form = new FormData();
      Object.keys(fields).forEach(k => form.append(k, fields[k] === null ? '' : fields[k]));
      S.files.forEach(f => form.append('files', f, f.name));
      result = await upload('/api/mail/send', form, fraction => {
        // The last stretch is the server uploading to the mail server and filing
        // the copy in Sent, which no progress event covers, so the bar stops at
        // the honest number and the word changes instead of inventing movement.
        if (fraction >= 1) sendChrome('Sending', 1, true);
        else sendChrome('Uploading ' + Math.round(fraction * 100) + '%', fraction, true);
      });
    }
    // Cleared before the pop, so neither the autosave nor the close handler can
    // file a draft for a message that has already gone out.
    clearCompose();
    if (UI.overlay === 'compose') history.back();
    toast(result.message || 'Sent.');
    // The draft this came from is destroyed by the send, so a Drafts folder on
    // screen is now one row out of date.
    if (S.folderRole === 'drafts') loadMessages(true);
  } catch (e) {
    if (!handled(e)) toast(e.message, true);
  } finally {
    S.sending = null;
    sendChrome('Send', null, false);
    // renderFiles owns the disabled state whenever a send is not in flight, and a
    // failed send leaves the files staged, so it has to have the last word here.
    renderFiles();
  }
}

/* ---------- wiring ---------- */

$('folders').addEventListener('click', e => {
  const b = e.target.closest('.fold');
  if (b) selectFolder(b.dataset.id, b.dataset.name, b.dataset.role);
});

/* The same rows in the phone sheet, doing one of two jobs. Both close the sheet
   by popping and act afterwards, never by removing the class themselves. */
$('sheetFolders').addEventListener('click', e => {
  const b = e.target.closest('.fold');
  if (!b || b.disabled) return;
  const id = b.dataset.id, name = b.dataset.name, role = b.dataset.role;
  if (UI.overlay === 'move') {
    // The folder it is already in is on this list too, marked as current. Say so
    // rather than spending a round trip proving it.
    if (id === S.folderId) { toast('It is already in ' + name + '.'); return; }
    const message = UI.id;
    popThen(() => moveMessage(message, id));
  } else {
    popThen(() => selectFolder(id, name, role));
  }
});

$('moreSheet').addEventListener('click', e => {
  const b = e.target.closest('[data-more]');
  if (!b || b.disabled) return;
  const what = b.getAttribute('data-more');
  const m = S.reader;
  if (!m) return;
  popThen(() => {
    if (what === 'reply-all') replyTo(m, true);
    else if (what === 'forward') forwardOf(m);
    else if (what === 'archive') archiveMessage(m.id);
    else if (what === 'unread') markUnread(m.id);
  });
});

$('list').addEventListener('click', e => {
  const b = e.target.closest('.msg');
  if (b) openMessage(b.dataset.id, {});
});

$('tabbar').addEventListener('click', e => {
  const b = e.target.closest('.tab');
  if (!b) return;
  const tab = b.dataset.tab;
  if (tab === 'inbox') {
    if (UI.pane !== 'list' || UI.overlay) history.back();
    else $('list').scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }
  if (tab === 'folders') goOverlay('folders');
  else if (tab === 'compose') openCompose();
  else if (tab === 'search') goOverlay('search');
  else if (tab === 'you') goOverlay('account');
});

['foldersSheet', 'accountSheet', 'devicesSheet', 'moreSheet', 'composeSheet'].forEach(id => {
  const el = $(id);
  el.addEventListener('click', e => {
    if (e.target === el || (e.target.closest && e.target.closest('[data-close]'))) history.back();
  });
});

let searchTimer = null;
$('q').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    S.query = $('q').value.trim();
    setTitles(S.query ? 'Search' : S.folderName);
    loadMessages(true);
  }, 320);
});

$('btnRefresh').addEventListener('click', refreshAll);
$('pbRefresh').addEventListener('click', refreshAll);
$('pbSearch').addEventListener('click', () => goOverlay('search'));
$('pbSearchBack').addEventListener('click', () => history.back());
$('pbAccount').addEventListener('click', () => goOverlay('account'));
$('btnCompose').addEventListener('click', () => openCompose());
$('railCompose').addEventListener('click', () => openCompose());
$('railLock').addEventListener('click', lockMailbox);
/* Both entry points to the devices screen, wired in the same change as the sheet
   itself. goOverlay replaces rather than stacks, so Back from Devices lands on
   the list and not back inside the You sheet, which is how every other overlay
   on this page already behaves. */
$('railDevices').addEventListener('click', () => goOverlay('devices'));
$('shDevices').addEventListener('click', () => goOverlay('devices'));
$('shLock').addEventListener('click', () => popThen(lockMailbox));
$('btnCancel').addEventListener('click', () => history.back());
$('btnCancelTop').addEventListener('click', () => history.back());
$('btnSend').addEventListener('click', sendMessage);
$('btnUnlock').addEventListener('click', unlockMailbox);
$('uPassword').addEventListener('keydown', e => { if (e.key === 'Enter') unlockMailbox(); });
$('uRemember').addEventListener('change', syncUnlockKeep);
syncUnlockKeep();

/* ---------- attach wiring ---------- */

$('btnAttach').addEventListener('click', () => $('cFiles').click());

$('cFiles').addEventListener('change', e => {
  addFiles(e.target.files);
  // Cleared, or choosing the same file again after removing it fires no change
  // event at all and the row never comes back.
  e.target.value = '';
});

$('fileList').addEventListener('click', e => {
  const keep = e.target.closest('[data-rmkeep]');
  if (keep) {
    COMPOSE.keep.splice(Number(keep.dataset.rmkeep), 1);
    renderFiles();
    onComposeInput();
    return;
  }
  const b = e.target.closest('[data-rm]');
  if (b) removeFile(Number(b.dataset.rm));
});

/* ---------- compose wiring ---------- */

/* Built once, after the whole file has loaded, because each one reaches for its
   own nodes at construction. The array is what lets a duplicate be folded across
   all three fields rather than only within the one being typed into. */
cTo = new ChipField('cTo', 'cToField', 'to');
cCc = new ChipField('cCc', 'cCcField', 'cc');
cBcc = new ChipField('cBcc', 'cBccField', 'bcc');
CHIPS = [cTo, cCc, cBcc];
CHIPS.forEach(f => { f.input.dataset.ph = f.input.placeholder; });

$('btnCcBcc').addEventListener('click', () => { showCcBcc(true); focusSoon(cCc.input); });
$('btnDiscard').addEventListener('click', discardDraft);
$('cSubject').addEventListener('input', onComposeInput);

/* Drop anywhere on the compose sheet, not only on the attach row. The row is a
   40px strip at the bottom of a full height sheet, and a file dragged from a
   folder is aimed at the window rather than at a target that small. dragover has
   to preventDefault or the browser navigates to the file and the whole draft is
   gone. */
(() => {
  const sheet = $('composeSheet');
  const field = $('attachField');
  let depth = 0;                 // dragenter and dragleave fire per descendant

  const dragged = e => Array.from((e.dataTransfer && e.dataTransfer.types) || []).indexOf('Files') >= 0;

  sheet.addEventListener('dragenter', e => {
    if (!dragged(e)) return;
    depth++;
    field.classList.add('dragging');
  });
  sheet.addEventListener('dragover', e => { if (dragged(e)) e.preventDefault(); });
  sheet.addEventListener('dragleave', e => {
    if (!dragged(e)) return;
    depth = Math.max(0, depth - 1);
    if (!depth) field.classList.remove('dragging');
  });
  sheet.addEventListener('drop', e => {
    if (!dragged(e)) return;
    e.preventDefault();
    depth = 0;
    field.classList.remove('dragging');
    addFiles(e.dataTransfer.files);
  });
})();

document.addEventListener('keydown', e => {
  // The unlock sheet deliberately has no dismiss: there is nothing behind it.
  if (e.key !== 'Escape') return;
  if ($('unlockSheet').classList.contains('open')) return;
  // The link row and the contact menu are layers inside the compose sheet rather
  // than states in the history machine, so Escape has to take them off before it
  // reaches the sheet they are on. Otherwise one Escape closes the whole message
  // to dismiss a menu.
  if (!$('linkRow').hidden) { closeLinkRow(true); return; }
  if (!$('acMenu').hidden) { acClose(); return; }
  if (UI.overlay) history.back();
});

/* Ctrl+Enter and Cmd+Enter send, from anywhere on the compose sheet. It is the
   one shortcut every mail client agrees on and the only one bound here. */
$('composeSheet').addEventListener('keydown', e => {
  if (e.key !== 'Enter' || !(e.ctrlKey || e.metaKey)) return;
  e.preventDefault();
  sendMessage();
});

if (ME.email) identify(ME.email);
if (!can('MAIL_SEND')) {
  $('btnCompose').style.display = 'none';
  $('railCompose').style.display = 'none';
  // grid-auto-columns:1fr redistributes the remaining four across the bar with
  // no second rule to write.
  $('tabCompose').style.display = 'none';
}

/* ---------- boot ---------- */

(async function start() {
  // The hash is read and then wiped from this entry, so the first Back from a
  // /mail#compose deep link lands on the list instead of returning to the link
  // and opening compose again forever. popstate fires for a hash change too,
  // which is why BASE has to be a safe everything-closed state.
  const wantsCompose = location.hash === '#compose';
  history.replaceState({ jm: BASE }, '', location.pathname + location.search);
  placeChrome();
  clearCompose();
  applyState(BASE);
  setReaderChrome(false);
  emptyReader();

  // Fired together rather than in series: two round trips in sequence is the
  // difference between a list at 300ms and a list at 700ms on a phone.
  const statusAsk = api('/api/mail/status').then(v => ({ v: v }), e => ({ e: e }));
  const foldersAsk = api('/api/mail/folders').then(v => ({ v: v }), e => ({ e: e }));

  /* Settings are fetched alongside those two rather than when the composer first
     opens, because the signature has to be in the body the moment Compose is
     pressed and a round trip started at that point is a visible flicker. Deliberately
     not awaited: mail is the job, and a settings call that is slow or fails must not
     hold the list back or stop the mailbox loading. */
  if (window.MailSettings && typeof window.MailSettings.load === 'function') {
    window.MailSettings.load().catch(() => { /* no signature is a fine outcome */ });
  }

  const status = await statusAsk;
  S.booting = false;

  /* The attachment limit is the server's number, not a constant repeated here
     that would go stale the day somebody changes the property. The fallbacks in S
     only cover the window before this answer lands. */
  if (status.v && status.v.attachmentLimit) S.attachLimit = status.v.attachmentLimit;
  if (status.v && status.v.attachmentMaxFiles) S.attachMaxFiles = status.v.attachmentMaxFiles;
  renderFiles();

  if (status.e) {
    await foldersAsk;
    $('list').innerHTML = errState(status.e.message, 'folders');
    $('list').setAttribute('aria-busy', 'false');
    setReader(errState(status.e.message, 'folders'));
    return;
  }

  if (!status.v.unlocked) {
    await foldersAsk;                       // settled, and deliberately discarded
    $('uAddress').value = status.v.suggested || ME.email || '';

    /* The one branch that decides whether anybody is asked for a password today.
       The folders answer above is already a 409 and is thrown away either way,
       so a successful restore refetches rather than reusing it. */
    if (await restoreFromDevice(status.v)) {
      S.folderId = null;
      await loadFolders();
      if (wantsCompose && can('MAIL_SEND')) openCompose();
      return;
    }

    $('list').innerHTML = emptyState('i-lock', 'Your mailbox is not open on this device yet.');
    $('list').setAttribute('aria-busy', 'false');
    openUnlock();
    return;
  }

  const folders = await foldersAsk;
  if (folders.e) {
    if (!handled(folders.e)) {
      $('list').innerHTML = errState(folders.e.message, 'folders');
      $('list').setAttribute('aria-busy', 'false');
    }
    return;
  }
  applyFolders(folders.v);

  // The console links here as /mail#compose. Honoured once the mailbox is known
  // to be open, because composing into a locked mailbox only fails at send time.
  if (wantsCompose && can('MAIL_SEND')) openCompose();
})();
