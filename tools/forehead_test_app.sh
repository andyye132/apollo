#!/usr/bin/env bash
# FeverPhone forehead test using the FeverProbe app (probe-app/), which keeps the screen on and
# logs thermistors + touch on the phone. Spoken cues walk the user through
# table -> hand -> forehead -> table. Output: data/forehead_app_<stamp>.csv and _touch.csv
set -u
cd "$(dirname "$0")/.."
ADB=(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"})
PKG=edu.uw.ubicomp.feverprobe
DIR=/sdcard/Android/data/$PKG/files
stamp=$(date +%Y%m%d_%H%M%S)
mkdir -p data

PHASES=(
  "leadin   0   20 Get ready. In twenty seconds I will ask you to unplug the phone."
  "table   20  110 Unplug the USB cable now, and leave the phone face up on the table. Do not touch it."
  "hand   110  170 Pick up the phone. Hold it by its four corners, with the screen facing away from you."
  "forehead 170 260 Now press the screen flat against your forehead, sideways. Hold still."
  "table2 260  320 Done. Put the phone back on the table, face up."
)
TOTAL=320

"${ADB[@]}" shell am force-stop $PKG
"${ADB[@]}" shell am start -W -n $PKG/.ProbeActivity >/dev/null
offset_ms=$(( ($(date +%s) - $("${ADB[@]}" shell date +%s | tr -d '\r')) * 1000 ))   # toybox date has no %N; 1 s precision is enough
t0=$(date +%s)
echo "probe app started; clock offset mac-phone = ${offset_ms} ms"

for p in "${PHASES[@]}"; do
  read -r name start _ cue <<<"$p"
  while [ $(( $(date +%s) - t0 )) -lt "$start" ]; do sleep 0.2; done
  echo "[t=${start}s] ${name}: ${cue}"
  say "$cue" &
  [ "$name" = forehead ] && ( sleep 60; say "Thirty seconds left." ) &
done
while [ $(( $(date +%s) - t0 )) -lt "$TOTAL" ]; do sleep 0.5; done
say "Test complete. Plug the phone back in." &
echo "[t=${TOTAL}s] complete; waiting up to 10 min for the phone to be plugged back in"

if ! timeout 600 "${ADB[@]}" wait-for-device 2>/dev/null; then
  echo "phone not reconnected; pull later from $DIR" >&2
  exit 1
fi
"${ADB[@]}" shell am force-stop $PKG   # closes the CSVs
for kind in thermal touch; do
  f=$("${ADB[@]}" shell "ls -t $DIR/${kind}_*.csv | head -1" | tr -d '\r')
  "${ADB[@]}" pull "$f" "data/forehead_app_${stamp}_${kind}_raw.csv" >/dev/null
done

# Label rows with phase; t_s = seconds since t0 on the Mac clock. Headroom is scaled x30 so its
# slope reads in degrees C (one headroom unit = 30 C on Pixel skin sensors).
label='
  BEGIN { n = split("leadin:0 table:20 hand:110 forehead:170 table2:260 after:320", ph, " ") }
  function phase_of(t,   i, kv, p) { p = "before"; for (i = 1; i <= n; i++) { split(ph[i], kv, ":"); if (t >= kv[2]) p = kv[1] } return p }
'
awk -F, -v off="$offset_ms" -v t0="$t0" "$label"'
  NR == 1 { print "t_s,phase,sensor,type,celsius"; next }
  $2 ~ /^(dump_|error)/ { print "warning: " $0 > "/dev/stderr"; next }
  { t = ($1 + off) / 1000 - t0; v = ($2 == "headroom_api") ? $4 * 30 : $4; s = ($2 == "headroom_api") ? "headroom_x30" : $2
    printf "%.3f,%s,%s,%s,%s\n", t, phase_of(t), s, $3, v }' \
  "data/forehead_app_${stamp}_thermal_raw.csv" > "data/forehead_app_${stamp}.csv"
awk -F, -v off="$offset_ms" -v t0="$t0" "$label"'
  NR == 1 { print "t_s,phase," substr($0, index($0, ",") + 1); next }
  { t = ($1 + off) / 1000 - t0; printf "%.3f,%s,%s\n", t, phase_of(t), substr($0, index($0, ",") + 1) }' \
  "data/forehead_app_${stamp}_touch_raw.csv" > "data/forehead_app_${stamp}_touch.csv"
echo "wrote data/forehead_app_${stamp}.csv and data/forehead_app_${stamp}_touch.csv"
