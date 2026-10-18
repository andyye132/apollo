"""Train the random forest the app uses to turn a 90 s forehead run into a body temperature.

Each labelled forehead run (a calibration with a thermometer reading, or a forehead test with a reading
typed into its report) becomes one example. Features come from tools/fever_explore.py, the same code the
app mirrors in FeverForest.java: the phone's start temperature, the warming rate, and the rise at 5, 30
and 90 s of contact (the two sensors behind the screen, averaged).

Labels are body temperatures. A skin reading (infrared thermometer in surface mode, "Skin / surface" in
the calibration screen) is turned into body temperature with SkinToCore's FDA formula.

Synthetic runs (--synthetic N): everyone measured so far is healthy, so the real readings span only ~0.4 °C
and a forest can't output a fever. Each real run is copied N times at other body temperatures (36.0-40.5 °C)
using the FeverPhone physics, rate = k * (skin - phone start): the run's whole warming curve is scaled by
(new skin - start) / (its skin - start), with the start moved by up to 1.5 °C and 10% noise. The copies keep the
real run's curve shape and noise. Scoring stays on real runs only: when a run is held out, its copies are too.

Writes proto-app/assets/fever_forest.txt (plain text, read by FeverForest.java), and prints the
leave-one-run-out error and the features and prediction of every run, for checking the app against.

Usage:
  python3 tools/train_fever_forest.py [--synthetic 20] data/apollo/pixel4 [more pulled folders...]
"""
import json
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
from sklearn.ensemble import RandomForestRegressor

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fever_explore import body_for, run_info, skin_for  # noqa: E402

FEATURES = ("start_c", "rate", "rise30", "jump5", "rise90")
OUT = Path(__file__).resolve().parent.parent / "proto-app" / "assets" / "fever_forest.txt"
TREES, SEED = 300, 0
# The first calibration (Oct 17 19:26) had a body temperature typed into the old skin box.
BODY_TYPED_AS_SKIN = {1792290413522}


def label(r):
    """Body temperature for a run, or None when it has no thermometer reading."""
    if r.get("type") == "calibration":
        if r.get("contact") not in (None, "forehead"):
            return None
        if r.get("body_c") is not None:
            return r["body_c"]
        if r.get("ref_c") is None:
            return None
        return r["ref_c"] if r["id"] in BODY_TYPED_AS_SKIN else body_for(r["ref_c"])   # a skin reading
    if r.get("type") == "forehead" and r.get("ref_c") is not None:
        return r["ref_c"]   # thermometer typed into the test's report (body temperature)
    return None


def collect(folders):
    rows, seen = [], set()
    for folder in map(Path, folders):
        for line in open(folder / "results.jsonl"):
            if not line.strip():
                continue
            r = json.loads(line)
            y = label(r)
            raw = r.get("raw_file")
            if y is None or not raw or r["id"] in seen or not (folder / raw).exists():
                continue
            seen.add(r["id"])
            f, *_ = run_info(r, folder / raw)
            x = [f[k] for k in FEATURES]
            if any(v is None or np.isnan(v) for v in x):
                continue
            who = (r.get("person") or "AY").split("_")[0].upper()   # unnamed runs are Andy's
            rows.append({"id": r["id"], "who": who, "file": str(folder / raw), "x": x, "y": float(y)})
    return rows


def forest():
    return RandomForestRegressor(n_estimators=TREES, min_samples_leaf=1, random_state=SEED)


def synthetic(x, y, n, rng, body=None):
    """n physics-scaled copies of one real run (features x, body temperature y) at other body temperatures."""
    gap = skin_for(y) - x[0]
    xs, ys = [], []
    for _ in range(n):
        b = rng.uniform(36.0, 40.5) if body is None else body
        start = float(np.clip(x[0] + rng.uniform(-1.5, 1.5), 23, 32))
        ratio = (skin_for(b) - start) / gap * rng.lognormal(0, 0.10)
        c = np.array(x, float)
        c[0] = start
        c[1:] *= ratio   # rate and every rise scale together
        xs.append(c); ys.append(b)
    return xs, ys


def train(X, y, keep, syn):
    """A forest on the real runs in `keep` plus their synthetic copies."""
    Xt = [X[i] for i in keep] + [v for i in keep for v in syn[i][0]]
    yt = [y[i] for i in keep] + [v for i in keep for v in syn[i][1]]
    return forest().fit(np.array(Xt), np.array(yt))


def main(folders, n_syn=0):
    rows = collect(folders)
    if len(rows) < 3:
        sys.exit(f"only {len(rows)} labelled runs: need at least 3")
    X = np.array([r["x"] for r in rows]); y = np.array([r["y"] for r in rows])
    rng = np.random.default_rng(SEED)
    syn = [synthetic(X[i], y[i], n_syn, rng) for i in range(len(y))]
    loo, probe = [], []
    for i in range(len(y)):
        f = train(X, y, [j for j in range(len(y)) if j != i], syn)
        loo.append(f.predict(X[i:i + 1])[0])
        if n_syn:   # the held-out run, made into a 38.5 °C fever: can the forest see it?
            px, _ = synthetic(X[i], y[i], 20, np.random.default_rng(1), body=38.5)
            probe.append(float(np.mean(f.predict(np.array(px)))))
    loo = np.array(loo)
    mae = float(np.mean(abs(loo - y)))
    m = train(X, y, range(len(y)), syn)
    people = sorted({r["who"] for r in rows})
    lines = [
        "# Apollo fever forest: sklearn RandomForestRegressor, written by tools/train_fever_forest.py",
        "# Node rows: feature threshold left right value. Leaves have a negative feature; go left when x <= threshold.",
        "features " + " ".join(FEATURES),
        f"meta runs={len(y)} people={len(people)} loo_mae={mae:.3f} lo={y.min():.2f} hi={y.max():.2f} "
        f"synthetic={n_syn * len(y)} fever_probe={np.mean(probe) if probe else float('nan'):.2f} trained={datetime.now():%Y-%m-%d}",
    ]
    for est in m.estimators_:
        t = est.tree_
        lines.append(f"tree {t.node_count}")
        for i in range(t.node_count):
            lines.append(f"{int(t.feature[i])} {float(t.threshold[i])!r} {int(t.children_left[i])} {int(t.children_right[i])} {float(t.value[i][0][0])!r}")
    OUT.write_text("\n".join(lines) + "\n")
    print(f"{len(y)} runs from {len(people)} people ({', '.join(people)}), readings {y.min():.2f}-{y.max():.2f} °C")
    print(f"leave-one-run-out error on real runs {mae:.3f} °C (always guessing the average: "
          f"{np.mean([abs(y[i] - np.delete(y, i).mean()) for i in range(len(y))]):.3f} °C)")
    print("held-out predictions:", " ".join(f"{p:.2f}/{t:.2f}" for p, t in zip(loo, y)), "(predicted/thermometer)")
    if probe:
        print(f"{n_syn * len(y)} synthetic runs. Held-out runs made into a 38.5 °C fever read {np.mean(probe):.2f} "
              f"(each: {' '.join(f'{v:.2f}' for v in probe)})")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
    full = m.predict(X)
    for r, p in zip(rows, full):   # for checking FeverForest.java against: file, features, prediction
        print("PARITY", r["file"], " ".join(f"{v:.6f}" for v in r["x"]), f"{p:.6f}")


if __name__ == "__main__":
    args = sys.argv[1:]
    n = 0
    if args[:1] == ["--synthetic"]:
        n, args = int(args[1]), args[2:]
    main(args or ["data/apollo/pixel4"], n)
