"""Features of forced-exhale recordings, and a peak-flow calibration fit.

  python3 analyze_blow.py cue <cue.wav>                         write the three-beep countdown
  python3 analyze_blow.py blow <out_dir> <who> <trial> <model>  analyse <who>_<trial>.wav, append to blows.csv
  python3 analyze_blow.py reanalyze <out_dir> <model>           rebuild blows.csv from every <who>_<trial>.wav
  python3 analyze_blow.py fit <out_dir>                         join blows.csv with meter.csv and fit

Feature: the envelope of the 8-20 kHz band (turbulent-airflow hiss), as RMS in 20 ms frames every 5 ms.
Below ~300 Hz a blow is dominated by wind buffeting the phone, which varies with aim, not with flow: on
2026-10-17 three P1 blows varied 7 dB below 300 Hz but only 2.5 dB in 8-20 kHz. The wind band is logged as
wind_db for diagnosis. Blows with more than CLIP_OK full-scale samples are flagged and left out of the fit,
because clipping distorts every band. We fit L/min against both the envelope peak and its square root.
"""
import csv
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 48000
SKIP_S = 1.9  # countdown beeps play during the first ~1.8 s of each recording
HISS_HZ = (8000, 20000)
WIND_HZ = (20, 300)
CLIP_OK = 5  # a handful of full-scale samples is harmless; more means the blow distorted
# No wind exclusion: on 2026-10-17, windy blows (wind up to -8.7 dBFS) matched clean ones above 3 kHz, where the
# wind spectrum has died out, so the 8-20 kHz feature is unaffected. Wind only matters if it clips (checked above).
WIND_NOTE_DB = -20  # louder wind is reported, as a hint that clipping is getting close
FIELDS = ["participant", "trial", "model", "file", "peak_dbfs", "clipped_samples", "env_peak", "env_peak_db",
          "wind_db", "rise_ms", "dur_s", "energy_1s"]


def write_cue(path):
    beep = np.sin(2 * np.pi * 880 * np.arange(int(0.12 * RATE)) / RATE) * 0.3
    gap = np.zeros(int(0.6 * RATE))
    sig = np.concatenate([beep, gap, beep, gap, beep])
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((sig * 32767).astype("<i2").tobytes())


def envelope(x, band):
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    spec[(f < band[0]) | (f >= band[1])] = 0
    y = np.fft.irfft(spec, len(x))
    win, hop = int(0.02 * RATE), int(0.005 * RATE)
    frames = np.lib.stride_tricks.sliding_window_view(y, win)[::hop]
    return np.sqrt((frames ** 2).mean(1)), hop / RATE


def blow(out, who, trial, model, quiet=False):
    out = Path(out)
    f = out / f"{who}_{trial}.wav"
    with wave.open(str(f)) as w:
        raw = np.frombuffer(w.readframes(w.getnframes()), "<i2")
    raw = raw[int(SKIP_S * RATE):]                          # drop the countdown beeps
    x = raw / 32768.0
    env, dt = envelope(x, HISS_HZ)
    wind = envelope(x, WIND_HZ)[0].max()
    k = int(np.argmax(env))
    peak = env[k]
    floor = np.median(env)
    above = np.where(env > peak * 0.1)[0]                  # within 20 dB of the peak
    onset = above[0] if len(above) else k
    rise = (k - onset) * dt * 1000
    dur = (above[-1] - above[0]) * dt if len(above) else 0.0
    energy_1s = float((env[onset: onset + int(1 / dt)] ** 2).sum() * dt)
    clipped = int((np.abs(raw) >= 32700).sum())
    row = {"participant": who, "trial": trial, "model": model, "file": f.name,
           "peak_dbfs": round(20 * np.log10(np.abs(x).max() + 1e-12), 2), "clipped_samples": clipped,
           "env_peak": round(float(peak), 6), "env_peak_db": round(20 * np.log10(peak + 1e-12), 2),
           "wind_db": round(20 * np.log10(wind + 1e-12), 2),
           "rise_ms": round(rise, 1), "dur_s": round(dur, 2), "energy_1s": round(energy_1s, 8)}
    log = out / "blows.csv"
    new = not log.exists()
    with log.open("a", newline="") as fh:
        wr = csv.DictWriter(fh, FIELDS)
        if new:
            wr.writeheader()
        wr.writerow(row)
    snr = 20 * np.log10(peak / (floor + 1e-12))
    print(f"{who}_{trial}: hiss peak {row['env_peak_db']} dBFS (SNR {snr:.0f} dB), wind {row['wind_db']} dBFS, "
          f"sample peak {row['peak_dbfs']} dBFS, rise {rise:.0f} ms, blow {dur:.1f} s")
    if quiet:
        return
    if wind > 10 ** (WIND_NOTE_DB / 20):
        print(f"  windy (wind {row['wind_db']} dBFS): fine for the hiss feature, but aim a little higher to stay clear of clipping")
    if clipped > CLIP_OK:
        print(f"  CLIPPED ({clipped} samples at full scale): hold the phone farther away and redo this blow")
    elif row["peak_dbfs"] > -1:
        print("  near clipping: consider holding the phone a little farther away")
    if snr < 20:
        print("  weak blow or missed the window: redo it")


def fit(out):
    out = Path(out)
    blows = list(csv.DictReader((out / "blows.csv").open()))
    meter = [m for m in csv.DictReader((out / "meter.csv").open()) if (m.get("lpm") or "").strip()]
    ok = [b for b in blows if int(b["clipped_samples"]) <= CLIP_OK]

    def group(rows, key):
        g = {}
        for r in rows:
            g.setdefault(r["participant"], []).append(float(r[key]))
        return g

    def top3(v):  # mean of the best three efforts: steadier than a single best blow
        return float(np.mean(sorted(v)[-3:]))

    phone_all, lpm_all = group(ok, "env_peak"), group(meter, "lpm")
    phone = {p: top3(v) for p, v in phone_all.items()}
    lpm = {p: top3(v) for p, v in lpm_all.items()}
    ids = sorted(set(phone) & set(lpm))
    print(f"{len(blows)} blows ({len(blows) - len(ok)} clipped, excluded), {len(meter)} meter readings, "
          f"{len(ids)} participants with both")
    for p in ids:
        m, e = np.array(lpm_all[p]), 20 * np.log10(np.array(phone_all[p]))
        print(f"  {p}: meter top-3 {lpm[p]:.0f} L/min (all {m.mean():.0f} +/- {m.std():.0f}, n={len(m)}), "
              f"phone top-3 {20 * np.log10(phone[p]):.1f} dBFS (all {e.mean():.1f} +/- {e.std():.1f} dB, n={len(e)})")
    if len(ids) < 3:
        print("need at least 3 participants with both to fit")
        return
    y = np.array([lpm[p] for p in ids])
    for label, xs in [("envelope peak", np.array([phone[p] for p in ids])),
                      ("sqrt(envelope peak)", np.sqrt([phone[p] for p in ids]))]:
        a, b = np.polyfit(xs, y, 1)
        pred = a * xs + b
        loo = []
        for i in range(len(ids)):  # leave-one-participant-out
            m = np.ones(len(ids), bool)
            m[i] = False
            ai, bi = np.polyfit(xs[m], y[m], 1)
            loo.append(abs(ai * xs[i] + bi - y[i]))
        r = np.corrcoef(xs, y)[0, 1]
        print(f"{label:>20}: lpm = {a:.1f} * x + {b:.1f}   r = {r:.3f}   fit MAE {np.abs(pred - y).mean():.0f} L/min"
              f"   leave-one-out MAE {np.mean(loo):.0f} L/min")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "cue":
        write_cue(sys.argv[2])
    elif cmd == "blow":
        blow(*sys.argv[2:6])
    elif cmd == "reanalyze":
        d = Path(sys.argv[2])
        (d / "blows.csv").unlink(missing_ok=True)
        for f in sorted(d.glob("P*_*.wav")):
            who, trial = f.stem.split("_", 1)
            blow(d, who, trial, sys.argv[3], quiet=True)
    elif cmd == "fit":
        fit(sys.argv[2])
