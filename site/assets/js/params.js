// Patches every [data-tbb-param] span with the live season values from the backend
// (GET /seasons/current), so an in-app admin edit shows on the static site without a rebuild.
// To avoid flashing stale baked values, the last-fetched season is cached in localStorage and
// applied synchronously here — this script is inlined at the end of <body> (baseof.html), so
// the cached patch lands during parsing, before first paint. The fetch then refreshes the
// cache and re-patches only if something changed. The baked values (data/params.json at build
// time) remain the fallback for first-ever visits and failed fetches.
(function () {
  // baseof.html sets TBB_BACKEND_URL from the build's backendURL param; the literal is the
  // canonical prod API, so a build that loses the param still reaches a real backend.
  var base = window.TBB_BACKEND_URL || "https://api.texasbiblebowl.org";
  var CACHE_KEY = "tbb-season";

  // Derived display keys — keep in sync with layouts/partials/season.html.
  function fmtCents(c) {
    if (c == null || c < 0) return "TBD";
    return c % 100 === 0 ? "$" + c / 100 : "$" + (c / 100).toFixed(2);
  }
  function fmtIsoDate(iso) {
    if (!iso) return "TBD";
    var d = new Date(iso + "T00:00:00");
    if (isNaN(d)) return "TBD";
    return d.toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });
  }
  // Can a coach actually register right now? The backend feature toggle is live AND today (Texas
  // time) falls inside the announced window — mirrors registrationOpen in
  // layouts/partials/season.html and the server's requireRegistrationFeature +
  // registrationWindowState. en-CA formats as YYYY-MM-DD, so the dates compare as plain strings.
  function isRegistrationOpen(s) {
    if (!s.registrationEnabled || !s.registrationOpensOn) return false;
    var today = new Date().toLocaleDateString("en-CA", { timeZone: "America/Chicago" });
    if (today < s.registrationOpensOn) return false;
    return !s.registrationClosesOn || today <= s.registrationClosesOn;
  }
  function derive(s) {
    var year = parseInt(s.eventYear, 10);
    var d = Object.assign({}, s);
    d.eventYearMinus1 = String(year - 1);
    d.schoolYear = (year - 1) + "–" + String(year % 100).padStart(2, "0");
    d.eventDates = s.eventDateRange + ", " + s.eventYear;
    d.scholarshipDeadlineFull =
      s.scholarshipDeadline === "TBD" ? "TBD" : s.scholarshipDeadline + ", " + s.eventYear;
    d.seasonTitle =
      s.eventTheme && s.eventTheme !== "TBD"
        ? s.eventTheme + " — " + s.eventScripture
        : s.eventScripture;
    d.priceContestant = fmtCents(s.priceContestantCents);
    d.priceAdult = fmtCents(s.priceVolunteerCents);
    d.priceChild = fmtCents(s.priceChildCents);
    d.priceTshirt = fmtCents(s.priceTshirtCents);
    d.registrationOpens = fmtIsoDate(s.registrationOpensOn);
    d.registrationDeadline = fmtIsoDate(s.registrationClosesOn);
    d.registrationOpensTitle = d.registrationOpens;
    d.feesTentativeNote =
      s.feesTentative === false ? "" : "Prices are tentative and subject to change.";
    d.registrationOpen = isRegistrationOpen(s);
    return d;
  }

  // "TBD" is the internal sentinel for an unset value (see layouts/partials/tbb-param.html, which
  // maps it identically at build time) — visitors always see the spelled-out phrase instead.
  var TBA = "To be announced";

  function patch(s) {
    if (!s) return;
    var d = derive(s);
    document.querySelectorAll("[data-tbb-param]").forEach(function (el) {
      var v = d[el.getAttribute("data-tbb-param")];
      if (v === "TBD") v = TBA;
      if (v != null && el.textContent !== v) el.textContent = v;
    });
    // Boolean-gated elements (currently the home page's Register Now button): show only while
    // the named derived flag is true. Baked hidden/visible to match, so nothing flashes when the
    // live season agrees. Bootstrap's reboot has `[hidden] { display: none !important }`, which
    // beats the .btn display.
    document.querySelectorAll("[data-tbb-show-if]").forEach(function (el) {
      el.hidden = !d[el.getAttribute("data-tbb-show-if")];
    });
    renderCurriculum(s);
  }

  // Re-render the Event > Curriculum schedule from the live eventYear, so an admin's season
  // change rotates it with no rebuild and no stale flash. Mirrors the build-time
  // `curriculum-schedule` shortcode (same rotation math + markup) — keep the two in sync. The
  // shortcode leaves a #curriculum-schedule container and a #curriculum-data JSON config; on
  // pages without them this is a no-op. When eventYear is unchanged the output matches the
  // baked HTML, so there's nothing to flash.
  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  function schoolYearLabel(y) {
    return y + "–" + String((y + 1) % 100).padStart(2, "0");
  }
  function renderCurriculum(s) {
    var host = document.getElementById("curriculum-schedule");
    var cfgEl = document.getElementById("curriculum-data");
    if (!host || !cfgEl) return;
    var eventYear = parseInt(s.eventYear, 10);
    if (!eventYear) return;
    var cfg;
    try {
      cfg = JSON.parse(cfgEl.textContent);
    } catch (e) {
      return;
    }
    var cycle = cfg.cycle || [];
    var n = cycle.length;
    if (!n) return;
    var anchor = parseInt(cfg.anchorYear, 10);
    var win = cfg.windowYears || 10;
    var start = eventYear - 1; // school-year start
    var mod = function (a) {
      return (((a % n) + n) % n);
    };
    var rows = "";
    for (var i = 0; i < win; i++) {
      var yr = start + i;
      var material = cycle[mod(yr - anchor)];
      var cur = i === 0;
      rows +=
        "<tr" + (cur ? ' class="curriculum-current"' : "") + "><td>" +
        schoolYearLabel(yr) +
        (cur ? ' <span class="curriculum-tag">this year</span>' : "") +
        "</td><td>" + esc(material) +
        (material === cfg.restartsAt ? " <em>(cycle restarts)</em>" : "") +
        "</td></tr>";
    }
    var html =
      '<table class="curriculum-table"><thead><tr><th>Year</th><th>Study Material</th>' +
      "</tr></thead><tbody>" + rows + "</tbody></table>";
    var plans = cfg.studyPlans || {};
    Object.keys(plans).sort().forEach(function (material) {
      var plan = plans[material];
      var idx = cycle.indexOf(material);
      if (idx < 0) return;
      var yr = start + mod(anchor + idx - start);
      html += "<h2>" + esc(material) + " Study Plan (" + schoolYearLabel(yr) + ")</h2>";
      if (plan.note) html += "<p><em>(" + esc(plan.note) + ")</em></p>";
      html += "<ul>";
      (plan.passages || []).forEach(function (p) {
        html += "<li>" + esc(p) + "</li>";
      });
      html += "</ul>";
    });
    host.innerHTML = html;
  }

  try {
    patch(JSON.parse(localStorage.getItem(CACHE_KEY)));
  } catch (e) {}

  // Year-of-operation spans (<span data-tbb-years-since="2010">, see the years-of-operation
  // shortcode). Independent of season data — recompute the ordinal from the browser's clock so
  // it ticks over on New Year's without waiting for a redeploy. The count is INCLUSIVE of the
  // founding year (2010 was the first, so 2026 is the seventeenth) — keep the +1 in sync with
  // the shortcode. Keep ordinalWord in sync with layouts/partials/ordinal.html.
  function ordinalWord(n) {
    var under20 = ["zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh",
      "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
      "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth"];
    var tensCard = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];
    var tensOrd = ["", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth"];
    if (n >= 0 && n <= 20) return under20[n];
    if (n < 100) {
      var t = Math.floor(n / 10), o = n % 10;
      return o === 0 ? tensOrd[t] : tensCard[t] + "-" + under20[o];
    }
    return "";
  }
  try {
    var thisYear = new Date().getFullYear();
    document.querySelectorAll("[data-tbb-years-since]").forEach(function (el) {
      var since = parseInt(el.getAttribute("data-tbb-years-since"), 10);
      var w = ordinalWord(thisYear - since + 1);
      if (w && el.textContent !== w) el.textContent = w;
    });
  } catch (e) {}

  // Account slot: the app caches the signed-in user menu (web/.../Session.kt writes "tbb.nav",
  // JSON of the NavMenu model in web/.../NavMenu.kt). On static pages, swap the "Sign in"
  // button for the same grouped dropdown the app's Shell renders, so the shared navbar reads
  // as one signed-in application — keep this markup in sync with Shell.updateNav. On /app/ the
  // Shell re-renders the slot from live state, overwriting this. The cache can lag server-side
  // role/toggle changes until the next app visit — cosmetic only; the app and server enforce.
  // (Bootstrap's bundle loads before this inline script and uses delegated events, so the
  // injected dropdown works without initialization.)
  try {
    var slot = document.getElementById("accountSlot");
    var link = slot && slot.querySelector("a");
    var menu = JSON.parse(localStorage.getItem("tbb.nav") || "null");
    if (menu && link) {
      var appBase = link.href.replace(/#.*$/, ""); // …/app/, correct on the GH Pages subpath
      var toggle = document.createElement("a");
      toggle.className = "btn btn-outline-light btn-sm px-3 dropdown-toggle";
      toggle.href = appBase + "#account";
      toggle.setAttribute("role", "button");
      toggle.setAttribute("data-bs-toggle", "dropdown");
      toggle.setAttribute("aria-expanded", "false");
      toggle.innerHTML = '<i class="bi bi-person-circle me-1"></i>';
      toggle.appendChild(document.createTextNode(menu.name));
      var list = document.createElement("ul");
      list.className = "dropdown-menu dropdown-menu-end";
      function li(el) {
        var l = document.createElement("li");
        l.appendChild(el);
        list.appendChild(l);
      }
      function divider() {
        var hr = document.createElement("hr");
        hr.className = "dropdown-divider";
        li(hr);
      }
      menu.sections.forEach(function (section, i) {
        if (i > 0) divider();
        var header = document.createElement("h6");
        header.className = "dropdown-header";
        header.textContent = section.label;
        li(header);
        section.items.forEach(function (item) {
          var a = document.createElement("a");
          a.className = "dropdown-item";
          a.href = appBase + "#" + item.route;
          a.textContent = item.label;
          if (item.badge) {
            var badge = document.createElement("span");
            badge.className = "badge text-bg-warning ms-2";
            badge.textContent = "hidden until launch";
            a.appendChild(badge);
          }
          li(a);
        });
      });
      divider();
      var signOut = document.createElement("button");
      signOut.type = "button";
      signOut.className = "dropdown-item";
      signOut.textContent = "Sign out";
      signOut.addEventListener("click", function () {
        ["tbb.token", "tbb.nav", "tbb.user-name"].forEach(function (k) {
          localStorage.removeItem(k);
        });
        slot.classList.remove("dropdown");
        slot.replaceChildren(link); // restore the baked Sign in button
      });
      li(signOut);
      slot.classList.add("dropdown");
      slot.replaceChildren(toggle, list);
    }
  } catch (e) {}

  fetch(base + "/seasons/current")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (s) {
      if (!s) return;
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify(s));
      } catch (e) {}
      patch(s);
    })
    .catch(function () {});
})();
