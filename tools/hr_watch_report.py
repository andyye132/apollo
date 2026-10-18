"""Phone (Apollo) vs WILD watch heart-rate report: one HTML page per watch session.

Usage:
  python3 tools/hr_watch_report.py data/watch/session_NNNN.csv [--no-pull] [--no-open]
  python3 tools/hr_watch_report.py --self-test        synthetic phone + watch data with known answers

Inputs:
- the watch session CSV and its _sync.json (from tools/watch_session.py: clock anchor + phone offset)
- Apollo's files from the phone (pulled into data/apollo/): results.jsonl (one line per measurement,
  with window_start_ms / window_end_ms), events.csv (finger coverage, recording start/end, slips), and
  the raw per-frame CSV for each measurement.

For every Apollo heart-rate measurement inside the watch session, both devices go through the same
pipeline as tools/analyze_ppg.py (band-pass 0.7-4 Hz, spectral peak + beat intervals, spectral SNR).
The watch's green channel is primary. The lag between the two pulse waveforms (cross-correlation) is
a sync check: it should be steady across measurements, since it's mostly device latency plus the
finger-vs-wrist pulse arrival difference.
"""
import argparse
import base64
import html
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_ppg import FS, analyse, bandpass, estimate, load, resample, sliding_hr, tiles  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
APOLLO_DIR = "/sdcard/Android/data/edu.uw.ubicomp.apolloproto/files"
PHONE_C, WATCH_C, REF_C = "#c0392b", "#1f6f8b", "#7d3c98"


# ---- Loading ------------------------------------------------------------------------------------

def pull_apollo(dest):
    dest.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["adb", "pull", APOLLO_DIR + "/.", str(dest)], capture_output=True, text=True)
    if r.returncode != 0:
        print(f"warning: couldn't pull from the phone ({r.stderr.strip()}); using what's in {dest}", file=sys.stderr)


def load_watch(csv_path):
    """Watch samples with a `phone_ms` column on the phone's clock, via the session's anchor."""
    sync = json.loads(csv_path.with_name(csv_path.stem + "_sync.json").read_text())
    if "header_uptime_ms" not in sync:
        sys.exit("this session wasn't stopped with `watch_session.py stop` (no clock anchor yet)")
    w = pd.read_csv(csv_path)
    mac_start_ms = 1000 * (sync["mac_start_send_s"] + sync["mac_start_ack_s"]) / 2
    offset = np.nanmean([sync["phone_offset_start_ms"], sync.get("phone_offset_stop_ms", np.nan)])
    w["phone_ms"] = mac_start_ms + (w.timestamp_ms - sync["header_uptime_ms"]) + offset
    return w, sync


def load_apollo(folder):
    results = []
    if (folder / "results.jsonl").exists():
        results = [json.loads(l) for l in (folder / "results.jsonl").read_text().splitlines() if l.strip()]
    events = pd.read_csv(folder / "events.csv") if (folder / "events.csv").exists() else pd.DataFrame(
        columns=["epoch_ms", "measurement_id", "event", "detail"])
    return results, events


def watch_channel(w, ch):
    """A 200 Hz watch channel, gaps filled, lightly smoothed (35 ms) before resampling to 30 Hz."""
    x = w[ch].astype(float).replace(-1, np.nan).interpolate(limit_direction="both").to_numpy()
    return np.convolve(x, np.ones(7) / 7, mode="same")


def phone_clock(d):
    """Phone frames on the wall clock: camera sensor time, shifted by the median frame arrival offset."""
    off = np.median(d.epoch_ms.to_numpy() - d.sensor_ns.to_numpy() / 1e6)
    return d.sensor_ns.to_numpy() / 1e6 + off


# ---- Analysis -----------------------------------------------------------------------------------

def lag_ms(t_phone, x_phone, t_watch, x_watch, max_lag_s=0.5):
    """Lag of the phone pulse after the watch pulse (ms), and the correlation at that lag."""
    t0, t1 = max(t_phone[0], t_watch[0]), min(t_phone[-1], t_watch[-1])
    if t1 - t0 < 5:
        return np.nan, np.nan
    grid = np.arange(t0, t1, 0.01)
    a = np.interp(grid, t_phone, x_phone)
    b = np.interp(grid, t_watch, x_watch)
    a, b = (a - a.mean()) / (a.std() or 1), (b - b.mean()) / (b.std() or 1)
    lags = np.arange(-int(max_lag_s * 100), int(max_lag_s * 100) + 1)
    r = [np.mean(a[max(0, k):len(a) + min(0, k)] * b[max(0, -k):len(b) - max(0, k)]) for k in lags]
    i = int(np.argmax(r))
    return 10.0 * lags[i], r[i]


def crop(e, t0, t1):
    """The pulse part of an estimate() result, restricted to t0..t1 (for plots and the lag)."""
    k = (e["tu"] >= t0) & (e["tu"] <= t1)
    idx = np.flatnonzero(k)
    peaks = e["peaks"][(e["peaks"] >= idx[0]) & (e["peaks"] <= idx[-1])] - idx[0] if len(idx) else e["peaks"][:0]
    return {**e, "tu": e["tu"][k], "pulse": e["pulse"][k], "peaks": peaks}


def analyse_measurement(m, w, apollo):
    a, b = m["window_start_ms"], m["window_end_ms"]
    out = {"m": m, "a": a, "b": b}
    seg = w[(w.phone_ms >= a) & (w.phone_ms <= b)]
    out["watch_n"] = len(seg)
    if len(seg) < 200 * 8:
        return out
    tw = seg.phone_ms.to_numpy() / 1000
    out["watch"] = {ch: estimate(tw - a / 1000, watch_channel(seg, f"ppg0_{ch}")) for ch in ("green", "red", "ir")}
    # For the waveform plot and the lag, filter with 5 s of padding (the watch ran throughout) and crop,
    # so the band-pass start-up transient falls outside the window.
    pad = w[(w.phone_ms >= a - 5000) & (w.phone_ms <= b + 5000)]
    green_pad = estimate(pad.phone_ms.to_numpy() / 1000 - a / 1000, watch_channel(pad, "ppg0_green"))
    out["watch_plot"] = crop(green_pad, 0, (b - a) / 1000)
    raw = apollo / m.get("raw_file", "")
    if raw.is_file():
        d = load(raw)
        d["clock_ms"] = phone_clock(d)
        out["phone_raw"] = d
        td = tiles(d)
        pseg = d[(d.clock_ms >= a) & (d.clock_ms <= b)]
        if len(pseg) > FS * 8:
            res = analyse(pseg.assign(t=(pseg.clock_ms - a) / 1000), td)
            out["phone"] = res.get("skin", res["centre"])
            out["phone_signal"] = "skin tiles" if "skin" in res else res["centre_channel"]
    if "phone" in out:
        p, g = out["phone"], out["watch_plot"]
        trim = (p["tu"] >= p["tu"][0] + 1.5) & (p["tu"] <= p["tu"][-1] - 1.5)   # skip the phone filter's edges
        out["lag_ms"], out["lag_r"] = lag_ms(p["tu"][trim], p["pulse"][trim], g["tu"], g["pulse"])
    return out


def session_trace(w):
    """Watch heart rate over the whole session from 10 s windows (green channel)."""
    t = w.phone_ms.to_numpy() / 1000
    tu, x = resample(t, watch_channel(w, "ppg0_green"))
    if len(x) < FS * 12:
        return np.array([]), np.array([]), np.array([])
    win = sliding_hr(bandpass(x))
    return tu[0] + win[:, 0], win[:, 1], win[:, 2]


# ---- Figures ------------------------------------------------------------------------------------

def png(fig):
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode()


def style(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(labelsize=8)


def fig_timeline(w, results, trace, t0):
    fig, ax = plt.subplots(figsize=(11, 3.4))
    tt, hr, snr = trace
    if len(tt):
        ax.scatter((tt - t0) / 60, hr, c=np.clip(snr, 0, 1), cmap="Blues", vmin=0, vmax=1, s=14, label="watch (10 s windows, darker = higher SNR)")
        ax.plot((tt - t0) / 60, hr, color=WATCH_C, lw=0.8, alpha=0.5)
    for r in results:
        if "a" not in r:
            continue
        a, b = (r["a"] / 1000 - t0) / 60, (r["b"] / 1000 - t0) / 60
        ax.axvspan(a, b, color=PHONE_C, alpha=0.12)
        if "phone" in r:
            ax.plot((a + b) / 2, r["phone"]["hr"], "o", color=PHONE_C, ms=8, mec="white",
                    alpha=1 if r["phone"]["verdict"] != "POOR" else 0.35)
        if r["m"].get("ref_bpm"):
            ax.plot((a + b) / 2, r["m"]["ref_bpm"], "s", color=REF_C, ms=7, mec="white")
    ax.plot([], [], "o", color=PHONE_C, label="phone measurement (faded = POOR)")
    ax.plot([], [], "s", color=REF_C, label="Fitbit (typed in)")
    ax.set_xlabel("minutes into the watch session", fontsize=9)
    ax.set_ylabel("bpm", fontsize=9)
    ax.set_title("Whole session: the watch ran throughout; shaded spans are phone measurements", loc="left", fontsize=10)
    ax.legend(fontsize=7.5, frameon=False, loc="upper left", ncol=3)
    style(ax)
    return png(fig)


def fig_agreement(pairs):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 3.8))
    p, wv = pairs.phone_bpm.to_numpy(float), pairs.watch_bpm.to_numpy(float)
    lo, hi = np.nanmin([p.min(), wv.min()]) - 5, np.nanmax([p.max(), wv.max()]) + 5
    ax1.fill_between([lo, hi], [lo - 5, hi - 5], [lo + 5, hi + 5], color="#2e8b57", alpha=0.1, label="within 5 bpm")
    ax1.plot([lo, hi], [lo, hi], color="#555", lw=1)
    ax1.scatter(wv, p, c=[PHONE_C if ok else "#bbb" for ok in pairs.both_usable], s=40, zorder=3)
    ax1.set_xlim(lo, hi)
    ax1.set_ylim(lo, hi)
    ax1.set_xlabel("watch bpm", fontsize=9)
    ax1.set_ylabel("phone bpm", fontsize=9)
    ax1.set_title("Phone vs watch (grey = a POOR signal on either side)", loc="left", fontsize=10)
    ax1.legend(fontsize=8, frameon=False)
    mean, diff = (p + wv) / 2, p - wv
    use = pairs.both_usable.to_numpy()
    ax2.scatter(mean, diff, c=[PHONE_C if ok else "#bbb" for ok in use], s=40, zorder=3)
    if use.sum() >= 2:
        bias, sd = diff[use].mean(), diff[use].std(ddof=1)
        for y, ls, lab in [(bias, "-", f"bias {bias:+.1f}"), (bias + 1.96 * sd, "--", f"+1.96 SD {bias + 1.96 * sd:+.1f}"),
                           (bias - 1.96 * sd, "--", f"-1.96 SD {bias - 1.96 * sd:+.1f}")]:
            ax2.axhline(y, color="#555", ls=ls, lw=1)
            ax2.text(ax2.get_xlim()[1], y, " " + lab, va="center", fontsize=7.5)
    ax2.axhline(0, color="#aaa", lw=0.8)
    ax2.set_xlabel("mean of phone and watch (bpm)", fontsize=9)
    ax2.set_ylabel("phone - watch (bpm)", fontsize=9)
    ax2.set_title("Bland-Altman (usable pairs set the lines)", loc="left", fontsize=10)
    for ax in (ax1, ax2):
        style(ax)
    fig.tight_layout()
    return png(fig)


def z(x):
    return (x - np.mean(x)) / (np.std(x) or 1)


def fig_measurement(r, events, w):
    m, a, b = r["m"], r["a"], r["b"]
    fig, axes = plt.subplots(4, 1, figsize=(11, 10.5), gridspec_kw={"height_ratios": [1.1, 1.2, 0.9, 1]})
    # 1. Context: whole phone test, raw brightness and coverage, watch raw green, events.
    ax = axes[0]
    d = r.get("phone_raw")
    if d is not None:
        ts = (d.clock_ms - a) / 1000
        ax.plot(ts, z(d.red.to_numpy()), color=PHONE_C, lw=1, label="phone red brightness (z)")
        ax.plot(ts, 3 * d.coverage - 4.5, color="#2e8b57", lw=1, label="phone coverage (0-100%)")
        lo, hi = d.clock_ms.min(), d.clock_ms.max()
    else:
        lo, hi = a, b
    ws = w[(w.phone_ms >= lo) & (w.phone_ms <= hi)]
    if len(ws):
        ax.plot((ws.phone_ms - a) / 1000, z(ws.ppg0_green.to_numpy(float)) + 4, color=WATCH_C, lw=0.8, label="watch green (z, shifted)")
    ax.axvspan(0, (b - a) / 1000, color="#f4d03f", alpha=0.18)
    for _, e in events[events.measurement_id == m["id"]].iterrows():
        x = (e.epoch_ms - a) / 1000
        ax.axvline(x, color="#555", lw=0.8, ls=":")
        ax.text(x, 1.01, e.event.replace("_", " "), rotation=30, fontsize=7, transform=ax.get_xaxis_transform(), ha="left", va="bottom")
    ax.set_yticks([])
    ax.set_xlabel("s from recording start (yellow = analysed window)", fontsize=8)
    ax.legend(fontsize=7, frameon=False, loc="lower left", ncol=3)
    style(ax)
    # 2. Pulses on the shared clock.
    ax = axes[1]
    g, gp = r["watch"]["green"], r["watch_plot"]
    ax.plot(gp["tu"], z(gp["pulse"]), color=WATCH_C, lw=1.2, label=f"watch green: {g['hr']:.1f} bpm, SNR {g['snr']:.2f} ({g['verdict']})")
    if "phone" in r:
        p = r["phone"]
        ax.plot(p["tu"], z(p["pulse"]), color=PHONE_C, lw=1.2, label=f"phone: {p['hr']:.1f} bpm, SNR {p['snr']:.2f} ({p['verdict']})")
        ax.plot(p["tu"][p["peaks"]], z(p["pulse"])[p["peaks"]], "o", color=PHONE_C, ms=3)
    ax.plot(gp["tu"][gp["peaks"]], z(gp["pulse"])[gp["peaks"]], "o", color=WATCH_C, ms=3)
    ax.set_ylim(-3.2, 5)
    lag = r.get("lag_ms", np.nan)
    ax.set_title("Pulse waveforms on the shared clock (band-passed, flipped, z-scored)"
                 + ("" if np.isnan(lag) else f"; phone lags watch by {lag:+.0f} ms (r = {r['lag_r']:.2f})"), loc="left", fontsize=10)
    ax.set_yticks([])
    ax.set_xlabel("s", fontsize=8)
    ax.legend(fontsize=8, frameon=False, loc="upper left", ncol=2)
    style(ax)
    # 3. Spectra.
    ax = axes[2]
    for e, c, lab in [(g, WATCH_C, "watch")] + ([(r["phone"], PHONE_C, "phone")] if "phone" in r else []):
        k = (e["f"] >= 0.5) & (e["f"] <= 4)
        ax.plot(60 * e["f"][k], e["p"][k] / e["p"][k].max(), color=c, lw=1.2, label=lab)
        ax.axvline(e["hr"], color=c, ls="--", lw=0.8)
    if m.get("ref_bpm"):
        ax.axvline(m["ref_bpm"], color=REF_C, ls=":", lw=1.5, label=f"Fitbit {m['ref_bpm']:.0f}")
    ax.set_xlabel("bpm", fontsize=8)
    ax.set_yticks([])
    ax.set_title("Spectra: dashed = each device's heart-rate peak", loc="left", fontsize=10)
    ax.legend(fontsize=8, frameon=False)
    style(ax)
    # 4. Heart rate over the window: 10 s windows and beat to beat, both devices.
    ax = axes[3]
    for e, c, lab in [(g, WATCH_C, "watch")] + ([(r["phone"], PHONE_C, "phone")] if "phone" in r else []):
        if len(e["ibi"]):
            ax.plot(e["tu"][e["peaks"][1:]], 60 / e["ibi"], ".", color=c, ms=5, alpha=0.6)
        if len(e["windows"]):
            ax.plot(e["tu"][0] + e["windows"][:, 0], e["windows"][:, 1], "-o", color=c, ms=3, lw=1.4,
                    label=f"{lab} 10 s windows (SNR {np.median(e['windows'][:, 2]):.2f} median)")
    ax.set_ylabel("bpm", fontsize=8)
    ax.set_xlabel("s (dots: beat to beat)", fontsize=8)
    ax.set_title("Heart rate across the window", loc="left", fontsize=10)
    ax.legend(fontsize=8, frameon=False)
    style(ax)
    fig.tight_layout()
    return png(fig)


# ---- Report -------------------------------------------------------------------------------------

CSS = """
body{font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;margin:0;background:#f5f6f8;color:#1b2430}
main{max-width:1100px;margin:0 auto;padding:28px 18px 60px}
h1{font-size:26px;margin:0 0 4px} h2{font-size:18px;margin:34px 0 10px} .muted{color:#5d6b7a}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px;margin:16px 0}
.card{background:#fff;border:1px solid #dde2e8;border-radius:10px;padding:12px 14px}
.card b{display:block;font-size:22px} .card span{font-size:12px;color:#5d6b7a;text-transform:uppercase;letter-spacing:.05em}
table{border-collapse:collapse;width:100%;background:#fff;border:1px solid #dde2e8;border-radius:10px;overflow:hidden;font-variant-numeric:tabular-nums}
th,td{padding:7px 10px;border-bottom:1px solid #eef1f4;text-align:right;white-space:nowrap} th{background:#f0f2f5;font-size:12px;color:#5d6b7a}
td:first-child,th:first-child{text-align:left} .GOOD{color:#1e8449;font-weight:600} .WEAK{color:#b9770e;font-weight:600} .POOR{color:#c0392b;font-weight:600}
img{max-width:100%;background:#fff;border:1px solid #dde2e8;border-radius:10px;margin:6px 0}
.wrap{overflow-x:auto} .warn{background:#fff4e5;border:1px solid #f0c27b;border-radius:10px;padding:10px 14px}
"""


def fmt(v, nd=1):
    return "" if v is None or (isinstance(v, float) and np.isnan(v)) else f"{v:.{nd}f}"


def build(session_csv, apollo, out_html):
    w, sync = load_watch(session_csv)
    results, events = load_apollo(apollo)
    lo, hi = w.phone_ms.min(), w.phone_ms.max()
    meas = [m for m in results if m.get("type") == "heart_rate" and "window_start_ms" in m
            and m["window_start_ms"] >= lo and m["window_end_ms"] <= hi]
    analysed = [analyse_measurement(m, w, apollo) for m in meas]
    analysed = [r for r in analysed if "watch" in r]

    frozen = [c for c in ("ppg0_red", "ppg0_ir", "ppg0_green") if w[c].nunique() <= 1]
    dt = np.diff(w.timestamp_ms.to_numpy())
    rows = []
    for r in analysed:
        g, p, m = r["watch"]["green"], r.get("phone"), r["m"]
        rows.append({
            "time": pd.Timestamp(r["a"], unit="ms", tz="UTC").tz_convert("America/Los_Angeles").strftime("%H:%M:%S"),
            "phone_app_bpm": m.get("bpm"), "phone_bpm": p["hr"] if p else np.nan, "phone_snr": p["snr"] if p else np.nan,
            "phone_quality": p["verdict"] if p else "-", "watch_bpm": g["hr"], "watch_snr": g["snr"], "watch_quality": g["verdict"],
            "diff_bpm": (p["hr"] - g["hr"]) if p else np.nan, "lag_ms": r.get("lag_ms", np.nan), "lag_r": r.get("lag_r", np.nan),
            "watch_red_bpm": r["watch"]["red"]["hr"], "watch_ir_bpm": r["watch"]["ir"]["hr"],
            "fitbit_bpm": m.get("ref_bpm") if m.get("ref_bpm") is not None else np.nan,
            "min_coverage": m.get("min_coverage") if m.get("min_coverage") is not None else np.nan,
            "both_usable": bool(p) and p["verdict"] != "POOR" and g["verdict"] != "POOR",
        })
    table = pd.DataFrame(rows)
    out_csv = out_html.with_suffix(".csv")
    if len(table):
        table.to_csv(out_csv, index=False)

    use = table[table.both_usable] if len(table) else table
    mae = use.diff_bpm.abs().mean() if len(use) else np.nan
    bias = use.diff_bpm.mean() if len(use) else np.nan
    within5 = 100 * (use.diff_bpm.abs() <= 5).mean() if len(use) else np.nan
    lag_sd = table.lag_ms.std() if len(table) > 1 else np.nan

    parts = [f"<title>Phone vs watch, session {sync['session']}</title><style>{CSS}</style><main>",
             f"<h1>Phone vs watch heart rate</h1><div class='muted'>Watch session {sync['session']} · "
             f"{(hi - lo) / 60000:.1f} min · {len(meas)} phone measurement(s) inside it · generated by tools/hr_watch_report.py</div>"]
    if frozen:
        parts.append(f"<p class='warn'><b>Watch PPG frozen:</b> {', '.join(frozen)} never changed in this session, so the "
                     "watch numbers are meaningless. Reset the watch and run <code>watch_session.py check</code>.</p>")
    parts.append("<div class='cards'>" + "".join(
        f"<div class='card'><span>{k}</span><b>{v}</b></div>" for k, v in [
            ("usable pairs", f"{len(use)} of {len(table)}"), ("mean |phone − watch|", f"{fmt(mae)} bpm"),
            ("bias (phone − watch)", f"{fmt(bias)} bpm"), ("within 5 bpm", f"{fmt(within5, 0)}%"),
            ("lag spread (sync check)", "needs 2+ measurements" if np.isnan(lag_sd) else f"{lag_sd:.0f} ms SD")]) + "</div>")
    if len(analysed):
        parts.append("<h2>Session timeline</h2>" + f"<img src='data:image/png;base64,{fig_timeline(w, analysed, session_trace(w), lo / 1000)}'>")
    if len(table):
        cols = ["time", "phone_app_bpm", "phone_bpm", "phone_snr", "phone_quality", "watch_bpm", "watch_snr", "watch_quality",
                "diff_bpm", "lag_ms", "fitbit_bpm", "min_coverage"]
        head = "".join(f"<th>{c.replace('_', ' ')}</th>" for c in cols)
        body = "".join("<tr>" + "".join(
            f"<td class='{v}'>{html.escape(str(v))}</td>" if c.endswith("quality") else
            f"<td>{fmt(v, 2 if 'snr' in c or c == 'min_coverage' else 0 if c == 'lag_ms' else 1) if isinstance(v, float) else html.escape(str(v))}</td>"
            for c, v in ((c, row[c]) for c in cols)) + "</tr>" for _, row in table.iterrows())
        parts.append(f"<h2>Measurements</h2><div class='wrap'><table><tr>{head}</tr>{body}</table></div>")
        parts.append("<p class='muted'>phone app bpm = what Apollo showed; phone bpm = recomputed here from the raw frames. "
                     "SNR = spectral power at the heart-rate peak and its harmonic over the 0.7–4 Hz band (GOOD ≥ 0.5). "
                     "lag = how far the phone pulse trails the watch pulse; a steady value across measurements means the clocks are lined up.</p>")
        parts.append("<h2>Agreement</h2>" + f"<img src='data:image/png;base64,{fig_agreement(table)}'>")
        for r, (_, row) in zip(analysed, table.iterrows()):
            parts.append(f"<h2>Measurement at {row['time']}</h2>"
                         f"<img src='data:image/png;base64,{fig_measurement(r, events, w)}'>")
    else:
        parts.append("<p class='warn'>No Apollo heart-rate measurement falls inside this watch session. Measurements need the "
                     "updated Apollo (it saves window_start_ms / window_end_ms) and must happen between start and stop.</p>")
    parts.append(f"<h2>Clock sync</h2><div class='wrap'><table>"
                 f"<tr><th>check</th><th>value</th></tr>"
                 f"<tr><td>phone clock − Mac clock (start, stop)</td><td>{fmt(sync['phone_offset_start_ms'], 0)} ms, {fmt(sync.get('phone_offset_stop_ms'), 0)} ms</td></tr>"
                 f"<tr><td>watch samples, median interval, gaps &gt; 20 ms</td><td>{len(w)}, {np.median(dt):.1f} ms, {(dt > 20).sum()}</td></tr>"
                 "</table></div></main>")
    out_html.write_text("\n".join(parts))
    return table, out_html


# ---- Self-test ----------------------------------------------------------------------------------

def self_test():
    """Synthetic session: watch pulse at a known, drifting heart rate; a phone measurement of the same pulse
    arriving 50 ms later with 40 ms of camera-callback delay. The report must recover both."""
    rng = np.random.default_rng(1)
    tmp = Path(tempfile.mkdtemp(prefix="hrwatch_"))
    (tmp / "apollo").mkdir()
    T0 = 1_792_300_000.0                      # Mac epoch s at `log start`
    U0, OFFSET = 600_000, 85.0                # watch uptime at session open (ms), phone - Mac (ms)
    dur = 120.0

    def bpm(t):                               # true heart rate (bpm), t = s into the session
        return 70 + 8 * np.clip((t - 20) / 80, 0, 1)

    def pulse(t):
        ph = 2 * np.pi * np.cumsum(bpm(t) / 60 * np.gradient(t))
        return np.sin(ph) + 0.3 * np.sin(2 * ph + 0.8)

    tw = np.arange(0, dur, 0.005)
    pw = pulse(tw)
    n = len(tw)
    watch = pd.DataFrame({"imu_count": 2 * np.arange(n), "ppg_count": 2 * np.arange(n) + 1,
                          "timestamp_ms": (U0 + 1000 * tw).astype(np.int64),
                          "ppg0_red": (40000 - 120 * pw + rng.normal(0, 20, n)).astype(int),
                          "ppg0_ir": (45000 - 150 * pw + rng.normal(0, 20, n)).astype(int),
                          "ppg0_green": (12000 - 300 * pw + 50 * np.sin(2 * np.pi * 0.25 * tw) + rng.normal(0, 25, n)).astype(int),
                          "ppg1_red": -1, "ppg1_ir": -1, "ppg1_green": -1,
                          "accel_x_raw": 0, "accel_y_raw": 0, "accel_z_raw": 16384, "gyro_x_raw": 0, "gyro_y_raw": 0, "gyro_z_raw": 0})
    csv = tmp / "session_0099.csv"
    watch.to_csv(csv, index=False)
    (tmp / "session_0099_sync.json").write_text(json.dumps({
        "session": 99, "mac_start_send_s": T0 - 0.01, "mac_start_ack_s": T0 + 0.01, "phone_offset_start_ms": OFFSET,
        "phone_offset_stop_ms": OFFSET, "header_uptime_ms": U0, "last_uptime_ms": int(U0 + 1000 * tw[-1]), "records": n}))

    # Phone: test starts 30 s in, full coverage at 32 s, records 35-55 s. Phone epoch = Mac + OFFSET.
    phone_epoch0 = 1000 * T0 + OFFSET
    tp = np.arange(30, 56, 1 / 30) + rng.normal(0, 0.001, len(np.arange(30, 56, 1 / 30)))
    delay = 0.050                             # finger pulse arrives 50 ms after the wrist pulse
    pp = np.interp(tp - delay, tw, pw)
    red = 180 - 1.5 * pp + rng.normal(0, 0.3, len(tp))
    rec = (tp >= 35)
    cols = {"sensor_ns": (1e9 * (tp + 5000)).astype(np.int64),            # arbitrary camera time base
            "epoch_ms": (phone_epoch0 + 1000 * tp + 40).astype(np.int64),  # callback 40 ms after capture
            "y": red * 0.45, "u": 105.0, "v": 128 + (red - red * 0.45) / 1.402, "exposure_ns": 1_500_000, "iso": 60,
            "ae_locked": rec.astype(int), "coverage": np.where(tp >= 32, 1.0, 0.6)}
    d = pd.DataFrame(cols)
    for k in range(24):
        d[f"ty{k}"] = d.y
    for k in range(24):
        d[f"tv{k}"] = d.v
    d.to_csv(tmp / "apollo" / "hr_1.csv", index=False)
    ws, we = int(d.epoch_ms[rec].iloc[0]), int(d.epoch_ms.iloc[-1])
    (tmp / "apollo" / "results.jsonl").write_text(json.dumps({
        "id": 1, "type": "heart_rate", "bpm": 0, "verdict": "GOOD", "raw_file": "hr_1.csv",
        "window_start_ms": ws, "window_end_ms": we, "ref_bpm": None}) + "\n")
    pd.DataFrame({"epoch_ms": [int(phone_epoch0 + 30_000), int(phone_epoch0 + 32_000), ws, we], "measurement_id": 1,
                  "event": ["test_started", "full_coverage", "recording_start", "recording_end"], "detail": ""}
                 ).to_csv(tmp / "apollo" / "events.csv", index=False)

    table, out = build(csv, tmp / "apollo", tmp / "report.html")
    true = bpm(np.arange(35, 55, 0.01)).mean()
    row = table.iloc[0]
    print(f"true mean {true:.1f} bpm | phone {row.phone_bpm:.1f} | watch {row.watch_bpm:.1f} | "
          f"lag {row.lag_ms:+.0f} ms (expected about +90: 50 ms pulse delay + 40 ms camera delay) | report {out}")
    assert abs(row.phone_bpm - true) < 1.5, "phone heart rate off"
    assert abs(row.watch_bpm - true) < 1.5, "watch heart rate off"
    assert abs(row.lag_ms - 90) <= 20, "clock alignment off"
    print("self-test passed")
    return out


def main():
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session_csv", type=Path)
    ap.add_argument("--no-pull", action="store_true", help="use the Apollo files already in data/apollo")
    ap.add_argument("--no-open", action="store_true", help="don't open the report in the browser")
    args = ap.parse_args()
    apollo = ROOT / "data" / "apollo"
    if not args.no_pull:
        pull_apollo(apollo)
    table, out = build(args.session_csv, apollo, args.session_csv.with_name(args.session_csv.stem + "_report.html"))
    if len(table):
        print(table.drop(columns=["both_usable"]).round(2).to_string(index=False))
    print(f"\nreport: {out.relative_to(ROOT) if out.is_relative_to(ROOT) else out}")
    if not args.no_open:
        subprocess.run(["open", str(out)])


if __name__ == "__main__":
    main()
