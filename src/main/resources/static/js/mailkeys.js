/* Jarurat Mail - keyboard shortcuts.
 *
 * The letters are the ones every mail client converged on twenty years ago and
 * that Gmail, Fastmail, Outlook and Proton still agree about: j and k to walk
 * the list, c to write, r to reply, e to archive, hash to delete, slash to
 * search, x to select, g then a letter to change folder, and a question mark
 * for the list of all of it. Inventing better letters would be inventing a
 * dialect nobody speaks.
 *
 * One thing in this file matters more than every binding in it, and it is the
 * scope. A shortcut that fires while somebody is writing an email archives the
 * thread they were quoting, deletes the message they were answering, or drops
 * the letter into a folder and closes the composer, and the person has no idea
 * which of their own keystrokes did it. So the guard comes first and it is
 * deliberately wider than it needs to be: nothing fires while the event target
 * or the focused element is an input, a textarea, a select or anything inside a
 * contenteditable, nothing fires while an IME is mid composition, nothing fires
 * while any element with aria-modal="true" is visible, and nothing at all fires
 * with Ctrl, Meta or Alt held, because those belong to the browser and to the
 * operating system. The one binding allowed through a dialog is the question
 * mark closing this file's own dialog.
 *
 * The listener is registered in the capture phase, which is what lets Escape
 * close the shortcut sheet before mail.js's own Escape handler pops the history
 * entry behind it. It is also why the typing guard has to be first: in capture,
 * this handler sees the key before the input the person is typing into does,
 * and a preventDefault here would eat the character.
 *
 * Nothing here reimplements an action. Every binding either drives a control
 * mail.js already owns, or calls the published MailBulk surface, which is what
 * gives a keyboard archive the same undo a bar archive gets.
 */
(function () {
  'use strict';

  var doc = document;
  var list = doc.getElementById('list');
  var sheet = doc.getElementById('jmKeysSheet');
  var CHORD_MS = 1500;      // how long g and star wait for their second key

  var chord = null;
  var chordTimer = null;
  var restoreFocus = null;

  function model() {
    try {
      /* jshint -W117 */
      return (typeof S !== 'undefined' && S) ? S : null;
    } catch (e) { return null; }
  }

  /* ---------- the guards ---------- */

  function isField(el) {
    if (!el || el === doc || el === doc.body) return false;
    if (el.isContentEditable) return true;
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'OPTION') return true;
    /* A custom control can be a text box without being one of those tags, and
       the accessibility tree is where it says so. */
    if (el.getAttribute && el.getAttribute('role') === 'textbox') return true;
    return false;
  }

  function typing(e) {
    return isField(e.target) || isField(doc.activeElement);
  }

  function shown(el) {
    if (!el) return false;
    if (typeof el.checkVisibility === 'function') {
      return el.checkVisibility({ checkVisibilityCSS: true, opacityProperty: true, visibilityProperty: true });
    }
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) return false;
    var cs = window.getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && Number(cs.opacity) > 0;
  }

  /* Read off the attribute and never off a class name, because this file is
     shared by pages whose sheets are called different things, and because
     section 15 already requires every dialog on every one of them to carry it. */
  function modalOpen() {
    var nodes = doc.querySelectorAll('[aria-modal="true"]');
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i] !== sheetPanel && shown(nodes[i])) return true;
    }
    return false;
  }

  /* ---------- the sheet ---------- */

  var sheetPanel = sheet ? sheet.querySelector('[role="dialog"]') : null;

  function sheetOpen() { return !!(sheet && !sheet.hidden); }

  function openSheet() {
    if (!sheet || sheetOpen()) return;
    restoreFocus = doc.activeElement;
    sheet.hidden = false;
    /* One frame between the element existing and the class that fades it in,
       because a transition from a display:none start state does not run. */
    requestAnimationFrame(function () { sheet.classList.add('open'); });
    var close = sheet.querySelector('[data-jmk-close]');
    if (close) close.focus();
  }

  function closeSheet() {
    if (!sheet || !sheetOpen()) return;
    sheet.classList.remove('open');
    var done = function () { sheet.hidden = true; };
    /* Matches --t-base. A transitionend listener would be exact and would also
       never fire under prefers-reduced-motion, where the transition is none. */
    setTimeout(done, 200);
    if (restoreFocus && doc.contains(restoreFocus)) restoreFocus.focus();
    restoreFocus = null;
  }

  if (sheet) {
    sheet.addEventListener('click', function (e) {
      if (e.target === sheet || (e.target.closest && e.target.closest('[data-jmk-close]'))) closeSheet();
    });
    /* A dialog keeps the tab ring inside itself. Without this, tabbing out of
       the sheet lands on the message list underneath it, which is still there
       and still looks operable. */
    sheet.addEventListener('keydown', function (e) {
      if (e.key !== 'Tab' || !sheetOpen()) return;
      var able = sheet.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
      var open = [];
      Array.prototype.forEach.call(able, function (el) { if (shown(el) && !el.disabled) open.push(el); });
      if (!open.length) return;
      var first = open[0], last = open[open.length - 1];
      if (e.shiftKey && doc.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && doc.activeElement === last) { e.preventDefault(); first.focus(); }
    });
  }

  /* ---------- what the keys drive ---------- */

  function rows() {
    return list ? Array.prototype.slice.call(list.querySelectorAll('.msg[data-id]')) : [];
  }

  /* The cursor is DOM focus and not a class of this file's own. The row is
     already a button, so the browser draws the ring, a screen reader reads the
     row out on arrival, and Enter activates it with no binding at all. A
     bespoke cursor would have had to reproduce all three and would still have
     been invisible to assistive technology. */
  function focusedRow() {
    var el = doc.activeElement;
    return (el && el.closest) ? el.closest('.msg[data-id]') : null;
  }

  function step(delta) {
    var all = rows();
    if (!all.length) return;
    var at = all.indexOf(focusedRow());
    var next = at < 0 ? (delta > 0 ? 0 : all.length - 1)
                      : Math.min(all.length - 1, Math.max(0, at + delta));
    all[next].focus({ preventScroll: true });
    all[next].scrollIntoView({ block: 'nearest' });
  }

  function readerOpen() {
    var m = model();
    if (m && m.reader) return true;
    return doc.body.getAttribute('data-pane') === 'reader';
  }

  /* The message a key means. The open one wins, then the one under the cursor,
     then the one the list is highlighting, which is the order the eye reads. */
  function target() {
    var m = model();
    if (readerOpen() && m && m.reader) return m.reader.id;
    var f = focusedRow();
    if (f) return f.getAttribute('data-id');
    var on = list ? list.querySelector('.msg.on') : null;
    return on ? on.getAttribute('data-id') : null;
  }

  /* r, a, f and s need the message body loaded, because reply-all gathers the
     recipients from it and forward quotes it. Rather than half open it, this
     opens it properly and then presses the button mail.js already owns, so
     there is exactly one implementation of reply on this page and it is not
     this one. */
  function withMessage(id) {
    if (!id) return Promise.reject();
    var m = model();
    if (m && m.reader && m.reader.id === id) return Promise.resolve();
    if (typeof window.openMessage === 'function') {
      return Promise.resolve(window.openMessage(id, {}));
    }
    var row = list ? list.querySelector('.msg[data-id="' + cssId(id) + '"]') : null;
    if (row) row.click();
    return Promise.resolve();
  }

  function cssId(id) {
    return (window.CSS && CSS.escape) ? CSS.escape(id) : String(id).replace(/"/g, '\\"');
  }

  function press(id) {
    var el = doc.getElementById(id);
    if (el && !el.disabled) { el.click(); return true; }
    return false;
  }

  function readerButton(id, messageId) {
    withMessage(messageId).then(function () { press(id); }).catch(function () {});
  }

  function bulk(action, id) {
    if (!id || !window.MailBulk || !window.MailBulk.enabled()) return;
    var wasOpen = readerOpen();
    window.MailBulk.actOn([id], action).then(function () {
      /* The reader is showing a message that is no longer in this folder. Its
         own Back button is the only thing that knows whether leaving means a
         history pop or a state replace, so it is pressed rather than guessed. */
      if (wasOpen && (action === 'archive' || action === 'delete')) {
        var back = doc.querySelector('#reader [data-act="back"]');
        if (back) back.click();
      }
    });
  }

  function goFolder(key) {
    var role = { i: 'inbox', s: 'sent', d: 'drafts', a: 'archive', t: 'trash', j: 'junk' }[key];
    if (!role) return;
    var b = doc.querySelector('#folders .fold[data-role="' + role + '"]');
    /* The rail is display:none below 900px but it is in the document at every
       width, and a click on a hidden element still reaches the delegated
       handler that owns it. That is why there is no phone branch here. */
    if (b) b.click();
  }

  function search() {
    var q = doc.getElementById('q');
    if (!q) return;
    if (window.matchMedia('(max-width:899.98px)').matches && typeof window.goOverlay === 'function') {
      window.goOverlay('search');
      setTimeout(function () { q.focus(); q.select(); }, 60);
      return;
    }
    q.focus();
    q.select();
  }

  function compose() {
    if (typeof window.openCompose === 'function') { window.openCompose(); return; }
    press('btnCompose') || press('railCompose');
  }

  function back() {
    var b = doc.querySelector('#reader [data-act="back"]');
    if (b && readerOpen()) b.click();
  }

  /* ---------- chords ---------- */

  function arm(which) {
    chord = which;
    clearTimeout(chordTimer);
    /* It expires. A g left armed forever turns the next i somebody types into
       a folder change minutes later, with nothing on screen to explain it. */
    chordTimer = setTimeout(function () { chord = null; }, CHORD_MS);
  }

  function disarm() {
    chord = null;
    clearTimeout(chordTimer);
  }

  /* ---------- the one listener ---------- */

  function onKey(e) {
    if (e.defaultPrevented) return;
    /* keyCode 229 is what every engine reports while an input method editor is
       composing, and e.isComposing is the modern spelling of the same thing.
       Without both, typing Hindi or Japanese into the search box fires letters. */
    if (e.isComposing || e.keyCode === 229) return;

    var k = e.key;

    if (k === 'Escape' && sheetOpen()) {
      e.preventDefault();
      e.stopPropagation();
      closeSheet();
      return;
    }

    if (typing(e)) return;
    if (e.ctrlKey || e.metaKey || e.altKey) return;

    /* Shift and slash, not only the character. Most engines report "?" for that
       combination on a US layout, but the character a layout puts on that key
       is not universal and at least one automation driver reports the unshifted
       key with the modifier flag set instead. Reading both spellings costs one
       clause and is the difference between the help being reachable and not. */
    if (k === '?' || (k === '/' && e.shiftKey)) {
      e.preventDefault();
      if (sheetOpen()) closeSheet();
      else if (!modalOpen()) openSheet();
      return;
    }
    if (sheetOpen() || modalOpen()) return;
    if (!list) return;

    if (chord === 'g') { disarm(); e.preventDefault(); goFolder(k); return; }
    if (chord === '*') {
      disarm();
      e.preventDefault();
      if (!window.MailBulk || !window.MailBulk.enabled()) return;
      if (k === 'a') window.MailBulk.selectVisible();
      else if (k === 'n') window.MailBulk.clear();
      return;
    }

    var id;

    switch (k) {
      case 'j': e.preventDefault(); step(1); return;
      case 'k': e.preventDefault(); step(-1); return;
      case 'o': {
        e.preventDefault();
        var row = focusedRow();
        if (row) row.click();
        return;
      }
      case 'u': e.preventDefault(); back(); return;
      case '/': e.preventDefault(); search(); return;
      case 'c': e.preventDefault(); compose(); return;
      case 'g': e.preventDefault(); arm('g'); return;
      case '*': e.preventDefault(); arm('*'); return;

      case 'r': e.preventDefault(); readerButton('rReply', target()); return;
      case 'a': e.preventDefault(); readerButton('rReplyAll', target()); return;
      case 'f': e.preventDefault(); readerButton('rForward', target()); return;
      case 's': e.preventDefault(); readerButton('rStar', target()); return;

      case 'e': e.preventDefault(); bulk('archive', target()); return;
      case '#': e.preventDefault(); bulk('delete', target()); return;
      case 'I': e.preventDefault(); bulk('read', target()); return;
      case 'U': e.preventDefault(); bulk('unread', target()); return;

      case 'x': {
        e.preventDefault();
        id = target();
        if (id && window.MailBulk && window.MailBulk.enabled()) window.MailBulk.toggle(id);
        return;
      }
      case 'z': {
        e.preventDefault();
        if (window.MailBulk && window.MailBulk.enabled()) window.MailBulk.undo();
        return;
      }
      default: return;
    }
  }

  doc.addEventListener('keydown', onKey, true);

  /* ---------- the documented surface ---------- */

  window.MailKeys = {
    /** False where the sheet fragment was left out; the bindings still work. */
    enabled: function () { return !!sheet; },
    open: openSheet,
    close: closeSheet,
    isOpen: sheetOpen,
    /** True when a key pressed right now would be swallowed by a field or a dialog. */
    blocked: function () {
      return isField(doc.activeElement) || modalOpen() || sheetOpen();
    }
  };
})();
