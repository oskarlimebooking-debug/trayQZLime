# Lime Print Bridge — iOS (BLE)

iOS app, ki omogoča tiskanje računov iz [Lime Booking](https://app.lime-booking.com)
PWA-ja na **BLE termalne tiskalnike** iz iPhone-a/iPad-a.

Lime Booking PWA tiska preko **QZ Tray** — Java daemona, ki teče samo na
desktopih. Ta app na iOS-u igra vlogo QZ Tray, tako da je Lime PWA
nedotaknjena in misli, da govori s pravim QZ Tray daemonom.

> **Samo BLE tiskalniki!** iOS Bluetooth Classic (RFCOMM SPP) zahteva MFi
> certifikacijo (Apple licencira hardware). Ceneni POS-58 BT (PT-210, MUNBYN
> klasične) **NE bodo delali** na iOS-u. Potrebujete **BLE varianto** —
> npr. Goojprt PT-210 BLE, MUNBYN ITPP047, Bixolon SPP-R200III, ali katero
> koli z "BLE" / "Bluetooth 4.x" oznako.

## Arhitektura

```
+----------------------+   webkit.messageHandlers   +---------------------+
|  WKWebView           |   +     evaluateJS         |  QzMessageRouter    |
|  app.lime-booking.com|  ◄────────────────────►   |  (Swift)            |
|  qz-tray.js          |  (window.WebSocket         |                     |
+----------------------+   monkey-patched)          +─────────┬───────────+
                                                              ▼
                                                    +────────────────────+
                                                    |  EscPosPrinter     |
                                                    |  CoreBluetooth →   |
                                                    |  BLE termalni      |
                                                    +────────────────────+
```

**Ključni razcep:** WKWebView **ne sproži** `webView(_:didReceive:completionHandler:)`
za `new WebSocket()` (samo za HTTP resource loads), zato pravi WSS strežnik
s self-signed cert-om ne deluje. Namesto tega:

1. WKUserScript injectira `QzShim.js` pri `.atDocumentStart` — nadomesti
   `window.WebSocket` z fake objektom.
2. Fake socketovi `send()` klici grejo skozi `webkit.messageHandlers.qz`
   v native `QzBridge` (Swift).
3. Bridge prevaja JSON v `QzMessageRouter` calls (`getProgramName`,
   `printers.find`, `print`, ...).
4. `print` payload se prevede v ESC/POS bytes in pošlje preko CoreBluetooth
   write-without-response na izbrani BLE termalni tiskalnik.

## Build & sideload

Potrebujete macOS + Xcode 15+. iOS deploy target 15.0.

### 1. Generiraj Xcode projekt

Repo ne shranjuje `.xcodeproj` (binary, šumi v diff-ih). Generiraj iz
[XcodeGen](https://github.com/yonaskolb/XcodeGen) `project.yml`:

```bash
brew install xcodegen
cd ios
xcodegen generate
open LimePrintBridge.xcodeproj
```

Če ne želite XcodeGen-a, lahko v Xcode-u "File → New → Project → iOS App",
povleknete vse Swift datoteke iz `ios/LimePrintBridge/` v projekt in nastavite
Info.plist + Bluetooth permission strings ročno.

### 2. Sideload na iPhone

iOS ne dovoli .ipa free-form sideload kot Android APK. Tri možnosti, urejene
po ceni in trajnosti:

#### A) Free osebno signiranje preko Xcode (7-dnevni cert)

1. V Xcode-u: `File → Open` → izberite `LimePrintBridge.xcodeproj`.
2. Klik na projekt → tab **Signing & Capabilities**.
3. **Team**: izberite svojo Apple ID osebno team (Personal Team). Če nimate,
   **Add Account…** in vpišite Apple ID. Brezplačno, ne potrebujete Apple
   Developer Program-a.
4. **Bundle Identifier**: naj bo unique (npr. `com.tvojeime.limeprintbridge`).
5. Povežite iPhone z USB kablom. V iPhone-u: Settings → Privacy & Security
   → Developer Mode → ON (potrebno enkratno).
6. V Xcode-u izberite napravo zgoraj, pritisnite **▶ Run**.
7. Prvič: na iPhone-u Settings → General → VPN & Device Management →
   izberite svoj cert → **Trust**.

**Omejitev:** cert poteče čez **7 dni**. Potem je app inertna do ponovnega
build-a iz Xcode-a. OK za interno testiranje, ne za salone.

#### B) AltStore / SideStore (free, 7-dnevni z auto-resign)

[AltStore](https://altstore.io) (oz. njegov fork [SideStore](https://sidestore.io))
poganja desktop "AltServer" oz. iOS-onbed JIT, ki vsakih 7 dni avtomatsko
ponovno podpiše app brez ročnega Xcode build-a.

```bash
# Build .ipa (release, podpisan z osebnim cert-om)
cd ios
xcodebuild -scheme LimePrintBridge -configuration Release \
           -archivePath build/LimePrintBridge.xcarchive archive
xcodebuild -exportArchive -archivePath build/LimePrintBridge.xcarchive \
           -exportPath build/ipa -exportOptionsPlist ExportOptions.plist
# .ipa je v build/ipa/LimePrintBridge.ipa
```

`ExportOptions.plist` vsebina:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>development</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>destination</key>
    <string>export</string>
</dict>
</plist>
```

Nato v AltStore desktop-u kliknete + → izberete .ipa → app se installa na
iPhone. AltStore daemon na ozadju vsakih 7 dni naredi resign.

#### C) Apple Developer Program ($99/leto) + Ad Hoc / TestFlight

Za **stabilen** sideload na **več telefonov** (do 100 UDID-ov za Ad Hoc,
neomejeno za TestFlight):

1. Kupite Apple Developer membership ($99/leto, [developer.apple.com](https://developer.apple.com)).
2. Na [App Store Connect](https://appstoreconnect.apple.com) ustvarite
   App ID + provisioning profile (Ad Hoc).
3. Dodajte UDID-je vseh testnih iPhone-ov (najdete jih v Finder-ju ali
   `system_profiler SPUSBDataType`).
4. V Xcode-u → Signing & Capabilities → izberite paid team + Distribution profile.
5. `Product → Archive` → Distribute App → Ad Hoc → upload na svoj server
   ali distribute preko Diawi/Firebase App Distribution.
6. Cert traja **1 leto**, app deluje brez prekinitev.

Alternativa: **TestFlight** — push v App Store Connect, dodate testerje po
Apple ID, lahko jih je do 10 000. Build trajno deluje 90 dni, potem nov upload.

### 3. Začetni setup

1. Vklopite BLE termalni tiskalnik. Pritisnite feed gumb da se aktivira BLE
   advertising (nekateri tiskalniki gredo v deep sleep po času brez aktivnosti).
2. Odprite Lime Print Bridge.
3. Sprejmete Bluetooth permission alert.
4. Kliknete **Iskanje BLE tiskalnikov**.
5. V seznamu izberite svoj tiskalnik (krepke postavke so verjetni
   tiskalniki — ime ali oglaševani GATT service ustreza znanim print modulom).
6. Po povezavi: **Testno tiskanje** — preverite da pride trakec z "Lime
   Print Bridge" + Č/Š/Ž.
7. Izberite širino papirja: **58 mm (33 col)** ali **80 mm (48 col)**.
8. Klik **Odpri Lime Booking** → naloži se PWA.
9. Login (prvič): preko domene Lime Booking-a. Sprejmete cert dialog
   "Dovoli Lime Booking, da tiska?" → **Vedno**.
10. Izdate račun, klik Cash → na termalniku se sprintaj račun s številko,
    ZOI, EOR in QR kodo.

## Struktura datotek

| Pot | Vsebina |
|-----|---------|
| `LimePrintBridge/LimePrintBridgeApp.swift` | App entry, instancira AppState |
| `LimePrintBridge/AppState.swift` | Shared singleton (router, BLE, registry) |
| `LimePrintBridge/Views/SettingsView.swift` | SwiftUI: izbira tiskalnika, test print |
| `LimePrintBridge/Views/WebViewScreen.swift` | WKWebView host + cert approval alert |
| `LimePrintBridge/Bridge/QzShim.swift` | JS shim, monkey-patcha `window.WebSocket` |
| `LimePrintBridge/Bridge/QzBridge.swift` | WKScriptMessageHandler ↔ router |
| `LimePrintBridge/Bridge/QzMessageRouter.swift` | QZ Tray protokol dispatcher |
| `LimePrintBridge/Bridge/QzAllowedCerts.swift` | Whitelist sprejetih cert-ov |
| `LimePrintBridge/Printer/EscPosPrinter.swift` | Top-level print orchestrator |
| `LimePrintBridge/Printer/PrintJob.swift` | qz JSON `data[]` → ESC/POS bytes |
| `LimePrintBridge/Printer/ImageRasterizer.swift` | base64 PNG → ESC/POS GS v 0 raster |
| `LimePrintBridge/Printer/BleManager.swift` | CoreBluetooth scan / connect |
| `LimePrintBridge/Printer/BlePrinter.swift` | Streamed write s flow control-om |
| `LimePrintBridge/Printer/PrinterRegistry.swift` | UserDefaults persistence |

## Reference v upstream QZ Tray (this repo)

iOS implementacija strogo sledi protokolu desktop QZ Tray daemona:

- [`src/qz/ws/PrintSocketClient.java`](../src/qz/ws/PrintSocketClient.java) — JSON envelope
- [`src/qz/ws/SocketMethod.java`](../src/qz/ws/SocketMethod.java) — call name enum
- [`src/qz/common/Constants.java`](../src/qz/common/Constants.java) — port defaults, PROBE
- [`js/qz-tray.js`](../js/qz-tray.js) — browser-side library (`qz.print` v 1704)

iOS koda je 1:1 paralelna z Android implementacijo v `../android/`, samo s
Swift/Combine namesto Kotlin/Jetpack ter CoreBluetooth namesto Android
Bluetooth Classic SPP.

## Znani BLE termalni tiskalniki

Hint: aplikacija prikaže **vse** BLE naprave v dosegu, ne le tiskalnike. Krepko
prikazane so tiste, kjer ime ali oglaševani GATT service ujema znane vzorce.
Na connect probe-amo vse karakteristike za `.write` ali `.writeWithoutResponse`,
tako da zaznavamo različne vendor-specific UUID sheme:

- `FF00` / `FF02` (najpogostejši ceneni POS-58 BLE)
- `18F0` / `2AF1` (Goojprt + variante)
- `FFE0` / `FFE5` (HM-10 BLE moduli)
- `FFF0` / `FFF1` (Bixolon)
- Microchip `49535343-FE7D-4AE5-8FA9-9FAFD205E455` / `...8841-43F4...`
- Nordic UART Service `6E400001-...`

Testirano modeli:
- _(dodajte ko boste testirali svoj specifični model)_

## Omejitve (MVP)

- **Samo BLE** — Bluetooth Classic SPP ni mogoč na iOS-u (MFi restrikcija).
- **Brez signature verification** — sandbox-only attack surface.
- **Brez cash drawer kick command-a**.
- **En tiskalnik naenkrat**.
- **Background printing** — UIBackgroundModes vključuje `bluetooth-central`,
  ampak iOS-u privzeto suspendira aplikacijo po nekaj minutah; držite app v
  foreground-u med izdajo računa.

## Debug

```
# View console logs from connected iPhone
sudo log stream --predicate 'subsystem == "com.limebooking.printbridge"'  # or
xcrun simctl spawn booted log stream --predicate 'process == "LimePrintBridge"'
```

Najpogostejši problemi:
1. **"Connection not yet authorized"** v logih — uporabnik ni potrdil cert dialoga.
2. **"Ni povezanega tiskalnika"** — povezava je padla; pojdite v Settings, ponovno povežite.
3. **Tiskalnik ne pošilja vse vrstice** — MTU prevelik, `BlePrinter.maxChunk` cap je
   180 bajtov; zmanjšajte na 100 če cheap firmware.
4. **Slovenian Č/Š/Ž zamenjani** — printer ne podpira CP1250; v Lime printer
   settings spremenite `options.encoding` na `cp852`.
