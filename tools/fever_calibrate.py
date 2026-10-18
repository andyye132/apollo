"""Calibrate FeverPhone's k per thermistor and measure how well it predicts temperature.

Physics (Newton's law of heating): at contact, a thermistor warms at
    slope_i = k_i * (T_target - T0_i)
where T0_i is that sensor's temperature just before contact. k_i (per minute) lumps contact
conductance, area, the sensor's distance from the glass and the phone's heat capacity, so it is
calibrated, not fitted: runs against a KNOWN temperature give k_i = slope_i / (T_ref - T0_i), and a
new run then gives T_i = T0_i + slope_i / k_i. The median over sensors is the estimate and the
spread over sensors is a quality flag.

Usage:
  python3 tools/fever_calibrate.py data/apollo/results.jsonl   # the app's calibration runs (adb pull first)
  python3 tools/fever_calibrate.py runs.csv                    # your own list of runs
  python3 tools/fever_calibrate.py --self-test                 # synthetic runs with a known answer

Pull the app's data first:  adb pull /sdcard/Android/data/edu.uw.ubicomp.apolloproto/files/. data/apollo/
With results.jsonl, every "Calibrate on your skin" run (and every forehead test that got a
calibration reading) is used, grouped by site and power mode.

runs.csv columns: raw_file,ref_c,kind
  raw_file  a forehead_<id>.csv pulled from the phone (adb pull .../files/.)
  ref_c     the true temperature of what the screen touched (water bag; or a forehead skin IR reading)
  kind      calib (has a reference), nocontact (phone held in the air: should read ~0 slope)

Leave-one-out: each calib run is predicted from k calibrated on all the OTHER runs. The mean
absolute error of those predictions is the honest accuracy number, the one to report.
Mirrors FeverAnalysis.java (drift over the 40 s before contact, slope from contact + 10 s,
T0 = mean of the 3 s before contact, sensors with 0.1 °C steps skipped).
"""
import argparse
import csv
import sys
from pathlib import Path

import numpy as np

LAG_S = 10
SKIP_PREFIX = ("headroom", "bcl-virt-monitor")
DRIFT_LIMIT = 0.1   # °C/min, the app's readiness rule


def fit(t, y):
    if len(t) < 3 or np.ptp(t) == 0:
        return np.nan, np.nan
    m, b = np.polyfit(t, y, 1)
    return m, (y - (m * t + b)).std()


def load(path):
    """{sensor: (t, celsius)} and the contact window (first/last 'forehead' sample)."""
    data, c0, c1 = {}, np.inf, -np.inf
    with open(path) as f:
        r = csv.reader(f)
        h = next(r)
        o = 1 if h[0] == "epoch_ms" else 0
        for row in r:
            t, phase, name = float(row[o]), row[o + 1], row[o + 2]
            if name.startswith(SKIP_PREFIX):
                continue
            if phase in ("forehead", "contact"):   # forehead test / calibration screen
                c0, c1 = min(c0, t), max(c1, t)
            data.setdefault(name, ([], []))
            data[name][0].append(t)
            data[name][1].append(float(row[o + 4]))
    return {k: (np.array(a), np.array(b)) for k, (a, b) in data.items()}, c0, c1


def sensors(data, c0, c1):
    """Per sensor: T0, drift, corrected slope (°C/min), noise. Coarse (0.1 °C) sensors dropped."""
    out = {}
    for name, (t, y) in data.items():
        steps = np.diff(np.unique(np.round(y, 4)))
        if len(steps) and steps.min() >= 0.09:
            continue
        pre = (t >= c0 - 40) & (t < c0)
        on = (t >= c0 + LAG_S) & (t <= c1)
        at = (t >= c0 - 3) & (t <= c0 + 0.5)
        if on.sum() < 10:
            continue
        drift, _ = fit(t[pre], y[pre])
        slope, noise = fit(t[on], y[on])
        drift = 0.0 if np.isnan(drift) else drift
        out[name] = dict(t0=y[at].mean() if at.any() else y[on][0], drift=drift * 60,
                         rate=(slope - drift) * 60, noise=noise)
    return out


def calibrate(runs):
    """Median k per sensor over runs that have a usable gap (reference >= 2 °C above the phone)."""
    ks = {}
    for run in runs:
        for name, s in run["sensors"].items():
            gap = run["ref_c"] - s["t0"]
            if gap >= 2 and s["rate"] > 0:
                ks.setdefault(name, []).append(s["rate"] / gap)
    return {n: float(np.median(v)) for n, v in ks.items()}, ks


def estimate(run, k):
    vals = {n: s["t0"] + s["rate"] / k[n] for n, s in run["sensors"].items() if n in k}
    if not vals:
        return np.nan, np.nan, vals
    v = np.array(list(vals.values()))
    return float(np.median(v)), float(v.std()) if len(v) > 1 else np.nan, vals


def report(runs):
    calib = [r for r in runs if r["kind"] == "calib"]
    print(f"{len(calib)} calibration run(s), {len(runs) - len(calib)} other\n")
    print("run                                ref °C  drift °C/min  best sensor rate")
    for r in runs:
        best = max(r["sensors"].items(), key=lambda kv: kv[1]["rate"] / max(kv[1]["noise"], 1e-3), default=None)
        drift = np.median([s["drift"] for s in r["sensors"].values()]) if r["sensors"] else np.nan
        flag = "  <- phone not steady" if abs(drift) > DRIFT_LIMIT else ""
        name = Path(r["file"]).name
        ref = f"{r['ref_c']:.2f}" if not np.isnan(r["ref_c"]) else "  -  "
        rate = f"{best[0]} {best[1]['rate']:+.2f}" if best else "-"
        print(f"{name:34s} {ref:>6s}  {drift:+12.2f}  {rate}{flag}")

    k, per_run = calibrate(calib)
    print("\nk per sensor (per minute): median, and spread across runs (CV; under ~15% is good)")
    for n in sorted(k, key=lambda n: -len(per_run[n])):
        v = np.array(per_run[n])
        cv = v.std() / v.mean() * 100 if len(v) > 1 else np.nan
        print(f"  {n:20s} k {k[n]:.3f}  runs {len(v)}  CV {cv:5.1f}%")

    if len(calib) >= 2:
        print("\nLeave-one-out (each run predicted from the others' k):")
        errs = []
        for i, r in enumerate(calib):
            k_other, _ = calibrate(calib[:i] + calib[i + 1:])
            est, spread, _ = estimate(r, k_other)
            if np.isnan(est):
                print(f"  {Path(r['file']).name}: no sensor in common")
                continue
            errs.append(est - r["ref_c"])
            print(f"  {Path(r['file']).name:34s} true {r['ref_c']:.2f}  est {est:.2f}  error {est - r['ref_c']:+.2f}  "
                  f"(sensor spread {spread:.2f})")
        if errs:
            e = np.array(errs)
            print(f"\n  MAE {np.abs(e).mean():.2f} °C, bias {e.mean():+.2f} °C, worst {np.abs(e).max():.2f} °C over {len(e)} runs")
            print("  (FeverPhone reported 0.23 °C MAE for core temperature on 37 people; aim for under 0.3 °C on the bench.)")
    for r in runs:
        if r["kind"] == "nocontact":
            est, _, _ = estimate(r, k)
            rates = [s["rate"] for s in r["sensors"].values()]
            print(f"\nNo-contact control {Path(r['file']).name}: median rate {np.median(rates):+.3f} °C/min "
                  f"(should be ~0); would read {est:.2f} °C, i.e. the phone's own temperature")
    return k


def load_results(path):
    """Calibration runs from the app's results.jsonl, grouped by (site, power mode)."""
    import json
    groups = {}
    base = Path(path).parent
    for line in open(path):
        o = json.loads(line)
        if o.get("type") == "calibration":
            site, ref = o.get("site", "arm"), o.get("ref_c")
        elif o.get("type") == "forehead" and "calibration_ref_c" in o:
            site, ref = o.get("protocol", "hand"), o.get("calibration_ref_c")
        else:
            continue
        f = base / o["raw_file"]
        if not f.exists():
            print(f"missing {f}, skipped")
            continue
        data, c0, c1 = load(f)
        run = dict(file=str(f) + (f"  ({o['person']})" if o.get("person") else ""), ref_c=float(ref), kind="calib",
                   sensors=sensors(data, c0, c1))
        groups.setdefault((site, o.get("power_mode", "?")), []).append(run)
    return groups


def load_runs(path):
    runs = []
    base = Path(path).parent
    with open(path) as f:
        for row in csv.DictReader(f):
            p = Path(row["raw_file"])
            if not p.exists():
                p = base / p
            data, c0, c1 = load(p)
            ref = float(row["ref_c"]) if row.get("ref_c") else np.nan
            runs.append(dict(file=str(p), ref_c=ref, kind=row.get("kind") or "calib", sensors=sensors(data, c0, c1)))
    return runs


def synth(path, skin, t_phone, drift, k_scale, rng, labels=("hand", "forehead")):
    """Writes a raw CSV like the app's: 60 s before, 90 s contact, 1 Hz, Newton heating after an 8 s lag."""
    base_k = {"pa-therm": 0.26, "chg-therm": 0.25, "quiet-therm": 0.20, "sdm-therm-monitor": 0.18}
    with open(path, "w") as f:
        f.write("epoch_ms,t_s,phase,sensor,type,celsius\n")
        for i, (name, k) in enumerate(base_k.items()):
            t0 = t_phone + 0.2 * i
            at_contact = t0 + drift * 1.0
            for t in range(151):
                if t < 60:
                    v, phase = t0 + drift * t / 60, labels[0]
                else:
                    tc = max(0, t - 68)
                    v = skin - (skin - at_contact) * np.exp(-k * k_scale * tc / 60) + drift * (t - 60) / 60
                    phase = labels[1]
                f.write(f"{t * 1000},{t:.3f},{phase},{name},3,{v + rng.normal(0, 0.004):.4f}\n")


def self_test(tmp, variation=0.10, quiet=False):
    """Known answer: 6 runs at known temperatures, contact firmness varying +-variation run to run."""
    rng = np.random.default_rng(4)
    tmp.mkdir(parents=True, exist_ok=True)
    rows = ["raw_file,ref_c,kind"]
    for i, skin in enumerate([34.0, 35.0, 36.0, 37.0, 38.0, 36.5]):
        p = tmp / f"synthetic_{i}.csv"
        synth(p, skin, rng.uniform(27, 31), rng.uniform(-0.08, 0.08), rng.uniform(1 - variation, 1 + variation), rng,
              ("hand", "forehead") if i % 2 == 0 else ("before", "contact"))
        rows.append(f"{p.name},{skin},calib")
    p = tmp / "synthetic_nocontact.csv"
    synth(p, 29.0, 29.0, 0.0, 1.0, rng)   # "skin" = phone temperature: no heat flows
    rows.append(f"{p.name},,nocontact")
    (tmp / "runs.csv").write_text("\n".join(rows) + "\n")
    runs = load_runs(tmp / "runs.csv")
    if not quiet:
        report(runs)
    calib = [r for r in runs if r["kind"] == "calib"]
    errs = []
    for i, r in enumerate(calib):
        k_other, _ = calibrate(calib[:i] + calib[i + 1:])
        errs.append(estimate(r, k_other)[0] - r["ref_c"])
    return float(np.abs(errs).mean())


def self_tests(tmp):
    mae_hand = self_test(tmp, 0.10)
    mae_table = self_test(tmp, 0.03, quiet=True)
    mae_exact = self_test(tmp, 0.0, quiet=True)
    print("\nself-test, leave-one-out MAE by how much contact firmness varies between runs:")
    print(f"  identical contact  {mae_exact:.2f} °C   (the method itself)")
    print(f"  +-3%  (table)      {mae_table:.2f} °C")
    print(f"  +-10% (hand-held)  {mae_hand:.2f} °C")
    ok = mae_exact < 0.1 and mae_table < mae_hand and mae_hand < 0.6
    print("PASS" if ok else "FAIL")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="?", help="runs.csv (raw_file,ref_c,kind)")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            ok = self_tests(Path(tmp))
        sys.exit(0 if ok else 1)
    if not a.runs:
        ap.error("give results.jsonl, runs.csv or --self-test")
    if a.runs.endswith(".jsonl"):
        groups = load_results(a.runs)
        if not groups:
            print("no calibration runs in", a.runs)
        for (site, mode), runs in groups.items():
            print(f"\n===== site {site}, power mode {mode} =====")
            report(runs)
    else:
        report(load_runs(a.runs))


if __name__ == "__main__":
    main()
