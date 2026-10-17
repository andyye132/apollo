#!/system/bin/sh
# Runs ON the phone as the adb shell user, so it keeps logging after the USB cable is unplugged.
# Usage: sh fp_log_device.sh <seconds> <out.csv>
# Rows: epoch_s,sensor,type,value   (battery_api is the public BatteryManager value, in tenths of a degree C)
end=$(( $(date +%s) + $1 ))
: > "$2"
while [ "$(date +%s)" -le "$end" ]; do
  t=$(date +%s)
  dumpsys thermalservice \
    | awk -v t="$t" '/^Current temperatures from HAL/{f=1; next} f && !/^[ \t]/{f=0} f' \
    | sed -n "s/.*mValue=\([^,]*\), mType=\([^,]*\), mName=\([^,]*\),.*/$t,\3,\2,\1/p" >> "$2"
  dumpsys battery | sed -n "s/^ *temperature: *\([0-9-]*\).*/$t,battery_api,2,\1/p" >> "$2"
  sleep 2
done
echo "$(date +%s),__done__,0,0" >> "$2"
