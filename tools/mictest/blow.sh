#!/usr/bin/env bash
# Record one forced-exhale blow at the phone (VOICE_RECOGNITION, 8 s) for peak-flow calibration.
# Recording starts, then three short beeps play from the laptop: blow on the third beep, as hard and fast as possible,
# phone at arm's length with the bottom edge toward your mouth. Prints the blow's features and a clipping check,
# and appends them to <out_dir>/blows.csv. Meter readings go in <out_dir>/meter.csv (participant,trial,lpm).
#
# Usage: tools/mictest/blow.sh <participant e.g. P1> <trial e.g. 1> [out_dir]
# Health data: use participant IDs, never names. out/ is gitignored; keep it that way.
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
who=$1; trial=$2
model=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')
out=${3:-out/peakflow_${model}_$(date +%Y%m%d)}
mkdir -p "$out"
name="${who}_${trial}"

[ -f "$out/cue.wav" ] || "$PY" analyze_blow.py cue "$out/cue.wav"
echo "$name: get ready... blow on the third beep"
# Recording starts first (-W waits for the activity), then the beeps play; analysis skips the first 1.9 s (the beeps).
"${ADB[@]}" shell am start -W -n $PKG/.MicTestActivity --ei source 6 --ei seconds 8 --es name "$name" >/dev/null
play "$out/cue.wav"
sleep 7.5
"${ADB[@]}" pull "/sdcard/Android/data/$PKG/files/$name.wav" "$out/$name.wav" >/dev/null
"$PY" analyze_blow.py blow "$out" "$who" "$trial" "$model"
