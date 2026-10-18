"""Measure how linearly each recorded source tracks the stepped 1 kHz tone.

Usage: python3 tools/mictest/analyze_linearity.py <out_dir>   (expects schedule.json and vr/mic/vc.wav)

Only the 1 kHz component is measured (quadrature demodulation in 20 ms frames), so ordinary room noise barely
matters. For each step it takes the median level over the step's central 1.5 s, then reports the fitted slope of
recorded dB vs played dB (1.0 = linear), the worst deviation from that fit, and the up-vs-down mismatch, which
exposes adaptive gain (AGC, compressors, expanders, noise gates).
"""
import json
import sys
import wave
from pathlib import Path

import numpy as np

RATE, HOP = 48000, 0.02


def band_level(x):
    t = np.arange(len(x)) / RATE
    n = int(HOP * RATE)
    m = len(x) // n
    i = (x * np.cos(2 * np.pi * 1000 * t))[: m * n].reshape(m, n).mean(1)
    q = (x * np.sin(2 * np.pi * 1000 * t))[: m * n].reshape(m, n).mean(1)
    return 2 * np.hypot(i, q)


def main():
    out = Path(sys.argv[1])
    sched = json.loads((out / "schedule.json").read_text())
    expected = np.zeros(int(40 / HOP))
    for s in sched:
        expected[int(s["start"] / HOP): int(s["end"] / HOP)] = 10 ** (s["db"] / 20)
    for name in ["vr", "mic", "vc"]:
        path = out / f"{name}.wav"
        if not path.exists():
            continue
        with wave.open(str(path)) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), "<i2") / 32768.0
        env = band_level(x)
        exp = expected[: len(env)]
        lag = int(np.argmax(np.correlate(env, exp, "full")) - (len(exp) - 1))  # recording start vs tone start
        got = np.array([20 * np.log10(np.median(env[int((s["start"] + 0.5) / HOP) + lag: int((s["end"] - 0.5) / HOP) + lag]) + 1e-12)
                        for s in sched])
        gaps = np.concatenate([env[int((s["end"] + 0.1) / HOP) + lag: int((s["end"] + 0.4) / HOP) + lag] for s in sched])
        played = np.array([s["db"] for s in sched])
        slope, icpt = np.polyfit(played, got, 1)
        dev = np.abs(got - (slope * played + icpt)).max()
        hyst = np.abs(got[:6] - got[5:][::-1]).max()
        linear = abs(slope - 1) < 0.05 and dev < 1.0 and hyst < 1.0
        print(f"\n== {name}: offset {lag * HOP:+.2f} s, 1 kHz noise floor {20 * np.log10(np.median(gaps) + 1e-12):.1f} dBFS, "
              f"sample peak {20 * np.log10(np.abs(x).max() + 1e-12):.1f} dBFS")
        print("played dB  : " + " ".join(f"{p:6.0f}" for p in played))
        print("recorded dB: " + " ".join(f"{g:6.1f}" for g in got))
        print("step size  :        " + " ".join(f"{g:6.1f}" for g in np.diff(got)))
        print(f"slope {slope:.3f} (1 = linear), max deviation {dev:.2f} dB, up-vs-down {hyst:.2f} dB -> "
              f"{'LINEAR' if linear else 'NOT LINEAR'}")


if __name__ == "__main__":
    main()
