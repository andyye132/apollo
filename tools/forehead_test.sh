#!/usr/bin/env bash
# FeverPhone forehead test: logs every thermistor on the phone while spoken cues walk the
# user through table -> hand -> forehead -> table. The phone logs on its own, so the cable
# can be unplugged to remove charging heat. Output: data/forehead_<stamp>.csv
set -u
cd "$(dirname "$0")/.."
ADB=(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"})
stamp=$(date +%Y%m%d_%H%M%S)
out="data/forehead_${stamp}.csv"
dev_log=/data/local/tmp/fp_raw.csv
mkdir -p data

# Phase schedule, seconds after start: name start end spoken-cue
PHASES=(
  "leadin   0   20 Get ready. In twenty seconds I will ask you to unplug the phone."
  "table   20  110 Unplug the USB cable now, and leave the phone face up on the table. Do not touch it."
  "hand   110  170 Pick up the phone. Hold it by its four corners, with the screen facing away from you."
  "forehead 170 260 Now press the screen flat against your forehead, sideways. Hold still."
  "table2 260  320 Done. Put the phone back on the table, face up."
)
TOTAL=320

old_mode=$("${ADB[@]}" shell settings get system screen_brightness_mode | tr -d '\r')
old_level=$("${ADB[@]}" shell settings get system screen_brightness | tr -d '\r')
"${ADB[@]}" shell settings put system screen_brightness_mode 0
"${ADB[@]}" shell settings put system screen_brightness 51   # about 20%, as in the paper

"${ADB[@]}" push tools/fp_log_device.sh /data/local/tmp/fp_log_device.sh >/dev/null
offset=$(( $(date +%s) - $("${ADB[@]}" shell date +%s | tr -d '\r') ))   # mac_epoch - phone_epoch
"${ADB[@]}" shell "setsid nohup sh /data/local/tmp/fp_log_device.sh $((TOTAL + 10)) $dev_log >/dev/null 2>&1 &"
t0=$(date +%s)
echo "logger started on phone; clock offset mac-phone = ${offset}s"

for p in "${PHASES[@]}"; do
  read -r name start _ cue <<<"$p"
  while [ $(( $(date +%s) - t0 )) -lt "$start" ]; do sleep 0.2; done
  echo "[t=${start}s] ${name}: ${cue}"
  say "$cue" &
  if [ "$name" = forehead ]; then
    ( sleep 60; say "Thirty seconds left." ) &
  fi
done
while [ $(( $(date +%s) - t0 )) -lt "$TOTAL" ]; do sleep 0.5; done
say "Test complete. Plug the phone back in." &
echo "[t=${TOTAL}s] complete; waiting up to 10 min for the phone to be plugged back in"

if ! timeout 600 "${ADB[@]}" wait-for-device 2>/dev/null; then
  echo "phone not reconnected; pull later with: adb pull $dev_log" >&2
  exit 1
fi
sleep 12   # let the logger write its last rows
"${ADB[@]}" pull "$dev_log" "data/forehead_${stamp}_raw.csv" >/dev/null
"${ADB[@]}" shell settings put system screen_brightness_mode "$old_mode"
"${ADB[@]}" shell settings put system screen_brightness "$old_level"

# Label each phone row with its phase (phone epoch + offset -> seconds since t0).
{
  echo "t_s,phase,sensor,type,celsius"
  awk -F, -v off="$offset" -v t0="$t0" '
    BEGIN { n = split("leadin:0 table:20 hand:110 forehead:170 table2:260 after:320", ph, " ") }
    $2 == "__done__" { next }
    {
      t = $1 + off - t0; phase = "before"
      for (i = 1; i <= n; i++) { split(ph[i], kv, ":"); if (t >= kv[2]) phase = kv[1] }
      v = ($2 == "battery_api") ? $4 / 10 : $4
      printf "%d,%s,%s,%s,%s\n", t, phase, $2, $3, v
    }' "data/forehead_${stamp}_raw.csv"
} > "$out"
echo "wrote $out ($(wc -l < "$out") rows)"
