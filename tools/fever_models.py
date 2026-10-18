"""Compare ways of turning a forehead run into a body temperature, against thermometer readings.

Every hand-held run with a thermometer reading is a labelled example: calibration runs (body
reading typed into the calibration screen) and forehead tests (body reading typed into the report).
Each model predicts each run from the OTHER runs only (leave-one-run-out), so the errors are honest.

Models:
  average      always the mean of the other runs' readings: the bar every real model must beat
  k            physics: rate = k * (skin - phone start), k per sensor from the other runs (the app today)
  k + c        physics with a constant: rate = k * (skin - start) + c (hand and phone heating)
  rate + start FeverPhone-style formula without k: body = a + b * rate + c * start (their linear model,
               minus contact area/location, which need raw touchscreen data)
  rate         body = a + b * rate

Usage:
  adb pull /sdcard/Android/data/edu.uw.ubicomp.apolloproto/files/. data/apollo/
  python3 tools/fever_models.py data/apollo/results.jsonl

Skin <-> body uses SkinToCore's FDA fit. Sensors: the two front thermistors (pa-therm, chg-therm),
averaged. Runs from other procedures (the old 60 s hand pre-hold, the table protocol) are left out.
"""
import csv
import json
import sys
from pathlib import Path

import numpy as np

A, B, C_ROOM, ROOM = 23.495169, 0.421421, -0.042999, 24.0
FRONT = ("pa-therm", "chg-therm")
LAG_S, CONTACT_S = 10, 90


def skin_for(body):
    return (body - A - C_ROOM * ROOM) / B


def body_for(skin):
    return A + B * skin + C_ROOM * ROOM


def fit(t, y):
    return np.polyfit(t, y, 1)[0] * 60 if len(t) >= 10 else np.nan


def features(path):
    """Phone start temperature, warming rate (drift removed) and drift, averaged over the front sensors."""
    rows = list(csv.reader(open(path)))[1:]
    by = {}
    for r in rows:
        by.setdefault(r[3], []).append((float(r[1]), r[2], float(r[5])))
    contact = [t for t, ph, _ in by[FRONT[0]] if ph in ("contact", "forehead")]
    wait = [t for t, ph, _ in by[FRONT[0]] if ph == "wait"]
    c0 = min(contact)
    start = min(wait) if wait else c0 - 10
    still = {round(t): v > 0.5 for t, _, v in by.get("_still", [])}
    out = []
    for name in FRONT:
        pts = by.get(name, [])
        t = np.array([p[0] for p in pts]); y = np.array([p[2] for p in pts])
        if still:   # lying-still samples before Start (newer runs)
            base = np.array([still.get(round(x), False) and x < start for x in t])
            if base.sum() >= 20:
                last = t[base].max()
                base &= t >= last - 40
        else:       # older runs: the untouched part before Start
            base = (t >= start - 41) & (t < start - 1)
        on = (t >= c0 + LAG_S) & (t <= c0 + CONTACT_S)
        drift = fit(t[base], y[base]) if base.sum() >= 15 else np.nan
        rate = fit(t[on], y[on]) - (0 if np.isnan(drift) else drift)
        t0 = y[(t >= c0 - 3) & (t <= c0 + 0.5)].mean()
        out.append((t0, rate, drift))
    with np.errstate(all="ignore"):
        arr = np.array(out)
        t0, rate, drift = [np.nan if np.all(np.isnan(arr[:, i])) else np.nanmean(arr[:, i]) for i in range(3)]
    return dict(t0=t0, rate=rate, drift=drift)


def load(results):
    runs, base = [], Path(results).parent
    for line in open(results):
        o = json.loads(line)
        kind = o.get("type")
        if kind == "calibration" and o.get("site") == "hand" and o.get("contact", "forehead") == "forehead":
            body = o.get("body_c") or (o["ref_c"] if o["ref_c"] > 35.5 else None)   # older runs saved a body reading as ref_c
        elif kind == "forehead" and o.get("protocol") == "hand" and o.get("method", "").endswith(("v3", "v4")) and "ref_c" in o:
            body = o["ref_c"]
        else:
            continue
        if body is None or not (base / o["raw_file"]).exists():
            continue
        f = features(base / o["raw_file"])
        runs.append(dict(id=o["id"], who=o.get("person", "AN?"), kind=kind, body=float(body), **f))
    return runs


def controls(results):
    """Hand-only and phone-only control runs: warming that doesn't come from a forehead."""
    out, base = [], Path(results).parent
    for line in open(results):
        o = json.loads(line)
        if o.get("type") == "control" and (base / o["raw_file"]).exists():
            out.append(dict(who=o.get("person", "?"), contact=o.get("contact"), **features(base / o["raw_file"])))
    return out


def predict(model, train, run):
    y = np.array([r["body"] for r in train])
    rate = np.array([r["rate"] for r in train]); t0 = np.array([r["t0"] for r in train])
    gap = np.array([skin_for(r["body"]) for r in train]) - t0
    if model == "average":
        return y.mean()
    if model == "k":
        k = np.median(rate / gap)
        return body_for(run["t0"] + run["rate"] / k)
    if model == "k + c":
        if len(train) < 3:
            return np.nan
        k, c = np.polyfit(gap, rate, 1)
        return body_for(run["t0"] + (run["rate"] - c) / k) if k > 0 else np.nan
    if model == "rate + start":
        if len(train) < 4:
            return np.nan
        X = np.c_[np.ones(len(train)), rate, t0]
        a = np.linalg.lstsq(X, y, rcond=None)[0]
        return a @ [1, run["rate"], run["t0"]]
    if model == "rate":
        if len(train) < 3:
            return np.nan
        b, a = np.polyfit(rate, y, 1)
        return a + b * run["rate"]
    raise ValueError(model)


def main():
    runs = [r for r in load(sys.argv[1] if len(sys.argv) > 1 else "data/apollo/results.jsonl") if not np.isnan(r["rate"])]
    print(f"{len(runs)} hand-held runs with a thermometer reading:")
    for r in runs:
        print(f"  {r['id']} {r['who']:4s} {r['kind']:11s} body {r['body']:.2f} °C | phone start {r['t0']:.2f}, "
              f"warming {r['rate']:+.2f} °C/min, drift {r['drift']:+.2f}")
    ctl = controls(sys.argv[1] if len(sys.argv) > 1 else "data/apollo/results.jsonl")
    if ctl:
        print("\ncontrol runs (warming without a forehead; the 'c' in k + c):")
        for c in ctl:
            print(f"  {c['who']:4s} {'hand only' if c['contact'] == 'hand' else 'phone only':10s} phone start {c['t0']:.2f}, "
                  f"warming {c['rate']:+.2f} °C/min, drift {c['drift']:+.2f}")
    spread = max(r["body"] for r in runs) - min(r["body"] for r in runs) if runs else 0
    print(f"\nthermometer readings span {spread:.2f} °C" + ("  <- too narrow to show a model tracks temperature;"
          " 'average' wins by default. Add runs at different temperatures (e.g. a warm water bag)." if spread < 1 else ""))
    print(f"\n{'model':14s} {'mean error':>10s} {'worst':>7s}   predictions (each from the other runs)")
    for model in ["average", "k", "k + c", "rate + start", "rate"]:
        preds = [predict(model, [o for o in runs if o is not r], r) for r in runs]
        err = np.array([p - r["body"] for p, r in zip(preds, runs)])
        if np.all(np.isnan(err)):
            print(f"{model:14s} {'needs more runs':>18s}  (has {len(runs)}; needs {3 if model in ('k + c', 'rate') else 4}+ to train on, per prediction)")
            continue
        print(f"{model:14s} {np.nanmean(np.abs(err)):10.2f} {np.nanmax(np.abs(err)):7.2f}   "
              + "  ".join(f"{p:.2f}" if not np.isnan(p) else "-" for p in preds))


if __name__ == "__main__":
    main()
