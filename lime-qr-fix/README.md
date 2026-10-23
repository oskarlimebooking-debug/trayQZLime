# Lime QR Raster Fix

Minimalen Chrome extension, ki tiskalnikom brez podpore za QR kode (npr.
Rongta 58mm) omogoči tiskanje QR kod z Lime Booking računov.

## Kako deluje

Lime Booking pošlje račun v QZ Tray kot ESC/POS ukaze, kjer je QR koda zapisana
z nativnim ukazom `GS ( k` — starejši Rongta tiskalniki tega ukaza ne poznajo
in QR preprosto izpustijo. Extension se zatakne v `window.btoa()` (klic, s
katerim Lime Booking base64-kodira ESC/POS podatke, **preden** jih qz-tray
podpiše), najde `GS ( k` QR sekvenco, iz njene vsebine sam zgenerira QR matriko
in jo nadomesti z rastrsko sliko (`GS v 0`), ki jo zna natisniti praktično vsak
termalni tiskalnik. Ker se sprememba zgodi pred podpisovanjem, ostane QZ Tray
podpis veljaven — nič drugega se ne spremeni.

- FURS / FINA QR: samodejna velikost, ECC M
- UPN QR: prisilno verzija 15 + ECC M (zahteva bank), bajti ISO-8859-2 ohranjeni
- Slika je centrirana na širino papirja (58 mm = 384 pik, 80 mm = 576 pik)

## Namestitev

1. Odpri `chrome://extensions`
2. Vklopi **Developer mode** (zgoraj desno)
3. Klikni **Load unpacked** in izberi to mapo
4. Klikni ikono extensiona in vklopi stikalo **Vklopljeno**

## Uporaba

- Stikalo **Vklopljeno/Izklopljeno** — privzeto izklopljeno; vklopi ga samo na
  računalniku, kjer je priklopljen tiskalnik brez QR podpore.
- **Širina papirja** — 58 mm (privzeto) ali 80 mm.
- **Način slike** — `Raster (GS v 0)` deluje na skoraj vseh tiskalnikih;
  če tiskalnik nariše smeti, preklopi na `ESC * (starejši)`.
- Sprememba nastavitev velja takoj, strani ni treba osvežiti.

Ob vsaki pretvorbi se v konzoli (F12) izpiše
`[Lime QR Fix] Rewrote native ESC/POS QR command to raster image`.

## Testi

`node test/verify.js` — zgradi enak bajtni zapis, kot ga generira Lime Booking
frontend (`buildEscPosQrCode`), ga pretvori in rastrsko sliko dekodira z
neodvisnim dekoderjem (jsQR): FURS payload, `testQR` testni izpis, UPN QR
(verzija 15, ISO-8859-2), ESC * način, 80 mm ter no-op varnostni testi.

## Datoteke

- `manifest.json` — MV3, content skripti samo na `*.lime-booking.com`
- `main.js` — MAIN-world hook na `btoa`
- `core.js` — ESC/POS parser + QR → raster pretvorba
- `qr-lib.js` — qrcode-generator (MIT, Kazuhiko Arase)
- `bridge.js` — most za nastavitve (chrome.storage → DOM dataset)
- `popup.html/js` — on/off stikalo in nastavitvi
