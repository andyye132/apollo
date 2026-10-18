"""One command for a phone-vs-watch heart-rate session.

Usage: python3 tools/hr_watch_suite.py [--tests 3] [--max-minutes 12]

1. Checks the phone (adb) and the watch (USB), and that the watch PPG sensor is producing data.
2. Starts a watch flash recording and anchors its clock (tools/watch_session.py).
3. You take --tests heart-rate measurements in Apollo on the phone, wearing the watch. The script
   watches Apollo's results over adb and announces each one as it lands.
4. Stops the watch, downloads the session, pulls Apollo's files, and opens the comparison report
   (tools/hr_watch_report.py).

Ctrl+C at any point stops the watch cleanly and still builds the report from what was recorded.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hr_watch_report as report  # noqa: E402
import watch_session as ws  # noqa: E402

RESULTS = report.APOLLO_DIR + "/results.jsonl"


def say(text):
    print(text)
    subprocess.Popen(["say", text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def phone_results():
    """Apollo heart-rate results on the phone (empty list if there are none yet)."""
    out = subprocess.run(["adb", "shell", f"cat {RESULTS} 2>/dev/null"], capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return [r for r in rows if r.get("type") == "heart_rate"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tests", type=int, default=3, help="Apollo heart-rate measurements to wait for (default 3)")
    ap.add_argument("--max-minutes", type=float, default=12, help="stop the watch after this long regardless")
    args = ap.parse_args()

    if subprocess.run(["adb", "get-state"], capture_output=True, text=True).stdout.strip() != "device":
        sys.exit("no phone on adb: plug in the Pixel (USB debugging on) and retry")
    port = ws.find_port()
    print("checking the watch's PPG sensor (3 s test recording)...")
    ws.cmd_check(port)                       # exits if the sensor is frozen

    known = {r.get("id") for r in phone_results()}
    ws.cmd_start(port)
    started = time.time()
    say(f"The watch is recording. On the phone, open Apollo and measure your heart rate {args.tests} times, "
        f"wearing the watch.")
    done = []
    try:
        while len(done) < args.tests:
            if time.time() - started > 60 * args.max_minutes:
                say("Time limit reached. Stopping the watch.")
                break
            time.sleep(3)
            for r in phone_results():
                if r.get("id") in known:
                    continue
                known.add(r.get("id"))
                done.append(r)
                if "window_start_ms" not in r:
                    print("warning: this Apollo build doesn't save measurement windows; run proto-app/build.sh --install")
                quality = {"GOOD": "clear signal", "WEAK": "noisy signal"}.get(r.get("verdict"), "unreliable")
                say(f"Measurement {len(done)} of {args.tests} saved, {quality}.")
    except KeyboardInterrupt:
        print("\nstopping early")

    ws.cmd_stop(port)
    sessions = [p for p in sorted(ws.OUT.glob("session_*_sync.json")) if "header_uptime_ms" in json.loads(p.read_text())]
    csv_path = sessions[-1].with_name(sessions[-1].name.replace("_sync.json", ".csv"))
    apollo = report.ROOT / "data" / "apollo"
    report.pull_apollo(apollo)
    table, out = report.build(csv_path, apollo, csv_path.with_name(csv_path.stem + "_report.html"))
    if len(table):
        print(table.drop(columns=["both_usable"]).round(2).to_string(index=False))
    say("Done. Opening the report.")
    print(f"report: {out}")
    subprocess.run(["open", str(out)])


if __name__ == "__main__":
    main()
