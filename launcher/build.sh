#!/bin/sh
# Baut den ZFix-Launcher ohne Gradle: aapt2 -> javac -> dx -> zipalign-frei -> apksig (v2).
# Werkzeuge in $SDK: aapt2, android.jar (API 33), dalvik-dx.jar, apksig.jar, debug.keystore (Passwort android)
set -e
cd "$(dirname "$0")"
SDK=${SDK:-$HOME/sdk}
VER=$(sed -n 's/.*versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
rm -rf build; mkdir -p build/gen build/classes build/dex out
$SDK/aapt2 compile --dir res -o build/res.zip
$SDK/aapt2 link -I $SDK/android.jar --manifest AndroidManifest.xml -o build/base.apk --java build/gen build/res.zip
javac --release 8 -nowarn -encoding UTF-8 -classpath $SDK/android.jar -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v "^warning\|^Note\|bootstrap" || true
java -cp $SDK/dalvik-dx.jar com.android.dx.command.Main --dex --min-sdk-version=26 --output=build/dex/classes.dex build/classes
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED -cp $SDK/apksig.jar:tools tools/Sign.java $SDK/debug.keystore build/unsigned.apk out/ZFix-Launcher-$VER.apk
echo "gebaut: out/ZFix-Launcher-$VER.apk"
