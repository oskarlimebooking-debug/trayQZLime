var DEFAULTS = { enabled: false, dots: 384, mode: "gsv0", moduleSize: 0 };

var enabledEl = document.getElementById("enabled");
var dotsEl = document.getElementById("dots");
var modeEl = document.getElementById("mode");
var statusEl = document.getElementById("status");

function render(s) {
  enabledEl.checked = !!s.enabled;
  dotsEl.value = String(s.dots === 576 ? 576 : 384);
  modeEl.value = s.mode === "escstar" ? "escstar" : "gsv0";
  statusEl.textContent = s.enabled
    ? "Aktivno — QR kode se tiskajo kot slika."
    : "Izklopljeno — podatki se ne spreminjajo.";
}

function save() {
  var s = {
    enabled: enabledEl.checked,
    dots: parseInt(dotsEl.value, 10) === 576 ? 576 : 384,
    mode: modeEl.value === "escstar" ? "escstar" : "gsv0",
    moduleSize: 0,
  };
  chrome.storage.local.set(s, function () {
    render(s);
  });
}

chrome.storage.local.get(DEFAULTS, render);
enabledEl.addEventListener("change", save);
dotsEl.addEventListener("change", save);
modeEl.addEventListener("change", save);
