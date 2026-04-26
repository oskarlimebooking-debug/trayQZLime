# Lime Print Bridge — Android

Android APK ki omogoča tiskanje računov iz [Lime Booking](https://app.lime-booking.com)
PWA-ja na **Bluetooth termalne tiskalnike** (POS-58, POS-80) iz telefona.

Lime Booking PWA tiska preko **QZ Tray** — Java daemona, ki teče samo na desktopih.
Ta APK na Androidu igra vlogo QZ Tray, tako da je Lime PWA nedotaknjena in misli,
da govori s pravim QZ Tray daemonom.

## Arhitektura

```
+--------------------------+   in-process JS bridge   +--------------------+
|  WebView                 |  ◄──────────────────►   |  QzMessageRouter   |
|  app.lime-booking.com    |   (window.WebSocket     |  (Kotlin)          |
|  qz-tray.js              |    monkey-patched)      |                    |
+--------------------------+                          +─────────┬──────────+
                                                                ▼
                                                       +────────────────────+
                                                       |  EscPosPrinter     |
                                                       |  Bluetooth SPP →   |
                                                       |  POS-58 termal     |
                                                       +────────────────────+
```

WebView naloži `https://app.lime-booking.com`. Pred prvo navigacijo `MainActivity`
v WebView injectira [`QzBridge.SHIM_JS`](app/src/main/java/com/limebooking/printbridge/qz/QzBridge.kt),
ki nadomesti `window.WebSocket` z fake objektom. Ko qz-tray.js poskusi odpreti
`wss://localhost:8181`, dobi naš fake socket, ki preusmeri sporočila skozi
`AndroidQzBridge` (JavascriptInterface) v native Kotlin `QzMessageRouter`.

Router razume celoten qz-tray.js call set ki ga Lime uporablja:
`getProgramName`, `getVersion`, `printers.find/getDefault/details`, `print`, ...

`print` payload (iz qz-tray.js, glej referenco v `js/qz-tray.js:1737-1744`)
vsebuje serijo `{type, format, flavor, data}` chunkov. Te razčlenimo v
[`PrintJob`](app/src/main/java/com/limebooking/printbridge/printer/PrintJob.kt):

- `type:raw, format:command, flavor:plain` → ESC/POS bytes (CP1250 za Č/Š/Ž)
- `type:raw, format:command, flavor:base64|hex` → decode → bytes
- `type:raw|pixel, format:image, flavor:base64` → raster bitmap (`GS v 0`)

Final byte stream gre preko Bluetooth RFCOMM SPP socket (UUID
`00001101-0000-1000-8000-00805F9B34FB`) na izbrani printer.

## Zakaj JS bridge namesto pravega WSS strežnika?

WebView **silently zavrne** self-signed TLS cert na `new WebSocket()` —
`WebViewClient.onReceivedSslError` se NE proži za WebSocket, samo za HTTP
resource loads. Edina uporabna pot v WebView-u je intercept JavaScript-a.

Pravi WSS strežnik na `localhost:8181` se vseeno zažene (v `QzService`
foreground service-u) za testiranje iz zunaj — npr. iz desktop curl-a z
pinned cert-om. Glej [`QzServer`](app/src/main/java/com/limebooking/printbridge/qz/QzServer.kt).

## Build

Potrebuješ JDK 17+ in Android SDK 34.

### Z Android Studio (priporočeno)

1. `File → Open` → izberi `android/` direktorij.
2. Studio sam pobere Gradle wrapper jar in vse odvisnosti.
3. `Run → Run 'app'` na povezan telefon (USB debugging ON).

### Z ukazno vrstico

```bash
cd android

# Prvič: bootstrap wrapper jar (potreben Gradle ali odprtje v Studio)
gradle wrapper --gradle-version 8.9

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Uporaba

1. **Sparite Bluetooth tiskalnik** v sistemskih nastavitvah Androida
   (Settings → Bluetooth → Pair). Vklop tiskalnika, dolg pritisk gumba feed
   za pairing mode (odvisno od modela).
2. Odpri **Lime Print Bridge**. Dovoli Bluetooth dovoljenja.
3. Izberi sparjeni tiskalnik iz seznama.
4. Klikni **Testno tiskanje** — preveriš, da povezava deluje (sprintaj
   "Lime Print Bridge OK" + Č/Š/Ž).
5. Izberi širino papirja (33 stolpcev za 58mm, 48 za 80mm).
6. Klikni **Odpri Lime Booking** → naloži se PWA v WebView.
7. Login v Lime, pojdi na blagajno, izdaj račun, klikni Cash/Card.
8. PWA bi morala kazati snackbar "Invoice was successfully created" **brez**
   print error-ja, na termalniku se sprintaj račun s številko, ZOI, EOR in QR kodo.

## Datoteke

| Pot | Vsebina |
|-----|---------|
| [`MainActivity.kt`](app/src/main/java/com/limebooking/printbridge/MainActivity.kt) | WebView + SSL override + bridge wiring |
| [`ui/SettingsActivity.kt`](app/src/main/java/com/limebooking/printbridge/ui/SettingsActivity.kt) | Compose UI: izbira printerja, test print |
| [`qz/QzBridge.kt`](app/src/main/java/com/limebooking/printbridge/qz/QzBridge.kt) | JavascriptInterface + SHIM_JS za WebSocket monkey-patch |
| [`qz/QzServer.kt`](app/src/main/java/com/limebooking/printbridge/qz/QzServer.kt) | Real WSS server (port 8181, za zunanje teste) |
| [`qz/QzMessageRouter.kt`](app/src/main/java/com/limebooking/printbridge/qz/QzMessageRouter.kt) | Protokol dispatcher — porten iz `src/qz/ws/PrintSocketClient.java` |
| [`qz/QzCertManager.kt`](app/src/main/java/com/limebooking/printbridge/qz/QzCertManager.kt) | Self-signed TLS cert generation (BouncyCastle) |
| [`qz/QzAllowedCerts.kt`](app/src/main/java/com/limebooking/printbridge/qz/QzAllowedCerts.kt) | Whitelist sprejetih PWA cert-ov |
| [`qz/QzService.kt`](app/src/main/java/com/limebooking/printbridge/qz/QzService.kt) | Foreground service — drži strežnik živ |
| [`printer/EscPosPrinter.kt`](app/src/main/java/com/limebooking/printbridge/printer/EscPosPrinter.kt) | Top-level print orchestrator |
| [`printer/PrintJob.kt`](app/src/main/java/com/limebooking/printbridge/printer/PrintJob.kt) | qz JSON `data[]` → ESC/POS byte stream |
| [`printer/ImageRasterizer.kt`](app/src/main/java/com/limebooking/printbridge/printer/ImageRasterizer.kt) | base64 PNG → ESC/POS `GS v 0` raster |
| [`printer/BluetoothTransport.kt`](app/src/main/java/com/limebooking/printbridge/printer/BluetoothTransport.kt) | RFCOMM SPP socket s chunked write |
| [`printer/PrinterRegistry.kt`](app/src/main/java/com/limebooking/printbridge/printer/PrinterRegistry.kt) | Paired BT naprave + privzeti printer pref |

## Reference v upstream QZ Tray (this repo)

Implementacija strogo sledi protokolu desktop QZ Tray daemona, ki je v tem repo-ju:

- [`src/qz/ws/PrintSocketClient.java`](../src/qz/ws/PrintSocketClient.java) — JSON envelope handling
- [`src/qz/ws/SocketMethod.java`](../src/qz/ws/SocketMethod.java) — call name enum
- [`src/qz/common/Constants.java`](../src/qz/common/Constants.java) — port defaults, PROBE_REQUEST/RESPONSE
- [`src/qz/auth/Certificate.java`](../src/qz/auth/Certificate.java) — signature verify (porten ampak v MVP-ju nezahtevan)
- [`js/qz-tray.js`](../js/qz-tray.js) — browser-side library (specifically `qz.print` at line 1704)
- [`sample.html`](../sample.html) — print test fixtures

## Omejitve (MVP)

- Samo **Bluetooth Classic SPP** (ni USB OTG ne network printerjev).
- Samo **POS-58 testiran** (POS-80 bi moral delati, koda je width-agnostic).
- **Brez signature verification** (MVP zaupa localhost-only attack surface-u).
- **Brez cash drawer kick command-a**.
- **En printer naenkrat** (multi-printer support v naslednji iteraciji).

## Kako pomagati

Logiranje preko `adb logcat | grep -E 'QzBridge|QzMessageRouter|QzServer|EscPosPrinter|BluetoothTransport'`.

Najpogostejši problemi:
1. **"Connection not yet authorized"** v logih — uporabnik ni potrdil cert dialoga.
2. **"Printer not selected"** — pojdi v Settings, izberi printer.
3. **"createRfcommSocket... failed"** — printer ni vklopljen ali pa je v power-save modu;
   poskusi feed gumb pritisniti enkrat.
4. **Slovenian Č/Š/Ž zamenjani** — printer ne podpira CP1250; spremeni `options.encoding`
   v Lime printer settings na `cp852`.
