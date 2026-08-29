/* Jarurat Mail - bulk selection.
 *
 * Selecting many messages and doing one thing to all of them is the daily job
 * of a shared info@ inbox, and it is the one job this mailbox could not do at
 * all: every write endpoint takes exactly one id, and the list offered one tap
 * per message. This file adds the selection, the bar, and an undo for every
 * action it can take, and it does all of it from outside mail.js.
 *
 * That last part is the constraint that shapes the file. mail.js owns the list
 * and rebuilds its whole subtree with innerHTML on every render, so nothing
 * this file puts inside the list can be assumed to survive. The answer is a
 * MutationObserver: after every rebuild the checkbox column and the bar are
 * put back from the same paint that drew them the first time, which means
 * there is one code path rather than a first-draw path and a repair path. The
 * bar itself is a node cloned once and re-parented rather than re-created, so
 * its listeners are bound a single time and no rebuild can leak another set.
 *
 * The endpoints are the other constraint, and it is an expensive one, stated
 * here so nobody has to measure it to find out. /api/mail/read, /flag, /move
 * and /delete each take one id, so archiving forty messages is forty round
 * trips at six in flight, not one. JMAP itself has no such limit: Email/set
 * takes a multi-key update map and would do all forty in a single call. The
 * fix belongs in MailService and MailApiController, and when it lands the only
 * thing that changes here is the body of each(), which is why every action
 * goes through that one function.
 *
 * Undo is not a recall and never pretends to be. It is the inverse operation,
 * sent afterwards: a move back to the folder the message came from, or the
 * read flag put back to the value it had. It is offered only where that
 * inverse is knowable, which rules out two cases, both handled explicitly
 * below: a destroy out of Trash, which no request can bring back, and any
 * action taken on a search result, whose rows came from folders this screen
 * cannot name one by one.
 */
(function () {
  'use strict';

  var doc = document;
  var list = doc.getElementById('list');
  var barTpl = doc.getElementById('jmBulkBar');
  var checkTpl = doc.getElementById('jmRowCheck');
  var menuTpl = doc.getElementById('jmMoveMenu');

  /* Every method below is defined whether or not this page is a mailbox, so a
     caller never has to test for the object and then again for the method. On
     a page with no message list, or with the fragment left out of the include,
     ready stays false and each one answers with the empty version of whatever
     it returns. */
  var ready = !!(list && barTpl && checkTpl && menuTpl);

  var CAP = 200;          // most rows one "select all matching" will ever gather
  var LANES = 6;          // requests in flight, because the endpoints are per id
  var UNDO_MS = 15000;    // how long the undo offer stays on the bar
  var PRESS_MS = 500;     // long press, the platform figure on both phone OSes
  var PRESS_SLOP = 10;    // pixels of drift that turn a press into a scroll

  var SEL = new Set();        // selected email ids, the whole selection model
  var mode = false;           // the checkbox column and the bar are on screen
  var scope = 'view';         // 'view', or 'all' once every match was gathered
  var anchor = null;          // last row touched, so shift can extend from it
  var busy = false;           // an action is running; the bar is not a menu now
  var undoState = null;       // {text, run, at, action, ids}
  var undoTimer = null;
  var swallowClick = false;   // the click that follows a long press is not a tap
  var press = null;

  var wide = window.matchMedia('(min-width:900px)');

  /* ---------- talking to the page it was included on ---------- */

  /* mail.js declares these as function declarations, so they are on window and
     can be borrowed. Borrowing rather than re-implementing is the point: post()
     carries the CSRF header, the 401 redirect and the 409 that means the
     mailbox is locked rather than the session dead, and a second copy of that
     logic here would be a second copy to get wrong. Each one falls back to
     something honest when this file is included somewhere mail.js is not. */

  function say(message, bad) {
    if (typeof window.toast === 'function') { window.toast(message, bad); return; }
    var box = doc.getElementById('toasts');
    if (!box) return;
    var el = doc.createElement('div');
    el.className = 'toast' + (bad ? ' bad' : '');
    el.textContent = message;
    box.appendChild(el);
    setTimeout(function () { el.remove(); }, 4600);
  }

  function csrf() {
    var m = doc.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  function post(url, params) {
    if (typeof window.post === 'function') return window.post(url, params);
    var body = new URLSearchParams();
    Object.keys(params || {}).forEach(function (k) {
      if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]);
    });
    return fetch(url, { method: 'POST', body: body, headers: { 'X-XSRF-TOKEN': csrf() } })
      .then(function (res) {
        if (!res.ok) throw new Error('Request failed');
        return res.json().catch(function () { return {}; });
      });
  }

  function api(url) {
    if (typeof window.api === 'function') return window.api(url);
    return fetch(url, { headers: { Accept: 'application/json' } }).then(function (res) {
      if (!res.ok) throw new Error('Request failed');
      return res.json();
    });
  }

  function fail(e) {
    if (typeof window.handled === 'function' && window.handled(e)) return;
    say((e && e.message) || 'That did not work.', true);
  }

  function allowed(permission) {
    if (typeof window.can !== 'function') return true;
    return window.can(permission);
  }

  /* mail.js holds its list in a const, which puts it in the global lexical
     scope rather than on window: reachable by name from another classic
     script, invisible to a property lookup. It is read here and never
     written to except through the two patches at the end of an action, and
     every reader falls back to the DOM, which is why a rename in mail.js
     costs this file some precision and never a crash. */
  function model() {
    try {
      /* jshint -W117 */
      return (typeof S !== 'undefined' && S && Array.isArray(S.messages)) ? S : null;
    } catch (e) { return null; }
  }

  function refresh() {
    if (typeof window.loadMessages === 'function') window.loadMessages(true);
    else doc.dispatchEvent(new CustomEvent('mail:refresh'));
  }

  function recount() {
    if (typeof window.loadFolderCounts === 'function') window.loadFolderCounts();
  }

  /* ---------- what is on screen ---------- */

  function rowNodes() {
    return Array.prototype.slice.call(list.querySelectorAll('.msg[data-id]'));
  }

  function viewIds() {
    return rowNodes().map(function (r) { return r.getAttribute('data-id'); });
  }

  function rowFor(id) {
    return rowNodes().filter(function (r) { return r.getAttribute('data-id') === id; })[0] || null;
  }

  /* The folder the list is showing, from mail.js when it is there and from the
     folder rail when it is not. The rail rows carry id, name and role as data
     attributes and are in the DOM at every width, so this answer is available
     even on a phone where the rail itself is display:none. */
  function folder() {
    var m = model();
    if (m && m.folderId) return { id: m.folderId, name: m.folderName, role: m.folderRole || '' };
    var on = doc.querySelector('#folders .fold.on');
    if (!on) return { id: '', name: '', role: '' };
    return { id: on.dataset.id, name: on.dataset.name, role: on.dataset.role || '' };
  }

  function folders() {
    return Array.prototype.slice.call(doc.querySelectorAll('#folders .fold')).map(function (b) {
      return { id: b.dataset.id, name: b.dataset.name, role: b.dataset.role || '' };
    });
  }

  function query() {
    var m = model();
    if (m) return m.query || '';
    var q = doc.getElementById('q');
    return q ? q.value.trim() : '';
  }

  /* The number of messages the current folder or search actually holds, which
     is what makes "select all 148" an honest offer rather than a guess. The
     footer mail.js draws under the list says it out loud in both of its two
     forms, so there is a second source when the model is out of reach. */
  function total() {
    var m = model();
    if (m && typeof m.total === 'number' && m.total > 0) return m.total;
    var foot = list.querySelector('.listfoot');
    if (foot) {
      var hit = foot.textContent.match(/of\s+(\d+)/) || foot.textContent.match(/(\d+)\s+message/);
      if (hit) return Number(hit[1]);
    }
    return viewIds().length;
  }

  /* ---------- the bar ---------- */

  var bar = null, master = null, countEl = null, noteEl = null, noteText = null,
      noteAct = null, doneBtn = null;

  function buildBar() {
    bar = barTpl.content.firstElementChild.cloneNode(true);
    master = bar.querySelector('[data-jmb-master]');
    countEl = bar.querySelector('[data-jmb-count]');
    noteEl = bar.querySelector('[data-jmb-note]');
    noteText = bar.querySelector('[data-jmb-note-text]');
    noteAct = bar.querySelector('[data-jmb-note-act]');
    doneBtn = bar.querySelector('[data-jmb-phone]');

    master.addEventListener('click', function (e) {
      e.stopPropagation();
      if (master.checked) selectVisible(); else clear();
    });

    bar.addEventListener('click', function (e) {
      var b = e.target.closest ? e.target.closest('[data-jmb-act]') : null;
      if (!b || b.disabled) return;
      var act = b.getAttribute('data-jmb-act');
      if (act === 'done') { leave(); return; }
      if (act === 'move') { openMove(b); return; }
      run(act);
    });

    noteAct.addEventListener('click', function () {
      var job = noteAct.getAttribute('data-role');
      if (job === 'undo') undo();
      else if (job === 'all') selectMatching();
      else if (job === 'clear') clear();
    });
  }

  function paintBar() {
    if (!bar) return;
    var n = SEL.size;
    var ids = viewIds();
    var everyRow = ids.length > 0 && ids.every(function (id) { return SEL.has(id); });

    master.checked = n > 0 && everyRow;
    /* Indeterminate is the honest third state and it is not a class: some of
       what is on screen is selected, and neither a tick nor an empty box says
       that. It has to be set from script because there is no attribute for it. */
    master.indeterminate = n > 0 && !everyRow;

    if (!busy) countEl.textContent = n ? (n + ' selected') : 'Select all';

    var send = allowed('MAIL_SEND');
    var here = folder();
    var archive = folders().filter(function (x) { return x.role === 'archive'; })[0];
    /* Archiving inside the archive is a move to where the message already is:
       a request that costs a round trip to change nothing. mail.js disables its
       own archive control for the same reason and on the same two conditions. */
    var canArchive = !!archive && archive.id !== here.id;
    Array.prototype.slice.call(bar.querySelectorAll('[data-jmb-act]')).forEach(function (b) {
      var act = b.getAttribute('data-jmb-act');
      if (act === 'done') { b.hidden = wide.matches; return; }
      var needsSend = (act === 'archive' || act === 'move' || act === 'delete');
      b.disabled = busy || !n || (needsSend && !send) || (act === 'archive' && !canArchive);
      b.hidden = needsSend && !send;
    });

    paintNote(ids, everyRow, n);
  }

  function paintNote(ids, everyRow, n) {
    if (busy) { noteEl.hidden = true; return; }
    if (undoState) {
      noteEl.hidden = false;
      noteText.textContent = undoState.text;
      noteAct.hidden = !undoState.run;
      noteAct.textContent = 'Undo';
      noteAct.setAttribute('data-role', 'undo');
      return;
    }
    var whole = total();
    if (scope === 'all' && n > 0) {
      noteEl.hidden = false;
      noteText.textContent = 'All ' + n + ' in ' + (folder().name || 'this folder') + ' are selected.';
      noteAct.hidden = false;
      noteAct.textContent = 'Clear selection';
      noteAct.setAttribute('data-role', 'clear');
      return;
    }
    if (n > 0 && everyRow && whole > ids.length) {
      noteEl.hidden = false;
      noteText.textContent = 'All ' + ids.length + ' on this screen are selected.';
      noteAct.hidden = false;
      noteAct.textContent = 'Select all ' + Math.min(whole, CAP)
        + (query() ? ' matching' : ' in ' + (folder().name || 'this folder'));
      noteAct.setAttribute('data-role', 'all');
      return;
    }
    noteEl.hidden = true;
  }

  /* ---------- painting the list ---------- */

  var obs = new MutationObserver(function () { paint(); });

  /* Disconnected for the duration of its own work rather than guarded by a
     flag. A MutationObserver delivers its records as a microtask, so a flag
     cleared at the end of paint is already false by the time the records from
     paint's own inserts arrive, and the observer calls paint again forever.
     Disconnecting drops those records with it. */
  function paint() {
    if (!ready) return;
    obs.disconnect();
    try { decorate(); } finally { obs.observe(list, { childList: true }); }
  }

  function decorate() {
    doc.body.classList.toggle('jmb-col', mode);

    var wantBar = mode || !!undoState;
    if (wantBar) {
      if (list.firstChild !== bar) list.insertBefore(bar, list.firstChild);
    } else if (bar && bar.parentNode) {
      bar.remove();
    }

    rowNodes().forEach(function (row) {
      var id = row.getAttribute('data-id');
      var box = row.previousElementSibling;
      var isCheck = !!(box && box.classList && box.classList.contains('jmb-check'));
      if (!mode) {
        if (isCheck) box.remove();
        row.classList.remove('jmb-on');
        return;
      }
      if (!isCheck) {
        box = checkTpl.content.firstElementChild.cloneNode(true);
        list.insertBefore(box, row);
      }
      var on = SEL.has(id);
      var input = box.querySelector('input');
      box.setAttribute('data-for', id);
      input.checked = on;
      input.setAttribute('aria-label', (on ? 'Unselect ' : 'Select ')
        + (row.querySelector('.subj') ? row.querySelector('.subj').textContent : 'this message'));
      box.classList.toggle('on', on);
      row.classList.toggle('jmb-on', on);
    });

    /* A row can leave without its box: mail.js rebuilds the list from its own
       model and the last render is the only authority on what is still there. */
    Array.prototype.slice.call(list.querySelectorAll('.jmb-check')).forEach(function (box) {
      var next = box.nextElementSibling;
      if (!next || !next.classList.contains('msg')) box.remove();
    });

    paintBar();
  }

  function announce() {
    doc.dispatchEvent(new CustomEvent('mail:selection', {
      detail: { active: mode, count: SEL.size, ids: ordered(), scope: scope, total: total() }
    }));
  }

  /* Selection order is list order for everything on screen and arrival order
     for anything gathered off it, because an action's progress reading "24 of
     200" should count down the screen the way the eye does. */
  function ordered() {
    var seen = {};
    var out = [];
    viewIds().forEach(function (id) { if (SEL.has(id)) { out.push(id); seen[id] = 1; } });
    SEL.forEach(function (id) { if (!seen[id]) out.push(id); });
    return out;
  }

  /* ---------- selection ---------- */

  function enter() {
    if (mode) return;
    mode = true;
    paint();
    announce();
  }

  function leave() {
    if (!mode && !SEL.size) return;
    mode = false;
    SEL.clear();
    scope = 'view';
    anchor = null;
    if (wide.matches) mode = true;      // the column is permanent on a desktop
    paint();
    announce();
  }

  function clear() {
    SEL.clear();
    scope = 'view';
    anchor = null;
    paint();
    announce();
  }

  function toggle(id, force, range) {
    if (!ready || !id) return;
    var want = (force === undefined) ? !SEL.has(id) : !!force;
    var ids = viewIds();
    var from = anchor ? ids.indexOf(anchor) : -1;
    var to = ids.indexOf(id);

    if (range && from >= 0 && to >= 0 && from !== to) {
      var lo = Math.min(from, to), hi = Math.max(from, to);
      for (var i = lo; i <= hi; i++) {
        if (want) SEL.add(ids[i]); else SEL.delete(ids[i]);
      }
    } else if (want) {
      SEL.add(id);
    } else {
      SEL.delete(id);
    }

    anchor = id;
    /* Touching one row means the selection is now a hand-made one, so an
       earlier "all 148 matching" claim is no longer true and must stop being
       displayed as though it were. */
    if (scope === 'all') scope = 'view';
    if (SEL.size && !mode) enter();
    else { paint(); announce(); }
  }

  function selectVisible() {
    viewIds().forEach(function (id) { SEL.add(id); });
    scope = 'view';
    if (!mode) enter(); else { paint(); announce(); }
  }

  /* "Every message that matches", which is a different promise from "every row
      on screen" and needs the ids the client has never been sent. They are
      fetched a page at a time from the same endpoint the list uses. The cap is
      not shyness: without a bulk endpoint each of these ids becomes its own
      request at action time, and a thousand of those is a denial of service
      aimed at our own mail server. */
  function selectMatching() {
    if (!ready || busy) return Promise.resolve();
    var f = folder();
    var q = query();
    var whole = total();
    var want = Math.min(whole, CAP);
    var got = [];

    busy = true;
    countEl.textContent = 'Gathering ' + want;
    paintBar();

    function pageAt(offset) {
      var url = q
        ? '/api/mail/search?q=' + encodeURIComponent(q) + '&offset=' + offset + '&limit=100'
        : '/api/mail/messages?folder=' + encodeURIComponent(f.id)
          + '&role=' + encodeURIComponent(f.role) + '&offset=' + offset + '&limit=100';
      return api(url).then(function (data) {
        (data.messages || []).forEach(function (m) { if (got.length < want) got.push(m.id); });
        if (got.length < want && (data.messages || []).length) return pageAt(offset + 100);
        return null;
      });
    }

    return pageAt(0).then(function () {
      got.forEach(function (id) { SEL.add(id); });
      scope = got.length >= whole ? 'all' : 'view';
      if (whole > CAP) {
        say('That folder holds ' + whole + ' messages. The first ' + CAP + ' are selected.');
      }
    }).catch(fail).then(function () {
      busy = false;
      if (!mode) enter(); else { paint(); announce(); }
    });
  }

  /* ---------- doing the thing ---------- */

  /* One pool, one place. When the server endpoints learn to take a list of ids
     this is the only function that changes, and every action, every undo and
     every progress reading changes with it. */
  function each(ids, fn, onStep) {
    var i = 0, done = 0, failed = 0, first = null;
    var lanes = Math.max(1, Math.min(LANES, ids.length));
    var work = [];
    for (var l = 0; l < lanes; l++) {
      work.push((function lane() {
        if (i >= ids.length) return Promise.resolve();
        var id = ids[i++];
        return Promise.resolve(fn(id)).catch(function (e) {
          failed++;
          if (!first) first = e;
        }).then(function () {
          done++;
          if (onStep) onStep(done, ids.length);
          return lane();
        });
      })());
    }
    return Promise.all(work).then(function () {
      return { done: done, failed: failed, error: first };
    });
  }

  function seenOf(id) {
    var m = model();
    if (m) {
      var row = m.messages.filter(function (x) { return x.id === id; })[0];
      if (row) return !!row.seen;
    }
    var node = rowFor(id);
    return node ? !node.classList.contains('unread') : true;
  }

  function markSeen(id, value) {
    var m = model();
    if (m) {
      var row = m.messages.filter(function (x) { return x.id === id; })[0];
      if (row) row.seen = value;
    }
    var node = rowFor(id);
    if (node) node.classList.toggle('unread', !value);
  }

  function dropRows(ids) {
    var m = model();
    if (m) {
      var gone = {};
      ids.forEach(function (id) { gone[id] = 1; });
      m.messages = m.messages.filter(function (x) { return !gone[x.id]; });
      m.total = Math.max(0, (m.total || 0) - ids.length);
      if (m.selected && gone[m.selected]) m.selected = null;
    }
    ids.forEach(function (id) {
      var node = rowFor(id);
      if (!node) return;
      var box = node.previousElementSibling;
      if (box && box.classList.contains('jmb-check')) box.remove();
      node.remove();
    });
  }

  var VERB = { read: 'read', unread: 'unread', archive: 'archived', move: 'moved', delete: 'deleted' };

  /* only is an explicit id list, which is how a keyboard shortcut aimed at one
     message gets the confirm, the progress and above all the undo that the bar
     gives a selection of two hundred, without the selection itself having to be
     hijacked and put back afterwards. Everything below is identical for one id
     and for two hundred, which is the reason there is no second code path for
     acting on a single message. */
  function run(action, folderId, only) {
    if (!ready || busy) return Promise.resolve();
    var ids = only && only.length ? only.slice() : ordered();
    if (!ids.length) return Promise.resolve();

    var f = folder();
    var searching = !!query();
    var destroying = (action === 'delete' && f.role === 'trash');

    if (action === 'archive' || action === 'move') {
      if (!folderId) {
        var target = folders().filter(function (x) { return x.role === 'archive'; })[0];
        if (action === 'archive' && !target) { say('This mailbox has no archive folder.', true); return Promise.resolve(); }
        if (action === 'archive') folderId = target.id;
      }
      if (!folderId) return Promise.resolve();
    }

    /* Section 8: every destructive action confirms. Archive and mark read are
       not destructive, they are reversible and the bar says so afterwards.
       Delete out of any other folder is a move to Trash and is reversible too,
       so the confirm it gets is the ordinary one; delete out of Trash destroys
       the message on the server and says which of the two it is. */
    if (action === 'delete') {
      var ask = destroying
        ? 'Permanently delete ' + ids.length + ' message' + (ids.length === 1 ? '' : 's')
          + '? This cannot be undone.'
        : 'Delete ' + ids.length + ' message' + (ids.length === 1 ? '' : 's') + '?';
      if (!window.confirm(ask)) return Promise.resolve();
    }

    var before = {};
    if (action === 'read' || action === 'unread') {
      ids.forEach(function (id) { before[id] = seenOf(id); });
    }

    busy = true;
    doc.dispatchEvent(new CustomEvent('mail:bulkstart', { detail: { action: action, count: ids.length } }));
    paintBar();

    var step = function (done, all) {
      countEl.textContent = (action === 'read' ? 'Marking ' : action === 'unread' ? 'Marking ' : 'Working ')
        + done + ' of ' + all;
    };

    var one;
    if (action === 'read') one = function (id) { return post('/api/mail/read', { id: id, value: true }); };
    else if (action === 'unread') one = function (id) { return post('/api/mail/read', { id: id, value: false }); };
    else if (action === 'delete') one = function (id) { return post('/api/mail/delete', { id: id }); };
    else one = function (id) { return post('/api/mail/move', { id: id, folder: folderId }); };

    return each(ids, one, step).then(function (result) {
      var moved = ids.slice(0, ids.length);
      if (result.failed && result.error) fail(result.error);

      if (action === 'read' || action === 'unread') {
        ids.forEach(function (id) { markSeen(id, action === 'read'); });
      } else {
        dropRows(ids);
      }
      recount();

      /* The inverse, and only where the inverse is knowable. A search result
         gathers rows from several folders at once and this screen is never
         told which, so moving them "back" would file them all in whichever
         folder happened to be open. Saying so is better than a button that
         quietly puts the mail somewhere new. */
      var undoRun = null;
      var why = '';
      if (action === 'read' || action === 'unread') {
        undoRun = function () {
          return each(ids, function (id) {
            return post('/api/mail/read', { id: id, value: before[id] });
          }).then(function () {
            ids.forEach(function (id) { markSeen(id, before[id]); });
            recount();
          });
        };
      } else if (destroying) {
        why = ' They cannot be brought back.';
      } else if (searching) {
        why = ' Undo is not offered on a search result.';
      } else if (f.id) {
        undoRun = function () {
          return each(moved, function (id) {
            return post('/api/mail/move', { id: id, folder: f.id });
          }).then(function () { refresh(); recount(); });
        };
      }

      var count = ids.length - result.failed;
      offerUndo(count + ' ' + VERB[action] + '.' + why, undoRun, action, ids);

      if (!only) {
        SEL.clear();
        scope = 'view';
        anchor = null;
      } else {
        ids.forEach(function (id) { SEL.delete(id); });
      }
      busy = false;
      paint();
      announce();
      doc.dispatchEvent(new CustomEvent('mail:bulkdone', {
        detail: { action: action, ids: ids, failed: result.failed, undoable: !!undoRun }
      }));
    }).catch(function (e) {
      busy = false;
      paint();
      fail(e);
    });
  }

  function offerUndo(text, runFn, action, ids) {
    clearTimeout(undoTimer);
    undoState = { text: text, run: runFn, action: action, ids: ids, at: Date.now() };
    undoTimer = setTimeout(function () {
      undoState = null;
      if (!mode && !wide.matches) { paint(); return; }
      paint();
    }, UNDO_MS);
  }

  function undo() {
    if (!undoState || !undoState.run || busy) return Promise.resolve();
    var held = undoState;
    undoState = null;
    clearTimeout(undoTimer);
    busy = true;
    countEl.textContent = 'Putting it back';
    paintBar();
    return Promise.resolve(held.run()).then(function () {
      say('Put back.');
      doc.dispatchEvent(new CustomEvent('mail:bulkundone', {
        detail: { action: held.action, ids: held.ids }
      }));
    }).catch(fail).then(function () {
      busy = false;
      paint();
      announce();
    });
  }

  /* ---------- the move menu ---------- */

  var menu = null;

  function closeMove() {
    if (!menu) return;
    var opener = menu.opener;
    menu.remove();
    menu = null;
    if (opener) opener.setAttribute('aria-expanded', 'false');
    doc.removeEventListener('click', outsideMove, true);
    window.removeEventListener('resize', closeMove);
  }

  function outsideMove(e) {
    if (menu && !menu.contains(e.target)) closeMove();
  }

  function openMove(opener) {
    if (menu) { closeMove(); return; }
    var here = folder();
    menu = menuTpl.content.firstElementChild.cloneNode(true);
    menu.opener = opener;
    folders().forEach(function (f) {
      var b = doc.createElement('button');
      b.type = 'button';
      b.className = 'jmb-mi';
      b.setAttribute('role', 'menuitem');
      b.disabled = f.id === here.id;
      b.innerHTML = '<svg class="ic" aria-hidden="true"><use href="#i-folder"/></svg>';
      var span = doc.createElement('span');
      span.textContent = f.name + (f.id === here.id ? ' (here now)' : '');
      b.appendChild(span);
      b.addEventListener('click', function () {
        closeMove();
        run('move', f.id);
      });
      menu.appendChild(b);
    });
    doc.body.appendChild(menu);

    var r = opener.getBoundingClientRect();
    var w = menu.offsetWidth, h = menu.offsetHeight;
    var left = Math.min(Math.max(8, r.right - w), window.innerWidth - w - 8);
    var top = r.bottom + 6;
    if (top + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 6);
    menu.style.left = left + 'px';
    menu.style.top = top + 'px';

    opener.setAttribute('aria-expanded', 'true');
    var first = menu.querySelector('.jmb-mi:not(:disabled)');
    if (first) first.focus();
    doc.addEventListener('click', outsideMove, true);
    window.addEventListener('resize', closeMove);
  }

  /* ---------- input ---------- */

  if (ready) {
    buildBar();

    /* Capture, and on the list rather than on the row. mail.js binds its own
       open-the-message handler to this same node in the bubble phase, so a
       capture listener here sees the click first and stopPropagation keeps the
       bubble one from ever running. That ordering is the whole reason a tap
       during selection selects instead of opening, and it is why this file
       needs no cooperation from mail.js at all. */
    list.addEventListener('click', function (e) {
      if (swallowClick) {
        swallowClick = false;
        e.stopPropagation();
        e.preventDefault();
        return;
      }
      var box = e.target.closest ? e.target.closest('.jmb-check') : null;
      if (box) {
        e.stopPropagation();
        /* The label would toggle its own input and then this handler would
           toggle the model to match, which is two sources for one boolean. The
           default is cancelled and the model is the only one that decides. */
        e.preventDefault();
        toggle(box.getAttribute('data-for'), undefined, e.shiftKey);
        return;
      }
      if (!mode || wide.matches) return;
      var row = e.target.closest ? e.target.closest('.msg[data-id]') : null;
      if (!row) return;
      /* Below 900px, once selection mode is open the row is a checkbox. That is
         the platform behaviour on both phone systems and the reason the 40px
         column is not asked to be a 44px tap target. */
      e.stopPropagation();
      e.preventDefault();
      toggle(row.getAttribute('data-id'), undefined, e.shiftKey);
    }, true);

    list.addEventListener('pointerdown', function (e) {
      if (mode || wide.matches) return;
      var row = e.target.closest ? e.target.closest('.msg[data-id]') : null;
      if (!row) return;
      var id = row.getAttribute('data-id');
      press = {
        id: id, x: e.clientX, y: e.clientY,
        timer: setTimeout(function () {
          press = null;
          swallowClick = true;
          /* The click this press is about to produce belongs to the press and
             not to the row, so it is refused once. It is also refused on a
             timer, because a press that ends as a drag off the row produces no
             click at all and would otherwise leave the next real tap armed to
             be eaten by a gesture the person made a minute ago. */
          setTimeout(function () { swallowClick = false; }, 800);
          if (navigator.vibrate) navigator.vibrate(15);
          SEL.add(id);
          anchor = id;
          enter();
        }, PRESS_MS)
      };
    }, true);

    var cancelPress = function () {
      if (!press) return;
      clearTimeout(press.timer);
      press = null;
    };
    list.addEventListener('pointermove', function (e) {
      if (!press) return;
      if (Math.abs(e.clientX - press.x) > PRESS_SLOP || Math.abs(e.clientY - press.y) > PRESS_SLOP) cancelPress();
    }, true);
    list.addEventListener('pointerup', cancelPress, true);
    list.addEventListener('pointercancel', cancelPress, true);
    list.addEventListener('scroll', cancelPress, true);

    /* A long press on a touch screen raises the context menu on some engines,
       which would land on top of the selection it just started. It is refused
       only for the press this file consumed and never for an ordinary right
       click, which still belongs to the browser. */
    list.addEventListener('contextmenu', function (e) {
      if (swallowClick) e.preventDefault();
    });

    wide.addEventListener('change', function () {
      closeMove();
      if (wide.matches) enter();
      else if (!SEL.size && !undoState) { mode = false; paint(); announce(); }
      else paint();
    });

    /* The desktop keeps its checkbox column open the way every desktop client
       does, because the column is how a selection is started there and a
       column that appears only once something is selected cannot be. */
    if (wide.matches) mode = true;
    paint();
    obs.observe(list, { childList: true });
  }

  /* ---------- the documented surface ---------- */

  window.MailBulk = {
    /** False on any page without a message list or without the fragment. */
    enabled: function () { return ready; },
    /** True while the checkbox column is on screen. */
    active: function () { return ready && mode; },
    /** Selected ids, list order first, then anything gathered off screen. */
    ids: function () { return ready ? ordered() : []; },
    count: function () { return ready ? SEL.size : 0; },
    has: function (id) { return ready && SEL.has(id); },
    /** 'view' for a hand-made selection, 'all' once every match was gathered. */
    scope: function () { return scope; },
    /** True while an action or an undo is in flight; callers should not queue. */
    busy: function () { return busy; },
    enter: function () { if (ready) enter(); },
    leave: function () { if (ready) leave(); },
    clear: function () { if (ready) clear(); },
    /** force omitted flips it; range true extends from the last row touched. */
    toggle: function (id, force, range) { toggle(id, force, range); },
    selectVisible: function () { if (ready) selectVisible(); },
    selectMatching: function () { return ready ? selectMatching() : Promise.resolve(); },
    /** 'read' | 'unread' | 'archive' | 'move' | 'delete'; move needs a folder id. */
    act: function (action, folderId) { return ready ? run(action, folderId) : Promise.resolve(); },
    /** The same action against an explicit id list, leaving the selection alone. */
    actOn: function (ids, action, folderId) {
      return ready ? run(action, folderId, ids || []) : Promise.resolve();
    },
    /** The folders the rail knows about: {id, name, role}. Used to build a move menu. */
    folders: function () { return ready ? folders() : []; },
    /** Runs the inverse of the last action, if one was offered. */
    undo: function () { return ready ? undo() : Promise.resolve(); },
    /** True while an undo is still on offer, which is what makes z worth binding. */
    undoable: function () { return !!(undoState && undoState.run); },
    /** Re-reads the list and repaints. Only needed by a caller that replaced it. */
    repaint: function () { paint(); }
  };
})();
