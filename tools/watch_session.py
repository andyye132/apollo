"""Record a WILD watch session in step with the phone, for phone-vs-watch heart-rate comparisons.

Usage (watch on USB; the phone on adb):
  python3 tools/watch_session.py list            stored sessions (safe: doesn't touch the sensor)
  python3 tools/watch_session.py check           3 s test recording: is the PPG sensor producing data?
  python3 tools/watch_session.py hr --seconds 30 record from the watch alone and print its heart rate
  python3 tools/watch_session.py start           start a flash recording and anchor its clock
  ... take Apollo heart-rate measurements on the phone ...
  python3 tools/watch_session.py stop            stop, download the session, check the clock anchor
  python3 tools/hr_watch_report.py data/watch/session_NNNN.csv

The watch timestamps samples with its uptime (ms since boot). `start` sends `log start` and notes the
Mac time around it; the session header holds the watch uptime when the file opened, which ties uptime to
wall-clock time. The phone's clock offset to the Mac is measured over adb at start and stop. All three
clocks then line up to within a few tens of milliseconds, far below what a 20 s heart-rate window needs.

Uses the lab's firmware tools from a ZygPulse checkout (default ~/projects/ZygPulse, or $ZYGPULSE).
Erases nothing except the 3 s recording that `check` makes for itself.

WARNING: in this firmware build, `log status` (download_log.py --status, menu option 2, and the session
chooser in its Download option) writes a reset to the MAX30101. The sensor then stops producing samples
until the watch reboots, and recordings carry one frozen value. This script never sends `log status`.
"""
import argparse
import json
import os
import re
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "watch"
ZYGPULSE = Path(os.environ.get("ZYGPULSE", Path.home() / "projects" / "ZygPulse"))
sys.path.insert(0, str(ZYGPULSE / "firmware" / "wild_zephyr"))
try:
    import download_log as dl  # the lab's serial protocol + WILDLOG1 decoder
except ImportError:
    sys.exit(f"can't import download_log.py from {ZYGPULSE}/firmware/wild_zephyr; set $ZYGPULSE")


def find_port():
    from serial.tools import list_ports
    ports = [p.device for p in list_ports.comports() if (p.vid, p.pid) == (0x2FE3, 0x0004)]
    if len(ports) != 1:
        sys.exit(f"expected one WILD watch (USB 2fe3:0004), found {ports or 'none'}")
    return ports[0]


def sessions(port):
    """{session id: bytes} from `log list`, which only reads the file system (unlike `log status`)."""
    lines = dl.run_log_command(port, "log list", lambda l: l == "# log list end", display=False)
    return {int(m.group(1)): int(m.group(2)) for l in lines if (m := dl.FILE_RE.match(l))}


def wait_for_new_session(port, before, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        new = set(sessions(port)) - set(before)
        if new:
            return max(new)
        time.sleep(0.2)
    sys.exit("the watch didn't open a new session; is it already recording? (`log stop` ends that)")


def uptime_note(bin_path):
    """How long the watch had been running when this session opened (resets to 0 on every reboot)."""
    up_min = struct.unpack_from(">I", Path(bin_path).read_bytes(), 16)[0] / 60000
    booted = time.strftime("%H:%M", time.localtime(time.time() - up_min * 60))
    return up_min, f"watch running for {up_min:.1f} min (last boot ~{booted})"


def frozen_advice(up_min):
    if up_min > 3:
        return ("The watch hasn't rebooted recently, so the reset didn't take. Press the tiny RST button next to the "
                "watch's USB-C port once: a light should blink fast for ~5 s, then slowly. Then retry.")
    return ("The watch did just reboot and the sensor is still not sampling: check the sensor sits on skin, then "
            "tell Richard/the firmware owner (it may be a hardware or init problem).")


def frozen_channels(csv_path):
    """PPG channels that never change: the sign of a sensor that was reset and isn't sampling."""
    import pandas as pd
    w = pd.read_csv(csv_path)
    return [c for c in ("ppg0_red", "ppg0_ir", "ppg0_green") if w[c].nunique() <= 1]


def phone_offset_ms(tries=9):
    """Phone clock minus Mac clock, from the adb round trip with the smallest delay."""
    best = None
    for _ in range(tries):
        t0 = time.time()
        out = subprocess.run(["adb", "shell", "echo $EPOCHREALTIME"], capture_output=True, text=True).stdout.strip()
        t1 = time.time()
        if not out:
            continue
        sample = (t1 - t0, 1000 * (float(out) - (t0 + t1) / 2))
        best = sample if best is None or sample[0] < best[0] else best
    if best is None:
        print("warning: couldn't read the phone clock over adb; assuming 0 offset", file=sys.stderr)
        return 0.0, None
    return best[1], 1000 * best[0]


def cmd_list(port):
    found = sessions(port)
    used = sum(found.values())
    print(f"{len(found)} session(s), {used / 1e6:.1f} MB used of 16.8 MB (~{(16.0e6 - used) / 8000 / 60:.0f} min left at 8 kB/s)")
    for sid, size in sorted(found.items()):
        print(f"  session {sid:4d}  {size / 1e6:6.2f} MB  ~{(size - 32) / 40 / 200 / 60:5.1f} min")


def cmd_check(port):
    before = sessions(port)
    dl.run_log_command(port, "log start", lambda l: l == "# log start requested", display=False)
    sid = wait_for_new_session(port, before)
    time.sleep(3)
    dl.run_log_command(port, "log stop", lambda l: l == "# log stop requested", display=False)
    time.sleep(0.5)
    probe = OUT / f"check_{sid:04d}.bin"
    OUT.mkdir(parents=True, exist_ok=True)
    dl.download_session(port, sid, probe, probe.with_suffix(".csv"))
    frozen = frozen_channels(probe.with_suffix(".csv"))
    up_min, note = uptime_note(probe)
    dl.run_log_command(port, f"log erase {sid}", lambda l: l.startswith("# log erase session="), display=False)
    print(note)
    if frozen:
        print(f"\nPPG FROZEN: {', '.join(frozen)} never changed. {frozen_advice(up_min)}")
        sys.exit(1)
    print("\nPPG OK: the sensor is producing changing values. Ready for `start`.")


def cmd_hr(port, seconds, open_plot=True):
    """Record `seconds` from the watch alone, then print its heart rate per channel and plot the green one."""
    sys.path.insert(0, str(ROOT / "tools"))
    import pandas as pd
    from analyze_ppg import estimate

    before = sessions(port)
    print(f"recording {seconds:.0f} s from the watch; keep your wrist still...")
    dl.run_log_command(port, "log start", lambda l: l == "# log start requested", display=False)
    sid = wait_for_new_session(port, before)
    for left in range(int(seconds), 0, -5):
        print(f"  {left} s left", flush=True)
        time.sleep(min(5, left))
    dl.run_log_command(port, "log stop", lambda l: l == "# log stop requested", display=False)
    time.sleep(0.5)
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"hr_{sid:04d}.bin"
    dl.download_session(port, sid, path, path.with_suffix(".csv"))
    w = pd.read_csv(path.with_suffix(".csv"))
    frozen = frozen_channels(path.with_suffix(".csv"))
    up_min, note = uptime_note(path)
    print(note)
    if frozen:
        print(f"\nPPG FROZEN ({', '.join(frozen)} never changed): no heart rate possible. {frozen_advice(up_min)}")
        sys.exit(1)
    t = (w.timestamp_ms.to_numpy() - w.timestamp_ms.iloc[0]) / 1000
    est = {}
    print(f"\nsession {sid}: {len(w)} samples over {t[-1]:.1f} s")
    for ch in ("green", "red", "ir"):
        x = w[f"ppg0_{ch}"].astype(float).replace(-1, float("nan")).interpolate(limit_direction="both").to_numpy()
        e = estimate(t, x)
        est[ch] = e
        print(f"  {ch:5s}  {e['hr']:5.1f} bpm (beat intervals {e['hr_beats']:5.1f})  SNR {e['snr']:.2f}  {e['verdict']}"
              f"  pulse {e['ac_pct']:.2f}% of signal")
    g = est["green"]
    print(f"\nwatch heart rate: {g['hr']:.0f} bpm from the green channel ({g['verdict']}). Compare with your pulse or Fitbit.")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(10, 5.5), gridspec_kw={"height_ratios": [1.3, 1]})
    a1.plot(g["tu"], g["pulse"], color="#1f6f8b", lw=1.1)
    a1.plot(g["tu"][g["peaks"]], g["pulse"][g["peaks"]], "o", color="#c0392b", ms=3)
    a1.set_title(f"watch green pulse ({g['hr']:.0f} bpm, SNR {g['snr']:.2f}, {g['verdict']})", loc="left", fontsize=10)
    a1.set_xlabel("s")
    a1.set_yticks([])
    k = (g["f"] >= 0.5) & (g["f"] <= 4)
    a2.plot(60 * g["f"][k], g["p"][k] / g["p"][k].max(), color="#333")
    a2.axvline(g["hr"], color="#c0392b", ls="--", lw=1)
    a2.set_xlabel("bpm")
    a2.set_yticks([])
    for a in (a1, a2):
        a.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    png = path.with_suffix(".png")
    fig.savefig(png, dpi=120)
    print(f"plot: {png.relative_to(ROOT)}")
    if open_plot:
        subprocess.run(["open", str(png)])


def cmd_start(port):
    before = sessions(port)
    used = sum(before.values())
    free_min = (16.0e6 - used) / 8000 / 60
    if free_min < 5:
        sys.exit(f"only ~{free_min:.0f} min of flash left; download and erase old sessions first")
    offset, rtt = phone_offset_ms()
    t_send = time.time()
    dl.run_log_command(port, "log start", lambda l: l == "# log start requested", display=False)
    t_ack = time.time()
    session = wait_for_new_session(port, before)
    OUT.mkdir(parents=True, exist_ok=True)
    sync = {"session": session, "port": port, "mac_start_send_s": t_send, "mac_start_ack_s": t_ack,
            "phone_offset_start_ms": offset, "phone_rtt_start_ms": rtt}
    (OUT / f"session_{session:04d}_sync.json").write_text(json.dumps(sync, indent=2))
    print(f"recording session {session}; phone clock is {offset:+.0f} ms vs the Mac (±{(rtt or 0) / 2:.0f} ms). "
          f"About {free_min:.0f} min of flash left. Take your Apollo measurements, then run `stop`.")


def cmd_stop(port):
    open_syncs = [p for p in sorted(OUT.glob("session_*_sync.json")) if "header_uptime_ms" not in json.loads(p.read_text())]
    if not open_syncs:
        sys.exit("no session started with `watch_session.py start` is waiting to be stopped")
    sync_path = open_syncs[-1]
    sync = json.loads(sync_path.read_text())
    session = sync["session"]
    t_stop = time.time()
    dl.run_log_command(port, "log stop", lambda l: l == "# log stop requested", display=False)
    time.sleep(0.5)
    offset, rtt = phone_offset_ms()
    bin_path = OUT / f"session_{session:04d}.bin"
    dl.download_session(port, session, bin_path, bin_path.with_suffix(".csv"))
    payload = bin_path.read_bytes()
    header_uptime_ms = struct.unpack_from(">I", payload, 16)[0]
    n = (len(payload) - dl.HEADER_SIZE) // dl.RECORD_SIZE
    last_uptime_ms = struct.unpack_from(">I", payload, dl.HEADER_SIZE + (n - 1) * dl.RECORD_SIZE + 2)[0] if n else None
    sync.update({"mac_stop_send_s": t_stop, "phone_offset_stop_ms": offset, "phone_rtt_stop_ms": rtt,
                 "header_uptime_ms": header_uptime_ms, "last_uptime_ms": last_uptime_ms, "records": n})
    sync_path.write_text(json.dumps(sync, indent=2))

    mac_s = t_stop - (sync["mac_start_send_s"] + sync["mac_start_ack_s"]) / 2
    watch_s = (last_uptime_ms - header_uptime_ms) / 1000 if n else float("nan")
    print(f"\nsession {session}: {n} samples, {watch_s:.1f} s by the watch clock vs {mac_s:.1f} s by the Mac "
          f"(mismatch {1000 * (watch_s - mac_s):+.0f} ms; under ~100 ms means the anchor holds)")
    print(f"phone clock offset: {sync['phone_offset_start_ms']:+.0f} ms at start, {offset:+.0f} ms at stop")
    frozen = frozen_channels(bin_path.with_suffix(".csv"))
    if frozen:
        print(f"WARNING: PPG frozen ({', '.join(frozen)} never changed): the watch sensor wasn't sampling. "
              f"Reset the watch and run `check` before the next session.")
    print(f"next: python3 tools/hr_watch_report.py {bin_path.with_suffix('.csv').relative_to(ROOT)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", choices=["list", "check", "hr", "start", "stop"])
    ap.add_argument("--seconds", type=float, default=30, help="recording length for `hr` (default 30)")
    ap.add_argument("--no-open", action="store_true", help="`hr`: don't open the plot")
    ap.add_argument("--port", help="serial port (default: auto-detect the watch)")
    args = ap.parse_args()
    port = args.port or find_port()
    if args.action == "hr":
        cmd_hr(port, args.seconds, not args.no_open)
        return
    {"list": cmd_list, "check": cmd_check, "start": cmd_start, "stop": cmd_stop}[args.action](port)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        sys.exit(f"error: {e}")
