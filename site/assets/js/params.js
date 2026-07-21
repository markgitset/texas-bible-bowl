// Patches every [data-tbb-param] span with the live season values from the backend
// (GET /seasons/current), so an in-app admin edit shows on the static site without a rebuild.
// To avoid flashing stale baked values, the last-fetched season is cached in localStorage and
// applied synchronously here — this script is inlined at the end of <body> (baseof.html), so
// the cached patch lands during parsing, before first paint. The fetch then refreshes the
// cache and re-patches only if something changed. The baked values (data/params.json at build
// time) remain the fallback for first-ever visits and failed fetches.
(function () {
  var base = window.TBB_BACKEND_URL || "https://texas-bible-bowl.fly.dev";
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
    return d;
  }

  function patch(s) {
    if (!s) return;
    var d = derive(s);
    document.querySelectorAll("[data-tbb-param]").forEach(function (el) {
      var v = d[el.getAttribute("data-tbb-param")];
      if (v != null && el.textContent !== v) el.textContent = v;
    });
  }

  try {
    patch(JSON.parse(localStorage.getItem(CACHE_KEY)));
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
