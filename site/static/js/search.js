// Client-side site search: fetches the Hugo-generated /index.json and queries it
// with Fuse.js. Activated only on the /search/ page (looks for #search-app).
(function () {
  var root = document.getElementById('search-app');
  if (!root || typeof Fuse === 'undefined') return;

  var input = document.getElementById('search-input');
  var results = document.getElementById('search-results');
  var status = document.getElementById('search-status');
  var fuse;

  function queryFromUrl() {
    return new URLSearchParams(window.location.search).get('q') || '';
  }

  function render(matches, q) {
    results.innerHTML = '';
    if (!q) { status.textContent = ''; return; }
    if (!matches.length) {
      status.textContent = 'No results for “' + q + '”.';
      return;
    }
    status.textContent = matches.length + ' result' + (matches.length > 1 ? 's' : '') +
      ' for “' + q + '”.';
    matches.forEach(function (m) {
      var it = m.item || m;
      var wrap = document.createElement('div');
      wrap.className = 'search-result mb-3';

      var h = document.createElement('h2');
      h.className = 'h5 mb-1';
      var a = document.createElement('a');
      a.href = it.url;
      a.textContent = it.title;
      h.appendChild(a);
      wrap.appendChild(h);

      if (it.section && it.section !== it.title) {
        var s = document.createElement('span');
        s.className = 'search-result-section';
        s.textContent = it.section;
        wrap.appendChild(s);
      }

      var p = document.createElement('p');
      p.className = 'text-muted small mb-0 mt-1';
      p.textContent = it.summary || '';
      wrap.appendChild(p);

      results.appendChild(wrap);
    });
  }

  function search(q) {
    q = (q || '').trim();
    if (!q) { render([], ''); return; }
    render(fuse.search(q).slice(0, 30), q);
  }

  fetch(root.dataset.index)
    .then(function (r) { return r.json(); })
    .then(function (data) {
      fuse = new Fuse(data, {
        keys: [
          { name: 'title', weight: 3 },
          { name: 'summary', weight: 1 },
          { name: 'body', weight: 0.4 }
        ],
        ignoreLocation: true,
        threshold: 0.4,
        minMatchCharLength: 2
      });
      var q = queryFromUrl();
      if (q) { input.value = q; search(q); }
    })
    .catch(function () { status.textContent = 'Search is unavailable right now.'; });

  input.addEventListener('input', function () { search(input.value); });
  input.addEventListener('change', function () {
    var url = new URL(window.location);
    if (input.value) { url.searchParams.set('q', input.value); }
    else { url.searchParams.delete('q'); }
    window.history.replaceState(null, '', url);
  });
})();
