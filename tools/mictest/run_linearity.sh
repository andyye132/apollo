#!/usr/bin/env bash
# Mic linearity test: the laptop plays a 1 kHz tone stepping -36 -> -6 -> -36 dBFS in 6 dB steps while the phone
# records it from VOICE_RECOGNITION, MIC and VOICE_COMMUNICATION. A linear source records 6 dB per step both ways.
#
# Before running: turn OFF the laptop's audio enhancements (Windows: Settings > System > Sound > speakers >
# Audio enhancements; also any Realtek/Dolby/MaxxAudio app), set volume to ~40%, make sure sound comes out of the
# speaker next to the phone (not an HDMI display), and lay the phone's bottom edge 10-20 cm from it, unlocked.
# Laptop-side compression looks exactly like phone-side gain control; the enhancements must be off.
#
# Usage: tools/mictest/build.sh --install && tools/mictest/run_linearity.sh [out_dir]
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ADB_BIN=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe; PY=${PYTHON:-python}
                        play() { powershell -NoProfile -Command "(New-Object System.Media.SoundPlayer '$(cygpath -w "$1")').PlaySync()"; } ;;
  *)                    ADB_BIN=${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb; PY=${PYTHON:-python3}
                        play() { afplay "$1"; } ;;
esac
ADB=("$ADB_BIN" ${ADB_SERIAL:+-s "$ADB_SERIAL"})
PKG=edu.uw.ubicomp.mictest
FILES=/sdcard/Android/data/$PKG/files
model=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')
out=${1:-out/linearity_${model}_$(date +%Y%m%d_%H%M%S)}
mkdir -p "$out"

"$PY" make_tone.py "$out"
for run in 6:vr 1:mic 7:vc; do
  src=${run%%:*}; name=${run##*:}
  "${ADB[@]}" shell am start -n $PKG/.MicTestActivity --ei source "$src" --ei seconds 40 --es name "$name" >/dev/null
  sleep 1.5
  echo "playing tone for $name (source $src)"
  play "$out/tone.wav"
  sleep 6
  "${ADB[@]}" pull "$FILES/$name.wav" "$out/$name.wav" >/dev/null
  "${ADB[@]}" pull "$FILES/$name.txt" "$out/$name.txt" >/dev/null
done
"$PY" analyze_linearity.py "$out" | tee "$out/results.txt"
