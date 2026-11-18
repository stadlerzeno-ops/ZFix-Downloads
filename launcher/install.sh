#!/bin/sh
# Installiert den ZFix-Launcher per WLAN-adb auf dem Autoradio.
# Aufruf: sh install.sh <IP:Port aus "Wireless Debugging"> [agila|ducato]
#   agila (Standard): Opel Agila wie bisher; DAB+-Kachel = App der USB-DAB-Box, sonst Noxon auf UKW 92.90
#   ducato: Fiat Ducato; DAB+-Kachel = App der USB-DAB-Box, unten Fahrzeugdaten vom OBD-Adapter (ELM327, Bluetooth)
set -e
cd "$(dirname "$0")"
ADB=${ADB:-adb}
LOGOS=${LOGOS:-$HOME/ZFix-Autoradio/logos/proj}
[ -n "$1" ] && $ADB connect "$1"
CAR=${2:-agila}
APK=$(ls out/ZFix-Launcher-*.apk ZFix-Launcher-*.apk 2>/dev/null | sort -V | tail -1)
$ADB install -r "$APK"
# Online-Update (ZFix-Menue > Update) darf APKs installieren
$ADB shell appops set ch.zfix.launcher REQUEST_INSTALL_PACKAGES allow || true
$ADB shell pm grant ch.zfix.launcher android.permission.ACCESS_FINE_LOCATION
$ADB shell pm grant ch.zfix.launcher android.permission.ACCESS_COARSE_LOCATION
$ADB shell pm grant ch.zfix.launcher android.permission.ACCESS_BACKGROUND_LOCATION
# OBD-Adapter (ELM327) per Bluetooth fuer die Fahrzeugdaten
$ADB shell pm grant ch.zfix.launcher android.permission.BLUETOOTH_CONNECT || true
$ADB shell pm grant ch.zfix.launcher android.permission.BLUETOOTH_SCAN || true
# Fahrzeugprofil (dieselbe Datei schreibt auch das ZFix-Deployment-Tool)
P=/sdcard/Android/data/ch.zfix.launcher/files
$ADB shell mkdir -p $P
if [ "$CAR" = ducato ]; then
  echo '{"fahrzeug":"ducato","name":"Fiat Ducato","dab":"auto","obd":"bt"}' > /tmp/zfix-profil.json
else
  echo '{"fahrzeug":"agila","name":"Opel Agila","dab":"auto","dab_freq":9290,"obd":"aus"}' > /tmp/zfix-profil.json
fi
$ADB push /tmp/zfix-profil.json $P/profil.json
# Wireless Debugging nach Neustart und nach jeder Fahrt automatisch wieder einschalten
# (wirkt nur im WLAN mit "In diesem Netzwerk immer zulassen" und nur fuer gekoppelte Computer)
$ADB shell pm grant ch.zfix.launcher android.permission.WRITE_SECURE_SETTINGS || true
# Nach dem Motorstart den ZFix-Startbildschirm statt der alten Radio-App zeigen
# (Nutzungszugriff, um die alte App zu erkennen; Einblenden erlaubt, um sich davor zu setzen)
$ADB shell appops set ch.zfix.launcher GET_USAGE_STATS allow || true
$ADB shell appops set ch.zfix.launcher SYSTEM_ALERT_WINDOW allow || true
# Google Maps als Fenster links neben der rechten Leiste (Fenstermodus erlauben)
$ADB shell settings put global enable_freeform_support 1
$ADB shell settings put global force_resizable_activities 1
# Standort (GPS) einschalten, sonst bleibt die Geschwindigkeit leer
$ADB shell settings put secure location_mode 3
$ADB shell cmd notification allow_listener ch.zfix.launcher/ch.zfix.launcher.MediaListener
$ADB shell cmd package set-home-activity ch.zfix.launcher/.MainActivity
# Offizielle CarPlay/Android-Auto-Logos fuer die Kacheln (ohne sie: weisse Umriss-Logos)
D=/sdcard/Android/data/ch.zfix.launcher/files/logos
$ADB shell mkdir -p $D
[ -f "$LOGOS/carplay.png" ] && $ADB push "$LOGOS/carplay.png" $D/carplay.png
[ -f "$LOGOS/aa_2026.png" ] && $ADB push "$LOGOS/aa_2026.png" $D/androidauto.png
# Tempolimit-Karten vom Mac (siehe tempolimit-karten.py), falls schon heruntergeladen
if [ -d tempolimit-kacheln ]; then
  $ADB shell mkdir -p /sdcard/Android/data/ch.zfix.launcher/files/limits
  $ADB push tempolimit-kacheln/. /sdcard/Android/data/ch.zfix.launcher/files/limits
fi
$ADB shell am force-stop ch.zfix.launcher
$ADB shell input keyevent KEYCODE_HOME
echo "Fertig: ZFix-Launcher ($CAR) ist installiert und Start-App."
# Rueckgaengig: adb shell cmd package set-home-activity altergames.carlauncher/<Activity>  oder  adb uninstall ch.zfix.launcher
