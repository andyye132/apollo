#!/usr/bin/env bash
# Build (and optionally install) the Apollo prototype without Gradle: plain Java + the Android SDK.
# Usage: proto-app/build.sh [--install]     --install also grants camera, step-counter and thermistor access.
set -euo pipefail
cd "$(dirname "$0")"
# Mac default SDK path; on Windows (Git Bash) fall back to Android Studio's default location.
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
[ -d "$SDK" ] || SDK=${LOCALAPPDATA:-}/Android/Sdk
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
PKG=edu.uw.ubicomp.apolloproto
ADB_BIN=$(command -v adb || echo "$SDK/platform-tools/adb")
ADB=("$ADB_BIN" ${ADB_SERIAL:+-s "$ADB_SERIAL"})
# Windows ships d8/apksigner as .bat wrappers; calling their jars directly works everywhere.
d8() { if [ -x "$BT/d8" ]; then "$BT/d8" "$@"; else java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"; fi; }
apksigner() { if [ -x "$BT/apksigner" ]; then "$BT/apksigner" "$@"; else java -jar "$BT/lib/apksigner.jar" "$@"; fi; }

rm -rf build && mkdir -p build/classes build/dex
javac --release 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
d8 --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" compile --dir res -o build/res.zip   # launcher icon and colours
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$JAR" -o build/unsigned.apk -A assets build/res.zip   # assets: the Inter font
if command -v zip >/dev/null; then (cd build/dex && zip -q -u ../unsigned.apk classes.dex)
else python3 -c "import zipfile; z = zipfile.ZipFile('build/unsigned.apk', 'a'); z.write('build/dex/classes.dex', 'classes.dex'); z.close()" 2>/dev/null   || python -c "import zipfile; z = zipfile.ZipFile('build/unsigned.apk', 'a'); z.write('build/dex/classes.dex', 'classes.dex'); z.close()"; fi
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Apollo Proto Debug" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --out build/apollo-proto.apk build/aligned.apk
echo "built proto-app/build/apollo-proto.apk"

if [ "${1:-}" = "--install" ]; then
  "${ADB[@]}" install -r build/apollo-proto.apk
  for p in CAMERA ACTIVITY_RECOGNITION DUMP BLUETOOTH_SCAN BLUETOOTH_CONNECT RECORD_AUDIO; do
    "${ADB[@]}" shell pm grant "$PKG" "android.permission.$p"
  done
  echo "installed; granted camera, activity recognition, thermistor (DUMP) and Bluetooth access"
fi
