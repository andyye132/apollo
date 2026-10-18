# MicTest: is the phone's microphone linear?

The lung features (SpiroSmart's forced exhale, the sustained-vowel test) read lung function from the **loudness envelope** of the recording. That only works if the phone's audio path has no adaptive gain:

- automatic gain control
- compressors
- expanders
- noise gates

This tool checks for that.

- `MicTestActivity` records 48 kHz mono from one Android `AudioSource` and forces every available pre-processing effect off. It writes a WAV and a text file listing the source, the routed mic and the effect states.
- `run_linearity.sh` plays a 1 kHz tone stepping −36 → −6 → −36 dBFS in 6 dB steps from the laptop, while the phone records it from three sources.
- `analyze_linearity.py` measures the 1 kHz level per step. A linear source records 6 dB per step, the same going up and coming down.

```bash
tools/mictest/build.sh --install          # needs the Android SDK; Git Bash on Windows
tools/mictest/run_linearity.sh            # results in tools/mictest/out/linearity_<model>_<stamp>/
```

**Before running, turn off the laptop's audio enhancements, and set the volume to about 40%.** Our first run showed strong compression on every source. It came from Windows audio processing, not the phone: with enhancements off, the same phone measured linear. Also check that sound plays from a speaker next to the phone, not from an HDMI display. Place the phone's bottom edge 10–20 cm from that speaker, screen unlocked. Room noise doesn't matter, because only the 1 kHz component is measured.

## Results (2026-10-17)

Neither phone supports `AudioSource.UNPROCESSED`: `config_supportAudioSourceUnprocessed = false` on both. Both route every source below to the **bottom** mic. Both apply framework pre-processing (AEC, NS) only to `voice_communication`. Automatic gain control is not offered as an effect on any of these sources.

| Source | Pixel 4 (Android 13, Snapdragon 855) | Pixel 7 (Android 15, Tensor G2) |
| --- | --- | --- |
| `VOICE_RECOGNITION` (6) | ✅ slope 0.992, max dev 0.27 dB, up/down 0.46 dB | ✅ slope 0.975, max dev 0.71 dB, up/down 0.88 dB |
| `MIC` (1) | ❌ slope 1.164, max dev 3.0 dB. Quiet steps are pushed down (10.7 dB for a 6 dB step): an expander in the audio DSP | ✅ slope 0.997, max dev 0.31 dB |
| `VOICE_COMMUNICATION` (7) | ❌ noise gate: everything below about −24 dBFS is cut to the noise floor | ❌ adaptive: the first steps are squashed, the last one overshoots |

**Use `VOICE_RECOGNITION`.**

- It's the only source that's linear on both phones, which is consistent with Android's CDD requiring AGC and noise reduction off for it.
- It also has the most headroom: about 11 dB less gain than `MIC` on the Pixel 7. That matters for hard blows.
- **Don't use `MIC` as a fallback.** It's fine on the Pixel 7, but on the Pixel 4 its DSP processing is invisible in every config file.
- **Never use `VOICE_COMMUNICATION`.**

**Not yet tested:**

- **Levels above −6 dBFS played:** a hard blow may clip the mic, so check the peak level of real blows.
- **Frequencies other than 1 kHz.**
- **Absolute level:** it differs between phones and placements, and the per-phone peak-flow calibration absorbs it.
