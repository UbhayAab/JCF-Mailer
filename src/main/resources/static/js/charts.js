/* ==========================================================================
   Charts

   Hand-rolled SVG. The box must render with zero external requests, so a chart
   library is not an option, and these seven shapes cover every screen we have.

   Every function takes a container element and replaces its contents. All of
   them draw a labelled empty state when there is no data, because the failure
   this replaces was a chart frame that silently painted nothing and read as a
   broken page rather than as "no sends yet".

   Called today: drawDonut and drawArea, from console.js. drawBars, drawGauge and
   drawSpark are drawn by nothing, and so are drawStackedShare and drawCompareBars
   at the bottom of this file - those two exist for the classifier bucket table
   and the campaign comparison, both of which are data /api/analytics already
   returns and no screen reads. Mounting them is a console.html and console.js
   change; the contract is written above each function.
   ========================================================================== */

/* The fourth entry was #8b5cf6, a violet, which contradicts the one colour rule the
   design system actually states: flat charcoal and one accent, never navy, never
   purple. It was legible, so no contrast pass ever flagged it; it was simply not part
   of the palette. Replaced with a slate that reads as a distinct series without
   introducing a hue the product uses nowhere else. */
const CHART_COLORS = ['#2f6fed', '#19a7a0', '#d99e0b', '#7f93a3', '#e0483c', '#1f9d55'];

function chartEmpty(el, message) {
  el.innerHTML = '<div class="chart-empty">' + escapeHtml(message || 'No data yet') + '</div>';
}

/* charts.js loads before console.js, so it cannot borrow esc() from there. */
function escapeHtml(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function svgEl(tag, attrs) {
  const n = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const k in attrs) n.setAttribute(k, attrs[k]);
  return n;
}

function chartNum(n) {
  const v = Number(n || 0);
  if (v >= 1000000) return (v / 1000000).toFixed(v >= 10000000 ? 0 : 1) + 'M';
  if (v >= 1000) return (v / 1000).toFixed(v >= 10000 ? 0 : 1) + 'k';
  return String(Math.round(v));
}

/* --------------------------------------------------------------------------
   Donut. segments: [{label, value, color}]
   -------------------------------------------------------------------------- */
function drawDonut(el, segments, opts) {
  opts = opts || {};
  const data = (segments || []).filter(s => Number(s.value) > 0);
  const total = data.reduce((a, s) => a + Number(s.value), 0);
  if (!total) { chartEmpty(el, opts.empty); return; }

  const size = opts.size || 190;
  const thickness = opts.thickness || 26;
  const r = (size - thickness) / 2;
  const cx = size / 2, cy = size / 2;
  const circumference = 2 * Math.PI * r;

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + size + ' ' + size, width: size, height: size, class: 'chart-donut'
  });

  // Track first, so a single tiny segment still reads as a proportion of a whole.
  svg.appendChild(svgEl('circle', {
    cx: cx, cy: cy, r: r, fill: 'none',
    stroke: 'rgba(255,255,255,0.06)', 'stroke-width': thickness
  }));

  let offset = 0;
  data.forEach((s, i) => {
    const frac = Number(s.value) / total;
    const arc = svgEl('circle', {
      cx: cx, cy: cy, r: r, fill: 'none',
      stroke: s.color || CHART_COLORS[i % CHART_COLORS.length],
      'stroke-width': thickness,
      'stroke-dasharray': (frac * circumference) + ' ' + circumference,
      'stroke-dashoffset': -offset,
      transform: 'rotate(-90 ' + cx + ' ' + cy + ')'
    });
    arc.appendChild(svgEl('title', {})).textContent =
      s.label + ': ' + Number(s.value).toLocaleString('en-IN')
      + ' (' + (frac * 100).toFixed(1) + '%)';
    svg.appendChild(arc);
    offset += frac * circumference;
  });

  const big = svgEl('text', {
    x: cx, y: cy - 2, 'text-anchor': 'middle', class: 'chart-donut-value'
  });
  big.textContent = opts.centerValue !== undefined ? opts.centerValue : chartNum(total);
  svg.appendChild(big);
  if (opts.centerLabel) {
    const small = svgEl('text', {
      x: cx, y: cy + 16, 'text-anchor': 'middle', class: 'chart-donut-label'
    });
    small.textContent = opts.centerLabel;
    svg.appendChild(small);
  }

  const legend = document.createElement('div');
  legend.className = 'chart-legend';
  legend.innerHTML = data.map((s, i) =>
    '<span class="chart-legend-item"><i style="background:'
    + (s.color || CHART_COLORS[i % CHART_COLORS.length]) + '"></i>'
    + escapeHtml(s.label) + ' <b>' + Number(s.value).toLocaleString('en-IN') + '</b></span>'
  ).join('');

  el.innerHTML = '';
  const wrap = document.createElement('div');
  wrap.className = 'chart-donut-wrap';
  wrap.appendChild(svg);
  wrap.appendChild(legend);
  el.appendChild(wrap);
}

/* --------------------------------------------------------------------------
   Area / line chart.
   series: [{label, color, points:[{x, y}]}]   x is a label, y a number.
   -------------------------------------------------------------------------- */
function drawArea(el, series, opts) {
  opts = opts || {};
  const live = (series || []).filter(s => s.points && s.points.length);
  if (!live.length) { chartEmpty(el, opts.empty); return; }

  // One SVG unit per CSS pixel, for the same reason as the analytics chart: a
  // fixed wide viewBox on a narrow screen shrinks the axis text along with
  // everything else, and no stylesheet can reach inside to undo it.
  const W = Math.max(320, Math.round(el.clientWidth || 900));
  const H = opts.height || 260;
  const padL = 46, padR = 14, padT = 14, padB = 30;
  const plotW = W - padL - padR, plotH = H - padT - padB;

  const n = Math.max(...live.map(s => s.points.length));
  let max = Math.max(...live.flatMap(s => s.points.map(p => Number(p.y) || 0)));
  if (max <= 0) max = 1;
  // Round the ceiling up to something a human would pick, so gridline labels are
  // readable numbers rather than 0, 3271, 6542.
  const mag = Math.pow(10, Math.floor(Math.log10(max)));
  max = Math.ceil(max / (mag / 2)) * (mag / 2);

  const X = i => padL + (n === 1 ? plotW / 2 : (i / (n - 1)) * plotW);
  const Y = v => padT + plotH - (Number(v || 0) / max) * plotH;

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + W + ' ' + H,
    class: 'chart-area', width: '100%', height: H
  });

  // Gridlines and y labels.
  for (let g = 0; g <= 4; g++) {
    const v = (max / 4) * g, y = Y(v);
    svg.appendChild(svgEl('line', {
      x1: padL, y1: y, x2: W - padR, y2: y,
      stroke: 'rgba(255,255,255,0.06)', 'stroke-width': 1
    }));
    const t = svgEl('text', { x: padL - 8, y: y + 4, 'text-anchor': 'end', class: 'chart-axis' });
    t.textContent = chartNum(v);
    svg.appendChild(t);
  }

  live.forEach((s, si) => {
    const color = s.color || CHART_COLORS[si % CHART_COLORS.length];
    const pts = s.points.map((p, i) => X(i) + ',' + Y(p.y));

    const areaId = 'grad' + si + '-' + Math.abs(hashString(s.label || String(si)));
    const defs = svgEl('defs', {});
    const lg = svgEl('linearGradient', { id: areaId, x1: '0', y1: '0', x2: '0', y2: '1' });
    lg.appendChild(svgEl('stop', { offset: '0%', 'stop-color': color, 'stop-opacity': '0.28' }));
    lg.appendChild(svgEl('stop', { offset: '100%', 'stop-color': color, 'stop-opacity': '0' }));
    defs.appendChild(lg);
    svg.appendChild(defs);

    svg.appendChild(svgEl('polygon', {
      points: X(0) + ',' + (padT + plotH) + ' ' + pts.join(' ') + ' '
            + X(s.points.length - 1) + ',' + (padT + plotH),
      fill: 'url(#' + areaId + ')'
    }));
    svg.appendChild(svgEl('polyline', {
      points: pts.join(' '), fill: 'none', stroke: color,
      'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'
    }));

    // Dots only when the series is sparse; at 30 points they become noise.
    if (s.points.length <= 14) {
      s.points.forEach((p, i) => {
        const c = svgEl('circle', { cx: X(i), cy: Y(p.y), r: 3, fill: color });
        c.appendChild(svgEl('title', {})).textContent =
          (s.label ? s.label + ' - ' : '') + p.x + ': ' + Number(p.y || 0).toLocaleString('en-IN');
        svg.appendChild(c);
      });
    }
  });

  // X labels, thinned so they never collide.
  const first = live[0].points;
  const step = Math.max(1, Math.ceil(first.length / 8));
  first.forEach((p, i) => {
    if (i % step && i !== first.length - 1) return;
    const t = svgEl('text', {
      x: X(i), y: H - 10, 'text-anchor': 'middle', class: 'chart-axis'
    });
    t.textContent = p.x;
    svg.appendChild(t);
  });

  el.innerHTML = '';
  el.appendChild(svg);
  if (live.length > 1 || opts.legend) {
    const legend = document.createElement('div');
    legend.className = 'chart-legend';
    legend.innerHTML = live.map((s, i) =>
      '<span class="chart-legend-item"><i style="background:'
      + (s.color || CHART_COLORS[i % CHART_COLORS.length]) + '"></i>'
      + escapeHtml(s.label || ('Series ' + (i + 1))) + '</span>').join('');
    el.appendChild(legend);
  }
}

function hashString(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) { h = ((h << 5) - h + s.charCodeAt(i)) | 0; }
  return h;
}

/* --------------------------------------------------------------------------
   Horizontal bars. items: [{label, value, max, note, color}]
   -------------------------------------------------------------------------- */
function drawBars(el, items, opts) {
  opts = opts || {};
  const data = (items || []).filter(Boolean);
  if (!data.length) { chartEmpty(el, opts.empty); return; }

  const ceiling = opts.max || Math.max(...data.map(d => Number(d.max || d.value) || 0)) || 1;
  el.innerHTML = '<div class="chart-bars">' + data.map((d, i) => {
    const v = Number(d.value) || 0;
    const pct = Math.max(0, Math.min(100, (v / ceiling) * 100));
    const color = d.color || CHART_COLORS[i % CHART_COLORS.length];
    return '<div class="chart-bar-row">'
      + '<span class="chart-bar-label" title="' + escapeHtml(d.label) + '">'
      + escapeHtml(d.label) + '</span>'
      + '<span class="chart-bar-track"><i style="width:' + pct.toFixed(1)
      + '%;background:' + color + '"></i></span>'
      + '<span class="chart-bar-value">' + escapeHtml(d.note || chartNum(v)) + '</span>'
      + '</div>';
  }).join('') + '</div>';
}

/* --------------------------------------------------------------------------
   Half gauge, the shape Zoho uses for a compliance score. value is 0..100.
   -------------------------------------------------------------------------- */
function drawGauge(el, value, opts) {
  opts = opts || {};
  const v = Math.max(0, Math.min(100, Number(value) || 0));
  const W = 260, H = 150, cx = W / 2, cy = 128, r = 96, thickness = 18;

  const polar = (deg) => {
    const rad = (deg - 180) * Math.PI / 180;
    return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
  };
  const arc = (from, to, color) => {
    const [x1, y1] = polar(from), [x2, y2] = polar(to);
    return svgEl('path', {
      d: 'M ' + x1 + ' ' + y1 + ' A ' + r + ' ' + r + ' 0 0 1 ' + x2 + ' ' + y2,
      fill: 'none', stroke: color, 'stroke-width': thickness, 'stroke-linecap': 'butt'
    });
  };

  const svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, class: 'chart-gauge' });
  const bands = opts.bands || [
    [0, 25, '#e0483c'], [25, 50, '#d99e0b'], [50, 75, '#e3b341'], [75, 100, '#1f9d55']
  ];
  bands.forEach(b => svg.appendChild(arc((b[0] / 100) * 180, (b[1] / 100) * 180, b[2])));

  // Needle.
  const [nx, ny] = polar((v / 100) * 180);
  svg.appendChild(svgEl('line', {
    x1: cx, y1: cy, x2: nx, y2: ny,
    stroke: 'var(--text)', 'stroke-width': 3, 'stroke-linecap': 'round'
  }));
  svg.appendChild(svgEl('circle', { cx: cx, cy: cy, r: 5, fill: 'var(--text)' }));

  const label = svgEl('text', { x: cx, y: cy - 22, 'text-anchor': 'middle', class: 'chart-gauge-value' });
  label.textContent = opts.format ? opts.format(v) : (Math.round(v) + '%');
  svg.appendChild(label);

  el.innerHTML = '';
  el.appendChild(svg);
}

/* --------------------------------------------------------------------------
   Sparkline for a KPI card. values: [numbers]
   -------------------------------------------------------------------------- */
function drawSpark(el, values, opts) {
  opts = opts || {};
  const v = (values || []).map(Number).filter(x => !isNaN(x));
  if (v.length < 2) { el.innerHTML = ''; return; }

  const W = 120, H = 32, pad = 2;
  const max = Math.max(...v), min = Math.min(...v);
  const span = (max - min) || 1;
  const X = i => pad + (i / (v.length - 1)) * (W - pad * 2);
  const Y = x => pad + (1 - (x - min) / span) * (H - pad * 2);
  const color = opts.color || '#2f6fed';

  const pts = v.map((x, i) => X(i) + ',' + Y(x)).join(' ');
  const svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, class: 'chart-spark',
                             preserveAspectRatio: 'none' });
  svg.appendChild(svgEl('polyline', {
    points: pts, fill: 'none', stroke: color, 'stroke-width': 1.8,
    'stroke-linejoin': 'round', 'stroke-linecap': 'round'
  }));
  el.innerHTML = '';
  el.appendChild(svg);
}

/* --------------------------------------------------------------------------
   One hundred percent stacked share bar.
   segments: [{label, value, color, note}]

   The shape for a population cut into a few named buckets where the whole is
   the point. The classifier's answer is the case in mind: of every pixel load
   recorded, how much was a person and how much was Apple, a proxy or a scanner.
   A donut says the same thing, but the classifier's split is routinely 90/8/2,
   and a donut is at its worst exactly there - two of the three arcs become
   slivers with no room for a label. On one track a 2% band is still a visible
   2% of the width.

   Percentages print above a band only when the band is wide enough to hold
   them. Everything else is named in the legend, so a narrow band loses its
   number and never its identity.
   -------------------------------------------------------------------------- */
function drawStackedShare(el, segments, opts) {
  opts = opts || {};
  const data = (segments || []).filter(s => Number(s.value) > 0);
  const total = data.reduce((a, s) => a + Number(s.value), 0);
  if (!total) { chartEmpty(el, opts.empty); return; }

  // Same reason as drawArea: measure the box and make one SVG unit one CSS
  // pixel, or a fixed viewBox on a phone scales the labels down with the bar
  // and no stylesheet can reach in to undo it.
  const W = Math.max(280, Math.round(el.clientWidth || 720));
  const barH = opts.height || 30;
  const capH = 16;                 // the row of percentages sitting above the track
  const H = capH + barH;
  const radius = barH / 2;

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + W + ' ' + H, width: '100%', height: H, class: 'chart-area'
  });

  // Track first, so a rounding remainder shows as track rather than as a gap.
  svg.appendChild(svgEl('rect', {
    x: 0, y: capH, width: W, height: barH, rx: radius,
    fill: 'rgba(255,255,255,0.06)'
  }));

  const clip = 'stack-' + Math.abs(hashString(data.map(s => s.label).join('|')));
  const defs = svgEl('defs', {});
  const cp = svgEl('clipPath', { id: clip });
  cp.appendChild(svgEl('rect', { x: 0, y: capH, width: W, height: barH, rx: radius }));
  defs.appendChild(cp);
  svg.appendChild(defs);

  // Every band is drawn square and the whole run is clipped to the rounded
  // track. Rounding each band on its own would put a notch between neighbours.
  const bands = svgEl('g', { 'clip-path': 'url(#' + clip + ')' });
  const caps = svgEl('g', {});
  let x = 0;
  data.forEach((s, i) => {
    const frac = Number(s.value) / total;
    const w = frac * W;
    const color = s.color || CHART_COLORS[i % CHART_COLORS.length];
    const band = svgEl('rect', { x: x, y: capH, width: Math.max(1, w), height: barH, fill: color });
    band.appendChild(svgEl('title', {})).textContent =
      s.label + ': ' + Number(s.value).toLocaleString('en-IN')
      + ' (' + (frac * 100).toFixed(1) + '%)';
    bands.appendChild(band);

    // 44px is about what "12.3%" needs at the 12.5px the axis class grows to on
    // a narrow screen. Below that the number would print over its neighbour.
    if (w >= 44) {
      const t = svgEl('text', {
        x: x + w / 2, y: capH - 5, 'text-anchor': 'middle', class: 'chart-axis-sm'
      });
      t.textContent = (frac * 100).toFixed(frac * 100 >= 10 ? 0 : 1) + '%';
      caps.appendChild(t);
    }
    x += w;
  });
  svg.appendChild(bands);
  svg.appendChild(caps);

  el.innerHTML = '';
  el.appendChild(svg);

  const legend = document.createElement('div');
  legend.className = 'chart-legend';
  legend.style.justifyContent = 'flex-start';
  legend.innerHTML = data.map((s, i) =>
    '<span class="chart-legend-item" title="' + escapeHtml(s.note || s.label) + '"><i style="background:'
    + (s.color || CHART_COLORS[i % CHART_COLORS.length]) + '"></i>'
    + escapeHtml(s.label) + ' <b>' + Number(s.value).toLocaleString('en-IN') + '</b></span>'
  ).join('');
  el.appendChild(legend);
}

/* --------------------------------------------------------------------------
   Grouped horizontal bars, for comparing the same measures across rows.
   rows:   [{label, values:[n, ...], notes:[s, ...], id}]
   series: [{label, color}]   one entry per value in every row
   opts.onSelect(row)         makes each row a real button

   drawBars puts one bar on a row and reads a single measure down a list. This
   reads two or three measures across a list, which is the only way to see that
   the campaign with the best open rate is not the one that got clicked.

   A row is clickable when a caller passes onSelect, because a comparison you
   cannot open is a ranking nobody can act on - the same rule the segment tiles
   follow. The hit area is one transparent rect over the whole row rather than
   the bars themselves: a campaign with a 0% click rate has a bar one pixel
   wide, and that must not be the thing you have to hit.
   -------------------------------------------------------------------------- */
function drawCompareBars(el, rows, series, opts) {
  opts = opts || {};
  const data = (rows || []).filter(r => r && r.values && r.values.length);
  const cols = (series || []).filter(Boolean);
  if (!data.length || !cols.length) { chartEmpty(el, opts.empty); return; }

  const W = Math.max(300, Math.round(el.clientWidth || 760));
  // The label column takes a third of a wide box but never more than 190px, so
  // a long campaign name cannot squeeze the bars it is supposed to explain.
  const labelW = Math.max(84, Math.min(190, Math.round(W * 0.32)));
  const valueW = 52;
  const barH = cols.length > 2 ? 9 : 11;
  const barGap = 3;
  const rowPad = 13;
  const rowH = cols.length * (barH + barGap) - barGap + rowPad;
  const padT = 6, axisH = 22;
  const plotW = Math.max(40, W - labelW - valueW);
  const H = padT + data.length * rowH + axisH;

  // The ceiling is chosen from the step up, not the other way round, which is the
  // same defect drawSeries records: a window where every campaign sits under 1%
  // took a ceiling of 1, divided it by four, and printed the axis as
  // 0, 0, 1, 1, 1. Picking a whole-number step first and multiplying back means
  // four gridlines can never carry the same label twice.
  const observed = Math.max(...data.flatMap(r => r.values.map(v => Number(v) || 0)));
  const max = opts.max || chartStep(observed <= 0 ? 1 : observed, 4) * 4;

  const X = v => labelW + (Math.max(0, Number(v) || 0) / max) * plotW;

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + W + ' ' + H, width: '100%', height: H, class: 'chart-area'
  });

  // Gridlines behind everything, and the scale printed once at the bottom. The
  // lines stay at four divisions whatever the width, because a line cannot
  // collide with its neighbour; only the labels are thinned. Measured on a 320px
  // window, five ticks of "60%" at the 11.5px the axis class grows to under the
  // phone breakpoint left a 4px gap, which is a gap in arithmetic and a collision
  // to read.
  const tickEvery = plotW < 240 ? 2 : 1;
  for (let g = 0; g <= 4; g++) {
    const v = (max / 4) * g, gx = X(v);
    svg.appendChild(svgEl('line', {
      x1: gx, y1: padT, x2: gx, y2: padT + data.length * rowH,
      stroke: 'rgba(255,255,255,0.06)', 'stroke-width': 1
    }));
    if (g % tickEvery) continue;
    const t = svgEl('text', {
      x: gx, y: H - 7, 'text-anchor': g === 0 ? 'start' : (g === 4 ? 'end' : 'middle'),
      class: 'chart-axis-sm'
    });
    t.textContent = opts.formatAxis ? opts.formatAxis(v) : chartNum(v);
    svg.appendChild(t);
  }

  data.forEach((r, ri) => {
    const top = padT + ri * rowH;
    const g = svgEl('g', {});

    // Every text node here carries a class rather than a font-size attribute.
    // style.css drops the axis classes to a larger size under max-width:900px,
    // and a media query cannot reach a presentation attribute on an SVG node,
    // so a hand-set font-size would simply refuse to respond on a phone.
    const label = svgEl('text', {
      x: 0, y: top + rowH / 2 - 2, 'dominant-baseline': 'middle', class: 'chart-axis'
    });
    label.textContent = chartClip(r.label, labelW - 10);
    label.appendChild(svgEl('title', {})).textContent = r.label;
    g.appendChild(label);

    r.values.forEach((v, ci) => {
      if (ci >= cols.length) return;
      const y = top + ci * (barH + barGap);
      const color = cols[ci].color || CHART_COLORS[ci % CHART_COLORS.length];
      const w = Math.max(1, X(v) - labelW);
      const bar = svgEl('rect', {
        x: labelW, y: y, width: w, height: barH, rx: Math.min(3, barH / 2), fill: color
      });
      bar.appendChild(svgEl('title', {})).textContent =
        r.label + ' - ' + cols[ci].label + ': '
        + ((r.notes && r.notes[ci]) || Number(v || 0).toLocaleString('en-IN'));
      g.appendChild(bar);
    });

    // One value column, showing the first measure, because that is the one the
    // rows are ordered by and reading it off the axis is guesswork.
    const val = svgEl('text', {
      x: W, y: top + rowH / 2 - 2, 'text-anchor': 'end',
      'dominant-baseline': 'middle', class: 'chart-axis'
    });
    val.textContent = (r.notes && r.notes[0]) || chartNum(r.values[0]);
    g.appendChild(val);

    if (opts.onSelect) {
      const hit = svgEl('rect', {
        x: 0, y: top - 3, width: W, height: rowH - 2, rx: 4,
        fill: 'rgba(0,0,0,0)', style: 'cursor:pointer',
        role: 'button', tabindex: '0'
      });
      hit.appendChild(svgEl('title', {})).textContent = 'Open ' + r.label;
      // No stylesheet reaches these generated nodes, so hover and focus are
      // painted here. Without a visible target the row reads as decoration and
      // nobody discovers it is clickable.
      const lit = on => hit.setAttribute('fill', on ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0)');
      hit.addEventListener('mouseenter', () => lit(true));
      hit.addEventListener('mouseleave', () => lit(false));
      hit.addEventListener('focus', () => lit(true));
      hit.addEventListener('blur', () => lit(false));
      hit.addEventListener('click', () => opts.onSelect(r));
      hit.addEventListener('keydown', e => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); opts.onSelect(r); }
      });
      g.appendChild(hit);
    }
    svg.appendChild(g);
  });

  el.innerHTML = '';
  el.appendChild(svg);

  const legend = document.createElement('div');
  legend.className = 'chart-legend';
  legend.style.justifyContent = 'flex-start';
  legend.innerHTML = cols.map((c, i) =>
    '<span class="chart-legend-item"><i style="background:'
    + (c.color || CHART_COLORS[i % CHART_COLORS.length]) + '"></i>'
    + escapeHtml(c.label) + '</span>').join('');
  el.appendChild(legend);
}

/** The smallest round step at or above max/divisions, so every gridline lands on a
    whole number. Rounding is always up, never to nearest, because a step below
    max/divisions would put the top gridline under the tallest bar. */
function chartStep(max, divisions) {
  const raw = max / divisions;
  const mag = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
  for (const m of [1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) {
    if (m * mag >= raw) return Math.max(1, Math.ceil(m * mag));
  }
  return Math.max(1, Math.ceil(raw));
}

/** SVG has no text-overflow, so a long label has to be cut before it is drawn.
    The width per character is estimated against the larger of the two sizes the
    axis class takes, so the phone breakpoint cannot push a name past its column;
    the full text stays on the node's title. */
function chartClip(text, maxPx) {
  const s = String(text === null || text === undefined ? '' : text);
  // 6.9px per character is the width at 12.5px, the larger of the two sizes the
  // axis class takes, so the estimate is generous at 11px and never short at the
  // phone breakpoint. Three dots rather than the single ellipsis character: no
  // other file in static/js uses one, and this is not the place to start.
  const per = 6.9;
  const room = Math.max(4, Math.floor(maxPx / per));
  return s.length <= room ? s : s.slice(0, room - 3) + '...';
}
