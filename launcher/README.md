# ZFix Launcher

Startbildschirm für die Android-Autoradios im **Opel Agila** (Topway TS10) und im **Fiat Ducato** (K4811 PRO, UIS8581, Android 16).
Grosse Kacheln (CarPlay · Android Auto, Karte, DAB+, Radio), Uhr, Tempo mit Tempolimit, im Ducato Fahrzeugdaten vom OBD-Adapter.

## Update im Auto
ZFix-Menü (lange auf „ZFIX“ drücken) > **Update suchen**. Der Launcher lädt die neueste Version aus den
Branch **autoradio** von [ZFix-Downloads](https://github.com/stadlerzeno-ops/ZFix-Downloads/tree/autoradio) und installiert sie nach einer Bestätigung. Das Auto braucht dafür nur Internet (WLAN oder Handy-Hotspot).

Der Branch enthält die APKs und eine `versionen.json` (Paket, versionCode, Datei, SHA-256). Angeboten wird jede App aus der Liste, die auf dem Radio installiert ist.
ZFix DAB wird im Ducato auch dann angeboten, wenn es fehlt.

## Neue Version veröffentlichen
1. `versionCode` und `versionName` in `AndroidManifest.xml` erhöhen.
2. `./build.sh` (Werkzeuge in `$SDK`: aapt2, android.jar API 33, dalvik-dx.jar, apksig.jar, debug.keystore).
3. `python3 tools/versionen.py out/ZFix-Launcher-X.Y.apk ZFix-DAB-…apk` erzeugt `versionen.json`.
4. Die neuen APKs und `versionen.json` in den Branch `autoradio` von ZFix-Downloads committen
   (main mit den PC-Programmen bleibt unberührt). Alte APKs dort löschen.

Alle Launcher-Versionen sind mit demselben Schlüssel signiert (Zertifikat SHA-256 `1e08a903…5953`), sonst lehnt Android das Update ab.

## Erstinstallation
- Mit Mac: `sh install.sh <IP:Port> agila|ducato` (WLAN-adb) oder ZFix Deploy.
- Ohne Mac: APK per USB-Stick im Dateimanager des Radios öffnen. Beim ersten Start fragt der Launcher, in welchem Auto er läuft.
