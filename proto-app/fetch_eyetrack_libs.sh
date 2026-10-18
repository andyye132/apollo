#!/usr/bin/env bash
# Downloads what passive eye tracking needs (MediaPipe Face Landmarker + its dependencies, and the model) so build.sh can
# add them without Gradle. Libraries go to proto-app/libs/, the model to proto-app/assets/. Neither is committed. Run once.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p libs assets
C=https://repo1.maven.org/maven2
G=https://maven.google.com
get() {   # repo group artifact version ext
  local out="libs/$3-$4.$5"
  [ -f "$out" ] || curl -fsSL -o "$out" "$1/$(echo "$2" | tr . /)/$3/$4/$3-$4.$5"
}
get $G com.google.mediapipe tasks-vision 1.0.0 aar
get $G com.google.mediapipe tasks-core 1.0.0 aar
get $C com.google.flogger flogger 0.6 jar
get $C com.google.flogger flogger-system-backend 0.6 jar
get $C com.google.guava guava 33.3.1-android jar
get $C com.google.guava failureaccess 1.0.2 jar
get $C com.google.protobuf protobuf-javalite 4.26.1 jar
get $C com.google.dagger dagger 2.28.3 jar
get $C javax.inject javax.inject 1 jar
get $G com.google.android.datatransport transport-api 3.0.0 aar
get $G com.google.android.datatransport transport-backend-cct 3.1.0 aar
get $G com.google.android.datatransport transport-runtime 3.1.0 aar
get $G com.google.firebase firebase-encoders 17.0.0 jar
get $G com.google.firebase firebase-encoders-json 18.0.0 aar
get $G com.google.firebase firebase-encoders-proto 16.0.0 jar
# Face Landmarker model (Apache 2.0, 3.7 MB)
[ -f assets/face_landmarker.task ] || curl -fsSL -o assets/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
echo "eye-tracking libraries and model are in place"
