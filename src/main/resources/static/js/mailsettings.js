/* =========================================================================
   Jarurat Mail - per-mailbox settings sheet
   Talks to /api/mail/settings and /api/mail/notify/rules, and to nothing else.
   Self contained on purpose: mail.html and
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
  var URL_RULES = '/api/mail/notify/rules';

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

  /* The same idea for the notification rules, which are a second endpoint rather than
     more fields on the first one. They are kept apart because they are read by a
     different consumer on a different schedule: the poll path asks for the rules on
     every arrival and never wants a signature with them. */
  var RULE_DEFAULTS = {
    mailbox: '',
    levels: ['everything', 'direct', 'vip', 'nothing'],
    folders: { inbox: 'direct', archive: 'nothing' },
    neverNotified: ['drafts', 'junk', 'spam', 'trash'],
    quietEnabled: true, quietStartHour: 21, quietEndHour: 8, zone: 'Asia/Kolkata',
    quietNow: false, quietSilences: true, quietHolds: false,
    vips: [], maxVips: 100,
    muted: [], muteDays: 30,
    maxDirectRecipients: 12,
    sharedWithMailbox: true,
    updatedAt: ''
  };

  var current = copy(DEFAULTS);
  var rules = copy(RULE_DEFAULTS);
  var loaded = false;
  var loading = null;
  var rulesLoaded = false;
  var rulesLoading = null;
  var sheet = null;
  var open = false;

  /* The VIP list being edited, which is not the stored one until Save.
     Held apart from rules.vips because adding a name and then changing your mind has
     to be free, and because the list is redrawn on its own without the whole sheet
     being rebuilt underneath somebody's cursor. */
  var vipDraft = [];

  /* Whether this device has a push subscription, which is the only honest answer to
     "will this reach me with the app shut". Undefined until the check answers. */
  var pushReach = null;
  /* Subscribed and proved are different facts and the screen must not conflate them:
     the first only says this browser asked, the second says a push actually arrived. */
  var pushSubscribed = false;
  /* The mail server lets a push registration lapse a week after a mailbox stops being
     opened. Somebody who only reads mail on a laptop needs telling that, or their phone
     goes quiet with no explanation and they conclude the feature is broken. */
  var pushExpiresAt = null;

  /* ------------------------------------------------------------------ tiny helpers */

  function copy(o) { var out = {}; for (var k in o) if (o.hasOwnProperty.call(o, k)) out[k] = o[k]; return out; }

  /* A date somebody can act on: "12 Sep" rather than a timestamp. Falls back to the
     raw value rather than throwing, because a lapse date the server phrased in a way
     this does not expect is still worth showing. */
  function shortDate(iso) {
    try {
      var d = new Date(iso);
      if (isNaN(d.getTime())) return String(iso);
      return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
    } catch (e) { return String(iso); }
  }

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
   * The notification rules, treating a locked mailbox as an ordinary answer.
   *
   * A separate fetch from the settings one rather than a merged endpoint. They are
   * saved together from one button, but they are read by different things at
   * different times, and a poll path that wants to know whether a message earns a
   * sound should not be pulling a signature down the wire to find out.
   */
  function loadRules(force) {
    if (rulesLoaded && !force) return Promise.resolve(rules);
    if (rulesLoading) return rulesLoading;
    rulesLoading = fetch(URL_RULES, { headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (res.status === 409 || res.status === 401 || res.status === 403) return null;
        return res.json().catch(function () { return null; });
      })
      .then(function (data) {
        if (data && !data.error) {
          rules = data;
          rulesLoaded = true;
          vipDraft = (data.vips || []).slice();
          announceRules();
        }
        rulesLoading = null;
        return rules;
      })
      .catch(function () { rulesLoading = null; return rules; });
    return rulesLoading;
  }

  /**
   * Saves the rules.
   *
   * form is a URLSearchParams rather than a plain object, because two of the fields
   * repeat: a VIP list is sent as one vip parameter per entry, which is how a form
   * sends a list without anybody having to pick a separator that will one day turn up
   * inside somebody's address.
   */
  function saveRules(form) {
    return fetch(URL_RULES, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': token() },
      body: form.toString()
    }).then(function (res) {
      return res.json().catch(function () { return null; }).then(function (data) {
        if (!res.ok || (data && data.error)) {
          throw new Error((data && data.error) || 'The notification rules could not be saved.');
        }
        rules = data;
        rulesLoaded = true;
        vipDraft = (data.vips || []).slice();
        announceRules();
        return data;
      });
    });
  }

  function announceRules() {
    try {
      window.dispatchEvent(new CustomEvent('mailnotify:rules', { detail: rules }));
    } catch (e) { /* see announce() */ }
  }

  /* ------------------------------------------------------------------ permission */

  /**
   * Whether this page is running as an installed app rather than in a browser tab.
   *
   * Two tests because the two platforms answer differently and only one of them is
   * standard: navigator.standalone is Safari's, and the display-mode media query is
   * everybody else's. A page that only asked the standard one would decide that every
   * iPhone home screen app was a tab and print the install instructions at somebody
   * who had already followed them.
   */
  function installed() {
    try {
      if (navigator.standalone === true) return true;
      return !!(window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
    } catch (e) { return false; }
  }

  function isIos() {
    var ua = navigator.userAgent || '';
    if (/iPad|iPhone|iPod/.test(ua)) return true;
    // An iPad on iPadOS 13 and later reports itself as a Mac, and the touch count is
    // the only thing left that separates the two.
    return ua.indexOf('Mac') >= 0 && navigator.maxTouchPoints > 1;
  }

  /**
   * The one function that decides what the notification control is allowed to claim.
   *
   * Five answers, and every one of them has to be drawn differently, because a toggle
   * that cannot work is worse than no toggle at all: the person flips it, nothing
   * happens, and they conclude the application is broken rather than that their
   * browser said no. Nothing here guesses; each branch is a fact the browser told us.
   *
   *   unsupported - no Notification constructor on this origin at all
   *   install     - iOS, and this is a Safari tab. WebKit gives a tab no PushManager
   *                 and no notification permission whatever, and no amount of asking
   *                 changes it. The app has to be on the Home Screen first.
   *   default     - never asked. The only state where a button may raise the prompt.
   *   granted     - on
   *   denied      - refused, or auto-blocked by Chrome's quiet UI, which is
   *                 indistinguishable from a refusal and has the same recovery.
   */
  function permissionState() {
    if (typeof Notification === 'undefined') {
      return isIos() && !installed() ? 'install' : 'unsupported';
    }
    if (isIos() && !installed()) return 'install';
    return Notification.permission;
  }

  /**
   * Where the person has to go to undo a refusal.
   *
   * Written per browser because "check your browser settings" is not an instruction,
   * it is an apology. A refusal cannot be re-prompted from script under any
   * circumstances, so this sentence is the entire recovery path and it is worth
   * getting exactly right for the three browsers this organisation actually uses.
   */
  function recoveryHint() {
    var ua = navigator.userAgent || '';
    if (isIos()) return 'Open Settings, then Notifications, then Jarurat Mail.';
    if (/Android/.test(ua)) {
      return 'Tap the icon at the left of the address bar, then Permissions, then Notifications.';
    }
    if (/Edg\//.test(ua)) {
      return 'Click the icon at the left of the address bar, then set Notifications to Allow.';
    }
    if (/Firefox\//.test(ua)) {
      return 'Click the padlock at the left of the address bar, then clear the blocked '
        + 'Notifications permission.';
    }
    return 'Click the icon at the left of the address bar, then set Notifications to Allow.';
  }

  /**
   * Whether a push subscription exists on this device.
   *
   * This is the difference between "we will tell you" and "we will tell you while you
   * are looking", and it is the one claim on this screen that must never be made on
   * faith. It is read from the service worker registration rather than from anything
   * this file believes, so if the push half of the feature is not deployed, or the
   * subscription has lapsed, the sentence downgrades itself without anybody editing it.
   */
  function checkPushReach() {
    if (!navigator.serviceWorker || !('PushManager' in window)) {
      pushReach = false;
      return Promise.resolve(false);
    }
    /* A subscription that EXISTS is not a subscription that WORKS, and telling
       somebody their phone will be woken when it will not is worse than saying
       nothing. The mail server signs and encrypts these itself, so if its VAPID key
       is unset or is a different key from the one the browser subscribed with, the
       push service answers 403 forever and not one notification is ever delivered.
       Nothing on this device can observe that by looking at its own subscription.

       jmNotify.pushProved is the only honest signal: it is set when a push has
       actually arrived at this device at least once. Until that has happened the
       screen says push is not proved, whatever getSubscription reports. */
    return navigator.serviceWorker.ready
      .then(function (reg) { return reg.pushManager.getSubscription(); })
      .then(function (sub) {
        var proved = false;
        var api = window.jmNotify;
        if (api && typeof api.state === 'function') {
          var st = api.state() || {};
          proved = !!st.pushProved;
          pushExpiresAt = st.pushExpiresAt || null;
        }
        pushSubscribed = !!sub;
        pushReach = !!sub && proved;
        return pushReach;
      })
      .catch(function () { pushReach = false; pushSubscribed = false; return false; });
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
      /* The VIP list. A plain list of rows rather than chips, because each entry
         carries a checkbox of its own and a chip with a checkbox inside it is a
         44px tap target wrapped around two smaller ones. */
      + '.ms-vips{list-style:none;margin:8px 0 0;padding:0}'
      + '.ms-vip{display:flex;align-items:center;gap:8px;min-height:44px;'
      + 'border-top:1px solid var(--border);padding:4px 0}'
      + '.ms-vip:first-child{border-top:0}'
      + '.ms-vip .ms-vip-a{flex:1;min-width:0;font-size:13px;overflow:hidden;'
      + 'text-overflow:ellipsis;white-space:nowrap}'
      + '.ms-vip .ms-vip-q{display:flex;align-items:center;gap:6px;flex:none;'
      + 'font-size:12px;color:var(--text-dim);cursor:pointer;padding:11px 0}'
      + '.ms-vip .pib{flex:none}'
      + '.ms-add{display:flex;gap:8px;align-items:stretch}'
      + '.ms-add input{flex:1;min-width:0}'
      + '.ms-empty{font-size:12px;color:var(--text-mute);padding:10px 0}'
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

  /* ------------------------------------------------------------------ notifications */

  var LEVEL_LABELS = {
    everything: 'Every message',
    direct: 'Messages to me, and VIPs',
    vip: 'VIPs only',
    nothing: 'Nothing'
  };

  var FOLDER_LABELS = { inbox: 'Inbox', archive: 'Archive' };

  function folderLabel(role) {
    if (FOLDER_LABELS[role]) return FOLDER_LABELS[role];
    return role.charAt(0).toUpperCase() + role.slice(1);
  }

  function pad(h) { return (h < 10 ? '0' : '') + h; }

  function hourOptions(chosen) {
    var options = [];
    for (var h = 0; h < 24; h++) options.push([h, (h < 10 ? '0' : '') + h + ':00']);
    return optionList(options, chosen);
  }

  /**
   * The notifications section.
   *
   * The order is deliberate and it is the order of the questions somebody actually
   * has: can this reach me at all, then what is loud, then when is it never loud,
   * then who is the exception. The permission block is first because every control
   * under it is meaningless if the answer to the first question is no, and putting it
   * last is how a person spends five minutes setting rules that will never fire.
   */
  function notifySection(r) {
    var folders = r.folders || {};
    var rows = '';
    for (var role in folders) {
      if (!Object.prototype.hasOwnProperty.call(folders, role)) continue;
      var options = [];
      for (var i = 0; i < (r.levels || []).length; i++) {
        var id = r.levels[i];
        options.push([id, LEVEL_LABELS[id] || id]);
      }
      rows += '<div class="field"><label for="msLvl-' + esc(role) + '">'
        + esc(folderLabel(role)) + '</label>'
        + '<select id="msLvl-' + esc(role) + '" data-folder="' + esc(role) + '">'
        + optionList(options, folders[role]) + '</select></div>';
    }

    return ''
      + '<section class="ms-sec">'
      + '<h4>' + icon('i-bell') + 'Notifications</h4>'
      + '<div id="msNotifyState"></div>'

      + '<p class="hint">Two dials, not one. The level below decides what is allowed to '
      + 'make a sound. Everything else that reaches the Inbox still appears, in full and '
      + 'silently, so nothing is hidden from you to keep it quiet.</p>'
      /* Full width and stacked rather than the two-column ms-grid the rest of this
         sheet uses. Measured at 390: the grid puts two selects side by side and clips
         "Messages to me, and VIPs" to "Messages to me, an", which turns the one
         control on this screen whose whole job is to be unambiguous into a guess. */
      + '<div>' + rows + '</div>'
      + '<p class="hint">Junk, Spam, Trash and Drafts never notify, and that is not a '
      + 'setting. A message in the spam folder is a message the server already doubted, '
      + 'and putting one on a lock screen is doing the phishing for them.</p>'
      + (r.sharedWithMailbox
          ? '<p class="hint">These rules belong to ' + esc(r.mailbox || 'this mailbox')
            + ' rather than to you, so anyone else who opens this mailbox sees and changes '
            + 'the same ones. Whether your phone is allowed to notify at all is yours '
            + 'alone and stays on this device.</p>'
          : '')

      // ---- quiet hours
      + check('msQuietOn', 'Quiet hours', r.quietEnabled)
      + '<div class="ms-grid">'
      + '<div class="field"><label for="msQuietFrom">Quiet from</label>'
      + '<select id="msQuietFrom">' + hourOptions(r.quietStartHour) + '</select></div>'
      + '<div class="field"><label for="msQuietTo">Until</label>'
      + '<select id="msQuietTo">' + hourOptions(r.quietEndHour) + '</select></div>'
      + '</div>'
      /* The sentence that has to be here rather than in a help page. Which of the two
         plausible behaviours this picked is the single thing a person needs to know
         about quiet hours, and getting it wrong in their head costs them a message. */
      + '<p class="hint">Quiet hours take the sound off. They do not hold anything back. '
      + 'A message that arrives at two in the morning is on your phone at two in the '
      + 'morning, silently, with the right time on it, so checking at three tells you the '
      + 'truth instead of nothing. Times are ' + esc(r.zone || 'Asia/Kolkata')
      + ', the same hours campaigns use.</p>'

      // ---- VIPs
      + '<h4 style="margin-top:16px">' + icon('i-star') + 'VIPs</h4>'
      + '<p class="hint">One address, or a whole organisation written @tmc.gov.in. VIPs '
      + 'get through at every level, including past the automatic-mail rule. Tick Even at '
      + 'night beside one to let them through quiet hours as well; that is the only way '
      + 'anything makes a sound between ' + esc(pad(r.quietStartHour)) + ':00 and '
      + esc(pad(r.quietEndHour)) + ':00.</p>'
      + '<div class="ms-add">'
      + '<input id="msVipNew" type="email" inputmode="email" autocomplete="off" '
      + 'placeholder="anand@tmc.gov.in or @tmc.gov.in" aria-label="Add a VIP">'
      + '<button class="btn" type="button" id="msVipAdd">Add</button>'
      + '</div>'
      + '<ul class="ms-vips" id="msVipList"></ul>'
      + '</section>';
  }

  /** Only the list, so adding a name does not rebuild the sheet under the cursor. */
  function paintVips() {
    var list = $s('#msVipList');
    if (!list) return;
    if (!vipDraft.length) {
      list.innerHTML = '<li class="ms-empty">Nobody yet. Until somebody is here, the '
        + 'Messages to me level is what decides, which needs no list to work.</li>';
      return;
    }
    var html = '';
    for (var i = 0; i < vipDraft.length; i++) {
      var vip = vipDraft[i];
      html += '<li class="ms-vip">'
        + icon(vip.quietBreak ? 'i-star-on' : 'i-star', 'ic-sm')
        + '<span class="ms-vip-a">' + esc(vip.address) + '</span>'
        + '<label class="ms-vip-q"><input type="checkbox" data-vip-quiet="' + i + '"'
        + (vip.quietBreak ? ' checked' : '') + '> Even at night</label>'
        + '<button class="pib" type="button" data-vip-remove="' + i + '" '
        + 'aria-label="Remove ' + esc(vip.address) + '">' + icon('i-close', 'ic-sm')
        + '</button></li>';
    }
    list.innerHTML = html;
  }

  /**
   * The permission block, which is the only part of this sheet that is allowed to
   * refuse to draw a control.
   *
   * Every branch here ends in either a working button or a sentence that says what to
   * do instead. What it never does is show something switch-shaped that cannot work,
   * because the person will flip it, see nothing happen, and stop believing the rest
   * of the screen as well.
   */
  function paintNotifyState() {
    var box = $s('#msNotifyState');
    if (!box) return;
    var state = permissionState();
    var html = '';

    if (state === 'unsupported') {
      html = '<div class="ms-state">' + icon('i-info', 'ic-sm')
        + '<span>This browser has no notifications. The unread count in the tab title and '
        + 'on the icon still works, and needs no permission.</span></div>';

    } else if (state === 'install') {
      // WebKit is unambiguous: a site open in a Safari tab has no PushManager and no
      // notification permission at all. There is nothing to toggle, so the honest
      // control is the instruction that leads to one.
      html = '<div class="ms-state">' + icon('i-share-ios', 'ic-sm')
        + '<span><strong>Notifications on iPhone and iPad need this on your Home Screen '
        + 'first.</strong> Tap Share, then Add to Home Screen, then open Jarurat Mail from '
        + 'there and turn notifications on. Safari cannot do it from a tab, whatever is set '
        + 'below. While this tab is open, new mail still updates the count on the '
        + 'title.</span></div>';

    } else if (state === 'denied') {
      // A denial cannot be re-prompted from script, and repeated attempts feed Chrome's
      // auto-block. So this is a sentence and never a button.
      html = '<div class="ms-state warn">' + icon('i-warn', 'ic-sm')
        + '<span><strong>Your browser is blocking notifications for this site.</strong> '
        + esc(recoveryHint())
        + ' The unread count in the tab title and on the icon still works.</span></div>';

    } else if (state === 'granted') {
      html = '<div class="ms-state on">' + icon('i-check', 'ic-sm')
        + '<span>Notifications are on for this device.'
        /* Three states, not two, because "subscribed" and "proved" are different
           facts. Saying a phone will be woken when no push has ever arrived is the
           one claim on this screen that a person would act on and be let down by:
           they would stop checking. So a subscription that has not yet delivered
           says so plainly rather than being rounded up to working. */
        + (pushReach === true
            ? ' Your phone will be told even with this closed.'
            : pushSubscribed
              ? ' This device is registered, but no notification has arrived on it yet, '
                + 'so it is not proven to reach you with everything closed. Until one does, '
                + 'they show while Jarurat Mail is open somewhere.'
              : pushReach === false
                ? ' They reach you while Jarurat Mail is open somewhere. With every window '
                  + 'closed, nothing is delivered to this device.'
                : '')
        + (pushReach === true && pushExpiresAt
            ? ' <span class="ms-dim">Registration lapses on ' + esc(shortDate(pushExpiresAt))
              + ' unless you open your mailbox before then.</span>'
            : '')
        + '</span></div>';

    } else {
      html = '<div class="ms-state">' + icon('i-bell', 'ic-sm')
        + '<span>Notifications are off on this device. Your browser will ask once, and a '
        + 'refusal cannot be undone from here, so the rules below are worth setting first.'
        + '<br><button class="btn pri" type="button" id="msNotifyAsk" '
        + 'style="margin-top:10px">Turn on notifications</button></span></div>';
    }

    if (rules.quietNow) {
      html += '<div class="ms-state">' + icon('i-clock', 'ic-sm')
        + '<span>It is quiet hours now, so anything arriving is being shown without a '
        + 'sound.</span></div>';
    }
    box.innerHTML = html;
  }

  function body(s) {
    return ''
      // ---- notifications
      + notifySection(rules)

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
      // The permission ask has to run inside this click and nowhere else. Browsers
      // that require a gesture drop a request that arrives from a timer or a promise
      // callback, silently, and it looks exactly like a person refusing. It goes
      // through jmNotify.enable rather than calling requestPermission here, because
      // notify.js already owns that prompt and two files asking is two chances to
      // spend a permission that can only be spent once.
      if (e.target.closest('#msNotifyAsk')) { askForPermission(); return; }
      if (e.target.closest('#msVipAdd')) { addVip(); return; }
      var remove = e.target.closest('[data-vip-remove]');
      if (remove) {
        vipDraft.splice(parseInt(remove.getAttribute('data-vip-remove'), 10), 1);
        paintVips();
        return;
      }
      if (e.target.closest('#msSave')) commit();
    });
    // Enter in the VIP box adds rather than submitting nothing, which is what every
    // person who has ever met a box with an Add button beside it expects.
    sheet.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && e.target && e.target.id === 'msVipNew') {
        e.preventDefault();
        addVip();
      }
    });
    sheet.addEventListener('input', function (e) {
      if (e.target.classList && e.target.classList.contains('ms-edit')) markEmpty(e.target);
    });
    sheet.addEventListener('keyup', paintToolbar);
    sheet.addEventListener('mouseup', paintToolbar);
    sheet.addEventListener('change', function (e) {
      if (e.target.id === 'msVacOn') paintVacationState();
      var quiet = e.target.getAttribute && e.target.getAttribute('data-vip-quiet');
      if (quiet !== null && quiet !== undefined) {
        var vip = vipDraft[parseInt(quiet, 10)];
        if (vip) { vip.quietBreak = e.target.checked; paintVips(); }
      }
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

  /**
   * Adds whatever is in the box to the draft list, or says why it did not.
   *
   * The server validates this properly and its message is the one that would be shown
   * on Save. Repeating a rough version of the check here is not duplication for its
   * own sake: without it, a typo is only discovered after the person has closed the
   * sheet, and the failure arrives detached from the box they typed into.
   */
  function addVip() {
    var input = $s('#msVipNew');
    if (!input) return;
    var raw = (input.value || '').trim().toLowerCase();
    if (!raw) return;

    var address = raw;
    if (address.indexOf('@') < 0) address = '@' + address;
    if (address.indexOf(' ') >= 0 || address.indexOf('.') < 0
        || address.lastIndexOf('@') !== address.indexOf('@')
        || address.charAt(address.length - 1) === '@') {
      say('Write one address, like anand@tmc.gov.in, or a whole organisation, '
        + 'like @tmc.gov.in.', true);
      return;
    }
    for (var i = 0; i < vipDraft.length; i++) {
      if (vipDraft[i].address === address) { input.value = ''; return; }
    }
    if (vipDraft.length >= (rules.maxVips || 100)) {
      say('That is the limit of ' + (rules.maxVips || 100) + ' VIPs. A list that long is '
        + 'not a list of people who matter more.', true);
      return;
    }
    vipDraft.push({ address: address, domain: address.charAt(0) === '@', quietBreak: false });
    input.value = '';
    paintVips();
    input.focus();
  }

  /**
   * Raises the browser prompt, then watches for the answer.
   *
   * requestPermission's answer does not always come back through the call that raised
   * it on every browser, and the permissions API is missing on some older Safari
   * builds, so the state is re-read a few times over the next couple of seconds and
   * again whenever the tab comes back. A person who granted the permission and came
   * back to a screen still saying it is off would conclude it had failed.
   */
  function askForPermission() {
    if (window.jmNotify && typeof window.jmNotify.enable === 'function') {
      window.jmNotify.enable();
    } else if (typeof Notification !== 'undefined') {
      try { Notification.requestPermission(); } catch (e) { /* not available here */ }
    }
    var tries = 0;
    var timer = setInterval(function () {
      paintNotifyState();
      if (++tries >= 6 || permissionState() !== 'default') clearInterval(timer);
    }, 500);
  }

  function paint() {
    if (!sheet) build();
    $s('#msBody').innerHTML = body(current);
    $s('#msWho').textContent = current.mailbox || '';
    var editors = sheet.querySelectorAll('.ms-edit');
    for (var i = 0; i < editors.length; i++) markEmpty(editors[i]);
    paintVacationState();
    paintNotifyState();
    paintVips();
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

    /* One button, two endpoints. They are posted together rather than merged into one
       call because they are genuinely two resources with two different readers, and
       merged they would mean the poll path pulling a signature down every time it
       wanted to know whether a message earns a sound. In sequence rather than at once,
       because the settings save is the one that can be refused for a reason worth
       stopping on, and writing rules against a form the server has just rejected would
       leave the two halves of this sheet describing different states. */
    save(form).then(function (data) {
      return saveRules(ruleForm()).then(function () { return data; });
    }).then(function (data) {
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

  /**
   * The notification rules as a form body.
   *
   * The VIP list is always sent, marked by the vips parameter, because an empty list
   * is a thing somebody can mean and the endpoint cannot tell "I removed everybody"
   * from "this request was not about VIPs" without being told. The pipe carries the
   * quiet-hours flag rather than a second parallel parameter, which would go out of
   * step with the first the moment one entry failed to parse.
   */
  function ruleForm() {
    // Not named body: that is a function in this file, and a local shadowing it here
    // would read as an assignment to it three months from now.
    var out = new URLSearchParams();
    var selects = sheet.querySelectorAll('[data-folder]');
    for (var i = 0; i < selects.length; i++) {
      out.append('folder.' + selects[i].getAttribute('data-folder'), selects[i].value);
    }
    out.append('quietEnabled', $s('#msQuietOn') ? $s('#msQuietOn').checked : rules.quietEnabled);
    if ($s('#msQuietFrom')) out.append('quietStartHour', $s('#msQuietFrom').value);
    if ($s('#msQuietTo')) out.append('quietEndHour', $s('#msQuietTo').value);
    out.append('vips', '1');
    for (var v = 0; v < vipDraft.length; v++) {
      out.append('vip', vipDraft[v].address + '|' + (vipDraft[v].quietBreak ? '1' : '0'));
    }
    return out;
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
    loadRules(true).then(paint);
    // Asked every time the sheet opens rather than once at start, because a
    // subscription can appear or lapse between two visits and the sentence it decides
    // is the one claim on this screen that must never be stale.
    checkPushReach().then(paintNotifyState);
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

  /**
   * Repaints when the permission changes outside this page.
   *
   * Without this, somebody follows the recovery instruction above, flips the switch in
   * their browser settings, comes back to a tab that still believes it is blocked, and
   * concludes the application is broken. It is three lines and it is the difference
   * between a recovery path that works and one that only reads as though it does.
   *
   * navigator.permissions is missing on some older Safari builds, so the whole thing is
   * guarded and the visibilitychange listener below is the fallback: coming back to the
   * tab is when a permission changed elsewhere is most likely to be stale.
   */
  function watchPermission() {
    try {
      if (!navigator.permissions || !navigator.permissions.query) return;
      navigator.permissions.query({ name: 'notifications' }).then(function (status) {
        status.onchange = function () { if (open) paintNotifyState(); };
      }).catch(function () { /* some browsers refuse this descriptor by name */ });
    } catch (e) { /* older Safari */ }
  }

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && open) paintNotifyState();
  });

  function start() {
    mount();
    // Warmed once so the composer can ask for a signature synchronously on the very
    // first compose. A locked mailbox answers 409 and leaves the defaults in place.
    load(false);
    // The rules are warmed too, because the poll path wants them on the first arrival
    // rather than on the first time somebody opens this sheet.
    loadRules(false);
    watchPermission();
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
    answeringAutomatically: function () { return !!current.vacationActive; },

    /**
     * The notification rules, for whoever paints the notification.
     *
     * Exported because the lane a message earns is decided on the server and the
     * thing that draws the notification is a different file again, so this is the
     * one place both of them can read the same answer without a second fetch. Never
     * null: the defaults stand in until the first answer arrives.
     */
    rules: function () { return rules; },
    loadRules: loadRules,

    /**
     * What this device can actually do, in one word, for a caller that wants to
     * decide whether to bother. Same five answers the sheet draws.
     */
    permissionState: permissionState
  };
})();
