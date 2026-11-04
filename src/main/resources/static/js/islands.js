// The two React parts of the manager's screens: the counter tiles on the dashboard and the
// resolution-time chart on Analytics. Everything else stays server-rendered by Thymeleaf.
//
// How a part gets onto the page: the template writes an empty element with an id, plus a
// <script type="application/json"> holding the numbers it already has. This file finds that pair,
// reads the JSON and lets React draw into the element. There is no extra API call and no build step:
// React, ReactDOM and htm are plain files in /js/vendor, and htm's html`...` stands in for JSX.
// The Content-Security-Policy forbids inline script, which is why this lives in its own file, and a
// JSON script block is data, not script, so it is allowed.
(function () {
  var html = htm.bind(React.createElement);

  // ---- dashboard tiles ------------------------------------------------------------------------

  var TILES = [
    { key: 'triage', label: 'Triage', note: 'waiting for you', icon: '🧭', tone: 'amber', show: 'TRIAGE' },
    { key: 'active', label: 'Active', note: 'somebody is on it', icon: '🛠️', tone: 'blue', show: 'ACTIVE' },
    { key: 'resolvedToday', label: 'Resolved today', note: 'since midnight', icon: '✅', tone: 'green' },
    { key: 'overdue', label: 'Past due', note: 'open and late', icon: '⏰', tone: 'red', show: 'PAST_DUE' },
  ];

  function Tile(props) {
    var t = props.tile;
    // Past due turns red only when something is late, so a red tile always means "look at this".
    var className = 'tile tone-' + t.tone + (t.key === 'overdue' && props.count > 0 ? ' tile-alarm' : '');
    var body = html`
      <span class="tile-icon" aria-hidden="true">${t.icon}</span>
      <div>
        <p class="tile-label">${t.label}</p>
        <p class="tile-count">${props.count}</p>
        <p class="tile-note">${t.note}</p>
      </div>`;
    return t.show
      ? html`<a class=${className} href=${'/complaints?show=' + t.show}>${body}</a>`
      : html`<div class=${className}>${body}</div>`;
  }

  // Triage and active never overlap, so together they are everything open. Past due is a part of
  // that, which is what the second line of the strip says in words.
  function OpenWork(props) {
    var c = props.counts;
    var open = c.triage + c.active;
    if (open === 0) {
      return html`<p class="mt-4 text-sm text-slate-600">Nothing open right now.</p>`;
    }
    return html`
      <div class="mt-4 card">
        <div class="flex items-center justify-between gap-4 flex-wrap mb-2">
          <p class="text-sm font-medium">${open} open</p>
          <span class="flex gap-3">
            <span class="legend" style=${{ '--swatch': '#f59e0b' }}>Triage ${c.triage}</span>
            <span class="legend" style=${{ '--swatch': '#2563eb' }}>Active ${c.active}</span>
          </span>
        </div>
        <div class="bar-track" role="img" aria-label=${c.triage + ' in triage, ' + c.active + ' active'}>
          <div class="bar-fill bar-amber" style=${{ width: (100 * c.triage) / open + '%' }}></div>
          <div class="bar-fill bar-blue" style=${{ width: (100 * c.active) / open + '%' }}></div>
        </div>
        <p class="mt-2 text-xs ${c.overdue > 0 ? 'text-red-700 font-medium' : 'text-slate-500'}">
          ${c.overdue > 0 ? c.overdue + ' of the ' + open + ' are past their due time.' : 'Everything open is on time.'}
        </p>
      </div>`;
  }

  function Tiles(props) {
    return html`
      <div class="tiles">
        ${TILES.map(function (t) {
          return html`<${Tile} key=${t.key} tile=${t} count=${props.counts[t.key]} />`;
        })}
      </div>
      <${OpenWork} counts=${props.counts} />`;
  }

  // ---- analytics chart ------------------------------------------------------------------------

  var SORTS = [
    { key: 'median', label: 'Median' },
    { key: 'p90', label: 'p90' },
    { key: 'n', label: 'Resolved' },
  ];

  function hours(value) {
    return value.toFixed(1) + ' h';
  }

  // One row per domain: the dark bar is the median, the light bar behind it runs on to p90, so the
  // gap between them shows how uneven that domain is. Domains with nothing resolved are left out;
  // the table under the chart still lists them.
  function ResolutionChart(props) {
    var state = React.useState('median');
    var sort = state[0];
    var setSort = state[1];
    var rows = props.domains
      .filter(function (d) { return d.n > 0; })
      .sort(function (a, b) { return b[sort] - a[sort]; });
    if (rows.length === 0) {
      return html`<p class="notice bg-white">Nothing resolved in the last 90 days, so there is nothing to chart yet.</p>`;
    }
    var longest = Math.max.apply(null, rows.map(function (d) { return d.p90; })) || 1;
    return html`
      <div class="card">
        <div class="flex items-center justify-between gap-4 flex-wrap mb-2">
          <span class="flex gap-3">
            <span class="legend" style=${{ '--swatch': '#16a34a' }}>Median</span>
            <span class="legend" style=${{ '--swatch': '#86efac' }}>Up to p90</span>
          </span>
          <span class="toggle" role="group" aria-label="Sort by">
            ${SORTS.map(function (s) {
              return html`<button key=${s.key} type="button" aria-pressed=${sort === s.key}
                onClick=${function () { setSort(s.key); }}>${s.label}</button>`;
            })}
          </span>
        </div>
        ${rows.map(function (d) {
          return html`
            <div class="chart-row" key=${d.domain}>
              <span class="text-sm font-medium">${d.domain}</span>
              <div class="bar-track" role="img"
                aria-label=${d.domain + ': median ' + hours(d.median) + ', p90 ' + hours(d.p90)}>
                <div class="bar-fill" style=${{ width: (100 * d.median) / longest + '%' }}></div>
                <div class="bar-fill bar-light" style=${{ width: (100 * (d.p90 - d.median)) / longest + '%' }}></div>
              </div>
              <span class="text-xs text-slate-600">
                median ${hours(d.median)} · p90 ${hours(d.p90)} · n ${d.n}
              </span>
            </div>`;
        })}
      </div>`;
  }

  // ---- mounting -------------------------------------------------------------------------------

  function mount(id, Component, propName) {
    var root = document.getElementById(id);
    var data = document.getElementById(id + '-data');
    if (root && data) {
      var props = {};
      props[propName] = JSON.parse(data.textContent);
      ReactDOM.createRoot(root).render(React.createElement(Component, props));
    }
  }

  mount('tiles', Tiles, 'counts');
  mount('resolution-chart', ResolutionChart, 'domains');
})();
