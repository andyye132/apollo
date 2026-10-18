"""Live view of the WILD watch ("flashwatch") over Bluetooth on the Mac: pulse, heart rate, signal quality.

Usage (the zygpulse conda env has bleak, PyQt5, pyqtgraph and qasync):
  ~/miniconda3/envs/zygpulse/bin/python tools/watch_live.py                  window with live plots
  ~/miniconda3/envs/zygpulse/bin/python tools/watch_live.py --text           terminal only, a line every 2 s
  ... --log data/watch/live.csv      also save every sample (Mac time, watch time, red, ir, green)
  ... --seconds 60                   stop after a minute

The watch takes one Bluetooth connection at a time: disconnect it in Apollo first (Watch screen →
Disconnect), or close Apollo. Same decoding and the same quality rules as Apollo's Watch screen
(proto-app WatchDecoder / WatchLink.assess), so the two give the same verdicts.
"""
import argparse
import asyncio
import collections
import sys
import time
from pathlib import Path

import numpy as np
from bleak import BleakClient, BleakScanner

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_ppg import FS, bandpass, estimate, resample  # noqa: E402

CHAR = "0000be9a-0000-1000-8000-00805f9b34fb"
NAME = "flashwatch"
DRIFT_LIMIT = 3.5   # baseline jumps / pulse size above this = the sensor moved (same as Apollo)


class Stream:
    """Decodes notifications: even count = IMU packet with the watch time, odd = PPG packet (count + 1)."""

    def __init__(self, log_path=None):
        self.samples = collections.deque(maxlen=200 * 60)   # (watch_ms, red, ir, green, mac_s)
        self.pending = None
        self.last_imu = None
        self.received = self.dropped = 0
        self.log = open(log_path, "w") if log_path else None
        if self.log:
            self.log.write("mac_s,watch_ms,ppg0_red,ppg0_ir,ppg0_green\n")

    def on_packet(self, data: bytes):
        now = time.time()
        if len(data) < 20:
            return
        count = int.from_bytes(data[0:2], "big")
        if count % 2 == 0:
            if self.last_imu is not None:
                gap = ((count - self.last_imu) & 0xFFFF) // 2 - 1
                if 0 < gap < 2000:
                    self.dropped += gap
            self.last_imu = count
            self.pending = (count, int.from_bytes(data[2:6], "big"))
        elif self.pending and count == (self.pending[0] + 1) & 0xFFFF:
            red, ir, green = (int.from_bytes(data[i:i + 3], "big") for i in (2, 5, 8))
            self.samples.append((self.pending[1], red, ir, green, now))
            self.received += 1
            if self.log:
                self.log.write(f"{now:.3f},{self.pending[1]},{red},{ir},{green}\n")
            self.pending = None

    def window(self, seconds):
        if not self.samples:
            return np.empty((0, 5))
        a = np.array(self.samples, dtype=float)
        return a[a[:, 0] >= a[-1, 0] - 1000 * seconds]

    def rate(self):
        w = self.window(5)
        return len(w) / max(0.5, (w[-1, 0] - w[0, 0]) / 1000) if len(w) > 1 else 0.0

    def lost_pct(self):
        total = self.received + self.dropped
        return 100 * self.dropped / total if total else 0.0


def assess(w):
    """Heart rate and a plain verdict from the last ~10 s of green; mirrors Apollo's WatchLink.assess."""
    out = {"hr": None, "verdict": None, "snr": None, "drift": None, "headline": "Waiting for data…", "advice": ""}
    if len(w) < 300 or w[-1, 0] - w[0, 0] < 6000:
        return out
    t = (w[:, 0] - w[0, 0]) / 1000
    green = w[:, 3]
    if np.all(green == green[0]):
        out.update(headline="Watch sensor isn't sampling",
                   advice="Its light readings are frozen. Press the watch's RST button once and reconnect.")
        return out
    smooth = np.convolve(green, np.ones(7) / 7, mode="same")
    e = estimate(t, smooth)
    means = [smooth[(t >= s) & (t < s + 1)].mean() for s in range(int(t[-1])) if np.any((t >= s) & (t < s + 1))]
    pulse = e["ac_pct"] / 100 * smooth.mean()
    drift = (max(means) - min(means)) / pulse if pulse > 0 and means else float("inf")
    moved = drift > DRIFT_LIMIT
    out.update(hr=e["hr"], verdict=e["verdict"], snr=e["snr"], drift=drift, hr_beats=e["hr_beats"])
    if e["verdict"] == "POOR":
        out["headline"] = "No clear pulse"
        out["advice"] = ("The sensor is shifting on your skin: rest this arm flat on the table and keep it still."
                         if moved else "The pulse is too faint: tighten the strap so the sensor sits flat on the skin.")
    else:
        out["headline"] = f"{'Clear' if e['verdict'] == 'GOOD' else 'Noisy'} pulse: {e['hr']:.0f} bpm"
        out["advice"] = "Some movement: keep this arm still." if moved else ""
    return out


async def connect(stream, on_status, stop):
    """Find the watch, subscribe, and keep reconnecting until `stop` is set."""
    while not stop.is_set():
        on_status(f"Looking for {NAME}…")
        dev = await BleakScanner.find_device_by_name(NAME, timeout=10)
        if dev is None:
            on_status(f"{NAME} not found. Is it on, and disconnected from Apollo on the phone?")
            await asyncio.sleep(2)
            continue
        try:
            async with BleakClient(dev) as client:
                on_status(f"Connected to {NAME}")
                stream.last_imu = None
                await client.start_notify(CHAR, lambda _, d: stream.on_packet(bytes(d)))
                while client.is_connected and not stop.is_set():
                    await asyncio.sleep(0.5)
                if client.is_connected:
                    await client.stop_notify(CHAR)
        except Exception as e:   # bleak raises a variety of backend errors on drop-outs
            on_status(f"Connection dropped ({type(e).__name__}); retrying…")
            await asyncio.sleep(1)


def run_text(stream, seconds):
    async def main():
        stop = asyncio.Event()
        status = {"s": ""}

        def on_status(s):
            if s != status["s"]:
                print(s, flush=True)
                status["s"] = s

        task = asyncio.create_task(connect(stream, on_status, stop))
        start = time.time()
        while seconds is None or time.time() - start < seconds:
            await asyncio.sleep(2)
            a = assess(stream.window(10))
            extra = f" · SNR {a['snr']:.2f} · drift {a['drift']:.1f}×" if a["snr"] is not None else ""
            print(f"[{time.time() - start:5.0f} s] {stream.rate():3.0f} samples/s, {stream.lost_pct():4.1f}% lost · "
                  f"{a['headline']}{extra}" + (f"\n          {a['advice']}" if a["advice"] else ""), flush=True)
        stop.set()
        await task

    asyncio.run(main())


def run_gui(stream, seconds):
    import pyqtgraph as pg
    from PyQt5.QtCore import QTimer
    from PyQt5.QtWidgets import QApplication, QLabel, QMainWindow, QVBoxLayout, QWidget
    from qasync import QEventLoop

    app = QApplication(sys.argv)
    loop = QEventLoop(app)
    asyncio.set_event_loop(loop)
    win = QMainWindow()
    win.setWindowTitle("WILD watch: live")
    win.resize(1000, 760)
    root = QWidget()
    lay = QVBoxLayout(root)
    win.setCentralWidget(root)
    root.setStyleSheet("background:#0e1318; color:#e8edf2;")
    status = QLabel("Starting…")
    status.setStyleSheet("color:#93a1af; font-size:14px;")
    hr = QLabel("--")
    hr.setStyleSheet("font-size:48px; font-weight:600;")
    advice = QLabel("")
    advice.setWordWrap(True)
    advice.setStyleSheet("font-size:15px;")
    for w in (status, hr, advice):
        lay.addWidget(w)
    pulse_plot = pg.PlotWidget(title="Pulse (green light, band-passed, last 8 s)")
    raw_plot = pg.PlotWidget(title="Raw channels, z-scored (jumps here = the sensor moving)")
    for p in (pulse_plot, raw_plot):
        p.setBackground("#151d26")
        p.showGrid(x=True, y=True, alpha=0.2)
        lay.addWidget(p)
    pulse_curve = pulse_plot.plot(pen=pg.mkPen("#4cc2a9", width=2))
    raw_curves = {c: raw_plot.plot(pen=pg.mkPen(col, width=1)) for c, col in ((1, "#e5534b"), (2, "#b393ea"), (3, "#4cc27a"))}
    stop = asyncio.Event()

    def refresh_plots():
        w = stream.window(8)
        if len(w) < 50:
            return
        t = (w[:, 0] - w[-1, 0]) / 1000
        for c, curve in raw_curves.items():
            x = w[:, c]
            curve.setData(t, (x - x.mean()) / (x.std() or 1))
        if len(w) > 200 and t[-1] - t[0] > 3:
            tu, x = resample(t, w[:, 3])
            pulse_curve.setData(tu, -bandpass(x))   # flipped: more blood = less light, so beats point up

    def refresh_hr():
        a = assess(stream.window(10))
        hr.setText(a["headline"])
        color = {"GOOD": "#4cc27a", "WEAK": "#e8b03a"}.get(a["verdict"], "#e8edf2" if a["verdict"] is None else "#e5534b")
        hr.setStyleSheet(f"font-size:40px; font-weight:600; color:{color};")
        detail = f"SNR {a['snr']:.2f} · baseline jumps {a['drift']:.1f}× the pulse" if a["snr"] is not None else ""
        advice.setText("\n".join(s for s in (detail, a["advice"]) if s))
        status.setText(f"{status.property('conn') or ''} · {stream.rate():.0f} samples/s (watch sends 200) · "
                       f"{stream.lost_pct():.1f}% lost")

    def on_status(s):
        status.setProperty("conn", s)

    t1, t2 = QTimer(), QTimer()
    t1.timeout.connect(refresh_plots)
    t2.timeout.connect(refresh_hr)
    t1.start(150)
    t2.start(2000)
    if seconds:
        QTimer.singleShot(int(seconds * 1000), app.quit)
    win.show()
    with loop:
        task = loop.create_task(connect(stream, on_status, stop))
        app.aboutToQuit.connect(stop.set)
        loop.run_forever()
        task.cancel()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--text", action="store_true", help="terminal output only, no window")
    ap.add_argument("--log", type=Path, help="save every sample to this CSV")
    ap.add_argument("--seconds", type=float, help="stop after this long")
    args = ap.parse_args()
    if args.log:
        args.log.parent.mkdir(parents=True, exist_ok=True)
    stream = Stream(args.log)
    try:
        (run_text if args.text else run_gui)(stream, args.seconds)
    except KeyboardInterrupt:
        pass
    finally:
        if stream.log:
            stream.log.close()
            print(f"saved {stream.received} samples to {args.log}")


if __name__ == "__main__":
    main()
