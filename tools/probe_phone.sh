#!/usr/bin/env bash
# Report which health-app sensing paths a connected Android phone supports.
# Usage: tools/probe_phone.sh            (uses the only connected device)
#        ADB_SERIAL=<serial> tools/probe_phone.sh
set -u

adb_shell() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell "$@" 2>&1 | tr -d '\r'; }

if ! adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} get-state >/dev/null 2>&1; then
  echo "No authorized device. Run 'adb devices -l' and accept the USB debugging prompt on the phone." >&2
  exit 1
fi

echo "== Device"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.hardware \
         ro.build.version.release ro.build.version.sdk ro.build.type ro.build.fingerprint \
         ro.oem_unlock_supported sys.oem_unlock_allowed ro.boot.flash.locked ro.boot.verifiedbootstate; do
  printf '  %-28s %s\n' "$p" "$(adb_shell getprop "$p")"
done

echo
echo "== Motion and environment sensors (sensorservice)"
adb_shell dumpsys sensorservice \
  | grep -E 'type: android\.sensor\.(pressure|step_counter|step_detector|accelerometer|gyroscope|ambient_temperature|heart_rate)\(' \
  | sed -E 's/^ *0x[0-9a-f]+\) */  /; s/ \| perm:.*//' | sort -u

echo
echo "== Thermal sensors visible to the shell (dumpsys thermalservice)"
adb_shell dumpsys thermalservice \
  | awk '/Current temperatures from HAL/{f=1;next} f&&/^[^ \t]/{f=0} f' \
  | grep -oE 'mValue=[^,]+, mType=[^,]+, mName=[^,]+' \
  | sed -E 's/mValue=([^,]+), mType=([^,]+), mName=(.+)/  \3\t\1 C\ttype=\2/' | column -t -s $'\t'

echo
echo "== Thermal headroom (public API path, no permission)"
adb_shell cmd thermalservice headroom 0 | sed 's/^/  /' | head -5

echo
echo "== Direct sysfs thermal read from shell (expect denied on stock builds)"
printf '  %s\n' "$(adb_shell cat /sys/class/thermal/thermal_zone0/type /sys/class/thermal/thermal_zone0/temp | tr '\n' ' ')"

echo
echo "== Root"
printf '  id: %s\n' "$(adb_shell id)"
printf '  su: %s\n' "$(adb_shell 'command -v su || echo none')"

echo
echo "== Raw touch driver nodes (existence only; reading them needs root)"
for n in /sys/devices/virtual/goog_touch_interface/gti.0 /proc/focaltech_touch \
         /sys/devices/virtual/sec/tsp /proc/fts/driver_test /sys/class/spi_master; do
  r=$(adb_shell "ls -d $n 2>&1")
  case "$r" in *"No such file"*) ;; *) printf '  %-55s %s\n' "$n" "$(echo "$r" | head -1)";; esac
done

echo
echo "== Rear camera flash and hardware level (media.camera)"
adb_shell dumpsys media.camera \
  | grep -E '== Camera device [0-9]+|android\.lens\.facing|android\.flash\.info\.available|android\.info\.supportedHardwareLevel' -A1 \
  | grep -E 'Camera device|\[|facing|available|HardwareLevel' | sed 's/^ */  /' | head -40

echo
echo "== Health Connect"
adb_shell pm list packages | grep -iE 'healthconnect|apps\.healthdata' | sed 's/^/  /' || echo "  not found"
