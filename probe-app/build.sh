#!/usr/bin/env bash
# Build, install, and grant DUMP to the FeverProbe app without Gradle.
# Needs Android SDK build-tools + a platform jar, and a JDK. Usage: probe-app/build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
PKG=edu.uw.ubicomp.feverprobe

rm -rf build && mkdir -p build/classes build/dex
javac --release 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$JAR" -o build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=FeverProbe Debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out build/feverprobe.apk build/aligned.apk
echo "built probe-app/build/feverprobe.apk"

if [ "${1:-}" = "--install" ]; then
  adb install -r build/feverprobe.apk
  adb shell pm grant "$PKG" android.permission.DUMP
  echo "installed and granted DUMP"
fi
