#!/usr/bin/env bash
# Download MediaPipe's Face Landmarker model (Apache 2.0, ~3.7 MB) for the Eye tracking screen.
# Same model as prototypes/eyetrack/fetch_model.sh. Not committed to git — run this after cloning.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p src/main/assets
curl -fL -o src/main/assets/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
echo "saved app/src/main/assets/face_landmarker.task ($(wc -c < src/main/assets/face_landmarker.task) bytes)"
