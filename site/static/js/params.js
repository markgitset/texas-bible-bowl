// Patches every [data-tbb-param] span with the live season values from the backend
// (GET /seasons/current), so an in-app admin edit shows on the static site without a rebuild.
// The baked values (data/params.json at build time) remain as the fallback — a failed fetch
// just means last-deploy values, which is fine.
(function () {
  var base = window.TBB_BACKEND_URL || "https://texas-bible-bowl.fly.dev";
  fetch(base + "/seasons/current")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (s) {
      if (!s) return;
      var year = parseInt(s.eventYear, 10);
      var d = Object.assign({}, s);
      // Derived display keys — keep in sync with layouts/partials/season.html.
      d.eventYearMinus1 = String(year - 1);
      d.schoolYear = (year - 1) + "–" + String(year % 100).padStart(2, "0");
      d.eventDates = s.eventDateRange + ", " + s.eventYear;
      d.scholarshipDeadlineFull =
        s.scholarshipDeadline === "TBD" ? "TBD" : s.scholarshipDeadline + ", " + s.eventYear;
      d.seasonTitle =
        s.eventTheme && s.eventTheme !== "TBD"
          ? s.eventTheme + " — " + s.eventScripture
          : s.eventScripture;
      d.registrationOpensTitle = s.registrationOpens.replace(/\b\w/g, function (c) {
        return c.toUpperCase();
      });
      document.querySelectorAll("[data-tbb-param]").forEach(function (el) {
        var v = d[el.getAttribute("data-tbb-param")];
        if (v != null) el.textContent = v;
      });
    })
    .catch(function () {});
})();
