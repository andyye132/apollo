"""Rank a phone's thermistors by how cleanly they respond to forehead contact.

Usage: python3 tools/analyze_forehead.py data/forehead_<stamp>.csv

For each sensor it fits a line to each phase (FeverPhone's rate-of-change feature),
subtracts the phone's own drift measured just before contact, and reports a
signal-to-noise score. Writes a plot next to the CSV.
"""
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

SKIP = {"bcl-virt-monitor"}
PHASE_COLORS = {"table": "#eeeeee", "hand": "#fde9c8", "forehead": "#f9c9c9", "table2": "#eeeeee"}


def fit(t, y):
    """Least-squares slope (C/s), intercept, and residual std of y over t."""
    if len(t) < 3 or np.ptp(t) == 0:
        return np.nan, np.nan, np.nan
    m, b = np.polyfit(t, y, 1)
    resid = y - (m * t + b)
    return m, b, resid.std(ddof=2) if len(t) > 3 else np.nan


def step_size(y):
    d = np.abs(np.diff(np.unique(np.round(y, 4))))
    return d.min() if len(d) else np.nan


def analyze(df):
    phases = df.groupby("phase")["t_s"].agg(["min", "max"])
    contact_start = phases.loc["forehead", "min"]
    rows = []
    for name, g in df.groupby("sensor"):
        if name in SKIP:
            continue
        g = g.sort_values("t_s")
        t, y = g["t_s"].to_numpy(float), g["celsius"].to_numpy(float)

        # Drift = the phone's own trend in the 40 s of hand-holding right before contact.
        pre = (t >= contact_start - 40) & (t < contact_start)
        on = g["phase"].to_numpy() == "forehead"
        drift, _, _ = fit(t[pre], y[pre])
        slope, _, noise = fit(t[on], y[on])
        rise = y[on][-1] - y[on][0] if on.any() else np.nan
        rows.append(
            {
                "sensor": name,
                "type": int(g["type"].iloc[0]),
                "rest_C": y[g["phase"].to_numpy() == "table"][-5:].mean(),
                "step_C": step_size(y),
                "drift_C_per_min": drift * 60,
                "contact_slope_C_per_min": slope * 60,
                "corrected_C_per_min": (slope - drift) * 60,
                "rise_C": rise,
                "fit_noise_C": noise,
                "snr": (slope - drift) * 90 / noise if noise and noise > 0 else np.nan,
            }
        )
    out = pd.DataFrame(rows)
    # Sensors that move in >= 0.1 C jumps can't resolve a 90 s slope; a single jump inside the
    # 40 s drift window fakes a huge "corrected" slope. Rank them last.
    out["coarse"] = out["step_C"] >= 0.09  # float steps read as 0.0999...
    return out.sort_values(["coarse", "snr"], ascending=[True, False])


def plot(df, ranked, path):
    top = [s for s in ranked["sensor"].head(4)] + ["battery_api"]
    fig, ax = plt.subplots(figsize=(9, 4.8))
    for phase, g in df.groupby("phase"):
        if phase in PHASE_COLORS:
            ax.axvspan(g["t_s"].min(), g["t_s"].max(), color=PHASE_COLORS[phase], zorder=0)
            ax.text(g["t_s"].mean(), 1.01, phase, transform=ax.get_xaxis_transform(), ha="center", fontsize=9)
    for s in dict.fromkeys(top):
        g = df[df["sensor"] == s].sort_values("t_s")
        if len(g):
            ax.plot(g["t_s"], g["celsius"] - g["celsius"].iloc[0], label=s, lw=1.6)
    ax.set_xlabel("seconds since start")
    ax.set_ylabel("change from start (°C)")
    ax.legend(loc="upper left", fontsize=8, frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(path, dpi=150)


def touch_summary(path):
    """How well forehead contact shows up in the public touch API (FeverPhone's contact features)."""
    t = pd.read_csv(path)
    print(f"\ntouch events by phase: {t.groupby('phase').size().to_dict()}")
    print(f"actions: {t['action'].value_counts().to_dict()}")
    on = t[t["phase"] == "forehead"]
    if on.empty:
        print("no touch events during forehead contact")
        return
    span = on["t_s"].max() - on["t_s"].min()
    gaps = on["t_s"].sort_values().diff().fillna(0)
    print(
        f"forehead: {len(on)} events over {span:.1f} s of the 90 s window, longest gap {gaps.max():.1f} s, "
        f"max pointers {on['pointers'].max()}"
    )
    print(on[["x", "y", "touch_major", "touch_minor", "size", "pressure"]].describe().round(2).to_string())


def main():
    path = Path(sys.argv[1])
    df = pd.read_csv(path)
    ranked = analyze(df)
    pd.set_option("display.width", 200)
    print(ranked.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    png = path.with_suffix(".png")
    plot(df, ranked, png)
    print(f"\nplot: {png}")
    touch = path.with_name(path.stem + "_touch.csv")
    if touch.exists():
        touch_summary(touch)


if __name__ == "__main__":
    main()
