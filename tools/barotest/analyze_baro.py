"""Blow events in barometer recordings, and a peak-flow calibration fit.

  python3 analyze_baro.py events <cal_P1.csv> [...]          list blow events per recording
  python3 analyze_baro.py fit <meter.csv> <cal_P1.csv> ...   fit meter L/min against each person's blow peaks

Recordings are t_ns,hpa from BaroLogActivity (25 Hz on a Pixel 5). Baseline is a 4 s running median, so slow
weather and temperature drift drop out. An event is the barometer more than EVENT_PA above baseline; its feature is
the positive peak in pascals (the glass flexing inward under the jet). The rebound dip after each blow is ignored.
Physics: jet force ~ density * flow^2 / area, so flow ~ sqrt(pressure); both linear and sqrt fits are reported.
Participant IDs come from the file name (cal_<ID>.csv). Health data: IDs only, files stay out of git.
"""
import csv
import re
import sys
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

RATE = 25.0
EVENT_PA = 6.0       # ~4x the 1.6 Pa noise measured on the Pixel 5
MERGE_S = 0.5        # samples above threshold closer than this belong to one blow


def load(path):
    rows = []
    for line in Path(path).read_text().splitlines()[1:]:
        a = line.split(",")
        if len(a) == 2 and a[1]:
            try:
                rows.append((float(a[0]), float(a[1])))
            except ValueError:
                pass   # a half-written last line
    d = np.array(rows)
    return d[:, 0] / 1e9, d[:, 1] * 100


def events(path):
    t, p = load(path)
    base = np.median(sliding_window_view(np.pad(p, 50, mode="edge"), 101), axis=1)[: len(p)]
    dev = p - base
    idx = np.where(dev > EVENT_PA)[0]
    if not len(idx):
        return []
    out = []
    for g in np.split(idx, np.where(np.diff(idx) > MERGE_S * RATE)[0] + 1):
        k = g[np.argmax(dev[g])]
        area = float(dev[g].clip(min=0).sum() / RATE)
        out.append({"t": float(t[k]), "peak_pa": float(dev[k]), "area_pa_s": area, "n": len(g)})
    return out


def who(path):
    m = re.search(r"cal_([A-Za-z0-9]+)", Path(path).stem)
    return m.group(1) if m else Path(path).stem


def main():
    cmd = sys.argv[1]
    if cmd == "events":
        for f in sys.argv[2:]:
            ev = events(f)
            print(f"{Path(f).name}: {len(ev)} events")
            for e in ev:
                print(f"  t={e['t']:5.1f}s  peak {e['peak_pa']:+6.1f} Pa  area {e['area_pa_s']:5.1f} Pa*s")
    elif cmd == "fit":
        meter = {}
        for r in csv.DictReader(open(sys.argv[2])):
            if (r.get("lpm") or "").strip():
                meter.setdefault(r["participant"], []).append(float(r["lpm"]))
        people = {}
        for f in sys.argv[3:]:
            # Calibration recordings contain only maximal blows, so every event counts and each person's top 3 are
            # used. No absolute floor: a weak-lunged person's maximal blow can be small, and dropping it would bias the fit.
            peaks = sorted(e["peak_pa"] for e in events(f))
            people.setdefault(who(f), []).extend(peaks)
        ids = sorted(p for p in people if p in meter and people[p])
        y, x = [], []
        for p in ids:
            top = sorted(people[p])[-3:]
            lpm = float(np.mean(sorted(meter[p])[-3:]))
            spread = 100 * np.std(people[p]) / np.mean(people[p]) if len(people[p]) > 1 else float("nan")
            print(f"  {p}: meter top-3 {lpm:.0f} L/min | blows {', '.join(f'{v:.0f}' for v in sorted(people[p]))} Pa "
                  f"| top-3 mean {np.mean(top):.1f} Pa (spread {spread:.0f}%)")
            y.append(lpm)
            x.append(np.mean(top))
        if len(ids) < 3:
            print(f"{len(ids)} participants so far; need 3+ to fit")
            return
        y, x = np.array(y), np.array(x)
        for label, xs in [("peak Pa", x), ("sqrt(peak Pa)", np.sqrt(x))]:
            a, b = np.polyfit(xs, y, 1)
            loo = []
            for i in range(len(ids)):
                m = np.arange(len(ids)) != i
                ai, bi = np.polyfit(xs[m], y[m], 1)
                loo.append(abs(ai * xs[i] + bi - y[i]))
            print(f"{label:>14}: lpm = {a:.1f} * x + {b:.1f}   r = {np.corrcoef(xs, y)[0, 1]:.3f}   "
                  f"fit MAE {np.abs(a * xs + b - y).mean():.0f}   leave-one-out MAE {np.mean(loo):.0f} L/min")


if __name__ == "__main__":
    main()
