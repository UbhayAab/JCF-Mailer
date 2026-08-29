/* =========================================================================
   Jarurat Mail - per-mailbox settings sheet
   Talks only to /api/mail/settings. Self contained on purpose: mail.html and
   mail.js belong to another agent this phase, so this file adds its own row to
   the account sheet, builds its own sheet, and carries its own styles. The only
   edit anybody else has to make is the one script tag that loads it.

   It leans on four things mail.js publishes as globals and degrades without
   any of them, because "the settings screen went blank because a helper was
   renamed" is a worse failure than a slightly plainer settings screen:
     toast()            - a message on screen        (falls back to alert)
     csrfToken()        - the XSRF cookie            (falls back to reading it here)
     popThen(fn)        - close a sheet, then act    (falls back to a class toggle)
     cleanForeignHtml() - the composer paste cleaner (falls back to innerHTML,
                          which is safe because the server rebuilds the markup
                          through the outbound allowlist regardless)
   ========================================================================= */

(function () {
  'use strict';

  var URL_SETTINGS = '/api/mail/settings';

  /* The one place the shape of a settings object is written down on this side.
     Used before the first fetch answers and whenever the mailbox is locked, so
     the sheet always has something coherent to draw. */
  var DEFAULTS = {
    mailbox: '',
    signatureHtml: '', signatureOnNew: true, signatureOnReply: false,
    signatureForNew: '', signatureForReply: '',
    vacationEnabled: false, vacationSubject: '', vacationHtml: '',
    vacationFrom: '', vacationTo: '', vacationPeriodDays: 7,
    vacationActive: false, vacationServerSide: false, vacationServerNote: '',
    vacationSyncedAt: '', vacationRepliedSenders: 0,
    preferHtml: true, loadRemoteImages: false, messagesPerPage: 50, readingPane: 'side',
    undoSendSeconds: 10, defaultReply: 'reply', requestReadReceipt: false,
    undoSendHonoured: false, readReceiptHonoured: false,
    updatedAt: ''
  };

  var current = copy(DEFAULTS);
  var loaded = false;
  var loading = null;
  var sheet = null;
  var open = false;

  /* ------------------------------------------------------------------ tiny helpers */

  function copy(o) { var out = {}; for (var k in o) if (o.hasOwnProperty.call(o, k)) out[k] = o[k]; return out; }

  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* Sprite only. There is no emoji anywhere in this file and there is not going
     to be: an emoji is a font the device may not have and a picture we cannot
     restyle, and half of them render as a grey box on the Android WebView the
     installed app runs in. */
  function icon(id, cls) {
    return '<svg class="ic' + (cls ? ' ' + cls : '') + '" aria-hidden="true"><use href="#' + id + '"/></svg>';
  }

  function say(message, bad) {
    if (typeof toast === 'function') toast(message, bad);
    else if (bad) window.alert(message);
  }

  function token() {
    if (typeof csrfToken === 'function') return csrfToken();
    var match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
  }

  function $s(sel) { return sheet ? sheet.querySelector(sel) : null; }

  /* ------------------------------------------------------------------ transport */

  /**
   * Reads the settings, and treats a locked mailbox as an ordinary answer.
   *
   * A 409 here means nobody has opened a mailbox yet, which on this screen is the
   * normal state on first load rather than a fault. Raising the unlock sheet from
   * a background prefetch would put a password prompt in front of somebody who has
   * not asked for anything, so the defaults are used and the next open tries again.
   */
  function load(force) {
    if (loaded && !force) return Promise.resolve(current);
    if (loading) return loading;
    loading = fetch(URL_SETTINGS, { headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (res.status === 409 || res.status === 401 || res.status === 403) return null;
        return res.json().catch(function () { return null; });
      })
      .then(function (data) {
        if (data && !data.error) {
          current = data;
          loaded = true;
          announce();
        }
        loading = null;
        return current;
      })
      .catch(function () { loading = null; return current; });
    return loading;
  }

  function save(form) {
    var body = new URLSearchParams();
    for (var key in form) if (Object.prototype.hasOwnProperty.call(form, key)) body.append(key, form[key]);
    return fetch(URL_SETTINGS, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': token() },
      body: body.toString()
    }).then(function (res) {
      return res.json().catch(function () { return null; }).then(function (data) {
        if (!res.ok || (data && data.error)) {
          throw new Error((data && data.error) || 'The settings could not be saved.');
        }
        current = data;
        loaded = true;
        announce();
        return data;
      });
    });
  }

  /**
   * Tells the rest of the screen the settings moved.
   *
   * A custom event rather than a callback register, because the composer, the
   * reader and the list are three different agents' files and none of them should
   * have to be wired into this one to hear about a signature change. Anybody who
   * cares listens for mailsettings:change and reads event.detail.
   */
  function announce() {
    try {
      window.dispatchEvent(new CustomEvent('mailsettings:change', { detail: current }));
    } catch (e) { /* CustomEvent is old enough that this cannot fail, but a settings
                     screen must never be the thing that throws during startup. */ }
  }

  /* ------------------------------------------------------------------ styles */

  /* Written here rather than in mail.html because that file is owned by somebody
     else this phase. Every selector is prefixed ms- so nothing here can reach a
     component the mailbox already ships, and everything that already exists on
     that page (.sheet, .field, .btn, .hint, .mrow) is reused rather than redrawn. */
  function styles() {
    if (document.getElementById('msStyle')) return;
    var css = ''
      + '#msSheet .sheet{width:min(640px,100%)}'
      + '.ms-sec{border-top:1px solid var(--border);padding:16px 0 4px;margin-top:4px}'
      + '.ms-sec:first-child{border-top:0;padding-top:0;margin-top:0}'
      + '.ms-sec > h4{margin:0 0 4px;font-size:13px;font-weight:600;display:flex;align-items:center;gap:8px}'
      + '.ms-sec > .hint{margin:0 0 12px}'
      + '.ms-row{display:flex;align-items:flex-start;gap:12px;min-height:44px;padding:4px 0}'
      + '.ms-row input[type=checkbox]{margin-top:13px;flex:none}'
      /* 13 and not 12: the label is the tap target for the checkbox beside it,
         and 18px of line with 12 either side measures 42, two pixels under the
         floor section 14 sets. Measured in the harness rather than reasoned about. */
      + '.ms-row label{flex:1;min-width:0;font-size:13px;line-height:18px;padding:13px 0;cursor:pointer}'
      + '.ms-row label .hint{display:block;margin-top:2px}'
      + '.ms-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:0 14px}'
      + '.field select{width:100%;padding:6px 10px;min-height:44px;background:var(--field-bg);'
      + 'border:1px solid var(--border-strong);border-radius:var(--radius-xs);color:var(--text);'
      + 'font-size:16px;line-height:18px;font-family:var(--sans)}'
      + '.field select:focus{border-color:var(--primary);box-shadow:0 0 0 2px rgba(47,111,237,.25);outline:none}'
      /* 16px and not 13px on every control that takes a caret or a menu, because
         iOS Safari zooms the whole page in on focus below 16 and never zooms back
         out, which leaves the sheet wider than the screen for the rest of the visit. */
      + '.field input{font-size:16px;min-height:44px}'
      + '.ms-edit{min-height:96px;max-height:220px;overflow:auto;padding:10px;font-size:16px;'
      + 'line-height:1.5;background:var(--field-bg);border:1px solid var(--border-strong);'
      + 'border-radius:var(--radius-xs);color:var(--text);outline:none}'
      + '.ms-edit:focus{border-color:var(--primary);box-shadow:0 0 0 2px rgba(47,111,237,.25)}'
      + '.ms-edit[data-empty="true"]::before{content:attr(data-placeholder);color:var(--text-mute);pointer-events:none}'
      + '.ms-edit p{margin:0 0 8px}.ms-edit p:last-child{margin-bottom:0}'
      + '.ms-bar{display:flex;flex-wrap:wrap;gap:2px;margin-bottom:6px}'
      + '.ms-fx{width:44px;height:44px;display:inline-grid;place-items:center;background:none;'
      + 'border:1px solid transparent;border-radius:var(--radius-xs);color:var(--text-dim);cursor:pointer}'
      + '.ms-fx:hover{background:var(--panel-3);color:var(--text)}'
      + '.ms-fx[aria-pressed="true"]{background:var(--panel-4);color:var(--text);border-color:var(--border-strong)}'
      + '.ms-fx:focus-visible{outline:2px solid var(--primary);outline-offset:1px}'
      + '.ms-state{display:flex;gap:8px;align-items:flex-start;font-size:12px;line-height:17px;'
      + 'padding:10px 12px;border-radius:var(--radius-xs);background:var(--panel-3);'
      + 'border:1px solid var(--border);color:var(--text-dim);margin:8px 0 4px}'
      + '.ms-state .ic{flex:none;margin-top:1px}'
      + '.ms-state.warn{border-color:var(--danger);color:var(--danger-fg);background:rgba(224,72,60,.09)}'
      + '.ms-state.on{border-color:var(--primary);color:var(--text)}'
      + '#msSheet .sheet-f{gap:8px}';
    var tag = document.createElement('style');
    tag.id = 'msStyle';
    tag.textContent = css;
    document.head.appendChild(tag);
  }

  /* ------------------------------------------------------------------ markup */

  function optionList(options, chosen) {
    var html = '';
    for (var i = 0; i < options.length; i++) {
      html += '<option value="' + esc(options[i][0]) + '"'
        + (String(options[i][0]) === String(chosen) ? ' selected' : '') + '>'
        + esc(options[i][1]) + '</option>';
    }
    return html;
  }

  function check(id, label, on, hint) {
    return '<div class="ms-row"><input type="checkbox" id="' + id + '"' + (on ? ' checked' : '') + '>'
      + '<label for="' + id + '">' + esc(label)
      + (hint ? '<span class="hint">' + esc(hint) + '</span>' : '') + '</label></div>';
  }

  function editor(id, placeholder, html) {
    return '<div class="ms-bar" role="toolbar" aria-label="Formatting">'
      + fx(id, 'bold', 'i-bold', 'Bold')
      + fx(id, 'italic', 'i-italic', 'Italic')
      + fx(id, 'underline', 'i-underline', 'Underline')
      + fx(id, 'insertUnorderedList', 'i-list-ul', 'Bulleted list')
      + fx(id, 'createLink', 'i-link', 'Link')
      + fx(id, 'removeFormat', 'i-clear-format', 'Clear formatting')
      + '</div>'
      + '<div class="ms-edit" id="' + id + '" contenteditable="true" role="textbox" aria-multiline="true"'
      + ' data-placeholder="' + esc(placeholder) + '">' + html + '</div>';
  }

  function fx(target, command, sprite, label) {
    return '<button type="button" class="ms-fx" data-edit="' + target + '" data-cmd="' + command
      + '" title="' + esc(label) + '" aria-label="' + esc(label) + '" aria-pressed="false">'
      + icon(sprite, 'ic-sm') + '</button>';
  }

  function body(s) {
    return ''
      // ---- signature
      + '<section class="ms-sec">'
      + '<h4>' + icon('i-signature') + 'Signature</h4>'
      + '<p class="hint">Added below what you write, after a line of two hyphens so other '
      + 'mail programs know where it starts and can fold it away in a long reply.</p>'
      + editor('msSig', 'Priya Sharma, Programmes, Jarurat Care', s.signatureHtml || '')
      + check('msSigNew', 'Add it to new messages', s.signatureOnNew)
      + check('msSigReply', 'Add it to replies and forwards', s.signatureOnReply,
              'Off by default. On a long thread every reply adds another copy.')
      + '</section>'

      // ---- out of office
      + '<section class="ms-sec">'
      + '<h4>' + icon('i-snooze') + 'Out of office</h4>'
      + '<p class="hint">One automatic reply per person per period, never to a mailing list '
      + 'and never to an address that cannot read it.</p>'
      + '<div id="msVacState"></div>'
      + check('msVacOn', 'Reply automatically while I am away', s.vacationEnabled)
      + '<div class="field"><label for="msVacSubject">Subject</label>'
      + '<input id="msVacSubject" maxlength="200" placeholder="Out of office until 20 September" '
      + 'value="' + esc(s.vacationSubject) + '"></div>'
      + '<div class="field"><label for="msVacMsg">Message</label>'
      + editor('msVacMsg', 'I am at a camp until the 20th and will reply when I am back.',
               s.vacationHtml || '')
      + '</div>'
      + '<div class="ms-grid">'
      + '<div class="field"><label for="msVacFrom">Starts</label>'
      + '<input id="msVacFrom" type="datetime-local" value="' + esc(toLocalInput(s.vacationFrom)) + '"></div>'
      + '<div class="field"><label for="msVacTo">Ends</label>'
      + '<input id="msVacTo" type="datetime-local" value="' + esc(toLocalInput(s.vacationTo)) + '"></div>'
      + '</div>'
      + '<div class="field"><label for="msVacDays">Reply to the same person at most once every</label>'
      + '<select id="msVacDays">' + optionList([
          [1, 'Day'], [3, '3 days'], [7, 'Week'], [14, 'Fortnight'], [30, 'Month']
        ], s.vacationPeriodDays) + '</select></div>'
      + '</section>'

      // ---- reading
      + '<section class="ms-sec">'
      + '<h4>' + icon('i-mail') + 'Reading</h4>'
      + '<div class="ms-grid">'
      + '<div class="field"><label for="msPrefer">Show messages as</label>'
      + '<select id="msPrefer">' + optionList([
          ['true', 'Formatted, when the sender sent it'], ['false', 'Plain text']
        ], String(s.preferHtml)) + '</select></div>'
      + '<div class="field"><label for="msPerPage">Messages per page</label>'
      + '<select id="msPerPage">' + optionList([
          [25, '25'], [50, '50'], [100, '100']
        ], s.messagesPerPage) + '</select></div>'
      + '<div class="field"><label for="msPane">Reading pane on a laptop</label>'
      + '<select id="msPane">' + optionList([
          ['side', 'Beside the list'], ['below', 'Below the list']
        ], s.readingPane) + '</select></div>'
      + '</div>'
      + check('msImages', 'Load pictures from the internet automatically', s.loadRemoteImages,
              'Off. A picture in a message is a read receipt: fetching it tells the sender '
              + 'you opened it, when, and roughly where you are. Every message still has a '
              + 'Show pictures button.')
      + '</section>'

      // ---- sending
      + '<section class="ms-sec">'
      + '<h4>' + icon('i-schedule') + 'Sending</h4>'
      + '<div class="ms-grid">'
      + '<div class="field"><label for="msUndo">Hold a message before it goes</label>'
      + '<select id="msUndo">' + optionList([
          [0, 'Send straight away'], [5, '5 seconds'], [10, '10 seconds'],
          [20, '20 seconds'], [30, '30 seconds']
        ], s.undoSendSeconds) + '</select></div>'
      + '<div class="field"><label for="msReply">Reply button sends to</label>'
      + '<select id="msReply">' + optionList([
          ['reply', 'The sender only'], ['reply-all', 'Everyone on the message']
        ], s.defaultReply) + '</select></div>'
      + '</div>'
      + (s.undoSendHonoured ? '' :
          '<div class="ms-state">' + icon('i-info', 'ic-sm')
          + '<span>The hold is stored but not yet applied. Sending still goes out at once '
          + 'until the send path can hand a message to the mail server with a release time.</span></div>')
      + check('msReceipt', 'Ask for a read receipt', s.requestReadReceipt,
              'Off. It asks the other person mail program to report back on them, which is a '
              + 'lot to ask of a donor. Stored but not yet applied.')
      + '</section>';
  }

  /* ------------------------------------------------------------------ dates */

  /** An ISO instant as the local wall clock string a datetime-local input wants. */
  function toLocalInput(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
      + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  /**
   * The other direction, and it deliberately goes through the browser own clock.
   *
   * A datetime-local value carries no zone, so "9 September, 09:00" means nine in
   * the morning where the person typing it is standing. Reading it as UTC would put
   * an out of office on five and a half hours early for everybody in this
   * organisation, which is the difference between covering a flight and not.
   */
  function toInstant(value) {
    if (!value) return '';
    var d = new Date(value);
    return isNaN(d.getTime()) ? '' : d.toISOString();
  }

  /* ------------------------------------------------------------------ the editors */

  function markEmpty(el) {
    if (!el) return;
    el.dataset.empty = el.textContent.trim() === '' && el.querySelectorAll('img,br').length === 0
      ? 'true' : 'false';
  }

  /**
   * What the editable is worth on the wire.
   *
   * Prefers the composer own serialiser, because it is the one that has been driven
   * against Word and Google Docs paste output and it is what the rest of this
   * application sends. Falling back to innerHTML is safe rather than merely
   * convenient: MailSettingsApi rebuilds whatever arrives through the outbound
   * allowlist, so the worst a raw innerHTML can cost is formatting.
   */
  function readEditor(el) {
    if (!el) return '';
    if (el.dataset.empty === 'true') return '';
    if (typeof cleanForeignHtml === 'function') {
      try { return cleanForeignHtml(el.innerHTML).html; } catch (e) { /* fall through */ }
    }
    return el.innerHTML;
  }

  function paintToolbar() {
    if (!sheet) return;
    var buttons = sheet.querySelectorAll('.ms-fx');
    for (var i = 0; i < buttons.length; i++) {
      var cmd = buttons[i].dataset.cmd;
      if (cmd === 'createLink' || cmd === 'removeFormat') continue;
      var on = false;
      // queryCommandState is unreliable for bold once a browser reports a numeric
      // font-weight, so the pressed state is read off the caret computed style, which
      // is what the composer settled on for the same reason.
      try { on = document.queryCommandState(cmd); } catch (e) { on = false; }
      buttons[i].setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function runCommand(button) {
    var target = document.getElementById(button.dataset.edit);
    if (!target) return;
    target.focus();
    var cmd = button.dataset.cmd;
    if (cmd === 'createLink') {
      var href = window.prompt('Link address', 'https://');
      if (!href) return;
      if (!/^https?:\/\//i.test(href) && !/^mailto:/i.test(href)) href = 'https://' + href;
      document.execCommand('createLink', false, href);
    } else {
      document.execCommand(cmd, false, null);
    }
    markEmpty(target);
    paintToolbar();
  }

  /* ------------------------------------------------------------------ the sheet */

  function build() {
    styles();
    sheet = document.createElement('div');
    sheet.className = 'backdrop bsheet';
    sheet.id = 'msSheet';
    sheet.innerHTML = ''
      + '<div class="sheet" role="dialog" aria-modal="true" aria-labelledby="msTitle">'
      + '<span class="grab" aria-hidden="true"></span>'
      + '<div class="sheet-h"><h3 id="msTitle">Mail settings</h3>'
      + '<span class="spacer"></span>'
      + '<button class="pib" type="button" data-ms-close aria-label="Close">'
      + icon('i-close') + '</button></div>'
      + '<div class="sheet-b" id="msBody"></div>'
      + '<div class="sheet-f">'
      + '<button class="btn pri" type="button" id="msSave">Save</button>'
      + '<button class="btn" type="button" data-ms-close>Cancel</button>'
      + '<span class="spacer"></span>'
      + '<span class="hint" id="msWho"></span>'
      + '</div></div>';
    document.body.appendChild(sheet);

    sheet.addEventListener('click', function (e) {
      if (e.target === sheet || e.target.closest('[data-ms-close]')) { close(); return; }
      var fxBtn = e.target.closest('.ms-fx');
      if (fxBtn) { e.preventDefault(); runCommand(fxBtn); return; }
      if (e.target.closest('#msSave')) commit();
    });
    sheet.addEventListener('input', function (e) {
      if (e.target.classList && e.target.classList.contains('ms-edit')) markEmpty(e.target);
    });
    sheet.addEventListener('keyup', paintToolbar);
    sheet.addEventListener('mouseup', paintToolbar);
    sheet.addEventListener('change', function (e) {
      if (e.target.id === 'msVacOn') paintVacationState();
    });
    // Escape closes this sheet and stops there. Letting it reach mail.js would pop
    // the history entry as well, and the pane behind would slide at the same time.
    sheet.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { e.stopPropagation(); close(); }
    });
  }

  function paintVacationState() {
    var box = $s('#msVacState');
    if (!box) return;
    var wanted = $s('#msVacOn') ? $s('#msVacOn').checked : current.vacationEnabled;
    var html = '';
    if (!wanted) {
      html = '';
    } else if (current.vacationServerSide && current.vacationEnabled) {
      html = '<div class="ms-state on">' + icon('i-check', 'ic-sm')
        + '<span>The mail server is answering this mailbox, so replies go out whether or not '
        + 'anyone has this page open.'
        + (current.vacationRepliedSenders
            ? ' ' + current.vacationRepliedSenders + ' people have had one so far.' : '')
        + '</span></div>';
    } else if (current.vacationServerNote) {
      html = '<div class="ms-state warn">' + icon('i-warn', 'ic-sm')
        + '<span>' + esc(current.vacationServerNote) + '</span></div>';
    } else {
      html = '<div class="ms-state">' + icon('i-info', 'ic-sm')
        + '<span>Save to hand this to the mail server. Until it takes it, no automatic '
        + 'replies are sent.</span></div>';
    }
    box.innerHTML = html;
  }

  function paint() {
    if (!sheet) build();
    $s('#msBody').innerHTML = body(current);
    $s('#msWho').textContent = current.mailbox || '';
    var editors = sheet.querySelectorAll('.ms-edit');
    for (var i = 0; i < editors.length; i++) markEmpty(editors[i]);
    paintVacationState();
  }

  function commit() {
    var button = $s('#msSave');
    if (button) { button.disabled = true; button.textContent = 'Saving'; }

    var form = {
      signatureHtml: readEditor($s('#msSig')),
      signatureOnNew: $s('#msSigNew').checked,
      signatureOnReply: $s('#msSigReply').checked,
      vacationEnabled: $s('#msVacOn').checked,
      vacationSubject: $s('#msVacSubject').value,
      vacationHtml: readEditor($s('#msVacMsg')),
      vacationFrom: toInstant($s('#msVacFrom').value),
      vacationTo: toInstant($s('#msVacTo').value),
      vacationPeriodDays: $s('#msVacDays').value,
      preferHtml: $s('#msPrefer').value,
      loadRemoteImages: $s('#msImages').checked,
      messagesPerPage: $s('#msPerPage').value,
      readingPane: $s('#msPane').value,
      undoSendSeconds: $s('#msUndo').value,
      defaultReply: $s('#msReply').value,
      requestReadReceipt: $s('#msReceipt').checked
    };

    save(form).then(function (data) {
      if (button) { button.disabled = false; button.textContent = 'Save'; }
      // Repaint rather than close. The out of office line is the one thing on this
      // sheet whose truth only arrives with the answer, and closing on success would
      // hide the sentence saying the mail server would not take it.
      paint();
      say(data.vacationEnabled && !data.vacationServerSide
        ? 'Settings saved. The mail server is not answering yet, see the note.'
        : 'Settings saved.');
    }).catch(function (e) {
      if (button) { button.disabled = false; button.textContent = 'Save'; }
      say(e.message || 'The settings could not be saved.', true);
    });
  }

  /* ------------------------------------------------------------------ open and close */

  /**
   * Opens the sheet and pushes one history entry, so Back closes it on a phone.
   *
   * The entry carries mail.js own state under jm untouched, because that file
   * popstate handler reads e.state.jm and re-applies it. Copying it forward means a
   * Back out of this sheet re-applies the state the screen was already in, which is
   * a no-op, instead of resetting the pane to the inbox.
   */
  function show() {
    if (!sheet) build();
    if (open) return;
    var carried = (history.state && history.state.jm) || null;
    history.pushState({ jm: carried, mailSettings: true }, '');
    open = true;
    sheet.classList.add('open');
    load(true).then(paint);
    paint();
    var first = $s('#msSig');
    if (first) requestAnimationFrame(function () { try { first.focus({ preventScroll: true }); } catch (e) { first.focus(); } });
  }

  function close() {
    if (!open) return;
    if (history.state && history.state.mailSettings) { history.back(); return; }
    hide();
  }

  function hide() {
    open = false;
    if (sheet) sheet.classList.remove('open');
  }

  window.addEventListener('popstate', function (e) {
    if (open && !(e.state && e.state.mailSettings)) hide();
  });

  /* ------------------------------------------------------------------ the launcher */

  /**
   * Adds one row to the account sheet, above Sign out.
   *
   * The account sheet is where the mailbox already keeps Campaign Studio, Install
   * app, Close mailbox and Sign out, so it is where somebody looks for settings, and
   * putting the row there costs the owner of mail.html nothing. Inserted before the
   * sign out link rather than appended, because a destructive action belongs last.
   */
  function mount() {
    var menu = document.querySelector('#accountSheet .sheet-menu');
    if (!menu || document.getElementById('msOpen')) return;

    var row = document.createElement('button');
    row.type = 'button';
    row.className = 'mrow';
    row.id = 'msOpen';
    row.innerHTML = icon('i-sliders') + '<span>Mail settings</span>'
      + icon('i-next', 'ic-sm chev');
    row.addEventListener('click', function () {
      // popThen is how every other row on this sheet acts: it pops the account
      // overlay first and runs afterwards, so one Back later lands on the list and
      // not back inside the sheet that opened this one.
      if (typeof popThen === 'function') popThen(show);
      else { var s = document.getElementById('accountSheet'); if (s) s.classList.remove('open'); show(); }
    });

    var signOut = menu.querySelector('a.mrow.danger');
    if (signOut) menu.insertBefore(row, signOut);
    else menu.appendChild(row);
  }

  function start() {
    mount();
    // Warmed once so the composer can ask for a signature synchronously on the very
    // first compose. A locked mailbox answers 409 and leaves the defaults in place.
    load(false);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();

  /* ------------------------------------------------------------------ public API */

  /**
   * What other files on this screen may use.
   *
   * signatureFor is the one the composer wants: it hands back the finished block,
   * separator included, or an empty string when the switch for that kind of message
   * is off, so the composer appends one string and never has to know which switch
   * applied or what the separator looks like.
   */
  window.MailSettings = {
    open: show,
    close: close,
    /** The last known settings. Never null, defaults before the first answer. */
    get: function () { return current; },
    /** Fetches if it has not already. Resolves with the settings either way. */
    load: load,
    /** kind is 'new' or 'reply'. Empty string when nothing should be appended. */
    signatureFor: function (kind) {
      return kind === 'reply' ? (current.signatureForReply || '') : (current.signatureForNew || '');
    },
    /** True when this mailbox is answering automatically right now. */
    answeringAutomatically: function () { return !!current.vacationActive; }
  };
})();
