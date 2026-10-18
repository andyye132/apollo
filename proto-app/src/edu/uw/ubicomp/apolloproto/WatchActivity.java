package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Find and connect the WILD watch over Bluetooth (no pairing), then show its live PPG and heart rate. */
public class WatchActivity extends Activity implements WatchLink.Listener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WatchLink link;
    private TextView status, detail, hr, hrWhy;
    private TextView findButton, reconnectButton, disconnectButton;
    private LinearLayout list, live;
    private ChartView wave;
    private StepList steps;
    private int ticks;
    private Runnable pendingAction;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshLive();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        link = WatchLink.get(this);
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Watch"), 4);
        Ui.add(col, Ui.title(this, "Connect the WILD watch"), 4);
        Ui.add(col, Ui.text(this, "The watch connects straight to this app. It doesn't support Bluetooth pairing, so "
                + "don't pair it in Android's Bluetooth settings (if it's listed there, tap Forget). Make sure no "
                + "computer or other app is connected to it: it takes one connection at a time.", 14, Ui.MUTED, false), 8);

        steps = Ui.add(col, new StepList(this, Ui.ACCENT,
                "Turn the watch on (its light blinks slowly when it's ready)",
                "Tap Find watch",
                "Tap Connect next to it",
                "Wear it snug on the arm you won't use for the camera, rest that arm flat, and check the live pulse below"), 16);

        LinearLayout card = Ui.add(col, Ui.card(this), 12);
        status = Ui.add(card, Ui.text(this, "", 18, Ui.TEXT, true), 0);
        detail = Ui.add(card, Ui.text(this, "", 13, Ui.MUTED, false), 6);
        LinearLayout buttons = Ui.add(card, Ui.row(this), 14);
        findButton = Ui.button(this, "Find watch", Ui.ACCENT, true, v -> withPermissions(() -> link.scan()));
        reconnectButton = Ui.button(this, "Reconnect", Ui.ACCENT, false, v -> withPermissions(() -> link.connectLast()));
        disconnectButton = Ui.button(this, "Disconnect", Ui.MUTED, false, v -> link.disconnect());
        for (TextView b : new TextView[]{findButton, reconnectButton, disconnectButton}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = Ui.dp(this, 8);
            buttons.addView(b, lp);
        }
        list = Ui.add(col, Ui.column(this), 10);

        live = Ui.add(col, Ui.card(this), 14);
        Ui.add(live, Ui.label(this, "Live from the watch (green light, last 10 s)"), 0);
        hr = Ui.add(live, Ui.text(this, "--", 44, Ui.TEXT, true), 6);
        hrWhy = Ui.add(live, Ui.text(this, "", 13, Ui.MUTED, false), 2);
        wave = new ChartView(this, 170).title("Pulse", "").hideYAxis();
        Ui.add(live, wave, 10);
        Ui.add(col, Ui.text(this, "While connected, the watch also records to its own memory, and Apollo's heart-rate test "
                + "records it alongside the phone. Wear it snug, sensor on the skin.", 12, Ui.MUTED, false), 14);
        Ui.add(col, Ui.button(this, "Done: back to home", Ui.ACCENT, true, v -> finish()), 18);
        Ui.add(col, Ui.text(this, "Going back keeps the watch connected.", 12, Ui.MUTED, false), 6);
        setContentView(Ui.page(this, col));
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addListener(this);
        onWatchChanged();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        link.removeListener(this);
        ui.removeCallbacks(tick);
        if (link.state() == WatchLink.State.SCANNING) link.stopScan();
    }

    /** Android 12+ needs the Bluetooth scan/connect permissions before either action. */
    private void withPermissions(Runnable action) {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> need = new ArrayList<>();
            for (String p : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
            if (!need.isEmpty()) {
                pendingAction = action;
                requestPermissions(need.toArray(new String[0]), 3);
                return;
            }
        }
        action.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok && pendingAction != null) pendingAction.run();
        else status.setText("Bluetooth permission is needed to talk to the watch");
        pendingAction = null;
    }

    @Override
    public void onWatchChanged() {
        WatchLink.State s = link.state();
        status.setText(link.message());
        status.setTextColor(s == WatchLink.State.STREAMING ? Ui.GOOD : s == WatchLink.State.BLUETOOTH_OFF ? Ui.BAD : Ui.TEXT);
        boolean busy = s == WatchLink.State.CONNECTING || s == WatchLink.State.STREAMING;
        findButton.setVisibility(busy ? View.GONE : View.VISIBLE);
        findButton.setText(s == WatchLink.State.SCANNING ? "Searching…" : "Find watch");
        boolean saved = Store.prefs(this).getString("watch_address", null) != null;
        reconnectButton.setVisibility(!busy && saved && s != WatchLink.State.SCANNING ? View.VISIBLE : View.GONE);
        disconnectButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        live.setVisibility(s == WatchLink.State.STREAMING ? View.VISIBLE : View.GONE);
        boolean nothingFound = link.found().isEmpty() && link.message().startsWith("No watch found");
        steps.setCurrent(s == WatchLink.State.STREAMING ? 3 : s == WatchLink.State.CONNECTING || !link.found().isEmpty() ? 2
                : nothingFound || s == WatchLink.State.BLUETOOTH_OFF ? 0 : 1);

        list.removeAllViews();
        if (!busy) {
            for (WatchLink.Found f : link.found()) {
                LinearLayout row = Ui.row(this);
                row.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 12)));
                row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
                LinearLayout text = Ui.column(this);
                text.addView(Ui.text(this, f.name, 16, Ui.TEXT, true));
                text.addView(Ui.text(this, String.format(Locale.US, "signal %d dBm (%s)", f.rssi,
                        f.rssi > -60 ? "strong" : f.rssi > -80 ? "fine" : "weak"), 13, Ui.MUTED, false));
                row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                row.addView(Ui.button(this, "Connect", Ui.ACCENT, true, v -> withPermissions(() -> link.connect(f))));
                Ui.add(list, row, 8);
            }
        }
    }

    private void refreshLive() {
        if (!link.streaming()) return;
        double[][] d = link.last(10);
        int n = d[0].length;
        double rate = 0;
        if (n > 1) rate = n / Math.max(0.5, (d[0][n - 1] - d[0][0]) / 1000.0);
        detail.setText(String.format(Locale.US, "%.0f samples/s (watch sends 200) · %.0f%% lost in transit",
                rate, 100 * link.dropFraction()));
        // Re-estimate every 2 s once there's 6 s of data (by time: Bluetooth loses some samples).
        if (n < 300 || (d[0][n - 1] - d[0][0]) < 6000 || ++ticks % 4 != 0) return;
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = (d[0][i] - d[0][0]) / 1000.0;
        WatchAnalysis.Assessment a = WatchAnalysis.assess(d);
        PpgAnalysis.Result r = a.r;
        if (r == null) {
            hr.setText("--");
            hrWhy.setText(a.headline + ". " + a.advice);
            return;
        }
        boolean poor = "POOR".equals(r.verdict);
        hr.setText(poor ? "--" : String.format(Locale.US, "%.0f bpm", r.hr));
        hr.setTextColor(poor ? Ui.MUTED : Ui.TEXT);
        hrWhy.setText(String.format(Locale.US, "%s · SNR %.2f · %s%s", a.headline, r.snr,
                r.agree ? "estimates agree" : String.format(Locale.US, "estimates disagree (%.0f vs %.0f)", r.hr, r.hrBeats),
                a.advice.isEmpty() ? "" : "\n" + a.advice));
        int from = Math.max(0, r.pulse.length - 180);
        double[] x = new double[r.pulse.length - from], y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = r.tu[from + i] - r.tu[r.tu.length - 1];
            y[i] = r.pulse[from + i];
        }
        wave.clear();
        wave.add(new ChartView.Series(x, y, Ui.STEPS, false, null));
        wave.invalidate();
    }
}
