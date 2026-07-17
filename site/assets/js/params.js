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
