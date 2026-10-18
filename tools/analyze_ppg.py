"""Heart rate from a camera PPG recording made by ppg-probe.

Usage:
  python3 tools/analyze_ppg.py data/ppg_<stamp>.csv [--ref BPM[,BPM...]]

If data/ppg_<stamp>_phases.csv exists (written by tools/hr_sites_test.sh), each placement is analysed
separately and --ref takes one reference per placement, in order (skip one with "-": --ref 72,-,75).
Otherwise the longest covered stretch is analysed as one segment.

Pipeline per segment: approximate red brightness -> resample to 30 Hz -> band-pass 0.7-4 Hz -> heart
rate two ways (spectral peak, and median beat-to-beat interval), which should agree. Two signals:
  centre  mean of the centre of the frame (what the first version used)
  skin    mean of the grid tiles that are red-lit skin, chosen by colour only (not by heart rate,
          so it can't flatter itself on noise)
The thresholds behind GOOD / WEAK / POOR are our own choices, not from a paper.
"""
import argparse
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy import signal

FS = 30.0
BAND = (0.7, 4.0)  # Hz, 42-240 bpm
HR_MAX_HZ = 3.5    # search ceiling for the spectral peak (210 bpm)
SETTLE_S = 6.0     # dropped at the start of each placement: moving the hand + AE re-lock


def load(path):
    d = pd.read_csv(path)
    d["t"] = (d.sensor_ns - d.sensor_ns.iloc[0]) / 1e9
    d["red"] = d.y + 1.402 * (d.v - 128)
    d["green"] = d.y - 0.344 * (d.u - 128) - 0.714 * (d.v - 128)
    d["covered"] = d.coverage >= 0.5 if "coverage" in d else (d.v - 128 > 15) & (d.y > 15)
    return d


def tiles(d):
    """Per-tile red, skin flag and V (frames x tiles), or None for recordings made before the grid."""
    ty = [c for c in d.columns if c.startswith("ty")]
    if not ty:
        return None
    y = d[ty].to_numpy(float)
    v = d[[c.replace("ty", "tv") for c in ty]].to_numpy(float)
    return y + 1.402 * (v - 128), (v - 128 > 15) & (y > 15), v


V_CLIPPED = 230  # V this high means the red channel is pinned at full scale and the pulse is clipped


def longest_covered(d):
    runs = (d.covered != d.covered.shift()).cumsum()
    groups = [g for _, g in d[d.covered].groupby(runs[d.covered])]
    if not groups:
        return None
    seg = max(groups, key=len)
    start = seg.t.iloc[0] + 3.0
    locked = seg[seg.ae_locked == 1]
    if len(locked):
        start = max(start, locked.t.iloc[0] + 1.0)
    return seg[seg.t >= start]


def resample(t, x):
    tu = np.arange(t[0], t[-1], 1 / FS)
    return tu, np.interp(tu, t, x)


def bandpass(x):
    sos = signal.butter(3, BAND, btype="bandpass", fs=FS, output="sos")
    return signal.sosfiltfilt(sos, x - x.mean())


def spectral_hr(x):
    """Peak of the Hann-windowed, zero-padded spectrum; SNR = power near the peak and its harmonic / band power."""
    n = max(len(x), 8192)
    f = np.fft.rfftfreq(n, 1 / FS)
    p = np.abs(np.fft.rfft(x * np.hanning(len(x)), n)) ** 2
    band = (f >= BAND[0]) & (f <= HR_MAX_HZ)
    peak = f[np.argmax(np.where(band, p, 0))]
    full = (f >= BAND[0]) & (f <= BAND[1])
    near = full & ((np.abs(f - peak) <= 0.1) | (np.abs(f - 2 * peak) <= 0.1))
    return 60 * peak, p[near].sum() / p[full].sum(), f, p


def find_beats(x):
    """Beats on the flipped signal (more blood = darker frame, so systole points up after flipping)."""
    pulse = -x
    peaks, _ = signal.find_peaks(pulse, distance=int(FS * 60 / 200), prominence=0.4 * np.std(pulse))
    peaks = peaks[(peaks > 0) & (peaks < len(pulse) - 1)]
    # Parabolic interpolation puts each beat between frames; at 30 fps the raw grid is 33 ms.
    y0, y1, y2 = pulse[peaks - 1], pulse[peaks], pulse[peaks + 1]
    den = y0 - 2 * y1 + y2
    shift = np.divide(0.5 * (y0 - y2), den, out=np.zeros_like(den), where=den != 0)
    return pulse, peaks, np.diff((peaks + shift) / FS)


def sliding_hr(x, win=10.0, step=2.0):
    n, s = int(win * FS), int(step * FS)
    rows = [((i + n / 2) / FS, *spectral_hr(x[i:i + n])[:2]) for i in range(0, len(x) - n + 1, s)]
    return np.array(rows).reshape(-1, 3)


def estimate(t, raw):
    """All heart-rate numbers for one brightness series."""
    tu, r = resample(t, raw)
    x = bandpass(r)
    hr_fft, snr, f, p = spectral_hr(x)
    pulse, peaks, ibi = find_beats(x)
    good = ibi[np.abs(ibi - np.median(ibi)) < 0.3 * np.median(ibi)] if len(ibi) else ibi
    hr_beats = 60 / np.median(good) if len(good) else np.nan
    agree = abs(hr_fft - hr_beats) <= 5
    hr_spectrum = hr_fft
    # Same rule as PpgAnalysis.java: breathing can split the spectrum into two peaks; the beats count as
    # confirmed when the spectrum has a peak at their rate at least half as tall, and it isn't a harmonic.
    if not agree and np.isfinite(hr_beats):
        band = (f >= 0.7) & (f <= 3.5)
        near = np.abs(f * 60 - hr_beats) <= 2
        ratio = p[near].max() / p[band].max() if near.any() and p[band].max() > 0 else 0
        hi, lo = max(hr_fft, hr_beats), min(hr_fft, hr_beats)
        if ratio >= 0.5 and abs(hi / lo - 2) >= 0.15:
            agree, hr_fft = True, hr_beats
    verdict = "GOOD" if snr >= 0.5 and agree else "WEAK" if snr >= 0.3 and agree else "POOR"
    return {
        "hr": hr_fft, "hr_spectrum": hr_spectrum, "snr": snr, "hr_beats": hr_beats, "agree": agree, "verdict": verdict,
        "beats": len(peaks), "rejected": len(ibi) - len(good), "ac_pct": 100 * np.ptp(np.percentile(x, [5, 95])) / r.mean(),
        "rmssd": 1000 * np.sqrt(np.mean(np.diff(good) ** 2)) if len(good) > 2 else np.nan,
        "tu": tu, "pulse": pulse, "peaks": peaks, "ibi": ibi, "f": f, "p": p, "windows": sliding_hr(x),
    }


def analyse(seg, tile_data):
    """Centre signal always; skin-tile signal when the grid exists and some tiles are skin."""
    t = seg.t.to_numpy()
    clipped = seg.v.median() > V_CLIPPED
    out = {"centre": estimate(t, (seg.green if clipped else seg.red).to_numpy()),
           "centre_channel": "green, red clipped" if clipped else "red"}
    if tile_data is not None:
        red_t, skin_t, v_t = (a[seg.index] for a in tile_data)
        skin = skin_t.mean(axis=0) >= 0.9          # skin in >= 90% of the segment's frames
        mask = skin & (np.median(v_t, axis=0) <= V_CLIPPED)
        out["skin_tiles"], out["clipped_tiles"] = int(mask.sum()), int((skin & ~mask).sum())
        if mask.any():
            out["skin"] = estimate(t, red_t[:, mask].mean(axis=1))
    out["coverage"] = seg.coverage.median() if "coverage" in seg else np.nan
    return out


def fmt(e):
    return f"{e['hr']:6.1f} bpm  SNR {e['snr']:.2f}  beats {e['hr_beats']:6.1f}  {e['verdict']}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", type=Path)
    ap.add_argument("--ref", help="reference bpm (comma-separated per placement; '-' to skip one)")
    args = ap.parse_args()

    d = load(args.csv)
    tile_data = tiles(d)
    dt = np.diff(d.t)
    print(f"frames {len(d)} over {d.t.iloc[-1]:.1f} s, {1 / np.median(dt):.2f} fps, "
          f"{(dt > 1.5 / FS).sum()} gaps > 50 ms, longest gap {dt.max() * 1000:.0f} ms")
    refs = [None if r.strip() in ("", "-") else float(r) for r in args.ref.split(",")] if args.ref else []

    phases_path = args.csv.with_name(args.csv.stem + "_phases.csv")
    if phases_path.exists():
        segments = []
        for _, ph in pd.read_csv(phases_path).iterrows():
            seg = d[(d.epoch_ms >= ph.start_ms) & (d.epoch_ms <= ph.end_ms - 1000)]
            start = ph.start_ms + SETTLE_S * 1000
            fixed = seg[seg.ae_locked == 1]                 # exposure settled for this placement
            if len(fixed):
                start = max(start, fixed.epoch_ms.iloc[0] + 1000)
            segments.append((ph.phase, seg[seg.epoch_ms >= start]))
    else:
        seg = longest_covered(d)
        segments = [("recording", seg if seg is not None else d.iloc[0:0])]

    results = []
    for i, (name, seg) in enumerate(segments):
        ref = refs[i] if i < len(refs) else None
        if len(seg) < FS * 10:
            print(f"\n[{name}] too short to analyse ({len(seg) / FS:.1f} s)")
            results.append((name, None, ref, seg))
            continue
        res = analyse(seg, tile_data)
        results.append((name, res, ref, seg))
        cover = "" if np.isnan(res["coverage"]) else f", skin coverage {100 * res['coverage']:.0f}%"
        print(f"\n[{name}] {seg.t.iloc[0]:.1f}-{seg.t.iloc[-1]:.1f} s{cover}, "
              f"exposure {seg.exposure_ns.median() / 1e6:.2f} ms, ISO {seg.iso.median():.0f}, "
              f"exposure fixed {100 * seg.ae_locked.mean():.0f}%")
        print(f"  centre: {fmt(res['centre'])}  ({res['centre_channel']})")
        if "skin" in res:
            print(f"  skin  : {fmt(res['skin'])}  ({res['skin_tiles']} of 24 tiles, {res['clipped_tiles']} clipped tiles left out)")
        elif "skin_tiles" in res:
            print(f"  skin  : no usable tile ({res['clipped_tiles']} skin tiles were clipped)")
        best = res.get("skin", res["centre"])
        w = best["windows"]
        if len(w):
            print(f"  10 s windows {w[:, 1].min():.0f}-{w[:, 1].max():.0f} bpm; pulse amplitude {best['ac_pct']:.2f}% "
                  f"of brightness")
        if ref is not None:
            print(f"  reference {ref:.0f} bpm -> error {best['hr'] - ref:+.1f} bpm")

    if len(results) > 1:
        print("\nsummary (skin signal where available)")
        print(f"  {'placement':<11}{'cover':>6}{'bpm':>8}{'SNR':>6}  {'quality':<8}{'ref':>5}{'error':>7}")
        for name, res, ref, _ in results:
            if res is None:
                print(f"  {name:<11}{'':>6}{'-':>8}")
                continue
            b = res.get("skin", res["centre"])
            err = f"{b['hr'] - ref:+.1f}" if ref is not None else ""
            cover = "" if np.isnan(res["coverage"]) else f"{100 * res['coverage']:.0f}%"
            print(f"  {name:<11}{cover:>6}{b['hr']:>8.1f}{b['snr']:>6.2f}  {b['verdict']:<8}"
                  f"{'' if ref is None else f'{ref:.0f}':>5}{err:>7}")

    png = args.csv.with_suffix(".png")
    plot(d, results, png)
    print(f"\nplot: {png}")


def plot(d, results, path):
    rows = [r for r in results if r[1] is not None]
    fig, axes = plt.subplots(1 + len(rows), 2, figsize=(12, 2.6 * (1 + len(rows))),
                             gridspec_kw={"width_ratios": [2.2, 1]}, squeeze=False)
    ax = axes[0, 0]
    ax.plot(d.t, d.red, color="#c0392b", lw=1, label="centre red")
    if "coverage" in d:
        ax2 = ax.twinx()
        ax2.plot(d.t, 100 * d.coverage, color="#7f8c8d", lw=0.8, alpha=0.7)
        ax2.set_ylabel("skin %", color="#7f8c8d")
        ax2.set_ylim(-5, 105)
    for name, res, _, seg in results:
        if len(seg):
            ax.axvspan(seg.t.iloc[0], seg.t.iloc[-1], color="#f4d03f", alpha=0.25)
            ax.text((seg.t.iloc[0] + seg.t.iloc[-1]) / 2, 1.01, name, transform=ax.get_xaxis_transform(),
                    ha="center", fontsize=8)
    ax.set_title("whole recording (shaded: analysed)", loc="left", fontsize=10, pad=14)
    ax.set_xlabel("s")
    axes[0, 1].axis("off")

    for i, (name, res, ref, _) in enumerate(rows, start=1):
        e = res.get("skin", res["centre"])
        ax = axes[i, 0]
        tu, pulse, peaks = e["tu"], e["pulse"], e["peaks"]
        show = tu >= tu[-1] - 12
        ax.plot(tu[show], pulse[show], color="#c0392b", lw=1.2)
        pk = peaks[show[peaks]]
        ax.plot(tu[pk], pulse[pk], "o", color="#1f4e8c", ms=3)
        ax.set_title(f"{name}: {'skin tiles' if 'skin' in res else 'centre'} pulse, last 12 s "
                     f"({e['hr']:.0f} bpm, {e['verdict']})", loc="left", fontsize=10)
        ax.set_yticks([])
        ax = axes[i, 1]
        m = (e["f"] >= 0.5) & (e["f"] <= 4)
        ax.plot(60 * e["f"][m], e["p"][m] / e["p"][m].max(), color="#333", lw=1)
        ax.axvline(e["hr"], color="#c0392b", ls="--", lw=1)
        if ref is not None:
            ax.axvline(ref, color="#1f4e8c", ls=":", lw=1.2)
        ax.set_title("spectrum (red = phone, blue = ref)", loc="left", fontsize=9)
        ax.set_xlabel("bpm")
        ax.set_yticks([])
    for a in axes.flat:
        a.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(path, dpi=120)


if __name__ == "__main__":
    main()
