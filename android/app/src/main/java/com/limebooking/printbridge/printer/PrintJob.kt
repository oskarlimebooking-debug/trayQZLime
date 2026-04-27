package com.limebooking.printbridge.printer

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Translates a qz-tray.js `data[]` array into a single ESC/POS byte stream.
 *
 * Supported entries (matching what Lime Booking actually sends — see the
 * RAW/PIXEL × COMMAND/IMAGE matrix in src/qz/printer/PrintingUtilities.java:41-128):
 *
 *   {type: "raw",   format: "command", flavor: "plain"  | "base64" | "hex"}
 *   {type: "raw",   format: "image",   flavor: "base64" | "file"  (file rejected)}
 *   {type: "pixel", format: "image",   flavor: "base64"}
 *
 * Anything else is logged and skipped — never crash the print job over an
 * unknown chunk, since Lime sometimes sends inert config chunks.
 */
object PrintJob {

    private const val TAG = "PrintJob"

    fun build(data: JSONArray, encoding: String, paperWidthDots: Int): ByteArray {
        val out = ByteArrayOutputStream()

        // Initialize printer + select code page for Slovenian glyphs
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @ — initialize
        out.write(codepageCommand(encoding))

        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            try {
                appendEntry(out, entry, encoding, paperWidthDots)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping bad entry $i: ${e.message}")
            }
        }

        return out.toByteArray()
    }

    private fun appendEntry(
        out: ByteArrayOutputStream,
        entry: JSONObject,
        encoding: String,
        paperWidthDots: Int
    ) {
        val type = entry.optString("type", "raw").lowercase()
        val format = entry.optString("format", "command").lowercase()
        val flavor = entry.optString("flavor", "plain").lowercase()
        val payload = entry.optString("data", "")

        // Pixel = always image
        if (type == "pixel" || format == "image") {
            val bitmap = ImageRasterizer.decodeBase64(payload)
                ?: run { Log.w(TAG, "Could not decode image payload"); return }
            out.write(ImageRasterizer.toEscPosBytes(bitmap, paperWidthDots))
            out.write(0x0A) // newline after image
            return
        }

        // Raw command stream
        when (flavor) {
            "plain" -> {
                // qz-tray.js sends \x1B etc. as literal characters. Map to chosen codepage.
                out.write(payload.toByteArray(charsetFor(encoding)))
            }
            "base64" -> {
                out.write(Base64.decode(payload, Base64.DEFAULT))
            }
            "hex" -> {
                out.write(hexToBytes(payload))
            }
            "file", "xml" -> {
                Log.w(TAG, "flavor=$flavor not supported — skipping")
            }
            else -> {
                Log.w(TAG, "Unknown flavor=$flavor — treating as plain")
                out.write(payload.toByteArray(charsetFor(encoding)))
            }
        }
    }

    private fun codepageCommand(encoding: String): ByteArray {
        // ESC t n — select character code table
        // 47 = WPC1250 (Central European, Slovenian Č/Š/Ž)
        // 18 = CP852 (legacy Slavic)
        // 16 = CP1252 (Western Europe)
        val n = when (encoding.replace("-", "").replace("_", "")) {
            "cp1250", "wpc1250", "windows1250" -> 47
            "cp852" -> 18
            "cp437" -> 0
            "cp1252", "windows1252" -> 16
            else -> 47
        }
        return byteArrayOf(0x1B, 0x74, n.toByte())
    }

    private fun charsetFor(encoding: String): java.nio.charset.Charset {
        val name = when (encoding.replace("-", "").replace("_", "")) {
            "cp1250", "wpc1250", "windows1250" -> "windows-1250"
            "cp852" -> "IBM852"
            "cp437" -> "IBM437"
            "cp1252", "windows1252" -> "windows-1252"
            "utf8" -> "UTF-8"
            else -> "windows-1250"
        }
        return try {
            java.nio.charset.Charset.forName(name)
        } catch (e: Exception) {
            Charsets.ISO_8859_1
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(Regex("[^0-9a-fA-F]"), "")
        require(cleaned.length % 2 == 0) { "Hex string has odd length" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            out[i] = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** Convenience for Settings "Test print" button — produces a known-good test page. */
    fun testPage(paperColumns: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // init
        out.write(byteArrayOf(0x1B, 0x74, 47)) // CP1250
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // ESC a 1 = center
        out.write(byteArrayOf(0x1B, 0x21, 0x30)) // ESC ! 0x30 = double H+W
        out.write("Lime\n".toByteArray(Charsets.ISO_8859_1))
        out.write("Print Bridge\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x1B, 0x21, 0x00)) // normal
        out.write(("=".repeat(paperColumns) + "\n").toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // left
        out.write("Test izpis OK\n".toByteArray(charsetForCp1250()))
        out.write("Čumika šžufti žaba\n".toByteArray(charsetForCp1250())) // Č š ž
        out.write("Datum: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}\n"
            .toByteArray(charsetForCp1250()))
        out.write(("=".repeat(paperColumns) + "\n").toByteArray(Charsets.ISO_8859_1))
        out.write("\n\n".toByteArray())
        return out.toByteArray()
    }

    private fun charsetForCp1250() = try {
        java.nio.charset.Charset.forName("windows-1250")
    } catch (e: Exception) { Charsets.ISO_8859_1 }
}
