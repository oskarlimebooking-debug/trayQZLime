import CoreGraphics
import Foundation
import UIKit

/// Converts a base64 PNG (typically a QR code from Lime's
/// `additionalReceiptData.QR`, or a header logo) into ESC/POS raster bitmap
/// commands using the `GS v 0` sequence, supported by all common thermal
/// printers.
///
/// Format: `GS v 0 m xL xH yL yH d1...dk`
///   - m = density (0 = single)
///   - xL/xH = bitmap width in BYTES (8 dots per byte)
///   - yL/yH = bitmap height in DOTS
enum ImageRasterizer {

    static func decodeBase64(_ b64: String) -> UIImage? {
        // Strip any "data:image/...;base64," prefix the PWA might include
        let cleaned = b64.contains(",") ? String(b64.split(separator: ",").last ?? "") : b64
        guard let data = Data(base64Encoded: cleaned, options: .ignoreUnknownCharacters) else { return nil }
        return UIImage(data: data)
    }

    static func toEscPos(_ image: UIImage, maxWidthDots: Int) -> Data {
        guard let cg = image.cgImage else { return Data() }
        let scaled = scale(cg, maxWidth: maxWidthDots)
        let mono = monochromeBytes(scaled)
        return rasterCommand(width: scaled.width, height: scaled.height, bitmap: mono)
    }

    private static func scale(_ image: CGImage, maxWidth: Int) -> CGImage {
        if image.width <= maxWidth { return image }
        let ratio = Double(maxWidth) / Double(image.width)
        let newH = max(1, Int(Double(image.height) * ratio))
        let space = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil, width: maxWidth, height: newH,
            bitsPerComponent: 8, bytesPerRow: 0, space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return image }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: maxWidth, height: newH))
        return ctx.makeImage() ?? image
    }

    /// Returns the bitmap packed as ESC/POS expects: row-major, MSB-first per byte,
    /// 1 = black/print, 0 = white/skip.
    private static func monochromeBytes(_ image: CGImage) -> Data {
        let w = image.width
        let h = image.height
        let bytesPerPixel = 4
        let bytesPerRow = w * bytesPerPixel
        var pixels = [UInt8](repeating: 0, count: bytesPerRow * h)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: &pixels, width: w, height: h,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow, space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                | CGBitmapInfo.byteOrder32Big.rawValue
        ) else { return Data() }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))

        let widthBytes = (w + 7) / 8
        var out = Data(count: widthBytes * h)
        out.withUnsafeMutableBytes { buf -> Void in
            let bytes = buf.bindMemory(to: UInt8.self).baseAddress!
            for y in 0..<h {
                for x in 0..<w {
                    let off = y * bytesPerRow + x * bytesPerPixel
                    let r = Int(pixels[off])
                    let g = Int(pixels[off + 1])
                    let b = Int(pixels[off + 2])
                    let a = Int(pixels[off + 3])
                    let isBlack: Bool
                    if a < 128 {
                        isBlack = false
                    } else {
                        let luma = (r * 299 + g * 587 + b * 114) / 1000
                        isBlack = luma < 128
                    }
                    if isBlack {
                        let byteIdx = y * widthBytes + (x / 8)
                        let bit: UInt8 = 0x80 >> (x % 8)
                        bytes[byteIdx] |= bit
                    }
                }
            }
        }
        return out
    }

    private static func rasterCommand(width: Int, height: Int, bitmap: Data) -> Data {
        let widthBytes = (width + 7) / 8
        var out = Data()
        // GS v 0 m xL xH yL yH
        out.append(contentsOf: [
            0x1D, 0x76, 0x30, 0x00,
            UInt8(widthBytes & 0xFF),
            UInt8((widthBytes >> 8) & 0xFF),
            UInt8(height & 0xFF),
            UInt8((height >> 8) & 0xFF)
        ])
        out.append(bitmap)
        out.append(contentsOf: [0x0A]) // line feed
        return out
    }
}
