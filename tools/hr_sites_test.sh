#!/usr/bin/env bash
# Which skin site works? Records fingertip, thumb pad, palm and wrist in one session with the PPG Probe
# app, then a face-down control that must NOT produce a heart rate. Spoken cues, ~3 minutes, phone
# plugged in. Wear the Fitbit on the other arm. Note its reading at each "read your Fitbit" cue, then:
#   python3 tools/analyze_ppg.py data/ppg_<stamp>.csv --ref 72,74,73,73
set -u
cd "$(dirname "$0")/.."
ADB=(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"})
PKG=edu.uw.ubicomp.ppgprobe
DIR=/sdcard/Android/data/$PKG/files
stamp=$(date +%Y%m%d_%H%M%S)
mkdir -p data

#        name      start end  cue
PHASES=(
  "fingertip  12   47 Cover the camera and the flash with your index fingertip. Press lightly and hold still."
  "thumb      47   82 Switch to the pad of your thumb. Cover the camera and the flash."
  "palm       82  117 Now press the fleshy part of your palm, just below your thumb, over the camera and the flash."
  "wrist     117  152 Now hold the camera and the flash against the inside of your wrist, where you would feel for a pulse."
  "control   152  177 Take your hand away and lay the phone face down on the table."
)
TOTAL=177

"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP   # Android refuses the camera while the screen is off
"${ADB[@]}" shell am force-stop $PKG
"${ADB[@]}" shell am start -W -n $PKG/.PpgActivity >/dev/null
offset_s=$(( $(date +%s) - $("${ADB[@]}" shell date +%s | tr -d '\r') ))   # mac - phone, 1 s precision
t0=$(date +%s)
at() { while [ $(( $(date +%s) - t0 )) -lt "$1" ]; do sleep 0.2; done; }

echo "[t=0s] get ready"
say "Sit with your arm resting on the table and the phone in your hand, camera side up. We will try four spots, then a check with no hand." &
phases_csv="data/ppg_${stamp}_phases.csv"
echo "phase,start_ms,end_ms" > "$phases_csv"
for p in "${PHASES[@]}"; do
  read -r name start end cue <<<"$p"
  at "$start"
  echo "[t=${start}s] ${name}: ${cue}"
  say "$cue" &
  echo "$name,$(( (t0 + start - offset_s) * 1000 )),$(( (t0 + end - offset_s) * 1000 ))" >> "$phases_csv"
  if [ "$name" != control ]; then
    at $(( start + 22 )); echo "[t=$(( start + 22 ))s] read the Fitbit (${name})"; say "Read your Fitbit now." &
  fi
done
at "$TOTAL"
say "Done." &

"${ADB[@]}" shell input keyevent KEYCODE_HOME     # onPause closes the CSV
sleep 1
f=$("${ADB[@]}" shell "ls -t $DIR/ppg_*.csv | head -1" | tr -d '\r')
"${ADB[@]}" pull "$f" "data/ppg_${stamp}.csv" >/dev/null
"${ADB[@]}" shell am force-stop $PKG
echo "wrote data/ppg_${stamp}.csv and $phases_csv"
python3 tools/analyze_ppg.py "data/ppg_${stamp}.csv" "$@"
echo
echo "add your Fitbit readings:  python3 tools/analyze_ppg.py data/ppg_${stamp}.csv --ref <fingertip>,<thumb>,<palm>,<wrist>"
