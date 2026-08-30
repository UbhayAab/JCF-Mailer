/* =========================================================================
   JCF Campaign Studio - the template library, connected
   -------------------------------------------------------------------------
   TemplateLibraryService has been finished and tested for a while and no screen
   has ever reached it, so the console shows a template's merge tags and nothing
   else about it. This file is the wire, not a second template system: it calls
   the endpoints CampaignsPlusApi already publishes over that service and it
   changes none of their behaviour.

   What it puts on the Templates screen:
     - the rendered size of each template, and a plain warning when it crosses
       the point where Gmail clips the message and hides the unsubscribe footer
     - which of a template's merge fields a campaign send will actually fill
     - a preview rendered against a real subscriber rather than placeholder text
     - save a campaign's copy back into the library, and load a template into a
       campaign

   Self contained on purpose. console.html and console.js belong to other authors
   this phase, so this file adds its own column, its own topbar button and its own
   modals, and carries its own styles. The only edit anybody else has to make is
   the one script tag that loads it:

       <script src="/js/templates.js"></script>

   It leans on what console.js publishes as globals and degrades without any of
   them, because "the Templates screen went blank because a helper was renamed"
   is a worse failure than a slightly plainer Templates screen:
     esc()             - HTML escaping                (falls back to a local copy)
     api() / post()    - GET and form POST with CSRF  (falls back to fetch here)
     toast()           - a message on screen          (falls back to alert)
     can()             - permission check             (falls back to reading PERMS)
     openModal()       - show a .modal-backdrop       (falls back to a class toggle)
     previewDocument() - the light pinned frame wrapper used by the composer
                         (falls back to an identical local copy, because a frame
                         with no ground of its own renders a dark themed creative
                         as white text on white paper)
   ========================================================================= */

(function () {
  'use strict';

  /* Every one of these is already live and already gated. /templates carries the
     library with its verdicts, {id}/preview renders one against a subscriber,
     /apply loads a template into a draft and /save-from-campaign goes the other
     way. Nothing called any of them before this file. */
  var LIBRARY       = '/api/campaignsplus/templates';
  var APPLY         = LIBRARY + '/apply';
  var FROM_CAMPAIGN = LIBRARY + '/save-from-campaign';
  var SUBSCRIBERS   = '/api/subscribers';
  var CAMPAIGNS     = '/api/campaigns';

  /* Only ever the denominator of the size bar. Every clip verdict on screen is the
     server's own overGmailClip flag, so this number drifting would make a bar
     slightly wrong and could never make the warning wrong. It matches
     TemplateLibraryService.GMAIL_CLIP_BYTES. */
  var CLIP_BYTES = 102 * 1024;

  var health = {};            // slug -> the library row for that template
  var loading = false;
  var loadFailed = false;
  var current = null;         // the row open in the inspector
  var chosen = null;          // {id} of the subscriber the preview renders against
  var searchTimer = null;
  var observer = null;

  /* ------------------------------------------------------------------ helpers */

  function el(id) { return document.getElementById(id); }

  function escape(s) {
    if (typeof esc === 'function') return esc(s);
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function say(message, kind) {
    if (typeof toast === 'function') toast(message, kind);
    else if (kind === 'err') window.alert(message);
  }

  function allowed(permission) {
    if (typeof can === 'function') return can(permission);
    return Array.isArray(window.PERMS) && window.PERMS.indexOf(permission) >= 0;
  }

  function token() {
    if (typeof csrfToken === 'function') return csrfToken();
    var match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
  }

  function getJson(url) {
    if (typeof api === 'function') return api(url);
    return fetch(url, { headers: { Accept: 'application/json' } }).then(function (res) {
      if (!res.ok) throw new Error('The server could not answer that request.');
      return res.json();
    });
  }

  function postForm(url, params) {
    if (typeof post === 'function') return post(url, params);
    var body = new URLSearchParams();
    Object.keys(params || {}).forEach(function (k) {
      if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]);
    });
    return fetch(url, { method: 'POST', body: body, headers: { 'X-XSRF-TOKEN': token() } })
      .then(function (res) {
        return res.json().catch(function () { return {}; }).then(function (payload) {
          if (!res.ok) throw new Error(payload.error || 'Request failed');
          return payload;
        });
      });
  }

  function show(id) {
    if (typeof openModal === 'function') openModal(id);
    else { var m = el(id); if (m) m.classList.add('open'); }
  }

  function hide(id) {
    if (typeof closeModal === 'function') closeModal(id);
    else { var m = el(id); if (m) m.classList.remove('open'); }
  }

  function icon(id, cls) {
    return '<svg class="ic' + (cls ? ' ' + cls : '') + '" aria-hidden="true"><use href="#' + id + '"/></svg>';
  }

  function kb(bytes) { return (Number(bytes || 0) / 1024).toFixed(1) + ' KB'; }

  function frameDocument(inner) {
    if (typeof previewDocument === 'function') return previewDocument(inner);
    return '<!doctype html><html><head><meta charset="utf-8">'
      + '<meta name="color-scheme" content="light">'
      + '<style>:root{color-scheme:light}'
      + 'html,body{margin:0;padding:0;background:#ffffff;color:#14212a}'
      + 'img{max-width:100%;height:auto}'
      + '@media (prefers-color-scheme:dark){html,body{background:#ffffff;color:#14212a}}'
      + '</style></head><body>' + inner + '</body></html>';
  }

  /* ------------------------------------------------------------------ styles */

  function injectStyle() {
    if (el('tlStyle')) return;
    var css =
      /* The size bar. Deliberately not .progress-track: that one is 6px with a
         --t-slow transition because it tracks a send in flight, and a static
         measurement animating itself up from zero on every repaint would read as
         work happening. */
        '.tl-bar{height:4px;border-radius:2px;background:rgba(255,255,255,.10);'
      + 'overflow:hidden;margin:5px 0 0;max-width:150px}'
      + '.tl-bar i{display:block;height:100%;border-radius:2px;background:var(--text-mute)}'
      + '.tl-bar.warn i{background:var(--warning)}'
      + '.tl-bar.bad i{background:var(--danger)}'
      + '.tl-size{font-family:var(--mono);font-size:var(--fs-sm);color:var(--text-dim);'
      + 'font-variant-numeric:tabular-nums}'
      + '.tl-cell{min-width:154px}'
      + '.tl-cell .btn{margin-top:7px}'
      + '.tl-flags{display:flex;flex-wrap:wrap;gap:4px;margin-top:5px}'
      /* The inspector. A grid so the verdict column keeps its width while the
         creative beside it takes the rest, and one column below 860px because two
         300px columns inside one modal leave neither of them readable. */
      + '#modalTemplateHealth .modal{max-width:900px}'
      + '#modalTemplateHealth .tl-grid{display:grid;grid-template-columns:262px 1fr;gap:var(--sp-5)}'
      + '@media (max-width:860px){#modalTemplateHealth .tl-grid{grid-template-columns:1fr}}'
      + '.tl-h{font-size:var(--fs-cap);font-weight:600;letter-spacing:.05em;text-transform:uppercase;'
      + 'color:var(--text-mute);margin:0 0 6px}'
      + '.tl-block{margin-bottom:var(--sp-4)}'
      + '.tl-big{font-family:var(--mono);font-size:20px;line-height:1.2;color:var(--text);'
      + 'font-variant-numeric:tabular-nums}'
      + '.tl-note{font-size:var(--fs-sm);line-height:1.5;color:var(--text-dim);margin:6px 0 0}'
      + '.tl-warnbox{margin:8px 0 0;padding:9px 11px;border-radius:var(--r-sm);font-size:var(--fs-sm);'
      + 'line-height:1.5;background:var(--tint-warning);border:1px solid rgba(217,158,11,.45);'
      + 'color:var(--warning-fg)}'
      + '.tl-badbox{background:var(--tint-danger);border-color:rgba(224,72,60,.50);color:var(--danger-fg)}'
      /* A results list rather than a select, because a select cannot show the
         address under the name and the address is the half that tells two people
         called Sharma apart. */
      + '.tl-who{display:flex;align-items:center;gap:8px;flex-wrap:wrap}'
      + '.tl-who .input{flex:1 1 180px;min-width:0}'
      + '.tl-hits:not(:empty){border:1px solid var(--border);border-radius:var(--r-sm);'
      + 'margin-top:6px;max-height:154px;overflow-y:auto}'
      + '.tl-hit{display:block;width:100%;text-align:left;background:none;border:0;cursor:pointer;'
      + 'padding:7px 10px;color:var(--text);font:inherit;font-size:var(--fs-sm);'
      + 'border-bottom:1px solid var(--border-soft)}'
      + '.tl-hit:last-child{border-bottom:0}'
      + '.tl-hit:hover{background:var(--panel-3)}'
      + '.tl-hit span{display:block;color:var(--text-mute);font-size:var(--fs-cap)}'
      + '.tl-sub{font-size:var(--fs-body);color:var(--text);margin:0 0 var(--sp-2);'
      + 'padding:8px 11px;border-radius:var(--r-sm);background:var(--panel-2);'
      + 'border:1px solid var(--border)}'
      + '#tlPvText{max-height:300px;overflow:auto;white-space:pre-wrap;margin-top:var(--sp-3)}'
      + '.tl-issue{align-items:flex-start}'
      + '.tl-issue .k{color:var(--text-dim);line-height:1.5}';
    var tag = document.createElement('style');
    tag.id = 'tlStyle';
    tag.textContent = css;
    document.head.appendChild(tag);
  }

  /* ------------------------------------------------------------------ the library */

  /**
   * The whole library, unfiltered, indexed by slug.
   *
   * Unfiltered on purpose even though the endpoint takes a type: the table above
   * has its own filter, and fetching the same subset would tie this file to a
   * control it does not own and refetch on every change of it. One call answers
   * every filter state. Slug is the key rather than id because id only appears on
   * screen inside an Edit button, which a role without TEMPLATES_WRITE never gets,
   * and slug is unique by a database constraint.
   */
  function load() {
    if (loading) return Promise.resolve();
    loading = true;
    return getJson(LIBRARY).then(function (rows) {
      loading = false;
      loadFailed = false;
      health = {};
      if (Array.isArray(rows)) rows.forEach(function (r) { if (r && r.slug) health[r.slug] = r; });
      paint();
    }).catch(function (e) {
      loading = false;
      // Marked rather than retried in a loop. The table above is already drawn and
      // still useful, so this degrades to the six columns that were always there.
      loadFailed = true;
      say(e.message || 'Could not read the template library', 'err');
      paint();
    });
  }

  /* ------------------------------------------------------------------ the column */

  function cellHtml(row) {
    var share = Math.min(100, Math.round((row.renderedBytes / CLIP_BYTES) * 100));
    var tone = row.overGmailClip ? ' bad' : (share >= 70 ? ' warn' : '');
    var flags = '';

    // plain suppresses the pill's own dot. This is the one pill on the row carrying
    // an icon, and a dot next to a warning triangle is two marks for one meaning.
    if (row.overGmailClip) {
      flags += '<span class="pill pill-pending plain">' + icon('i-warn', 'ic-sm')
        + ' Gmail clips this</span>';
    }
    // A marketing template whose rendered output carries no unsubscribe link is a
    // compliance problem rather than a style note, so it is stated on the row and
    // not left for somebody to find inside the inspector.
    if (row.hasUnsubscribeLink === false && row.type !== 'TRANSACTIONAL') {
      flags += '<span class="pill pill-failed">No unsubscribe</span>';
    }
    if (row.unresolvedFields && row.unresolvedFields.length) {
      flags += '<span class="pill pill-pending">' + row.unresolvedFields.length
        + (row.unresolvedFields.length === 1 ? ' field renders empty' : ' fields render empty')
        + '</span>';
    }

    return '<span class="tl-size">' + escape(kb(row.renderedBytes)) + '</span>'
      + '<div class="tl-bar' + tone + '"><i style="width:' + share + '%"></i></div>'
      + (flags ? '<div class="tl-flags">' + flags + '</div>' : '')
      + '<button class="btn btn-sm" data-tl-inspect="' + escape(row.slug) + '">'
      + icon('i-eye', 'ic-sm') + ' Inspect</button>';
  }

  /**
   * Writes the extra cell into every row console.js has just drawn.
   *
   * console.js owns #templateBody and replaces its innerHTML wholesale on every
   * load, filter change, save and delete, so there is no callback to hang this on
   * and no row to decorate until it has run. Watching the tbody is what makes a
   * single script tag genuinely enough: whatever repaints the table, the column
   * comes back with it.
   *
   * The observer is disconnected around its own writes. Inserting a cell is itself
   * a childList mutation on that subtree, so leaving it connected would queue a
   * callback for work this function had just finished doing.
   */
  function paint() {
    var body = el('templateBody');
    if (!body) return;
    if (observer) observer.disconnect();
    try {
      var rows = body.querySelectorAll('tr');
      for (var i = 0; i < rows.length; i++) paintRow(rows[i]);
    } finally {
      if (observer) observer.observe(body, { childList: true });
    }

    // Fetched only once something on screen actually wants it. A console opened on
    // the Overview and never taken to Templates never calls this endpoint.
    if (!loading && !loadFailed && body.querySelector('tr[data-tl="wanting"]')) load();
  }

  function paintRow(row) {
    if (row.getAttribute('data-tl') === 'done') return;

    // The empty, error and loading states are one cell spanning the table, and the
    // table has just grown a column. Widening that span is the difference between
    // those three states staying centred and each one sitting off to the left with
    // an empty cell beside it.
    var spanning = row.querySelector('td[colspan]');
    if (spanning) {
      spanning.setAttribute('colspan', '7');
      row.setAttribute('data-tl', 'done');
      return;
    }

    if (row.hasAttribute('data-skeleton')) {
      if (row.children.length === 6) {
        var bone = document.createElement('td');
        bone.innerHTML = '<span class="skeleton" style="height:11px;width:60%"></span>';
        row.insertBefore(bone, row.lastElementChild);
      }
      row.setAttribute('data-tl', 'done');
      return;
    }

    // A row painted before the fetch answered already carries this file's own cell,
    // and that one is filled in place rather than counted as a seventh column.
    var existing = row.querySelector('td.tl-cell');

    // Six cells is the shape console.js writes. Anything else is a table this file
    // does not recognise, and a wrong cell added to it is worse than no cell at all.
    if (row.children.length - (existing ? 1 : 0) !== 6) {
      row.setAttribute('data-tl', 'done');
      return;
    }

    var slug = (row.children[1].textContent || '').trim();
    var data = health[slug];
    var cell = existing || document.createElement('td');
    cell.className = 'tl-cell';
    // Below 760px the table becomes cards and every cell is named by its own
    // data-label, because the header row is display:none there.
    cell.setAttribute('data-label', 'Deliverability');
    cell.innerHTML = data ? cellHtml(data)
      : '<span style="color:var(--text-mute)">' + (loadFailed ? 'unavailable' : 'checking') + '</span>';
    // Ahead of the actions cell, to match where the header went.
    if (!existing) row.insertBefore(cell, row.lastElementChild);
    // A row is only finished once real data has landed in it, so the fetch that
    // follows the first paint has something to come back to.
    row.setAttribute('data-tl', data ? 'done' : 'wanting');
  }

  /* ------------------------------------------------------------------ the inspector */

  function issueRow(issue) {
    var pill = issue.severity === 'ERROR' ? 'pill-failed'
      : (issue.severity === 'WARN' ? 'pill-pending' : 'pill-draft');
    return '<div class="health-row tl-issue"><span class="k">' + escape(issue.message) + '</span>'
      + '<span class="v"><span class="pill ' + pill + '">' + escape(issue.severity) + '</span></span></div>';
  }

  function fieldChips(fields, unresolved) {
    if (!fields || !fields.length) return '<span style="color:var(--text-mute)">No merge fields.</span>';
    var missing = {};
    (unresolved || []).forEach(function (f) { missing[f] = true; });
    var chips = fields.map(function (f) {
      return '<span class="chip static"' + (missing[f]
        ? ' style="color:var(--warning-fg);border-color:rgba(217,158,11,.45)"'
          + ' title="A campaign send does not fill this one"'
        : '') + '>' + escape(f) + '</span>';
    }).join(' ');
    // The colour has to be named somewhere. Without this line an amber chip is a
    // decoration, and the reader has to guess which of the two states is the bad one.
    return chips + ((unresolved && unresolved.length)
      ? '<p class="tl-note">Amber fields are not filled by a campaign send.</p>' : '');
  }

  function openInspector(slug) {
    current = health[slug];
    if (!current) { say('That template has not been measured yet', 'warn'); return; }
    chosen = null;
    el('tlPvName').textContent = current.name;
    el('tlPvSearch').value = '';
    el('tlPvHits').innerHTML = '';
    el('tlPvText').hidden = true;
    el('tlPvTextBtn').textContent = 'Show plain text';
    el('tlPvUse').hidden = !allowed('CAMPAIGNS_WRITE');
    show('modalTemplateHealth');
    render();
  }

  /** Refetches the preview for whichever subscriber is currently chosen. */
  function render() {
    if (!current) return;
    var url = LIBRARY + '/' + current.id + '/preview'
      + (chosen ? '?subscriberId=' + encodeURIComponent(chosen.id) : '');
    el('tlPvSubject').innerHTML = '<span style="color:var(--text-mute)">Rendering...</span>';
    getJson(url).then(function (p) {
      var v = p.validation || {};
      var limit = v.limitBytes || CLIP_BYTES;
      // The bar is capped and the number is not. Printing "100%" over a message that
      // is 113% of the limit is the one number on this panel that has to be true.
      var share = Math.round((v.renderedBytes / limit) * 100);
      var tone = v.overGmailClip ? ' bad' : (share >= 70 ? ' warn' : '');

      el('tlPvSize').innerHTML =
        '<div class="tl-big">' + escape(kb(v.renderedBytes)) + '</div>'
        + '<div class="tl-bar' + tone + '" style="max-width:none"><i style="width:'
        + Math.min(100, share) + '%"></i></div>'
        + '<p class="tl-note">' + share + '% of the ' + escape(kb(limit))
        + ' at which Gmail replaces the rest with a "View entire message" link.</p>'
        + (v.overGmailClip
          ? '<div class="tl-warnbox">Gmail will clip this message. Everything past the cut is hidden '
            + 'behind a link most people never open, and the unsubscribe footer is at the end.</div>'
          : '')
        + (v.hasUnsubscribeLink === false && current.type !== 'TRANSACTIONAL'
          ? '<div class="tl-warnbox tl-badbox">The rendered email has no unsubscribe link. '
            + 'Do not send this.</div>'
          : '');

      el('tlPvFields').innerHTML = fieldChips(v.mergeFields, v.unresolvedFields);
      el('tlPvIssues').innerHTML = (v.issues && v.issues.length)
        ? v.issues.map(issueRow).join('')
        : '<div class="health-row"><span class="k">Nothing to flag.</span>'
          + '<span class="v"><span class="pill pill-sent">Clear</span></span></div>';

      var who = (p.sample && p.sample.describedAs) ? p.sample.describedAs : 'sample data';
      el('tlPvSubject').innerHTML = '<b>' + escape(p.subject || '(no subject)') + '</b>'
        + '<div style="font-size:var(--fs-sm);color:var(--text-mute);margin-top:3px">rendered for '
        + escape(who) + '</div>';
      // Sandboxed and pinned light, the way the composer previews a creative. The
      // body is author supplied HTML and this console's CSP allows inline script.
      el('tlPvFrame').srcdoc = frameDocument(p.html || '');
      el('tlPvText').textContent = p.plainText || '';
    }).catch(function (e) {
      el('tlPvSubject').innerHTML = '<span style="color:var(--danger-fg)">'
        + escape(e.message || 'Could not render that preview.') + '</span>';
    });
  }

  /**
   * Looks up a real subscriber to render against.
   *
   * Hidden outright without SUBSCRIBERS_READ rather than shown and left to fail.
   * The preview endpoint refuses a subscriberId to a caller who lacks that
   * permission, on purpose: HR holds TEMPLATES_READ and was deliberately denied the
   * subscriber base, and a preview echoes back the name and address of whoever it
   * rendered for. A search box that 403s on every keystroke would read as broken
   * and would also be the wrong thing to offer.
   */
  function searchSubscribers() {
    var q = el('tlPvSearch').value.trim();
    var hits = el('tlPvHits');
    if (q.length < 2) { hits.innerHTML = ''; return; }
    getJson(SUBSCRIBERS + '?q=' + encodeURIComponent(q) + '&size=6').then(function (d) {
      var rows = (d && d.rows) || [];
      hits.innerHTML = rows.length ? rows.map(function (s) {
        return '<button type="button" class="tl-hit" data-tl-who="' + escape(s.id) + '">'
          + escape(s.name || s.email) + '<span>' + escape(s.email) + '</span></button>';
      }).join('') : '<div style="padding:8px 10px;color:var(--text-mute);font-size:var(--fs-sm)">'
        + 'Nobody matched.</div>';
    }).catch(function () {
      hits.innerHTML = '<div style="padding:8px 10px;color:var(--danger-fg);font-size:var(--fs-sm)">'
        + 'Could not search subscribers.</div>';
    });
  }

  /* ------------------------------------------------------------------ campaigns */

  function fillCampaignPicker(selectId, editableOnly) {
    var select = el(selectId);
    if (!select) return Promise.resolve();
    select.innerHTML = '<option value="">Loading...</option>';
    return getJson(CAMPAIGNS).then(function (rows) {
      var list = (rows || []).filter(function (c) {
        // applyToCampaign refuses anything sending or already sent, so offering
        // those would be offering a button whose only outcome is an error.
        return !editableOnly || c.status === 'DRAFT' || c.status === 'PAUSED';
      });
      select.innerHTML = list.length
        ? list.map(function (c) {
          return '<option value="' + escape(c.id) + '">' + escape(c.name)
            + ' - ' + escape(String(c.status).toLowerCase()) + '</option>';
        }).join('')
        : '<option value="">No campaign this can apply to</option>';
    }).catch(function () {
      select.innerHTML = '<option value="">Could not load campaigns</option>';
    });
  }

  function openFromCampaign() {
    el('tlFcName').value = '';
    el('tlFcDesc').value = '';
    show('modalTemplateFromCampaign');
    fillCampaignPicker('tlFcCampaign', false);
  }

  function saveFromCampaign() {
    var campaignId = el('tlFcCampaign').value;
    var name = el('tlFcName').value.trim();
    if (!campaignId) { say('Choose a campaign', 'warn'); return; }
    if (!name) { say('Name the template', 'warn'); return; }
    postForm(FROM_CAMPAIGN, { campaignId: campaignId, name: name, description: el('tlFcDesc').value })
      .then(function (r) {
        say(r.message || 'Saved to the template library.', 'ok');
        hide('modalTemplateFromCampaign');
        refreshTable();
      }).catch(function (e) { say(e.message, 'err'); });
  }

  function openApply() {
    if (!current) return;
    el('tlApTemplate').textContent = current.name;
    show('modalTemplateApply');
    fillCampaignPicker('tlApCampaign', true);
  }

  function applyToCampaign() {
    if (!current) return;
    var campaignId = el('tlApCampaign').value;
    if (!campaignId) { say('Choose a campaign', 'warn'); return; }
    postForm(APPLY, {
      templateId: current.id, campaignId: campaignId,
      overwriteSubject: el('tlApSubject').checked
    }).then(function (r) {
      say(r.message || 'Template loaded.', 'ok');
      hide('modalTemplateApply');
      hide('modalTemplateHealth');
    }).catch(function (e) { say(e.message, 'err'); });
  }

  /** A write changes what both the table and the column say, so both are redrawn. */
  function refreshTable() {
    health = {};
    load();
    if (typeof loadTemplates === 'function') loadTemplates();
  }

  /* ------------------------------------------------------------------ markup */

  function buildModals() {
    if (el('modalTemplateHealth')) return;
    var wrap = document.createElement('div');
    wrap.innerHTML =
      '<div class="modal-backdrop" id="modalTemplateHealth"><div class="modal wide">'
      + '<div class="modal-head">' + icon('i-template') + '<h3 id="tlPvName">Template</h3></div>'
      + '<div class="modal-body"><div class="tl-grid">'
      + '<div>'
      + '<div class="tl-block"><p class="tl-h">Rendered size</p><div id="tlPvSize"></div></div>'
      + '<div class="tl-block"><p class="tl-h">Merge fields</p><div id="tlPvFields"></div></div>'
      + '<div class="tl-block"><p class="tl-h">Checks</p><div id="tlPvIssues"></div></div>'
      + '</div>'
      + '<div>'
      + '<div class="tl-block" id="tlPvWhoBlock"><p class="tl-h">Preview as</p>'
      + '<div class="tl-who">'
      + '<input class="input" id="tlPvSearch" placeholder="Search a real subscriber">'
      + '<button class="btn btn-sm" id="tlPvSample">Sample data</button></div>'
      + '<div class="tl-hits" id="tlPvHits"></div></div>'
      + '<div class="tl-sub" id="tlPvSubject"></div>'
      + '<div class="preview-shell"><iframe class="preview-frame" id="tlPvFrame" sandbox=""'
      + ' title="Rendered template"></iframe></div>'
      + '<pre class="code" id="tlPvText" hidden></pre>'
      + '</div></div></div>'
      + '<div class="modal-foot">'
      + '<button class="btn" id="tlPvTextBtn">Show plain text</button>'
      + '<button class="btn btn-primary" id="tlPvUse">Use in a campaign</button>'
      + '<button class="btn" id="tlPvClose">Close</button>'
      + '</div></div></div>'

      + '<div class="modal-backdrop" id="modalTemplateFromCampaign"><div class="modal">'
      + '<div class="modal-head">' + icon('i-save') + '<h3>Save a campaign as a template</h3></div>'
      + '<div class="modal-body">'
      + '<label class="field"><span>Campaign</span><select class="input" id="tlFcCampaign"></select></label>'
      + '<label class="field"><span>Template name</span>'
      + '<input class="input" id="tlFcName" placeholder="April patient digest"></label>'
      + '<label class="field"><span>Description <em class="hint">optional</em></span>'
      + '<input class="input" id="tlFcDesc" placeholder="What this creative is for"></label>'
      + '</div><div class="modal-foot">'
      + '<button class="btn" id="tlFcCancel">Cancel</button>'
      + '<button class="btn btn-primary" id="tlFcSave">Save to library</button>'
      + '</div></div></div>'

      + '<div class="modal-backdrop" id="modalTemplateApply"><div class="modal">'
      + '<div class="modal-head">' + icon('i-campaign') + '<h3>Load into a campaign</h3></div>'
      + '<div class="modal-body">'
      + '<p class="tl-note" style="margin:0 0 var(--sp-3)">Copies <b id="tlApTemplate"></b> into a '
      + 'draft campaign. Only campaigns that can still be edited are listed.</p>'
      + '<label class="field"><span>Campaign</span><select class="input" id="tlApCampaign"></select></label>'
      + '<label style="display:flex;gap:8px;align-items:center;font-size:var(--fs-body);'
      + 'color:var(--text-dim)"><input type="checkbox" id="tlApSubject" checked>'
      + ' Overwrite the campaign subject too</label>'
      + '</div><div class="modal-foot">'
      + '<button class="btn" id="tlApCancel">Cancel</button>'
      + '<button class="btn btn-primary" id="tlApGo">Load it</button>'
      + '</div></div></div>';

    while (wrap.firstChild) document.body.appendChild(wrap.firstChild);

    el('tlPvClose').addEventListener('click', function () { hide('modalTemplateHealth'); });
    el('tlPvUse').addEventListener('click', openApply);
    el('tlPvTextBtn').addEventListener('click', function () {
      var pre = el('tlPvText');
      pre.hidden = !pre.hidden;
      this.textContent = pre.hidden ? 'Show plain text' : 'Hide plain text';
    });
    el('tlPvSample').addEventListener('click', function () {
      chosen = null;
      el('tlPvSearch').value = '';
      el('tlPvHits').innerHTML = '';
      render();
    });
    el('tlPvSearch').addEventListener('input', function () {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(searchSubscribers, 250);
    });
    // Delegated, because the results are rebuilt on every keystroke that answers.
    el('tlPvHits').addEventListener('click', function (e) {
      var hit = e.target.closest('[data-tl-who]');
      if (!hit) return;
      chosen = { id: hit.getAttribute('data-tl-who') };
      el('tlPvSearch').value = (hit.firstChild && hit.firstChild.textContent
        ? hit.firstChild.textContent : '').trim();
      el('tlPvHits').innerHTML = '';
      render();
    });

    el('tlFcCancel').addEventListener('click', function () { hide('modalTemplateFromCampaign'); });
    el('tlFcSave').addEventListener('click', saveFromCampaign);
    el('tlApCancel').addEventListener('click', function () { hide('modalTemplateApply'); });
    el('tlApGo').addEventListener('click', applyToCampaign);

    // Without SUBSCRIBERS_READ the preview endpoint will not accept a subscriber id
    // at all, so the whole picker goes rather than sitting there as a dead control.
    if (!allowed('SUBSCRIBERS_READ')) el('tlPvWhoBlock').hidden = true;
  }

  /* ------------------------------------------------------------------ mounting */

  /**
   * Adds the column head, the topbar button and the row watcher.
   *
   * Everything hangs off #view-templates rather than off document, so a console
   * whose Templates screen has moved or been renamed gets nothing at all rather
   * than a column welded onto whichever table was found first.
   */
  function mount() {
    var view = el('view-templates');
    if (!view || el('tlHead')) return;

    var headRow = view.querySelector('table.data thead tr');
    if (!headRow) return;
    var th = document.createElement('th');
    th.id = 'tlHead';
    th.textContent = 'Deliverability';
    // Second to last, not last. The final column is the unlabelled actions column,
    // and Edit and Delete sitting in the middle of a row reads as a layout fault;
    // below 760px the row becomes a card and the stylesheet gives td.actions the
    // trailing border treatment, which only looks right while it is still trailing.
    headRow.insertBefore(th, headRow.lastElementChild);

    // saveCampaignAsTemplate writes a template and reads a campaign, so it needs
    // both permissions. HR holds TEMPLATES_WRITE and no campaign permission at all,
    // and would otherwise get a button whose only possible outcome is an empty list.
    if (allowed('TEMPLATES_WRITE') && allowed('CAMPAIGNS_READ')) {
      var actions = view.querySelector('.topbar-actions');
      if (actions) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn';
        btn.id = 'tlFromCampaign';
        btn.innerHTML = icon('i-save', 'ic-sm') + ' From a campaign';
        btn.addEventListener('click', openFromCampaign);
        // Before New template, because the filled button is the primary action on
        // this screen and a secondary one placed after it reads as its overflow.
        actions.insertBefore(btn, actions.firstChild);
      }
    }

    buildModals();

    var body = el('templateBody');
    if (!body) return;
    // One delegated listener on the tbody rather than a handler per button. The
    // buttons are destroyed and rebuilt on every repaint of the table; the tbody
    // element itself survives all of them.
    body.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-tl-inspect]');
      if (btn) openInspector(btn.getAttribute('data-tl-inspect'));
    });
    observer = new MutationObserver(function () { paint(); });
    observer.observe(body, { childList: true });
    paint();
  }

  function start() {
    injectStyle();
    mount();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();

  /* What the rest of the console may use. Nothing calls this today; it is here so a
     future screen can ask for a verdict without growing a second copy of the call. */
  window.TemplateLibrary = {
    reload: load,
    verdictFor: function (slug) { return health[slug] || null; },
    inspect: openInspector
  };
})();
