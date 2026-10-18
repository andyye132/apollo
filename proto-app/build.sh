#!/usr/bin/env bash
# Build (and optionally install) the Apollo prototype without Gradle: plain Java + the Android SDK.
# Usage: proto-app/build.sh [--install]     --install also grants camera, step-counter and thermistor access.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
PKG=edu.uw.ubicomp.apolloproto
ADB=(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"})

rm -rf build && mkdir -p build/classes build/dex
javac --release 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" compile --dir res -o build/res.zip   # launcher icon and colours
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$JAR" -o build/unsigned.apk build/res.zip
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Apollo Proto Debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out build/apollo-proto.apk build/aligned.apk
echo "built proto-app/build/apollo-proto.apk"

if [ "${1:-}" = "--install" ]; then
  "${ADB[@]}" install -r build/apollo-proto.apk
  for p in CAMERA ACTIVITY_RECOGNITION DUMP BLUETOOTH_SCAN BLUETOOTH_CONNECT RECORD_AUDIO; do
    "${ADB[@]}" shell pm grant "$PKG" "android.permission.$p"
  done
  echo "installed; granted camera, activity recognition, thermistor (DUMP) and Bluetooth access"
fi
