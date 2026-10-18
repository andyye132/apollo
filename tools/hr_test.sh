#!/usr/bin/env bash
# Fingertip heart-rate smoke test with the PPG Probe app (ppg-probe/). The phone stays plugged in.
# Spoken cues: place finger -> settle -> 40 s recording -> done. Output: data/ppg_<stamp>.csv + .png
# Usage: tools/hr_test.sh [--ref BPM]   (--ref: a watch or oximeter reading, passed to the analysis)
set -u
cd "$(dirname "$0")/.."
ADB=(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"})
PKG=edu.uw.ubicomp.ppgprobe
DIR=/sdcard/Android/data/$PKG/files
stamp=$(date +%Y%m%d_%H%M%S)
mkdir -p data

"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP   # Android refuses the camera while the screen is off
"${ADB[@]}" shell am force-stop $PKG
"${ADB[@]}" shell am start -W -n $PKG/.PpgActivity >/dev/null
t0=$(date +%s)
cue() { echo "[t=$(( $(date +%s) - t0 ))s] $1"; say "$1" & }
at() { while [ $(( $(date +%s) - t0 )) -lt "$1" ]; do sleep 0.2; done; }

cue "Sit down and rest your arm on the table. Cover the camera and the flash on the back of the phone with your index fingertip. Press lightly."
at 14; cue "Recording. Hold still and breathe normally for forty seconds."
at 34; cue "Halfway. If you are wearing a watch that shows heart rate, glance at it now."
at 54; cue "Done. You can lift your finger."
at 56

"${ADB[@]}" shell input keyevent KEYCODE_HOME     # onPause closes the CSV
sleep 1
f=$("${ADB[@]}" shell "ls -t $DIR/ppg_*.csv | head -1" | tr -d '\r')
"${ADB[@]}" pull "$f" "data/ppg_${stamp}.csv" >/dev/null
"${ADB[@]}" shell am force-stop $PKG
echo "wrote data/ppg_${stamp}.csv"
python3 tools/analyze_ppg.py "data/ppg_${stamp}.csv" "$@"
