#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=${BT:-$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)}
BT=${BT%/}
AJ=${AJ:-$SDK/platforms/android-34/android.jar}
if [ -n "${JAVA_HOME:-}" ]; then JBIN=$JAVA_HOME/bin; else JBIN=$(dirname "$(command -v javac)"); fi

KEYSTORE=${KEYSTORE:-keystore.jks}
KEYSTORE_PASS=${KEYSTORE_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-tvbluetooth}
OUT_APK=${OUT_APK:-tv-bluetooth.apk}

[ -f "$AJ" ] || { echo "Android platform 34 not found at $AJ" >&2; exit 1; }

rm -rf out && mkdir -p out/classes
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml out/res.zip
"$JBIN/javac" --release 11 -cp "$AJ" -d out/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && cp base.apk unsigned.apk && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  "$JBIN/keytool" -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Bluetooth" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --ks-key-alias "$KEY_ALIAS" --out "$OUT_APK" out/aligned.apk
echo "Built $OUT_APK"
