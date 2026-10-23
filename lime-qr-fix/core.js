// Lime QR Raster Fix — core transform (no browser APIs; also runs under Node for tests)
//
// Rewrites native ESC/POS QR commands (GS ( k, cn=49) inside a binary-string
// ESC/POS stream into a raster bit image (GS v 0, or ESC * for very old
// firmware) that printers without native QR support (e.g. Rongta 58mm) can
// print. Everything else in the stream is passed through byte-for-byte.
(function (global) {
  "use strict";

  function strToBytes(s) {
    var b = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 0xff;
    return b;
  }

  function bytesToStr(arr) {
    var CHUNK = 8192;
    var parts = [];
    for (var i = 0; i < arr.length; i += CHUNK) {
      parts.push(String.fromCharCode.apply(null, arr.slice(i, i + CHUNK)));
    }
    return parts.join("");
  }

  // Quick check: does the stream contain a "store QR data" block (GS ( k .. 49 80 48)?
  function hasQrSequence(str) {
    var i = -1;
    while ((i = str.indexOf("\x1d\x28\x6b", i + 1)) !== -1) {
      if (
        str.charCodeAt(i + 5) === 0x31 && // cn = 49 (QR)
        str.charCodeAt(i + 6) === 0x50 // fn = 80 (store data)
      ) {
        return true;
      }
    }
    return false;
  }

  function getQrLib() {
    if (typeof global.__limeQrFixQrcodeLib !== "undefined")
      return global.__limeQrFixQrcodeLib;
    if (typeof global.qrcode !== "undefined") return global.qrcode;
    throw new Error("qrcode-generator library not loaded");
  }

  // Build the QR module matrix. UPN QR payloads must stay QR version 15 / ECC M
  // (strict Slovenian bank apps reject deviations); everything else auto-sizes.
  function buildQrMatrix(dataBytes) {
    var qrcode = getQrLib();
    // Byte-preserving conversion (the payload is already encoded upstream —
    // ASCII for FURS/FINA, ISO-8859-2 for UPN). Never re-encode as UTF-8.
    qrcode.stringToBytes = function (s) {
      var bytes = [];
      for (var i = 0; i < s.length; i++) bytes.push(s.charCodeAt(i) & 0xff);
      return bytes;
    };
    var content = bytesToStr(dataBytes);
    var isUpn = content.slice(0, 5) === "UPNQR";
    var typeNumber = isUpn ? 15 : 0; // 0 = auto
    var qr;
    try {
      qr = qrcode(typeNumber, "M");
      qr.addData(content, "Byte");
      qr.make();
    } catch (e) {
      // Fallback: retry with auto sizing if the forced version was too small.
      qr = qrcode(0, "M");
      qr.addData(content, "Byte");
      qr.make();
    }
    return qr;
  }

  // Render the QR matrix into a centered 1-bit bitmap.
  // Returns { rows: Uint8Array[], widthBytes, heightDots }
  function rasterize(qr, moduleSize, printerDots) {
    var count = qr.getModuleCount();
    var quiet = 4; // quiet zone, modules per side
    var ms = moduleSize && moduleSize >= 2 && moduleSize <= 16 ? moduleSize : 6;
    while (ms > 2 && (count + 2 * quiet) * ms > printerDots) ms--;
    var qrDots = (count + 2 * quiet) * ms;
    var width = Math.max(printerDots, qrDots);
    var widthBytes = Math.ceil(width / 8);
    var left = Math.max(0, Math.floor((width - qrDots) / 2));

    var rows = [];
    for (var my = 0; my < count; my++) {
      var row = new Uint8Array(widthBytes); // all white
      for (var mx = 0; mx < count; mx++) {
        if (!qr.isDark(my, mx)) continue;
        var x0 = left + (quiet + mx) * ms;
        for (var d = 0; d < ms; d++) {
          var x = x0 + d;
          row[x >> 3] |= 0x80 >> (x & 7);
        }
      }
      for (var r = 0; r < ms; r++) rows.push(row);
    }
    return { rows: rows, widthBytes: widthBytes, heightDots: rows.length };
  }

  // GS v 0 — print raster bit image (standard on nearly all ESC/POS printers).
  function emitGsV0(raster) {
    var out = [];
    out.push(0x0a); // spacer line before
    out.push(
      0x1d, 0x76, 0x30, 0x00,
      raster.widthBytes & 0xff, (raster.widthBytes >> 8) & 0xff,
      raster.heightDots & 0xff, (raster.heightDots >> 8) & 0xff
    );
    for (var y = 0; y < raster.rows.length; y++) {
      var row = raster.rows[y];
      for (var x = 0; x < row.length; x++) out.push(row[x]);
    }
    out.push(0x0a); // spacer line after
    return out;
  }

  // ESC * m=33 (24-dot double density) — fallback for very old firmware
  // without GS v 0. Prints in 24-dot bands with line spacing set to 24 dots.
  function emitEscStar(raster) {
    var widthDots = raster.widthBytes * 8;
    var out = [];
    out.push(0x0a);
    out.push(0x1b, 0x33, 24); // line spacing = 24 dots
    for (var band = 0; band < raster.heightDots; band += 24) {
      out.push(0x1b, 0x2a, 33, widthDots & 0xff, (widthDots >> 8) & 0xff);
      for (var x = 0; x < widthDots; x++) {
        for (var k = 0; k < 3; k++) {
          var b = 0;
          for (var bit = 0; bit < 8; bit++) {
            var y = band + k * 8 + bit;
            if (y < raster.heightDots) {
              var row = raster.rows[y];
              if (row[x >> 3] & (0x80 >> (x & 7))) b |= 0x80 >> bit;
            }
          }
          out.push(b);
        }
      }
      out.push(0x0a);
    }
    out.push(0x1b, 0x32); // restore default line spacing
    out.push(0x0a);
    return out;
  }

  /**
   * Transform an ESC/POS binary string: replace native GS ( k QR command
   * sequences with a raster image of the same QR content.
   *
   * opts: { dots: 384|576, mode: "gsv0"|"escstar", moduleSize: 0 (=keep printer's) }
   * Returns the transformed binary string, or null when nothing changed.
   */
  function transformEscPos(input, opts) {
    opts = opts || {};
    var printerDots = opts.dots === 576 ? 576 : 384;
    var mode = opts.mode === "escstar" ? "escstar" : "gsv0";

    if (!hasQrSequence(input)) return null;

    var bytes = strToBytes(input);
    var out = [];
    var i = 0;
    var moduleSize = opts.moduleSize || 6;
    var pendingData = null;
    var changed = false;

    while (i < bytes.length) {
      if (
        bytes[i] === 0x1d &&
        i + 6 < bytes.length &&
        bytes[i + 1] === 0x28 &&
        bytes[i + 2] === 0x6b
      ) {
        var pL = bytes[i + 3];
        var pH = bytes[i + 4];
        var len = pL + (pH << 8); // counts cn, fn and data
        var cn = bytes[i + 5];
        var fn = bytes[i + 6];
        var blockEnd = i + 5 + len;

        if (
          cn === 0x31 && // QR code function group
          len >= 2 &&
          blockEnd <= bytes.length &&
          (fn === 0x41 || fn === 0x43 || fn === 0x45 || fn === 0x50 || fn === 0x51)
        ) {
          changed = true;
          if (fn === 0x43 && len >= 3) {
            // set module size
            moduleSize = bytes[i + 7] || moduleSize;
          } else if (fn === 0x50 && len >= 3) {
            // store data (skip the m byte at i+7)
            pendingData = bytes.slice(i + 8, blockEnd);
          } else if (fn === 0x51) {
            // print — emit raster replacement here
            if (pendingData && pendingData.length) {
              var qr = buildQrMatrix(pendingData);
              var raster = rasterize(qr, moduleSize, printerDots);
              var img = mode === "escstar" ? emitEscStar(raster) : emitGsV0(raster);
              for (var j = 0; j < img.length; j++) out.push(img[j]);
              pendingData = null;
            }
          }
          // fn 0x41 (model) and 0x45 (ECC) are simply dropped
          i = blockEnd;
          continue;
        }
      }
      out.push(bytes[i]);
      i++;
    }

    if (!changed) return null;
    return bytesToStr(Uint8Array.from(out));
  }

  var api = {
    transformEscPos: transformEscPos,
    hasQrSequence: hasQrSequence,
    _internal: { buildQrMatrix: buildQrMatrix, rasterize: rasterize },
  };

  global.__limeQrFixCore = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
