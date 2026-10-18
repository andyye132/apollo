#!/usr/bin/env bash
# Download MediaPipe's Face Landmarker model (Apache 2.0, ~3.7 MB) into the app's assets. Not committed to git.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p app/src/main/assets
curl -fL -o app/src/main/assets/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
echo "saved app/src/main/assets/face_landmarker.task ($(wc -c < app/src/main/assets/face_landmarker.task) bytes)"
