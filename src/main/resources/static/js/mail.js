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
  if (S.booting) return true;
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
  $('moreSheet').classList.toggle('open', UI.overlay === 'more');
  $('composeSheet').classList.toggle('open', UI.overlay === 'compose');
  $('foldersSheetTitle').textContent = UI.overlay === 'move' ? 'Move to folder' : 'Folders';

  // visibility:hidden is the portable half for older Android WebView; inert is
  // the half that keeps a parked pane out of the tab order everywhere else.
  inert(document.querySelector('.app'), modal);
  inert($('tabbar'), modal);
  inert($('reader'), phone && !reading);
  inert($('list'), phone && reading);
  $('reader').setAttribute('aria-hidden', (phone && !reading) ? 'true' : 'false');

  const active = reading ? '' : (UI.overlay === 'move' ? 'folders' : (UI.overlay || 'inbox'));
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

  // popstate is not cancellable, so a compose with unsaved text is put back on
  // the stack and the question is asked from there.
  if (!popGuard && UI.overlay === 'compose' && s.overlay !== 'compose' && composeDirty()) {
    popGuard = true;
    history.pushState({ jm: Object.assign({}, UI) }, '');
    popGuard = false;
    applyState(UI);
    if (window.confirm('Discard this message?')) { clearCompose(); history.back(); }
    return;
  }

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
}

mqPhone.addEventListener('change', () => { placeChrome(); applyState(UI); });

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
function mountBody(container, doc) {
  const old = container.querySelector('.rwrap');
  if (old) old.remove();            // two 400KB srcdoc documents per open is a real leak
  const wrap = document.createElement('div');
  wrap.className = 'rwrap';
  const frame = document.createElement('iframe');
  frame.className = 'rframe';
  frame.setAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox');
  frame.setAttribute('referrerpolicy', 'no-referrer');
  frame.setAttribute('title', 'Message body');
  wrap.appendChild(frame);
  container.appendChild(wrap);
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
  if (reason) $('unlockWhy').textContent = reason;
  $('unlockSheet').classList.add('open');
  ($('uAddress').value ? $('uPassword') : $('uAddress')).focus();
}

async function unlockMailbox() {
  const btn = $('btnUnlock');
  const address = $('uAddress').value.trim();
  const password = $('uPassword').value;
  if (!address || !password) { toast('Both fields are needed.', true); return; }

  btn.disabled = true;
  btn.textContent = 'Opening';
  try {
    await post('/api/mail/unlock', { address: address, password: password });
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

/* ---------- compose ---------- */

function composeFingerprint() {
  /* The files are part of it. Attaching a report and then closing the sheet used
     to be a silent loss, because the four text boxes were unchanged and the
     discard question never fired. */
  return JSON.stringify([$('cTo').value, $('cCc').value, $('cSubject').value, $('cBody').value,
    S.files.map(f => f.name + ':' + f.size)]);
}

/* Dirty means changed since it was opened, not merely non-empty: a reply is
   pre-filled with a quote, and asking to discard something nobody typed is the
   fastest way to teach people to ignore the question. */
function composeDirty() { return composeFingerprint() !== S.composeSeed; }

function clearCompose() {
  $('cTo').value = '';
  $('cCc').value = '';
  $('cSubject').value = '';
  $('cBody').value = '';
  S.files = [];
  renderFiles();
  S.composeSeed = composeFingerprint();
}

function openCompose(to, subject, body, cc) {
  if (!can('MAIL_SEND')) return;
  $('cTo').value = to || '';
  $('cCc').value = cc || '';
  $('cSubject').value = subject || '';
  $('cBody').value = body || '';
  S.files = [];
  renderFiles();
  S.composeSeed = composeFingerprint();
  goOverlay('compose');
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

  $('fileList').innerHTML = S.files.map((f, i) =>
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

function quoteOf(m) {
  return '\n\nOn ' + fullWhen(m.receivedAt) + ', '
    + (m.from.display || m.from.email) + ' wrote:\n> (original message)';
}

function replyTo(m, all) {
  const subject = /^re:/i.test(m.subject || '') ? m.subject : 'Re: ' + (m.subject || '');
  let cc = '';
  if (all) {
    const mine = String(S.mailbox || ME.email || '').toLowerCase();
    const others = [].concat(m.to || [], m.cc || [])
      .map(a => a.email)
      .filter(a => a && a.toLowerCase() !== mine && a.toLowerCase() !== (m.from.email || '').toLowerCase());
    cc = Array.from(new Set(others)).join(', ');
  }
  openCompose(m.from.email, subject, quoteOf(m), cc);
}

function forwardOf(m) {
  const subject = /^fwd:/i.test(m.subject || '') ? m.subject : 'Fwd: ' + (m.subject || '');
  const head = '\n\n---------- Forwarded message ----------\nFrom: '
    + (m.from.display || m.from.email) + '\nDate: ' + fullWhen(m.receivedAt)
    + '\nSubject: ' + (m.subject || '(no subject)')
    + '\n\n(original message)';
  openCompose('', subject, head);
}

async function sendMessage() {
  if (S.sending) return;                       // a second tap must not start a second send

  const total = stagedBytes();
  if (total > S.attachLimit) {
    toast('Those files come to ' + bytes(total) + ', about ' + bytes(encodedBytes(total))
      + ' once encoded for email, over the ' + bytes(S.attachLimit)
      + ' a message can carry. Take one off.', true);
    return;
  }

  const fields = {
    to: $('cTo').value,
    cc: $('cCc').value,
    subject: $('cSubject').value,
    body: $('cBody').value
  };

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
    // Cleared before the pop, so the unsaved-text question does not fire on a
    // message that has already gone out.
    clearCompose();
    if (UI.overlay === 'compose') history.back();
    toast(result.message || 'Sent.');
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

['foldersSheet', 'accountSheet', 'moreSheet', 'composeSheet'].forEach(id => {
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
$('shLock').addEventListener('click', () => popThen(lockMailbox));
$('btnCancel').addEventListener('click', () => history.back());
$('btnCancelTop').addEventListener('click', () => history.back());
$('btnSend').addEventListener('click', sendMessage);
$('btnUnlock').addEventListener('click', unlockMailbox);
$('uPassword').addEventListener('keydown', e => { if (e.key === 'Enter') unlockMailbox(); });

/* ---------- attach wiring ---------- */

$('btnAttach').addEventListener('click', () => $('cFiles').click());

$('cFiles').addEventListener('change', e => {
  addFiles(e.target.files);
  // Cleared, or choosing the same file again after removing it fires no change
  // event at all and the row never comes back.
  e.target.value = '';
});

$('fileList').addEventListener('click', e => {
  const b = e.target.closest('[data-rm]');
  if (b) removeFile(Number(b.dataset.rm));
});

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
  if (UI.overlay) history.back();
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
  applyState(BASE);
  setReaderChrome(false);
  emptyReader();

  // Fired together rather than in series: two round trips in sequence is the
  // difference between a list at 300ms and a list at 700ms on a phone.
  const statusAsk = api('/api/mail/status').then(v => ({ v: v }), e => ({ e: e }));
  const foldersAsk = api('/api/mail/folders').then(v => ({ v: v }), e => ({ e: e }));

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
    $('list').innerHTML = emptyState('i-lock', 'Your mailbox is not open on this device yet.');
    $('list').setAttribute('aria-busy', 'false');
    $('uAddress').value = status.v.suggested || ME.email || '';
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
