#!/usr/bin/env bash
# Run the app's analysis code (Dsp, PpgAnalysis, FeverAnalysis) on recorded CSVs on the Mac, to compare
# with tools/analyze_ppg.py and tools/analyze_forehead.py. No phone needed.
# Usage: proto-app/check.sh core data/ppg_<stamp>.csv data/forehead_app_<stamp>.csv data/watch/ble_raw.csv ...
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d)
javac -d "$out" proto-app/src/edu/uw/ubicomp/apolloproto/{Dsp,PpgAnalysis,FeverAnalysis,WatchDecoder,Sonar,JitaTrigger,TouchWorld,SkinToCore,ExposureControl,WatchAnalysis,JoltDetector,Steadiness,FeverForest,ReadingCheck,BlowDetector,EyeMetrics,EyeLog,FloorCounter}.java \
  proto-app/test/edu/uw/ubicomp/apolloproto/{Check,WatchCheck,CoreCheck}.java
for f in "$@"; do
  case "$f" in
    core) java -cp "$out" edu.uw.ubicomp.apolloproto.CoreCheck ;;   # known-answer tests for the cores
    *ble_raw*) java -cp "$out" edu.uw.ubicomp.apolloproto.WatchCheck "$f" ;;   # captured watch Bluetooth packets
    *) java -cp "$out" edu.uw.ubicomp.apolloproto.Check "$f" ;;
  esac
done
