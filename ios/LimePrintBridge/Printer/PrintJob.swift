import Foundation

/// Translates a qz-tray.js `data[]` array into a single ESC/POS byte stream.
///
/// Supported entries (matching what Lime Booking sends — see the
/// RAW/PIXEL × COMMAND/IMAGE matrix in `src/qz/printer/PrintingUtilities.java`):
///
///   {type: "raw",   format: "command", flavor: "plain"  | "base64" | "hex"}
///   {type: "raw",   format: "image",   flavor: "base64"}
///   {type: "pixel", format: "image",   flavor: "base64"}
///
/// Anything else is logged and skipped — never crash the print job over an
/// unknown chunk.
enum PrintJob {

    static func build(data: [[String: Any]], encoding: String, paperWidthDots: Int) -> Data {
        var out = Data()
        // ESC @ — initialize printer
        out.append(contentsOf: [0x1B, 0x40])
        // Select code page for Slovenian glyphs
        out.append(contentsOf: codepageCommand(encoding))

        for (idx, entry) in data.enumerated() {
            do {
                try appendEntry(&out, entry: entry, encoding: encoding, paperWidthDots: paperWidthDots)
            } catch {
                NSLog("PrintJob: skipping bad entry \(idx): \(error)")
            }
        }
        return out
    }

    private static func appendEntry(_ out: inout Data,
                                    entry: [String: Any],
                                    encoding: String,
                                    paperWidthDots: Int) throws {
        let type = (entry["type"] as? String ?? "raw").lowercased()
        let format = (entry["format"] as? String ?? "command").lowercased()
        let flavor = (entry["flavor"] as? String ?? "plain").lowercased()
        let payload = entry["data"] as? String ?? ""

        if type == "pixel" || format == "image" {
            guard let img = ImageRasterizer.decodeBase64(payload) else {
                NSLog("PrintJob: could not decode image payload")
                return
            }
            out.append(ImageRasterizer.toEscPos(img, maxWidthDots: paperWidthDots))
            return
        }

        switch flavor {
        case "plain":
            out.append(payload.data(using: charset(encoding)) ?? Data(payload.utf8))
        case "base64":
            if let d = Data(base64Encoded: payload, options: .ignoreUnknownCharacters) {
                out.append(d)
            }
        case "hex":
            out.append(hexToData(payload))
        case "file", "xml":
            NSLog("PrintJob: flavor=\(flavor) not supported")
        default:
            NSLog("PrintJob: unknown flavor=\(flavor), treating as plain")
            out.append(payload.data(using: charset(encoding)) ?? Data(payload.utf8))
        }
    }

    /// `ESC t n` — selects character code table.
    /// 47 = WPC1250 (Central European, Č/Š/Ž), 18 = CP852, 16 = CP1252, 0 = CP437.
    private static func codepageCommand(_ encoding: String) -> [UInt8] {
        let n: UInt8
        switch encoding.replacingOccurrences(of: "-", with: "")
                       .replacingOccurrences(of: "_", with: "")
                       .lowercased() {
        case "cp1250", "wpc1250", "windows1250":  n = 47
        case "cp852":                              n = 18
        case "cp437":                              n = 0
        case "cp1252", "windows1252":              n = 16
        default:                                   n = 47
        }
        return [0x1B, 0x74, n]
    }

    private static func charset(_ encoding: String) -> String.Encoding {
        // iOS doesn't expose CP1250/CP852 as `String.Encoding` constants; we go
        // through CFStringEncoding. The `kCFStringEncoding*` C macros are NOT
        // imported into Swift in newer SDKs, so we use the documented raw
        // values from CoreFoundation/CFString.h directly:
        //   kCFStringEncodingWindowsLatin2 (CP1250) = 0x0501
        //   kCFStringEncodingDOSLatin2     (CP852)  = 0x0411
        let cp1250: CFStringEncoding = 0x0501
        let cp852:  CFStringEncoding = 0x0411

        let n = encoding.replacingOccurrences(of: "-", with: "")
                        .replacingOccurrences(of: "_", with: "")
                        .lowercased()
        let cfEncoding: CFStringEncoding
        switch n {
        case "cp1250", "wpc1250", "windows1250":
            cfEncoding = cp1250
        case "cp1252", "windows1252":
            return .windowsCP1252
        case "cp852":
            cfEncoding = cp852
        case "utf8":
            return .utf8
        default:
            cfEncoding = cp1250
        }
        return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cfEncoding))
    }

    private static func hexToData(_ hex: String) -> Data {
        let cleaned = hex.unicodeScalars.filter {
            CharacterSet(charactersIn: "0123456789abcdefABCDEF").contains($0)
        }.map(Character.init)
        var s = String(cleaned)
        if s.count % 2 == 1 { s = "0" + s }
        var out = Data()
        out.reserveCapacity(s.count / 2)
        var i = s.startIndex
        while i < s.endIndex {
            let next = s.index(i, offsetBy: 2)
            if let b = UInt8(s[i..<next], radix: 16) {
                out.append(b)
            }
            i = next
        }
        return out
    }

    /// Convenience for the Settings "Test print" button — produces a known-good test page.
    static func testPage(paperColumns: Int) -> Data {
        // CP1250 raw value documented in CoreFoundation/CFString.h
        let cp1250 = String.Encoding(rawValue:
            CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(0x0501)))
        var out = Data()
        out.append(contentsOf: [0x1B, 0x40])           // init
        out.append(contentsOf: [0x1B, 0x74, 47])       // CP1250
        out.append(contentsOf: [0x1B, 0x61, 0x01])     // ESC a 1 = center
        out.append(contentsOf: [0x1B, 0x21, 0x30])     // ESC ! 0x30 = double H+W
        out.append("Lime\n".data(using: cp1250) ?? Data())
        out.append("Print Bridge\n".data(using: cp1250) ?? Data())
        out.append(contentsOf: [0x1B, 0x21, 0x00])     // normal
        out.append((String(repeating: "=", count: paperColumns) + "\n").data(using: cp1250) ?? Data())
        out.append(contentsOf: [0x1B, 0x61, 0x00])     // left
        out.append("Test izpis OK\n".data(using: cp1250) ?? Data())
        out.append("Čumika šžufti žaba\n".data(using: cp1250) ?? Data())
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm"
        out.append("Datum: \(f.string(from: Date()))\n".data(using: cp1250) ?? Data())
        out.append((String(repeating: "=", count: paperColumns) + "\n").data(using: cp1250) ?? Data())
        out.append("\n\n".data(using: .ascii) ?? Data())
        return out
    }
}
