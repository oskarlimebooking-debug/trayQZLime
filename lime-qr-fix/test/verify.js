// Verification for core.js — run: node test/verify.js
// Replicates Lime Booking's buildEscPosQrCode() byte-for-byte, runs the
// transform, then decodes the produced raster with jsQR (independent decoder).
"use strict";

const path = require("path");
globalThis.__limeQrFixQrcodeLib = require("/tmp/node_modules/qrcode-generator");
const core = require(path.join(__dirname, "..", "core.js"));
const jsQR = require("/tmp/node_modules/jsqr");

let failures = 0;
function assert(cond, msg) {
  if (cond) console.log("  PASS", msg);
  else {
    failures++;
    console.error("  FAIL", msg);
  }
}

// ---- exact replica of client/src/utils/pos-printer/utils.ts buildEscPosQrCode ----
function buildEscPosQrCode(encodedData, moduleSize = 6) {
  const qrDataLength = encodedData.length + 3;
  return Uint8Array.from([
    0x1b, 0x61, 0x01, // center
    0x1d, 0x28, 0x6b, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00, // model 2
    0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x43, moduleSize, // size
    0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x45, 0x31, // ECC M
    0x1d, 0x28, 0x6b, qrDataLength & 0xff, (qrDataLength >> 8) & 0xff,
    0x31, 0x50, 0x30, ...encodedData, // store
    0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x51, 0x30, // print
    0x1b, 0x61, 0x00, // left
  ]);
}

function bytesToStr(b) {
  let s = "";
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
  return s;
}
function strToBytes(s) {
  const b = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 0xff;
  return b;
}

function buildReceipt(qrPayload, moduleSize) {
  const enc = new TextEncoder();
  const head = enc.encode("RACUN ST.: 123\nZOI: abc\nEOR: def\n");
  const qr = qrPayload ? buildEscPosQrCode(enc.encode(qrPayload), moduleSize) : new Uint8Array();
  const tail = enc.encode("\nHvala za obisk!\n\n\n");
  return bytesToStr(Uint8Array.from([...head, ...qr, ...tail]));
}

// Parse GS v 0 out of the transformed stream, return {matrixDecode, widthDots, heightDots, before, after}
function extractGsV0(str) {
  const b = strToBytes(str);
  for (let i = 0; i < b.length - 8; i++) {
    if (b[i] === 0x1d && b[i + 1] === 0x76 && b[i + 2] === 0x30) {
      const m = b[i + 3];
      const xBytes = b[i + 4] + (b[i + 5] << 8);
      const yDots = b[i + 6] + (b[i + 7] << 8);
      const dataStart = i + 8;
      const dataLen = xBytes * yDots;
      return {
        m, xBytes, yDots,
        data: b.slice(dataStart, dataStart + dataLen),
        before: str.slice(0, i),
        after: str.slice(dataStart + dataLen),
      };
    }
  }
  return null;
}

// Convert 1-bit raster to RGBA and decode with jsQR
function decodeRaster(img) {
  const w = img.xBytes * 8, h = img.yDots;
  const rgba = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const black = (img.data[y * img.xBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
      const v = black ? 0 : 255;
      const o = (y * w + x) * 4;
      rgba[o] = rgba[o + 1] = rgba[o + 2] = v;
      rgba[o + 3] = 255;
    }
  }
  return jsQR(rgba, w, h);
}

// ---------- Test 1: FURS-style payload (as sent on a real receipt) ----------
console.log("Test 1: FURS QR (60-char payload), GS v 0, 58mm");
{
  const payload = "123456789012345678901234567890123456789012345678901234567890";
  const input = buildReceipt(payload, 6);
  const out = core.transformEscPos(input, { dots: 384, mode: "gsv0" });
  assert(out !== null, "transform produced output");
  assert(!core.hasQrSequence(out), "no GS ( k QR commands remain");
  assert(out.includes("RACUN ST.: 123") && out.includes("Hvala za obisk!"), "surrounding text intact");
  assert(out.includes("\x1b\x61\x01") && out.includes("\x1b\x61\x00"), "alignment commands preserved");
  const img = extractGsV0(out);
  assert(img !== null, "GS v 0 raster present");
  assert(img.xBytes === 48, "width = 384 dots (48 bytes) — fits 58mm paper");
  const decoded = decodeRaster(img);
  assert(decoded !== null, "jsQR decodes the raster image");
  assert(decoded && decoded.data === payload, "decoded content matches original payload");
}

// ---------- Test 2: test-print payload ("testQR") ----------
console.log("Test 2: test-print payload 'testQR'");
{
  const input = buildReceipt("testQR", 6);
  const out = core.transformEscPos(input, { dots: 384, mode: "gsv0" });
  const img = extractGsV0(out);
  const decoded = decodeRaster(img);
  assert(decoded && decoded.data === "testQR", "decoded 'testQR' correctly");
}

// ---------- Test 3: UPN QR — must stay version 15 (77 modules) ----------
console.log("Test 3: UPN QR payload (version 15, ISO-8859-2 bytes preserved)");
{
  // realistic-ish UPN payload with 8859-2 chars (č=0xE8, š=0xB9, ž=0xBE in 8859-2)
  const upnBody =
    "UPNQR\n\n\n\n\nJanez Novak\nDunajska cesta 1\n1000 Ljubljana\n00000012345\n\n\nSI56020170014356205\nPla\xE8ilo ra\xE8una\n\nSI001234\nIssuer d.o.o.\nVojkova 63\n1000 Ljubljana\n";
  const padded = upnBody; // content, raw bytes
  const enc = [];
  for (let i = 0; i < padded.length; i++) enc.push(padded.charCodeAt(i) & 0xff);
  const input = buildReceipt("", 0).slice(0, 0) +
    bytesToStr(new TextEncoder().encode("UPN nalog:\n")) +
    bytesToStr(buildEscPosQrCode(Uint8Array.from(enc), 4)) +
    "Konec\n";
  const out = core.transformEscPos(input, { dots: 384, mode: "gsv0" });
  assert(out !== null && !core.hasQrSequence(out), "UPN QR replaced");
  const img = extractGsV0(out);
  assert(img !== null, "raster present");
  // version 15 = 77 modules; quiet 4+4 => 85 modules; max ms with 384 dots = 4 => 340 dots
  // find black extent to estimate module count
  const decoded = decodeRaster(img);
  assert(decoded !== null, "jsQR decodes UPN raster");
  if (decoded) {
    const decodedBytes = decoded.binaryData ? Buffer.from(decoded.binaryData) : null;
    const expected = Buffer.from(enc);
    assert(decodedBytes && decodedBytes.equals(expected), "UPN bytes preserved exactly (incl. ISO-8859-2 chars)");
    assert(decoded.version === 15, "QR version is 15 (UPN standard), got " + decoded.version);
  }
  assert(img.xBytes * 8 >= 340 - 8 && img.xBytes === 48, "fits 384-dot width");
}

// ---------- Test 4: ESC * fallback mode ----------
console.log("Test 4: ESC * legacy mode structure");
{
  const payload = "https://verify.example/abc";
  const input = buildReceipt(payload, 6);
  const out = core.transformEscPos(input, { dots: 384, mode: "escstar" });
  assert(out !== null && !core.hasQrSequence(out), "QR replaced in ESC * mode");
  assert(out.includes("\x1b\x33\x18"), "line spacing set to 24 dots");
  assert(out.includes("\x1b\x2a\x21"), "ESC * m=33 bands present");
  assert(out.includes("\x1b\x32"), "default line spacing restored");
  // reconstruct bitmap from ESC * bands and decode
  const b = strToBytes(out);
  let cols = null, bands = [];
  for (let i = 0; i < b.length - 5; i++) {
    if (b[i] === 0x1b && b[i + 1] === 0x2a && b[i + 2] === 33) {
      const n = b[i + 3] + (b[i + 4] << 8);
      cols = n;
      bands.push(b.slice(i + 5, i + 5 + n * 3));
    }
  }
  assert(cols === 384, "band width = 384 columns");
  const h = bands.length * 24;
  const rgba = new Uint8ClampedArray(cols * h * 4);
  bands.forEach((band, bi) => {
    for (let x = 0; x < cols; x++) {
      for (let k = 0; k < 3; k++) {
        const byte = band[x * 3 + k];
        for (let bit = 0; bit < 8; bit++) {
          const y = bi * 24 + k * 8 + bit;
          const black = (byte >> (7 - bit)) & 1;
          const o = (y * cols + x) * 4;
          const v = black ? 0 : 255;
          rgba[o] = rgba[o + 1] = rgba[o + 2] = v;
          rgba[o + 3] = 255;
        }
      }
    }
  });
  const decoded = jsQR(rgba, cols, h);
  assert(decoded && decoded.data === payload, "ESC * bitmap decodes to original payload");
}

// ---------- Test 5: pass-through safety ----------
console.log("Test 5: pass-through and idempotency");
{
  const noQr = buildReceipt("", 0);
  assert(core.transformEscPos(noQr, { dots: 384 }) === null, "stream without QR untouched (null)");
  const withQr = buildReceipt("abc123", 6);
  const once = core.transformEscPos(withQr, { dots: 384 });
  assert(core.transformEscPos(once, { dots: 384 }) === null, "second pass is a no-op (idempotent)");
  // random binary containing GS but no QR blocks
  let junk = "";
  for (let i = 0; i < 1000; i++) junk += String.fromCharCode((i * 37) & 0xff);
  assert(core.transformEscPos(junk, { dots: 384 }) === null, "arbitrary binary untouched");
}

// ---------- Test 6: 80mm width ----------
console.log("Test 6: 80mm (576 dots)");
{
  const payload = "123456789012345678901234567890123456789012345678901234567890";
  const out = core.transformEscPos(buildReceipt(payload, 6), { dots: 576, mode: "gsv0" });
  const img = extractGsV0(out);
  assert(img && img.xBytes === 72, "width = 576 dots (72 bytes)");
  const decoded = decodeRaster(img);
  assert(decoded && decoded.data === payload, "decodes at 80mm width");
}

console.log(failures === 0 ? "\nALL TESTS PASSED" : "\n" + failures + " FAILURES");
process.exit(failures ? 1 : 0);
