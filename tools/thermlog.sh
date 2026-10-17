#!/usr/bin/env bash
# Log every shell-visible thermal sensor to CSV, for the FeverPhone forehead test.
# Usage: tools/thermlog.sh [seconds=150] [interval=2] > run.csv
# Protocol: start logging, hold the phone in hand 30 s, press the screen to the
# forehead (landscape, fingers on the corners) for 90 s, then lift it.
set -u
dur=${1:-150}
step=${2:-2}

adb_shell() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell "$@" 2>/dev/null | tr -d '\r'; }

echo "t_s,sensor,type,celsius,headroom"
start=$(date +%s)
while :; do
  now=$(date +%s); t=$((now - start))
  [ "$t" -gt "$dur" ] && break
  hr=$(adb_shell cmd thermalservice headroom 0 | grep -oE '[-0-9.]+(e-?[0-9]+)?|NaN' | tail -1)
  adb_shell dumpsys thermalservice \
    | awk '/Current temperatures from HAL/{f=1;next} f&&/^[^ \t]/{f=0} f' \
    | grep -oE 'mValue=[^,]+, mType=[^,]+, mName=[^,]+' \
    | sed -E "s/mValue=([^,]+), mType=([^,]+), mName=(.+)/$t,\3,\2,\1,${hr:-}/"
  sleep "$step"
done
