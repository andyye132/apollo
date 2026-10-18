#!/usr/bin/env bash
# Build (and optionally install) the BaroTest recorder without Gradle. Works on macOS and in Git Bash on Windows.
# Needs the Android SDK (build-tools + a platform) and Python 3. Uses Android Studio's bundled JDK if found.
# Usage: tools/barotest/build.sh [--install]      (set ADB_SERIAL to pick a phone)
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SDK=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}; EXE=.exe
                        JBR=${JAVA_BIN:-"/c/Program Files/Android/Android Studio/jbr/bin"}; PY=${PYTHON:-python} ;;
  *)                    SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}; EXE=
                        JBR=${JAVA_BIN:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin"}; PY=${PYTHON:-python3} ;;
esac
[ -x "$JBR/javac$EXE" ] || JBR=$(dirname "$(command -v javac)")
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
ADB=("$SDK/platform-tools/adb$EXE" ${ADB_SERIAL:+-s "$ADB_SERIAL"})
PKG=edu.uw.ubicomp.barotest

rm -rf build && mkdir -p build/classes build/dex
"$JBR/javac$EXE" --release 11 -nowarn -cp "$JAR" -d build/classes $(find src -name '*.java')
"$JBR/java$EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$JAR" \
  --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2$EXE" link --manifest AndroidManifest.xml -I "$JAR" -o build/unsigned.apk
"$PY" -c "import zipfile; z = zipfile.ZipFile('build/unsigned.apk', 'a'); z.write('build/dex/classes.dex', 'classes.dex'); z.close()"
"$BT/zipalign$EXE" -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || "$JBR/keytool$EXE" -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BaroTest Debug" >/dev/null 2>&1
"$JBR/java$EXE" -jar "$BT/lib/apksigner.jar" sign --ks debug.keystore --ks-pass pass:android \
  --out build/barotest.apk build/aligned.apk 2>&1 | grep -v WARNING || true
echo "built tools/barotest/build/barotest.apk"

if [ "${1:-}" = "--install" ]; then
  "${ADB[@]}" install -r build/barotest.apk
  echo "installed"
fi
