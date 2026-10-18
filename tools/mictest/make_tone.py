"""Write the stepped 1 kHz test tone and its schedule for the mic linearity test.

Usage: python3 tools/mictest/make_tone.py <out_dir>   -> <out_dir>/tone.wav, <out_dir>/schedule.json
"""
import json
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 48000
LEVELS_DB = [-36, -30, -24, -18, -12, -6, -12, -18, -24, -30, -36]
LEAD_S, STEP_S, GAP_S, RAMP_S = 2.0, 2.5, 0.5, 0.02


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    parts, schedule, t = [np.zeros(int(LEAD_S * RATE))], [], LEAD_S
    for db in LEVELS_DB:
        n, r = int(STEP_S * RATE), int(RAMP_S * RATE)
        env = np.ones(n)
        env[:r], env[-r:] = np.linspace(0, 1, r), np.linspace(1, 0, r)  # no clicks at step edges
        parts += [np.sin(2 * np.pi * 1000 * np.arange(n) / RATE) * 10 ** (db / 20) * env, np.zeros(int(GAP_S * RATE))]
        schedule.append({"db": db, "start": t, "end": t + STEP_S})
        t += STEP_S + GAP_S
    sig = np.concatenate(parts)
    with wave.open(str(out / "tone.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((sig * 32767).astype("<i2").tobytes())
    (out / "schedule.json").write_text(json.dumps(schedule))


if __name__ == "__main__":
    main()
