package com.limebooking.printbridge.printer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64

/**
 * Converts a base64 PNG (typically a QR code from Lime's `additionalReceiptData.QR`,
 * or a header logo) into ESC/POS raster bitmap commands using the GS v 0 sequence,
 * which all common thermal printers support.
 *
 * Format reference: ESC/POS GS v 0 m xL xH yL yH d1...dk
 *   m  = density (0 = single, 1 = double width, 2 = double height, 3 = quad)
 *   xL/xH = bitmap width in BYTES (8 dots per byte)
 *   yL/yH = bitmap height in DOTS
 */
object ImageRasterizer {

    fun decodeBase64(base64: String): Bitmap? {
        return try {
            val cleaned = base64.substringAfter("base64,") // strip data: prefix if present
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun scale(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        val newH = (src.height * ratio).toInt()
        return Bitmap.createScaledBitmap(src, maxWidth, newH, true)
    }

    private fun toMonochrome(src: Bitmap): BooleanArray {
        // true = black (print), false = white (skip)
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        val out = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a < 128) { out[i] = false; continue }
            val luma = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()
            out[i] = luma < 128
        }
        return out
    }

    /**
     * Scales [bitmap] so width <= [maxWidthDots], converts to 1-bit, and emits
     * the ESC/POS GS v 0 raster command stream.
     */
    fun toEscPosBytes(bitmap: Bitmap, maxWidthDots: Int): ByteArray {
        val scaled = scale(bitmap, maxWidthDots)
        val w = scaled.width
        val h = scaled.height
        val mono = toMonochrome(scaled)

        // Round width up to byte boundary
        val widthBytes = (w + 7) / 8
        val data = ByteArray(widthBytes * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mono[y * w + x]) {
                    val byteIdx = y * widthBytes + (x / 8)
                    data[byteIdx] = (data[byteIdx].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }

        // GS v 0 m xL xH yL yH data...
        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (widthBytes and 0xFF).toByte(),
            ((widthBytes shr 8) and 0xFF).toByte(),
            (h and 0xFF).toByte(),
            ((h shr 8) and 0xFF).toByte()
        )
        return header + data
    }
}
