"""Fit a phone-sized model from forehead skin temperature to oral (core) temperature.

Data: FDA "Facial and oral temperature data from a large set of human subject volunteers"
(Wang et al., PhysioNet 2023, CC0): 1,020 people, IR-camera forehead temperatures, oral
thermometer (monitor mode), room temperature. https://physionet.org/content/face-oral-temp-data/

Usage:
  python3 tools/skin_to_core.py            # downloads the CSV once into data/external/, fits, prints Java constants

Models compared with 5-fold cross-validation:
  linear        oral = a + b * skin
  linear+room   oral = a + b * skin + c * room
  heat balance  oral = skin + K * (skin - room) + d     (the form forehead thermometers use)
Reports mean absolute error, fever AUC, and the cutoff on the prediction that flags fever
(oral >= 37.5 °C) at about 90% specificity, since a regression's estimates shrink toward 37.
"""
import argparse
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd

URL = "https://physionet.org/files/face-oral-temp-data/1.0.0/FLIR_groups1and2.csv"
CACHE = Path(__file__).resolve().parent.parent / "data" / "external" / "FLIR_groups1and2.csv"
FEVER = 37.5


def load(path):
    df = pd.read_csv(path, header=2, encoding="utf-8-sig")
    rounds = range(1, 5)
    out = pd.DataFrame({
        "skin": df[[f"T_FHCC{i}" for i in rounds]].mean(axis=1),     # forehead centre, 3x3 px
        "offset": df[[f"T_offset{i}" for i in rounds]].mean(axis=1),
        "room": df["T_atm"],
        "oral": df["aveOralM"],
    }).dropna()
    return out


def design(d, model):
    one = np.ones(len(d))
    if model == "linear":
        return np.c_[one, d.skin]
    if model == "linear+room":
        return np.c_[one, d.skin, d.room]
    if model == "heat balance":   # target is oral - skin
        return np.c_[one, d.skin - d.room]
    raise ValueError(model)


def target(d, model):
    return d.oral - d.skin if model == "heat balance" else d.oral


def predict(d, model, beta):
    p = design(d, model) @ beta
    return p + d.skin.values if model == "heat balance" else p


def auc(score, y):
    r = pd.Series(score).rank().values
    pos, neg = y.sum(), len(y) - y.sum()
    return (r[y].sum() - pos * (pos + 1) / 2) / (pos * neg)


def evaluate(d, model, folds=5, seed=0):
    fold = np.random.default_rng(seed).integers(0, folds, len(d))
    pred = np.zeros(len(d))
    for k in range(folds):
        tr, te = fold != k, fold == k
        beta = np.linalg.lstsq(design(d[tr], model), target(d[tr], model).values, rcond=None)[0]
        pred[te] = predict(d[te], model, beta)
    y = (d.oral >= FEVER).values
    # Fever cutoff on the prediction: the lowest that keeps specificity >= 90% (chosen on all data; report as such).
    cut = np.quantile(pred[~y], 0.90)
    flag = pred >= cut
    return dict(mae=np.abs(pred - d.oral.values).mean(), auc=auc(pred, y), cut=cut,
                sens=(flag & y).sum() / y.sum(), spec=(~flag & ~y).sum() / (~y).sum(),
                sens375=((pred >= FEVER) & y).sum() / y.sum())


def main():
    # numpy + Apple's Accelerate BLAS print spurious divide-by-zero warnings from matmul; results are finite.
    np.seterr(all="ignore")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", default=str(CACHE))
    a = ap.parse_args()
    path = Path(a.csv)
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {URL}")
        urllib.request.urlretrieve(URL, path)
    d = load(path)
    y = d.oral >= FEVER
    print(f"{len(d)} people, {y.sum()} with oral >= {FEVER} °C; forehead {d.skin.mean():.2f}±{d.skin.std():.2f} °C, "
          f"room {d.room.min():.1f}-{d.room.max():.1f} °C")
    base = np.abs(d.oral - d.oral.median()).mean()
    print(f"\nbaseline (always {d.oral.median():.2f} °C): MAE {base:.3f}")
    print(f"{'model':24s} {'MAE':>6s} {'AUC':>5s}  fever flag at ~90% specificity     at the plain 37.5 cutoff")
    for skin_col, label in [("skin", ""), ("skin+offset", " (+T_offset)")]:
        dd = d.copy()
        if skin_col == "skin+offset":
            dd["skin"] = d.skin + d.offset
        for model in ["linear", "linear+room", "heat balance"]:
            r = evaluate(dd, model)
            print(f"{model + label:24s} {r['mae']:6.3f} {r['auc']:5.2f}  >= {r['cut']:.2f} °C: sens {r['sens']:.2f} spec {r['spec']:.2f}"
                  f"     sens {r['sens375']:.2f}")

    print("\nFinal fits on all data (paste into SkinToCore.java):")
    for model in ["linear+room", "heat balance"]:
        beta = np.linalg.lstsq(design(d, model), target(d, model).values, rcond=None)[0]
        if model == "linear+room":
            print(f"  linear+room:  oral = {beta[0]:.3f} + {beta[1]:.4f}*skin + {beta[2]:+.4f}*room")
        else:
            print(f"  heat balance: oral = skin + {beta[1]:.4f}*(skin - room) + {beta[0]:+.3f}")
        r = evaluate(d, model)
        print(f"                fever flag at prediction >= {r['cut']:.2f} °C (sens {r['sens']:.2f}, spec {r['spec']:.2f})")
    # Sensitivity of each model to a wrong room temperature (the phone can only guess it).
    for model in ["linear+room", "heat balance"]:
        beta = np.linalg.lstsq(design(d, model), target(d, model).values, rcond=None)[0]
        shifted = d.copy()
        shifted["room"] = d.room + 2
        print(f"  {model}: room temperature off by +2 °C shifts the estimate by "
              f"{np.mean(predict(shifted, model, beta) - predict(d, model, beta)):+.2f} °C")


if __name__ == "__main__":
    main()
