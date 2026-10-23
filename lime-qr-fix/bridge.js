// Lime QR Raster Fix — settings bridge (ISOLATED world).
// Mirrors chrome.storage settings into a DOM dataset attribute so the
// MAIN-world hook (main.js) can read them synchronously at print time.
(function () {
  "use strict";

  var DEFAULTS = { enabled: false, dots: 384, mode: "gsv0", moduleSize: 0 };

  function push(settings) {
    var apply = function () {
      try {
        document.documentElement.dataset.limeQrFix = JSON.stringify(settings);
      } catch (e) {
        /* ignore */
      }
    };
    if (document.documentElement) apply();
    else document.addEventListener("DOMContentLoaded", apply);
  }

  function refresh() {
    chrome.storage.local.get(DEFAULTS, push);
  }

  refresh();
  chrome.storage.onChanged.addListener(function (changes, area) {
    if (area === "local") refresh();
  });
})();
