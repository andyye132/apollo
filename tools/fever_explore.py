"""Explore every temperature run pulled from a phone: curves, features, motion, and honest model checks.

Reads results.jsonl plus the raw per-second CSVs (calib_*.csv, forehead_*.csv) and writes one JSON file
with everything the report page draws: each run's front-sensor curve aligned to the moment of contact,
the gyroscope trace, per-run features, and leave-one-run-out errors for several ways of turning a run
into a body temperature.

Usage:
  adb pull /sdcard/Android/data/edu.uw.ubicomp.apolloproto/files/. data/apollo/pixel4/
  python3 tools/fever_explore.py data/apollo/pixel4 out.json
"""
import csv
import json
import sys
from datetime import datetime
from pathlib import Path

import numpy as np

A, B, C_ROOM, ROOM = 23.495169, 0.421421, -0.042999, 24.0   # SkinToCore (FDA fit)
FRONT = ("pa-therm", "chg-therm")   # the two thermistors nearest the screen
CONTACT_PHASES = ("contact", "forehead")
STILL_GYRO = 0.015   # rad/s, Steadiness.STILL_GYRO_RAD_S
LAG_S = 10           # skip the first seconds of contact (heat takes time to reach the sensors)


def skin_for(body):
    return (body - A - C_ROOM * ROOM) / B


def body_for(skin):
    return A + B * skin + C_ROOM * ROOM


def slope(t, y):
    t, y = np.asarray(t), np.asarray(y)
    return float(np.polyfit(t, y, 1)[0] * 60) if len(t) >= 8 else float("nan")


def load(path):
    """{sensor: [(t, phase, value)]} from a raw run CSV."""
    by = {}
    with open(path) as f:
        for r in list(csv.reader(f))[1:]:
            by.setdefault(r[3], []).append((float(r[1]), r[2], float(r[5])))
    return by


def run_info(rec, path):
    by = load(path)
    ref = by[FRONT[0]]
    contact_t = [t for t, ph, _ in ref if ph in CONTACT_PHASES]
    c0, c1 = min(contact_t), max(contact_t)
    # Front sensors averaged, per second, relative to the start of contact.
    curves = {}
    for name in FRONT:
        curves[name] = {round(t - c0): v for t, ph, v in by.get(name, [])}
    secs = sorted(set(curves[FRONT[0]]) & set(curves[FRONT[1]]))
    front = [(s, (curves[FRONT[0]][s] + curves[FRONT[1]][s]) / 2) for s in secs]
    pre_phase = {}
    for t, ph, _ in ref:
        pre_phase.setdefault(ph, []).append(t - c0)
    tt = np.array([s for s, _ in front], float)
    yy = np.array([v for _, v in front])
    start = float(np.mean(yy[(tt >= -3) & (tt <= 0)])) if np.any((tt >= -3) & (tt <= 0)) else float(yy[np.argmin(abs(tt))])
    dur = c1 - c0
    m = (tt >= LAG_S) & (tt <= dur)
    rate = slope(tt[m], yy[m])
    at = lambda s: float(np.interp(s, tt, yy))
    # Drift: only seconds the phone lay still before contact, when the gyro log exists.
    gyro = [(t - c0, ph, v) for t, ph, v in by.get("_gyro", [])]
    still_pre = [(t, v) for t, ph, v in gyro if t < 0 and v < STILL_GYRO]
    drift = float("nan")
    if len(still_pre) >= 20:
        st = np.array([t for t, _ in still_pre])
        sm = (tt >= st.min()) & (tt <= st.max()) & np.isin(np.round(tt), np.round(st))
        drift = slope(tt[sm], yy[sm]) if sm.sum() >= 20 else float("nan")
    g_contact = np.array([v for t, ph, v in gyro if ph in CONTACT_PHASES])
    g_pre = np.array([v for t, ph, v in gyro if t < -12])   # before Start (the 10 s wait is pickup)
    feats = {
        "start_c": start,
        "rate": rate,
        "rate_net": rate - drift if not np.isnan(drift) else None,
        "drift": None if np.isnan(drift) else drift,
        "jump5": at(5) - start,
        "rise30": at(30) - start,
        "rise60": at(60) - start,
        "rise90": at(min(90, dur)) - start,
        "curv": (at(min(90, dur)) - at(50)) / 40 * 60 - (at(45) - at(5)) / 40 * 60,   # late slope minus early slope
        "gyro_contact_med": float(np.median(g_contact)) if len(g_contact) else None,
        "gyro_contact_p90": float(np.percentile(g_contact, 90)) if len(g_contact) else None,
        "still_frac_contact": float(np.mean(g_contact < STILL_GYRO)) if len(g_contact) else None,
        "gyro_pre_med": float(np.median(g_pre)) if len(g_pre) else None,
        "still_pre_s": int(np.sum(g_pre < STILL_GYRO)) if len(g_pre) else None,
        "pre_phases": {k: [round(min(v), 1), round(max(v), 1)] for k, v in pre_phase.items()},
        "contact_s": round(dur, 1),
    }
    # Every thermistor's contact-window slope: the front pair sits behind the screen, rcam/usbc at the back
    # and bottom edge where the hand holds the phone.
    per = {}
    for name in ("pa-therm", "chg-therm", "sdm-therm-monitor", "quiet-therm", "rcam-therm", "usbc-therm-monitor"):
        pts = [(t - c0, v) for t, ph, v in by.get(name, [])]
        if not pts:
            continue
        a = np.array(pts)
        mm = (a[:, 0] >= LAG_S) & (a[:, 0] <= dur)
        per[name] = slope(a[mm, 0], a[mm, 1])
    feats["sensor_rates"] = per
    if "rcam-therm" in per:
        feats["front_minus_rear"] = rate - per["rcam-therm"]
    series = [[s, round(v, 3)] for s, v in front if -100 <= s <= 100]
    rear = [[round(t - c0), round(v, 3)] for t, ph, v in by.get("rcam-therm", []) if -100 <= t - c0 <= 100]
    gseries = [[round(t, 1), round(v, 4)] for t, ph, v in gyro if -100 <= t <= 100]
    return feats, series, gseries, rear


def clean(v):
    """NaN and numpy scalars to plain JSON (NaN becomes null)."""
    if isinstance(v, dict):
        return {k: clean(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [clean(x) for x in v]
    if isinstance(v, (float, np.floating)):
        return None if np.isnan(v) else float(v)
    if isinstance(v, np.integer):
        return int(v)
    return v


def main(folder, out):
    folder = Path(folder)
    recs = [json.loads(l) for l in open(folder / "results.jsonl") if l.strip()]
    runs = []
    seen = set()
    for r in recs:
        if r.get("type") not in ("calibration", "control", "forehead") or not r.get("raw_file"):
            continue
        path = folder / r["raw_file"]
        if not path.exists():
            continue
        seen.add(r["raw_file"])
        feats, series, gseries, rear = run_info(r, path)
        body = r.get("body_c")
        if r.get("type") == "calibration" and body is None and r.get("ref_c"):
            body = r["ref_c"]   # the first run: body temperature typed into the skin box
        if r.get("type") == "forehead" and r.get("ref_c"):
            body = r["ref_c"]   # thermometer typed into the report
        label = r.get("person") or ""
        who = label.split("_")[0].upper() if label else ("AY?" if r["type"] != "control" else "")
        runs.append({
            "id": r["id"], "when": datetime.fromtimestamp(r["id"] / 1000).strftime("%b %d %H:%M"),
            "type": r["type"], "label": label, "who": who,
            "contact": r.get("contact") or ("forehead" if r["type"] != "control" else "none"),
            "protocol": r.get("protocol") or r.get("site"), "method": r.get("method"),
            "body_c": body, "unreliable": bool(r.get("unreliable")),
            "app_rate": r.get("rate_c_per_min"), "app_core": r.get("core_c_est"),
            "check_error_c": r.get("check_error_c"), "no_number": r.get("no_number_reason"),
            "app_sensors": [{k: s.get(k) for k in ("name", "rate_c_per_min", "k", "drift_c_per_min")} for s in r.get("sensors", [])],
            "f": feats, "series": series, "gyro": gseries, "rear": rear,
        })
    for p in sorted(folder.glob("calib_*.csv")):
        if p.name not in seen:   # a run that finished but was never saved
            feats, series, gseries, rear = run_info({}, p)
            ms = int(p.stem.split("_")[1])
            runs.append({"id": ms, "when": datetime.fromtimestamp(ms / 1000).strftime("%b %d %H:%M"), "type": "unsaved",
                         "label": "", "who": "", "contact": "forehead?", "protocol": None, "method": None, "body_c": None,
                         "unreliable": None, "f": feats, "series": series, "gyro": gseries, "rear": rear})
    runs.sort(key=lambda x: x["id"])

    # ---- Models, leave one run out, on forehead runs with a thermometer reading ------------------
    lab = [r for r in runs if r["contact"] == "forehead" and r["body_c"] and r["type"] in ("calibration", "forehead")]
    y = np.array([r["body_c"] for r in lab])
    X = {
        "start": np.array([r["f"]["start_c"] for r in lab]),
        "rate": np.array([r["f"]["rate"] for r in lab]),
        "rise30": np.array([r["f"]["rise30"] for r in lab]),
        "jump5": np.array([r["f"]["jump5"] for r in lab]),
        "rise90": np.array([r["f"]["rise90"] for r in lab]),
    }
    n = len(lab)
    X["rear"] = np.array([r["f"]["sensor_rates"].get("rcam-therm", np.nan) for r in lab])
    preds = {k: np.full(n, np.nan) for k in ("average", "k_physics", "hand_corrected", "linear_rate_start", "random_forest")}
    try:
        from sklearn.ensemble import RandomForestRegressor
    except ImportError:
        RandomForestRegressor = None
    for i in range(n):
        tr = np.arange(n) != i
        preds["average"][i] = y[tr].mean()
        skin_tr = skin_for(y[tr])
        k = np.median(X["rate"][tr] / (skin_tr - X["start"][tr]))
        preds["k_physics"][i] = body_for(X["start"][i] + X["rate"][i] / k)
        # Physics with the hand's heat: front rate = a * (skin - start) + b * rear rate
        G = np.c_[skin_tr - X["start"][tr], X["rear"][tr]]
        ab, *_ = np.linalg.lstsq(G, X["rate"][tr], rcond=None)
        preds["hand_corrected"][i] = body_for(X["start"][i] + (X["rate"][i] - ab[1] * X["rear"][i]) / ab[0])
        M = np.c_[np.ones(tr.sum()), X["rate"][tr], X["start"][tr]]
        coef, *_ = np.linalg.lstsq(M, y[tr], rcond=None)
        preds["linear_rate_start"][i] = coef @ [1, X["rate"][i], X["start"][i]]
        if RandomForestRegressor:
            F = np.c_[X["start"], X["rate"], X["rise30"], X["jump5"], X["rise90"]]
            rf = RandomForestRegressor(n_estimators=300, min_samples_leaf=1, random_state=0).fit(F[tr], y[tr])
            preds["random_forest"][i] = rf.predict(F[i:i + 1])[0]
    models = {}
    for k, p in preds.items():
        if np.all(np.isnan(p)):
            continue
        err = p - y
        models[k] = {"mae": float(np.mean(abs(err))), "max": float(np.max(abs(err))),
                     "pred": [round(float(v), 2) for v in p]}
    k_each = [float(r["f"]["rate"] / (skin_for(r["body_c"]) - r["f"]["start_c"])) for r in lab]
    out_obj = {
        "generated": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "runs": runs,
        "labelled": [{"id": r["id"], "who": r["who"], "label": r["label"], "when": r["when"], "body_c": r["body_c"],
                      "k": round(k_each[i], 4), "start": round(r["f"]["start_c"], 2), "rate": round(r["f"]["rate"], 3)}
                     for i, r in enumerate(lab)],
        "models": models,
        "label_range": [float(y.min()), float(y.max())] if n else None,
    }
    Path(out).write_text(json.dumps(clean(out_obj), indent=1, allow_nan=False))
    # A readable summary for the terminal
    print(f"{len(runs)} runs, {n} labelled forehead runs, thermometer range {y.min():.2f}-{y.max():.2f} °C")
    for r in runs:
        f = r["f"]
        print(f"{r['when']}  {r['type']:11s} {r['label'] or r['who']:12s} {r['contact']:9s} body={r['body_c']}  "
              f"start={f['start_c']:.2f} rate={f['rate']:+.3f} jump5={f['jump5']:+.2f} rise90={f['rise90']:+.2f} "
              f"drift={f['drift']} gyroC={f['gyro_contact_med']} stillC={f['still_frac_contact']} gyroPre={f['gyro_pre_med']} "
              f"stillPre={f['still_pre_s']} unrel={r['unreliable']}")
    for i, r in enumerate(lab):
        print(f"  k {r['who']:4s} {r['when']}: {k_each[i]:.3f}")
    for k, m in models.items():
        print(f"{k:18s} MAE {m['mae']:.2f} °C  max {m['max']:.2f}  preds {m['pred']}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
