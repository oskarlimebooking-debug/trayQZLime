// Lime QR Raster Fix — page-world hook (runs in MAIN world at document_start).
//
// Lime Booking renders receipts to ESC/POS, base64-encodes them with
// window.btoa(), and only THEN hands them to qz-tray, which signs the message.
// Patching btoa therefore rewrites the data BEFORE signing — QZ Tray accepts
// the job with a valid signature and no other behavior changes.
(function () {
  "use strict";

  if (window.__limeQrFixInstalled) return;
  window.__limeQrFixInstalled = true;

  var DEFAULTS = { enabled: false, dots: 384, mode: "gsv0", moduleSize: 0 };

  function getSettings() {
    try {
      var raw =
        document.documentElement &&
        document.documentElement.dataset.limeQrFix;
      if (!raw) return DEFAULTS;
      var s = JSON.parse(raw);
      return {
        enabled: !!s.enabled,
        dots: s.dots === 576 ? 576 : 384,
        mode: s.mode === "escstar" ? "escstar" : "gsv0",
        moduleSize: s.moduleSize | 0,
      };
    } catch (e) {
      return DEFAULTS;
    }
  }

  var origBtoa = window.btoa.bind(window);

  window.btoa = function (input) {
    try {
      var cfg = getSettings();
      if (
        cfg.enabled &&
        typeof input === "string" &&
        input.length > 32 &&
        window.__limeQrFixCore &&
        window.__limeQrFixCore.hasQrSequence(input)
      ) {
        var transformed = window.__limeQrFixCore.transformEscPos(input, cfg);
        if (transformed !== null) {
          console.info(
            "[Lime QR Fix] Rewrote native ESC/POS QR command to raster image (" +
              cfg.mode + ", " + cfg.dots + " dots)"
          );
          input = transformed;
        }
      }
    } catch (e) {
      console.warn("[Lime QR Fix] transform failed, passing through:", e);
    }
    return origBtoa(input);
  };
})();
