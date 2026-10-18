package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home: three places under a floating bar. Today splits the tests you take (temperature, heart rate)
 * from what Apollo measures all day in the background (steps, elevation, the phone's own warmth, the
 * watch), with Trends one tap away. Care finds medicine and nearby care (the team's Care feature).
 * Apollo, on the right, is the chat. Steps, the barometer, phone readiness and watch reconnection run
 * whenever this screen is open.
 */
public class MainActivity extends Activity implements SensorEventListener, CarePage.Host, ChatPage.Host {
    private static final int TODAY = 0, CARE = 1, APOLLO = 2;
    // The check-in runs every test once, temperature first (the heart-rate flash warms the phone).
    private static final String[] STEPS = {"Temperature", "Heart rate", "Calm sensing", "Breath strength"};
    private static final Class<?>[] STEP_SCREENS = {FeverActivity.class, HeartRateActivity.class, CalmSenseActivity.class, BreathActivity.class};
    private static final String[] STEP_HINTS = {"90 s with the screen on your forehead", "a 20-second fingertip check",
            "a minute of sonar while you hold the phone", "four hard blows at the screen"};
    private static final String[] STEP_TIMES = {"About 2.5 minutes", "About 30 seconds", "About a minute", "About 40 seconds"};
    private static final int REQ_STEP = 40;   // + step index
    private static final String[] TABS = {"Today", "Care", "Apollo"};
    private static final String[] TAB_ICONS = {"home", "care", null};   // Apollo's is his face
    private static final int STEP_GOAL = 10_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ThermalMonitor monitor;
    private SensorManager sensors;
    private FrameLayout content;
    private View statusScrim, nav;   // scrim: ivory behind the status bar once the page scrolls
    private final List<IconView> navIcons = new ArrayList<>();
    private final List<TextView> navLabels = new ArrayList<>();
    private int tab, trendRange = 0, statusTop, navInset;
    private String sub;   // a page inside the tab: "trends" (Today) or "cat:<id>" (Care); null = the tab itself
    private long stepsToday = -1;
    private CarePage care;
    private ChatPage chat;
    private ScrollView page;
    private int keepScroll = -1;

    // Live views on the current page (null when it isn't showing)
    private TextView stepsText, stepsCaption, floorsText, floorsCaption, phoneText, phoneCaption, tempStatus, watchStatus,
            checkInBadge, checkInText, eyeBlinks, eyeShifts, eyeRead, eyeStatus;
    private LinearLayout eyeSlot;       // holds the Start/Stop button
    private Boolean eyeButtonRunning;   // which one it holds now
    private EyeLog.Day eyeToday;        // today's saved minutes, for the card while tracking is off
    private Ui.Bar stepsBar;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        monitor = ThermalMonitor.get(this);
        sensors = getSystemService(SensorManager.class);
        care = new CarePage(this, this);
        chat = new ChatPage(this, this);
        if (state != null) {
            tab = state.getInt("tab");
            sub = state.getString("sub");
            care.tab = state.getInt("careTab");
        }
        // Edge to edge: the sunset reaches the top, the gesture bar floats over the page, and the keyboard
        // insets are handled here (so the chat box stays above it).
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        statusScrim = new View(this);
        statusScrim.setBackgroundColor(0xF2F6F3EE);
        statusScrim.setAlpha(0);
        root.addView(statusScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));
        nav = navBar();
        root.addView(nav, navParams());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            boolean typing = insets.isVisible(WindowInsets.Type.ime());
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            content.setPadding(0, 0, 0, typing ? ime : 0);
            nav.setVisibility(typing ? View.GONE : View.VISIBLE);
            if (bars.bottom != navInset) {
                navInset = bars.bottom;
                nav.setLayoutParams(navParams());
            }
            if (bars.top != statusTop) {
                statusTop = bars.top;
                statusScrim.getLayoutParams().height = statusTop;
                statusScrim.requestLayout();
                render();
            }
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(light, light);   // needs the decor, so after setContentView
        statusTop = Ui.dp(this, 28);
        render();
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 2);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("sub", sub);
        out.putInt("careTab", care.tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitor.acquire();
        Sensor steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (steps != null) sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
        AllDayService.start(this);   // steps and flights climbed, all day, in the background
        // Eye tracking the user left on: Android only lets a camera service start while the app is visible, so this is
        // where it comes back after a reboot or a crash (the camera permission has to still be on).
        if (EyeState.wanted(this) && !EyeState.running && EyeState.hasCamera(this)) EyeState.start(this);
        if (tab != APOLLO) render();   // results may have changed on another screen (the chat keeps its place)
        ui.post(tick);
        // The watch is the reference: reconnect to it automatically unless the user chose Disconnect.
        WatchLink w = WatchLink.get(this);
        boolean btOk = android.os.Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (btOk && w.state() == WatchLink.State.IDLE && Store.prefs(this).getBoolean("watch_auto", false)) w.connectLast();
    }

    @Override
    protected void onPause() {
        super.onPause();
        monitor.release();
        sensors.unregisterListener(this);
        ui.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (sub != null) {
            sub = null;
            render();
        } else if (tab != TODAY) {
            select(TODAY);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_EYE_CAMERA) {
            if (granted) startEyes();
        } else if (code == REQ_EYE_NOTIFY) {
            startEyes();   // the notification is optional for the service to run, so go on either way
        } else if (code == CarePage.REQ_LOCATION) {
            care.locationResult(granted);
        } else if (granted) {
            Sensor steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (steps != null) sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
            AllDayService.start(this);
        }
    }

    // ---- Tabs and the floating bar --------------------------------------------------------------

    private View navBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 30)));
        Ui.lift(bar, 10);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            if (TAB_ICONS[i] != null) {
                IconView icon = Ui.icon(this, TAB_ICONS[i], Ui.MUTED, 24);
                item.addView(icon);
                navIcons.add(icon);
            } else {
                // Apollo himself, on the bar's white: tap to chat.
                ImageView face = Ui.mascot(this, "hello", 34);
                ((LinearLayout.LayoutParams) face.getLayoutParams()).topMargin = -Ui.dp(this, 5);
                item.addView(face);
                item.setClipToPadding(false);   // he rises into the tab's padding: keep his leaf
                item.setClipChildren(false);
                navIcons.add(null);
            }
            TextView label = Ui.styled(this, TABS[i], 11, Ui.MUTED, 600);
            label.setGravity(Gravity.CENTER);
            Ui.add(item, label, TAB_ICONS[i] != null ? 3 : -2);
            item.setBackground(Ui.rounded(0x00000000, Ui.dp(this, 22)));
            Ui.tappable(item, v -> select(idx));
            item.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            item.setContentDescription(i == APOLLO ? "Ask Apollo" : TABS[i]);
            bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            navLabels.add(label);
        }
        return bar;
    }

    private FrameLayout.LayoutParams navParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), navInset + Ui.dp(this, 10));
        return lp;
    }

    private void select(int i) {
        if (i == tab && sub == null) return;
        tab = i;
        sub = null;
        render();
    }

    private void render() {
        if (content == null) return;
        for (int i = 0; i < navLabels.size(); i++) {
            int color = i == tab ? Ui.CORAL : Ui.MUTED;
            if (navIcons.get(i) != null) navIcons.get(i).setColor(color);
            navLabels.get(i).setTextColor(color);
        }
        stepsText = stepsCaption = floorsText = floorsCaption = phoneText = phoneCaption = tempStatus = watchStatus = null;
        checkInBadge = checkInText = null;
        stepsBar = null;
        LinearLayout col = pageColumn();
        ScrollView s = scroll(col);
        if (tab == CARE) {
            if (sub != null && sub.startsWith("cat:")) care.category(col, sub.substring(4), () -> {
                sub = null;
                render();
            });
            else care.build(col);
        } else if (tab == APOLLO) {
            chat.build(col, s);
        } else if ("trends".equals(sub)) {
            trends(col);
        } else if ("eyes".equals(sub)) {
            eyes(col);
        } else {
            today(col);
        }
        content.removeAllViews();
        content.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = s;
        if (keepScroll >= 0) {   // a filter changed: stay where the user was
            int y = keepScroll;
            s.post(() -> s.scrollTo(0, y));
            keepScroll = -1;
        }
        updateLive();
        showSteps();
        showFloors();
    }

    /** CarePage.Host: re-render in place (filters and toggles keep the scroll position). */
    @Override
    public void refresh() {
        keepScroll = page != null ? page.getScrollY() : -1;
        render();
    }

    @Override
    public void openCategory(String id) {
        tab = CARE;
        sub = "cat:" + id;
        render();
    }

    /** ChatPage.Host: what a reply's button does. */
    @Override
    public void act(String action) {
        if (action.startsWith("dial:")) {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + action.substring(5))));
        } else if (action.startsWith("category:")) {
            openCategory(action.substring(9));
        } else if (action.startsWith("care:")) {
            care.tab = action.endsWith("services") ? 1 : 0;
            tab = CARE;
            sub = null;
            render();
        } else if ("trends".equals(action)) {
            tab = TODAY;
            sub = "trends";
            render();
        } else {
            Class<?> screen = "temp".equals(action) ? FeverActivity.class : "hr".equals(action) ? HeartRateActivity.class
                    : "calibrate".equals(action) ? CalibrateActivity.class : "calm".equals(action) ? CalmSenseActivity.class
                    : "breath".equals(action) ? BreathActivity.class : null;
            if (screen != null) startActivity(new Intent(this, screen));
        }
    }

    @Override
    public long steps() {
        return stepsToday;
    }

    /** A scrolling page with the sunset glow at the top; the floating bar leaves room at the bottom. */
    private ScrollView scroll(LinearLayout col) {
        FrameLayout stack = new FrameLayout(this);
        stack.addView(new Ui.Sunset(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 320)));
        stack.addView(col);
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.addView(stack);
        statusScrim.setAlpha(0);
        s.setOnScrollChangeListener((v, x, y, ox, oy) -> statusScrim.setAlpha(Math.min(1f, y / (float) Ui.dp(this, 36))));
        return s;
    }

    private LinearLayout pageColumn() {
        LinearLayout col = Ui.column(this);
        col.setPadding(Ui.dp(this, 20), statusTop + Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 128) + navInset);
        col.setClipToPadding(false);   // sideways rows bleed past the gutter to the screen edge
        return col;
    }

    /** A section title with a one-line explanation under it. */
    private void sectionHead(LinearLayout col, String title, String caption, int topDp) {
        Ui.add(col, Ui.styled(this, title, 21, Ui.TEXT, 700), topDp);
        Ui.add(col, Ui.text(this, caption, 14, Ui.MUTED, false), 3);
    }

    // ---- Today ----------------------------------------------------------------------------------

    private void today(LinearLayout col) {
        LinearLayout head = Ui.add(col, Ui.row(this), 0);
        LinearLayout names = Ui.column(this);
        TextView title = Ui.add(names, Ui.title(this, "Today at a glance"), 4);
        Ui.fitOneLine(title, 32, 22);
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View gear = Ui.icon(this, "gear", Ui.TEXT, 44);
        gear.setBackground(Ui.rounded(0xE6FFFFFF, Ui.dp(this, 22)));
        gear.setPadding(Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11));
        gear.setContentDescription("Settings");
        Ui.tappable(gear, v -> settings());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        gl.leftMargin = Ui.dp(this, 10);
        gl.gravity = Gravity.BOTTOM;
        head.addView(gear, gl);

        // ---- The check-in runs all four tests in a row; under it, the tiles start one on its own.

        // The daily check-in: a ring of the four tests around Apollo, how far along today is, and one button.
        int next = nextStep(), done = stepsDone();
        View.OnClickListener go = v -> {
            int n = nextStep();
            if (n >= 0) startStep(n);
            else {
                sub = "trends";
                render();
            }
        };
        LinearLayout check = Ui.add(col, Ui.column(this), 22);
        check.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView daily = Ui.label(this, "Your daily check-in");
        daily.setTextColor(Ui.CORAL_DARK);
        daily.setGravity(Gravity.CENTER);
        check.addView(daily);
        FrameLayout ringBox = new FrameLayout(this);
        CheckInRing ring = new CheckInRing(this);
        boolean[] doneEach = new boolean[STEPS.length];
        for (int i = 0; i < STEPS.length; i++) doneEach[i] = stepDone(i);
        ring.set(doneEach, next);
        ringBox.addView(ring, new FrameLayout.LayoutParams(Ui.dp(this, 216), Ui.dp(this, 216)));
        View face = Ui.mascot(this, next < 0 ? "happy" : "hello", 116);   // on the page's ivory, inside the ring
        ringBox.addView(face, new FrameLayout.LayoutParams(Ui.dp(this, 116), Ui.dp(this, 116), Gravity.CENTER));
        ringBox.setContentDescription(next < 0 ? "Daily check-in: all 4 tests done" : "Daily check-in: " + done + " of 4 tests done, "
                + STEPS[next].toLowerCase(Locale.US) + " is next");
        ringBox.setBackground(Ui.rounded(0x00000000, Ui.dp(this, 108)));
        Ui.tappable(ringBox, go);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(this, 216), Ui.dp(this, 216));
        rl.topMargin = Ui.dp(this, 12);
        check.addView(ringBox, rl);
        Ui.add(check, Ui.styled(this, next < 0 ? "All 4 done" : done == 0 ? "4 quick tests" : done + " of 4 done", 24, Ui.TEXT, 700), 14)
                .setGravity(Gravity.CENTER);
        checkInText = Ui.add(check, Ui.text(this, "", 15, Ui.MUTED, false), 4);
        checkInText.setGravity(Gravity.CENTER);
        checkInText.setSingleLine(true);   // what's next, in one line
        checkInText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.add(check, Ui.button(this, next < 0 ? "See your trends" : done == 0 ? "Start your check-in" : "Continue your check-in",
                Ui.CORAL, true, go), 16);
        Ui.add(col, Ui.styled(this, "Take a specific test", 21, Ui.TEXT, 700), 28);

        LinearLayout tests = Ui.add(col, Ui.row(this), 12);
        tests.setBaselineAligned(false);
        tests.setGravity(Gravity.TOP);
        List<JSONObject> fh = new ArrayList<>();
        for (JSONObject o : Store.history(this, "forehead")) if (o.optDouble("core_c_est", -1) > 0) fh.add(o);
        LinearLayout temp = testTile(tests, "thermo", "Temperature", 0, v -> startActivity(new Intent(this, FeverActivity.class)));
        if (fh.isEmpty()) Ui.add(temp, Ui.styled(this, "Not yet", 22, Ui.TEXT, 600), 2);
        else Ui.add(temp, reading(String.format(Locale.US, "%.1f", fh.get(0).optDouble("core_c_est")), "°C", 28), 2);
        tempStatus = Ui.wrap(temp, Ui.chip(this, "Checking", Ui.FAINT), 4);
        tempStatus.setBackground(null);
        tempStatus.setPadding(0, 0, 0, 0);

        List<JSONObject> hr = readable(Store.history(this, "heart_rate"));
        LinearLayout heart = testTile(tests, "heart", "Heart rate", 12, v -> startActivity(new Intent(this, HeartRateActivity.class)));
        if (hr.isEmpty()) {
            Ui.add(heart, Ui.styled(this, "Not yet", 22, Ui.TEXT, 600), 2);
            Ui.add(heart, Ui.text(this, "20-second check", 13, Ui.MUTED, false), 4);
        } else {
            JSONObject last = hr.get(0);
            Ui.add(heart, reading(String.format(Locale.US, "%.0f", last.optDouble("bpm")), "bpm", 28), 2);
            if (hr.size() >= 2) {
                SparkView spark = new SparkView(this);
                double[] vals = new double[Math.min(10, hr.size())];
                for (int i = 0; i < vals.length; i++) vals[i] = hr.get(vals.length - 1 - i).optDouble("bpm");
                spark.setValues(vals);
                Ui.add(heart, spark, 3).getLayoutParams().height = Ui.dp(this, 20);
            }
            Ui.add(heart, Ui.text(this, ("GOOD".equals(last.optString("verdict")) ? "Good signal · " : "Weak signal · ")
                    + clock(last.optLong("id")), 13, Ui.MUTED, false), 3);
        }

        LinearLayout tests2 = Ui.add(col, Ui.row(this), 12);
        tests2.setBaselineAligned(false);
        tests2.setGravity(Gravity.TOP);
        List<JSONObject> calm = Store.history(this, "calm");
        LinearLayout calmTile = testTile(tests2, "moon", "Calm sensing", 0, v -> startActivity(new Intent(this, CalmSenseActivity.class)));
        if (calm.isEmpty()) {
            Ui.add(calmTile, Ui.styled(this, "Not yet", 24, Ui.TEXT, 600), 6);
            Ui.add(calmTile, Ui.text(this, "About a minute", 13, Ui.MUTED, false), 10);
        } else {
            JSONObject last = calm.get(0);
            int msgs = last.optInt("interventions");
            Ui.add(calmTile, reading(msgs == 0 ? "Calm" : String.valueOf(msgs), msgs == 0 ? "" : msgs == 1 ? "nudge" : "nudges", msgs == 0 ? 24 : 32), 6);
            Ui.add(calmTile, Ui.text(this, String.format(Locale.US, "%.0f min · %s", last.optDouble("duration_s") / 60,
                    clock(last.optLong("id"))), 13, Ui.MUTED, false), 6);
        }
        List<JSONObject> breaths = Store.history(this, "breath");
        LinearLayout breathTile = testTile(tests2, "waves", "Breath strength", 12, v -> startActivity(new Intent(this, BreathActivity.class)));
        JSONObject lastBreath = breaths.isEmpty() ? null : breaths.get(0);
        if (lastBreath == null || !lastBreath.has("strength_pa")) {
            Ui.add(breathTile, Ui.styled(this, "Not yet", 24, Ui.TEXT, 600), 6);
        } else if (lastBreath.has("percent_of_baseline")) {
            Ui.add(breathTile, reading(String.valueOf(lastBreath.optInt("percent_of_baseline")), "% of usual", 32), 6);
        } else {
            Ui.add(breathTile, reading(String.format(Locale.US, "%.0f", lastBreath.optDouble("strength_pa")), "Pa", 32), 6);
        }
        Ui.add(breathTile, Ui.text(this, breathLine(), 13, Ui.MUTED, false), lastBreath == null ? 10 : 6);

        // ---- All day: measured in the background, nothing to start.
        LinearLayout panel = Ui.add(col, Ui.column(this), 30);
        panel.setBackground(Ui.rounded(Ui.LEAF_TINT, Ui.dp(this, 26)));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 14));
        LinearLayout ph = Ui.add(panel, Ui.row(this), 0);
        ph.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 4), 0);
        LinearLayout pt = Ui.column(this);
        pt.addView(Ui.styled(this, "All day", 21, Ui.TEXT, 700));
        Ui.add(pt, Ui.text(this, "Measured quietly in the background. Nothing to start.", 14, Ui.LEAF_DARK, false), 3);
        ph.addView(pt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ph.addView(liveBadge());

        LinearLayout steps = Ui.add(panel, Ui.card(this), 14);
        cardTop(steps, "steps", Ui.LEAF_DARK, "Steps", "Today");
        stepsText = Ui.add(steps, Ui.number(this, "–", 34), 8);
        stepsBar = Ui.add(steps, Ui.bar(this, 0, Ui.LEAF), 10);
        stepsCaption = Ui.add(steps, Ui.text(this, "From the phone's step counter", 12.5f, Ui.MUTED, false), 8);

        LinearLayout pair = Ui.add(panel, Ui.row(this), 12);
        pair.setBaselineAligned(false);
        pair.setGravity(Gravity.TOP);
        LinearLayout elev = Ui.card(this);
        cardTop(elev, "layers", Ui.LEAF_DARK, "Floors climbed", null);
        floorsText = Ui.add(elev, Ui.number(this, "0", 26), 8);
        floorsCaption = Ui.add(elev, Ui.text(this, "Today, on foot", 12.5f, Ui.MUTED, false), 4);
        pair.addView(elev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout warm = Ui.card(this);
        cardTop(warm, "thermo", Ui.LEAF_DARK, "Phone warmth", null);
        phoneText = Ui.add(warm, Ui.number(this, "–", 26), 8);
        phoneCaption = Ui.add(warm, Ui.text(this, "Its own sensors", 12.5f, Ui.MUTED, false), 4);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        wl.leftMargin = Ui.dp(this, 12);
        pair.addView(warm, wl);

        eyeCard(panel);

        watchStatus = panelRow(panel, "watch", "Reference watch", "", v -> startActivity(new Intent(this, WatchActivity.class)));
        panelRow(panel, "trends", "Your trends", "Heart rate, temperature and steps over time", v -> {
            sub = "trends";
            render();
        });

        // ---- Setup
        sectionHead(col, "Setup", "Calibration runs with a thermometer, for the temperature model.", 30);
        int runs = Store.history(this, "calibration").size();
        extraTile(col, "plus", Ui.CORAL, "Calibrate temperature", runs == 0 ? "Teach Apollo how your forehead warms the phone"
                : runs + " run" + (runs == 1 ? "" : "s") + " saved · forehead runs and controls", CalibrateActivity.class);

        Ui.add(col, Ui.text(this, "Research prototype, not a medical device. Everything stays on this phone.", 12, Ui.FAINT, false), 22)
                .setGravity(Gravity.CENTER);
    }

    /** The breath tile's line: when, or how far the baseline has got. */
    private String breathLine() {
        List<JSONObject> b = Store.history(this, "breath");
        int counted = 0;
        for (JSONObject o : b) if (o.optBoolean("added_to_baseline")) counted++;
        if (b.isEmpty()) return "About 40 s";
        if (counted < BlowDetector.MIN_FOR_BASELINE) return "Baseline " + counted + " of " + BlowDetector.MIN_FOR_BASELINE;
        return clock(b.get(0).optLong("id"));
    }

    // ---- The check-in: every test once, in order ------------------------------------------------

    /** Whether step i has a usable result from today. */
    private boolean stepDone(int i) {
        String type = i == 0 ? "forehead" : i == 1 ? "heart_rate" : i == 2 ? "calm" : "breath";
        for (JSONObject o : Store.history(this, type)) {
            if (!isToday(o.optLong("id"))) break;   // newest first
            boolean ok = i == 0 ? o.optDouble("core_c_est", -1) > 0
                    : i == 1 ? !"POOR".equals(o.optString("verdict"))
                    : i == 2 || "GOOD".equals(o.optString("quality")) || "OK".equals(o.optString("quality"));
            if (ok) return true;
        }
        return false;
    }

    private int stepsDone() {
        int n = 0;
        for (int i = 0; i < STEPS.length; i++) if (stepDone(i)) n++;
        return n;
    }

    /** The first step not done today, or -1 when all are. */
    private int nextStep() {
        for (int i = 0; i < STEPS.length; i++) if (!stepDone(i)) return i;
        return -1;
    }

    private void startStep(int i) {
        android.widget.Toast.makeText(this, String.format(Locale.US, "Check-in %d of %d: %s", i + 1, STEPS.length, STEPS[i]),
                android.widget.Toast.LENGTH_SHORT).show();
        startActivityForResult(new Intent(this, STEP_SCREENS[i]).putExtra("checkin", true), REQ_STEP + i);
    }

    /** A step's screen closed: if it got a result, go straight on to the next one; if not, the chain stops here. */
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        int i = code - REQ_STEP;
        if (i < 0 || i >= STEPS.length || !stepDone(i)) return;
        int n = nextStep();
        if (n >= 0) startStep(n);
    }

    /** A test tile (half width): icon, name, then the latest result is added by the caller. */
    private LinearLayout testTile(LinearLayout row, String icon, String name, int leftDp, View.OnClickListener go) {
        LinearLayout t = Ui.card(this);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));   // a little shorter than a full card
        Ui.tappable(t, go);
        LinearLayout top = Ui.add(t, Ui.row(this), 0);
        top.addView(Ui.iconBadge(this, icon, Ui.CORAL, 32));
        top.addView(Ui.space(this));
        top.addView(Ui.chevron(this));
        Ui.add(t, Ui.styled(this, name, 15, Ui.TEXT, 600), 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = Ui.dp(this, leftDp);
        row.addView(t, lp);
        return t;
    }

    /** A white row inside the All day panel; returns its description for live text. */
    private TextView panelRow(LinearLayout panel, String icon, String name, String desc, View.OnClickListener go) {
        LinearLayout tile = Ui.add(panel, Ui.tile(this, go), 12);
        tile.addView(Ui.iconBadge(this, icon, Ui.LEAF_DARK, 38));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.styled(this, name, 15.5f, Ui.TEXT, 600));
        TextView d = Ui.add(text, Ui.text(this, desc, 13, Ui.MUTED, false), 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(this, 12);
        tile.addView(text, lp);
        tile.addView(Ui.chevron(this));
        return d;
    }

    /**
     * Passive eye tracking: blinks and gaze shifts per minute from the front camera, kept running by a foreground
     * service (Android shows its green camera dot and a notification while it runs). Start and Stop live here.
     */
    private void eyeCard(LinearLayout panel) {
        LinearLayout card = Ui.add(panel, Ui.card(this), 12);
        cardTop(card, "eye", Ui.LEAF_DARK, "Eyes", null);
        LinearLayout nums = Ui.add(card, Ui.row(this), 10);
        nums.setGravity(Gravity.TOP);
        LinearLayout blinks = Ui.column(this), shifts = Ui.column(this);
        eyeBlinks = Ui.add(blinks, Ui.number(this, "–", 26), 0);
        Ui.add(blinks, Ui.text(this, "Blinks per minute", 12.5f, Ui.MUTED, false), 2);
        eyeShifts = Ui.add(shifts, Ui.number(this, "–", 26), 0);
        Ui.add(shifts, Ui.text(this, "Gaze shifts per minute", 12.5f, Ui.MUTED, false), 2);
        nums.addView(blinks, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nums.addView(shifts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        eyeRead = Ui.add(card, Ui.styled(this, "", 14.5f, Ui.MUTED, 600), 12);
        eyeStatus = Ui.add(card, Ui.text(this, "", 12.5f, Ui.MUTED, false), 4);
        eyeSlot = Ui.add(card, Ui.column(this), 14);
        eyeButtonRunning = null;
        long now = System.currentTimeMillis();
        eyeToday = EyeLog.summarize(EyeLog.on(EyeLog.load(Store.dir(this)), now), now);
        TextView more = Ui.add(card, Ui.styled(this, "See your eye history  ›", 14.5f, Ui.CORAL_DARK, 600), 12);
        more.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        more.setOnClickListener(v -> {
            sub = "eyes";
            render();
        });
        Ui.add(card, Ui.text(this, "Only blink and gaze counts are kept, never pictures. While it runs, Android shows a green "
                + "camera dot and a notification. Exploratory: it shows whether your eyes are busier than your own usual, "
                + "not a diagnosis.", 12, Ui.FAINT, false), 10);
        updateEyes();
    }

    private void updateEyes() {
        if (eyeStatus == null) return;
        boolean run = EyeState.running, cameraOk = EyeState.hasCamera(this);
        boolean haveRate = run && !Double.isNaN(EyeState.blinkRate);
        boolean saved = !run && eyeToday != null && eyeToday.ratedMinutes > 0;   // off: show today's saved averages
        eyeBlinks.setText(haveRate ? String.format(Locale.US, "%.0f", EyeState.blinkRate)
                : saved ? String.format(Locale.US, "%.0f", eyeToday.blinkRate) : "–");
        eyeShifts.setText(haveRate ? String.format(Locale.US, "%.0f", EyeState.shiftRate)
                : saved ? String.format(Locale.US, "%.0f", eyeToday.shiftRate) : "–");
        String read = EyeState.read;
        eyeRead.setText(saved ? String.format(Locale.US, "Today so far: %.0f min tracked, %d restless.", eyeToday.faceMinutes,
                eyeToday.restlessMinutes)
                : !run ? "Counts blinks and eye movement in the background to notice restless eyes."
                : read.isEmpty() ? "First reading after a minute of face time." : read.equals("Restless")
                ? "Restless: your eyes are busier than usual, which can mean stress or distraction." : read.equals("Settled")
                ? "Settled: your eyes are steady." : read + ".");
        eyeRead.setTextColor(run && read.equals("Restless") ? Ui.WARN : run && read.equals("Settled") ? Ui.GOOD : Ui.MUTED);
        eyeStatus.setText(run ? "Camera on · " + EyeState.status
                : !cameraOk ? "Camera access is off. Tap Start to allow it." : EyeState.status.equals("Off") ? "Off" : EyeState.status);
        if (eyeButtonRunning == null || eyeButtonRunning != run) {
            eyeButtonRunning = run;
            eyeSlot.removeAllViews();
            Ui.add(eyeSlot, run ? Ui.button(this, "Stop eye tracking", Ui.TEXT, false, v -> EyeState.stop(this))
                    : Ui.button(this, "Start eye tracking", Ui.CORAL, true, v -> startEyes()), 0);
        }
    }

    private static final int REQ_EYE_CAMERA = 31, REQ_EYE_NOTIFY = 32;

    /** Start: needs the camera permission (asked, or sent to Settings once refused for good) and a notification. */
    private void startEyes() {
        if (!EyeState.hasCamera(this)) {
            SharedPreferences p = Store.prefs(this);
            if (p.getBoolean("eye_asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
            } else {
                p.edit().putBoolean("eye_asked", true).apply();
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_EYE_CAMERA);
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Store.prefs(this).getBoolean("eye_notify_asked", false)) {
            Store.prefs(this).edit().putBoolean("eye_notify_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_EYE_NOTIFY);
            return;
        }
        EyeState.start(this);
    }

    // ---- Eyes (from All day): the saved minutes, today and this week ------------------------------

    private void eyes(LinearLayout col) {
        TextView back = Ui.add(col, Ui.styled(this, "‹  Today", 15, Ui.TEXT, 500), 0);
        back.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        back.setOnClickListener(v -> {
            sub = null;
            render();
        });
        Ui.add(col, Ui.label(this, "All day · Eyes"), 4);
        Ui.add(col, Ui.title(this, "Your eyes"), 6);
        Ui.add(col, Ui.text(this, "Blinks and gaze shifts per minute you were looking at the phone. Every minute is saved on this phone.",
                15, Ui.MUTED, false), 6);

        List<EyeLog.Minute> all = EyeLog.load(Store.dir(this));
        long now = System.currentTimeMillis();
        List<EyeLog.Minute> today = EyeLog.on(all, now);
        EyeLog.Day d = EyeLog.summarize(today, now);

        // Today at a glance: four numbers.
        LinearLayout sum = Ui.add(col, Ui.card(this), 18);
        LinearLayout st = Ui.add(sum, Ui.row(this), 0);
        st.addView(Ui.styled(this, "Today", 16, Ui.TEXT, 600), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        st.addView(Ui.badge(this, EyeState.running ? "Tracking now" : "Off", EyeState.running ? Ui.LEAF : Ui.MUTED));
        String[][] stats = {
                {String.format(Locale.US, "%.0f", d.faceMinutes), "minutes tracked"},
                {d.ratedMinutes == 0 ? "–" : String.format(Locale.US, "%.0f", d.blinkRate), "blinks per minute"},
                {d.ratedMinutes == 0 ? "–" : String.format(Locale.US, "%.0f", d.shiftRate), "gaze shifts per minute"},
                {String.valueOf(d.restlessMinutes), d.restlessMinutes == 1 ? "restless minute" : "restless minutes"}};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.add(sum, Ui.row(this), r == 0 ? 12 : 14);
            row.setGravity(Gravity.TOP);
            for (int k = 0; k < 2; k++) {
                LinearLayout cell = Ui.column(this);
                cell.addView(Ui.number(this, stats[2 * r + k][0], 28));
                Ui.add(cell, Ui.text(this, stats[2 * r + k][1], 12.5f, Ui.MUTED, false), 2);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
        }

        // Through the day: each rated minute, with restless stretches shaded.
        LinearLayout tl = Ui.add(col, Ui.card(this), 14);
        cardTop(tl, "eye", Ui.LEAF_DARK, "Through the day", "per minute");
        List<EyeLog.Minute> rated = new ArrayList<>();
        for (EyeLog.Minute m : today) if (m.rated()) rated.add(m);
        if (rated.size() < 2) {
            Ui.add(tl, Ui.text(this, rated.isEmpty() ? "No tracked minutes yet today. Start eye tracking on Today and use the phone as usual."
                    : "One tracked minute so far. The chart fills in as you use the phone.", 14, Ui.MUTED, false), 10);
        } else {
            LinearLayout legend = Ui.add(tl, Ui.row(this), 10);
            legend.addView(swatch(Ui.CORAL, "Blinks"));
            legend.addView(swatch(Ui.LEAF_DARK, "Gaze shifts"));
            legend.addView(swatch(0x55E9B04C, "Restless"));
            double day0 = EyeLog.dayStart(now);
            double[] x = new double[rated.size()], yb = new double[rated.size()], ys = new double[rated.size()];
            for (int i = 0; i < rated.size(); i++) {
                x[i] = (rated.get(i).start - day0) / 3_600_000.0;
                yb[i] = rated.get(i).blinkRate;
                ys[i] = rated.get(i).shiftRate;
            }
            ChartView c = new ChartView(this, 170).title("", "").xUnit("h").minSpan(10);
            for (EyeLog.Minute m : rated) {
                if (m.restless()) c.band((m.start - day0) / 3_600_000.0, (m.end - day0) / 3_600_000.0, 0x55E9B04C, "");
            }
            c.add(new ChartView.Series(x, yb, Ui.CORAL, false, "blinks/min"));
            c.add(new ChartView.Series(x, ys, Ui.LEAF_DARK, false, "shifts/min"));
            c.add(new ChartView.Series(x, ys, Ui.LEAF_DARK, true, null));
            Ui.add(tl, c, 8);
            Ui.add(tl, Ui.text(this, "Hours since midnight. A minute is rated once your face was in view for 20 s of it.",
                    12, Ui.FAINT, false), 6);
        }

        // This week: one row a day.
        LinearLayout wk = Ui.add(col, Ui.card(this), 14);
        cardTop(wk, "trends", Ui.LEAF_DARK, "Last 7 days", null);
        List<EyeLog.Day> days = EyeLog.days(all, now, 7);
        double maxShift = 1;
        for (EyeLog.Day day : days) if (day.ratedMinutes > 0) maxShift = Math.max(maxShift, day.shiftRate);
        boolean any = false;
        for (int i = days.size() - 1; i >= 0; i--) {
            EyeLog.Day day = days.get(i);
            if (day.ratedMinutes == 0) continue;
            any = true;
            LinearLayout r = Ui.add(wk, Ui.row(this), 12);
            TextView name = Ui.styled(this, i == days.size() - 1 ? "Today" : new SimpleDateFormat("EEE", Locale.US).format(new Date(day.dayStart)),
                    14, Ui.TEXT, 600);
            r.addView(name, new LinearLayout.LayoutParams(Ui.dp(this, 52), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout mid = Ui.column(this);
            Ui.Bar bar = Ui.bar(this, (float) (day.shiftRate / maxShift), day.restlessMinutes > 0 ? Ui.WARN : Ui.LEAF);
            mid.addView(bar);
            Ui.add(mid, Ui.text(this, String.format(Locale.US, "%.0f min · %.0f blinks, %.0f shifts a minute%s", day.faceMinutes,
                    day.blinkRate, day.shiftRate, day.restlessMinutes > 0 ? " · " + day.restlessMinutes + " restless" : ""),
                    12, Ui.MUTED, false), 4);
            r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        if (!any) Ui.add(wk, Ui.text(this, "Nothing tracked this week yet.", 14, Ui.MUTED, false), 10);

        // How sensitive the gaze-shift count is right now.
        LinearLayout how = Ui.add(col, Ui.card(this), 14);
        Ui.add(how, Ui.label(this, "What counts as a gaze shift"), 0);
        double thr = !Double.isNaN(EyeState.threshold) ? EyeState.threshold : today.isEmpty() ? Double.NaN : today.get(today.size() - 1).threshold;
        boolean measured = !Double.isNaN(thr) && thr != EyeMetrics.SHIFT_THRESHOLD;   // 0.10 is the start-up value
        Ui.add(how, Ui.text(this, !measured
                ? "Your eyes moving quickly by more than a set amount. For the first half minute of face time that's about 14°; "
                + "after that Apollo measures how steadily the camera sees your eyes and lowers the cut-off to suit, to about 7° "
                + "in good light and close up."
                : String.format(Locale.US, "Right now: a move of about %.0f° or more within a fifth of a second (%.3f eye widths). "
                + "Apollo sets this from how steadily the camera sees your eyes: smaller in good light and close up, larger when the "
                + "picture is noisy.", Math.toDegrees(Math.asin(Math.min(1, thr / 0.4))), thr), 14, Ui.TEXT, false), 8);
        Ui.add(how, Ui.text(this, "Restless: gaze shifts 1.5 times your own recent usual, or 28+ blinks a minute. Exploratory, not a diagnosis.",
                12.5f, Ui.MUTED, false), 8);
        Ui.add(col, Ui.text(this, "Saved as eyetrack_passive_*.csv (one row a minute) and eyetrack_frames_*.csv (every frame, the first "
                + "10 minutes of each session) in Apollo's files folder. Only numbers, never pictures.", 12, Ui.FAINT, false), 14);
    }

    private View swatch(int color, String name) {
        LinearLayout r = Ui.row(this);
        View dot = new View(this);
        dot.setBackground(Ui.rounded(color, Ui.dp(this, 5)));
        r.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        TextView t = Ui.styled(this, name, 12.5f, Ui.MUTED, 500);
        t.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 14), 0);
        r.addView(t);
        return r;
    }

    /** "● Live" with a slowly breathing leaf dot. */
    private View liveBadge() {
        LinearLayout b = Ui.row(this);
        b.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 100)));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        View dot = new View(this);
        dot.setBackground(Ui.rounded(Ui.LEAF, Ui.dp(this, 4)));
        b.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8)));
        TextView t = Ui.styled(this, "Live", 12.5f, Ui.LEAF_DARK, 600);
        t.setPadding(Ui.dp(this, 7), 0, 0, 0);
        b.addView(t);
        ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f);
        pulse.setDuration(1100);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
        return b;
    }

    /** "72 bpm": a big number with its unit set beside it. */
    private LinearLayout reading(String value, String unit, float sp) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.BOTTOM);
        r.addView(Ui.number(this, value, sp));
        TextView u = Ui.styled(this, unit, 15, Ui.MUTED, 500);
        u.setPadding(Ui.dp(this, 5), 0, 0, Ui.dp(this, sp > 40 ? 10 : 5));
        r.addView(u);
        return r;
    }

    /** A setup/extra tile. */
    private void extraTile(LinearLayout col, String icon, int color, String name, String desc, Class<?> screen) {
        LinearLayout tile = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, screen))), 10);
        tile.addView(Ui.iconBadge(this, icon, color, 40));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.styled(this, name, 16, Ui.TEXT, 600));
        Ui.add(text, Ui.text(this, desc, 13, Ui.MUTED, false), 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(this, 14);
        tile.addView(text, lp);
        tile.addView(Ui.chevron(this));
    }

    // ---- Trends (from Today) --------------------------------------------------------------------

    private void trends(LinearLayout col) {
        TextView back = Ui.add(col, Ui.styled(this, "‹  Today", 15, Ui.TEXT, 500), 0);
        back.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        back.setOnClickListener(v -> {
            sub = null;
            render();
        });
        Ui.add(col, Ui.title(this, "Trends"), 6);
        Ui.add(col, Ui.text(this, "A little perspective on your readings.", 15, Ui.MUTED, false), 6);
        Ui.add(col, Ui.segmented(this, new String[]{"Week", "Month", "All"}, trendRange, i -> {
            trendRange = i;
            render();
        }), 18);
        long now = System.currentTimeMillis(), from = trendRange == 0 ? now - 7 * 86_400_000L : trendRange == 1 ? now - 30 * 86_400_000L : 0;
        String span = trendRange == 0 ? "This week" : trendRange == 1 ? "This month" : "All time";

        List<JSONObject> hr = new ArrayList<>();
        for (JSONObject o : readable(Store.history(this, "heart_rate"))) if (o.optLong("id") >= from) hr.add(o);
        LinearLayout heart = Ui.add(col, Ui.card(this), 14);
        cardTop(heart, "heart", Ui.CORAL, "Heart rate", span);
        if (hr.isEmpty()) {
            Ui.add(heart, Ui.text(this, "No heart-rate readings in this range yet.", 14, Ui.MUTED, false), 10);
        } else {
            double sum = 0;
            for (JSONObject o : hr) sum += o.optDouble("bpm");
            Ui.add(heart, reading(String.format(Locale.US, "%.0f", sum / hr.size()), "bpm average", 40), 10);
            Ui.add(heart, chartField(hr, "bpm", "bpm", now, Ui.CORAL), 10);
            Ui.add(heart, Ui.text(this, String.format(Locale.US, "%d reading%s · camera pulse check-ins, not continuous monitoring",
                    hr.size(), hr.size() == 1 ? "" : "s"), 12, Ui.FAINT, false), 6);
        }

        List<JSONObject> temps = new ArrayList<>();
        for (JSONObject o : Store.history(this, "forehead")) if (o.optLong("id") >= from && o.optDouble("core_c_est", -1) > 0) temps.add(o);
        LinearLayout temp = Ui.add(col, Ui.card(this), 14);
        cardTop(temp, "thermo", Ui.CORAL, "Temperature", span);
        if (temps.isEmpty()) {
            Ui.add(temp, Ui.text(this, "No temperature estimates in this range yet. Calibrate first, then check in.", 14, Ui.MUTED, false), 10);
        } else {
            Ui.add(temp, reading(String.format(Locale.US, "%.1f", temps.get(0).optDouble("core_c_est")), "°C latest", 40), 10);
            if (temps.size() >= 2) Ui.add(temp, chartField(temps, "core_c_est", "°C", now, Ui.CORAL), 10);
            Ui.add(temp, Ui.text(this, "Research estimate from the forehead warming test", 12, Ui.FAINT, false), 6);
        }

        LinearLayout steps = Ui.add(col, Ui.card(this), 14);
        cardTop(steps, "steps", Ui.LEAF_DARK, "Daily steps", "Last 7 days");
        long[] days = stepDays();
        long total = 0;
        int counted = 0;
        for (long d : days) {
            if (d >= 0) {
                total += d;
                counted++;
            }
        }
        Ui.add(steps, reading(counted == 0 ? "–" : String.format(Locale.US, "%,d", total / counted), "average", 40), 10);
        Bars bars = new Bars(this, days);
        Ui.add(steps, bars, 12).getLayoutParams().height = Ui.dp(this, 120);
        Ui.add(steps, Ui.text(this, "Counted while Apollo is open, from the phone's step counter", 12, Ui.FAINT, false), 6);

        Ui.add(col, Ui.section(this, "Latest readings"), 26);
        LinearLayout list = Ui.add(col, Ui.card(this), 10);
        int n = 0;
        for (JSONObject o : Store.history(this, null)) {
            String type = o.optString("type");
            if (!type.equals("heart_rate") && !type.equals("forehead")) continue;
            if (n++ >= 8) break;
            boolean isHeart = type.equals("heart_rate");
            String value = isHeart ? ("POOR".equals(o.optString("verdict")) ? "--" : String.format(Locale.US, "%.0f bpm", o.optDouble("bpm")))
                    : o.optDouble("core_c_est", -1) > 0 ? String.format(Locale.US, "%.1f °C", o.optDouble("core_c_est"))
                    : String.format(Locale.US, "%+.2f °C/min", o.optDouble("rate_c_per_min"));
            String subline = isHeart ? quality(o.optString("verdict")) : o.optBoolean("unreliable") ? "Unreliable conditions" : "Temperature check";
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            LinearLayout t = Ui.column(this);
            t.addView(Ui.styled(this, when(o.optLong("id")), 14.5f, Ui.TEXT, 600));
            t.addView(Ui.text(this, subline, 12.5f, Ui.MUTED, false));
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(Ui.styled(this, value, 15, Ui.TEXT, 600));
            if (n > 1) {
                View div = new View(this);
                div.setBackgroundColor(Ui.LINE);
                list.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            list.addView(r);
        }
        if (n == 0) list.addView(Ui.text(this, "Nothing yet. Take a measurement to start.", 14, Ui.MUTED, false));
    }

    private void cardTop(LinearLayout card, String icon, int color, String name, String right) {
        LinearLayout top = Ui.add(card, Ui.row(this), 0);
        top.addView(Ui.icon(this, icon, color, 18));
        TextView n = Ui.styled(this, name, 15, Ui.TEXT, 600);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(this, 7);
        top.addView(n, lp);
        if (right != null) top.addView(Ui.label(this, right));
    }

    /** Readings over time, days back from now, oldest left. */
    private ChartView chartField(List<JSONObject> rows, String field, String unit, long now, int color) {
        double[] x = new double[rows.size()], y = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            JSONObject o = rows.get(rows.size() - 1 - i);
            x[i] = (o.optLong("id") - now) / 86_400_000.0;
            y[i] = o.optDouble(field);
        }
        ChartView c = new ChartView(this, 150).title("", "").xUnit("days").minSpan("bpm".equals(unit) ? 10 : 1);
        c.add(new ChartView.Series(x, y, color, false, null));
        c.add(new ChartView.Series(x, y, color, true, null));
        return c;
    }

    /** Step totals for the last 7 days (oldest first), -1 where there's no record. */
    private long[] stepDays() {
        SharedPreferences p = Store.prefs(this);
        long[] out = new long[7];
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = 6; i >= 0; i--) {
            out[i] = p.getLong("steps_" + f.format(cal.getTime()), -1);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        if (stepsToday >= 0) out[6] = stepsToday;
        return out;
    }

    /** Seven leaf-green bars with weekday initials; today is the darker one. */
    private static final class Bars extends View {
        private final long[] v;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Bars(Context c, long[] v) {
            super(c);
            this.v = v;
            text.setColor(Ui.FAINT);
            text.setTextSize(Ui.dp(c, 11));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Ui.font(c, 500, 14));
        }

        @Override
        protected void onDraw(Canvas c) {
            long max = 1;
            for (long x : v) max = Math.max(max, x);
            float w = getWidth(), h = getHeight() - Ui.dp(getContext(), 18), slot = w / v.length, bw = slot * 0.46f;
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -(v.length - 1));
            for (int i = 0; i < v.length; i++) {
                float cx = slot * (i + 0.5f);
                float bh = v[i] <= 0 ? Ui.dp(getContext(), 4) : Math.max(Ui.dp(getContext(), 4), h * v[i] / max);
                bar.setColor(v[i] < 0 ? Ui.CARD_2 : i == v.length - 1 ? 0xFF6F8A5C : 0xFFB9C7A8);
                r.set(cx - bw / 2, h - bh, cx + bw / 2, h);
                c.drawRoundRect(r, Ui.dp(getContext(), 5), Ui.dp(getContext(), 5), bar);
                c.drawText(new SimpleDateFormat("EEEEE", Locale.US).format(cal.getTime()), cx, getHeight() - 2, text);
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
    }

    // ---- Live parts -----------------------------------------------------------------------------

    private void updateLive() {
        ThermalMonitor.State s = monitor.state();
        String v = s.verdict;
        int color = "READY".equals(v) ? Ui.GOOD : v == null || "UNSUPPORTED".equals(v) ? Ui.MUTED : Ui.WARN;
        if (tempStatus != null) Ui.setChip(tempStatus, "READY".equals(v) ? "Phone ready" : Ui.readyLabel(v), color == Ui.MUTED ? Ui.FAINT : color);
        if (checkInText != null) {
            int next = nextStep();
            if (next < 0) {
                if (checkInBadge != null) restyle(checkInBadge, "Done", Ui.GOOD);
                checkInText.setText("All four are in for today. Nicely done.");
            } else if (next == 0) {   // temperature is next: it's the one that needs a settled phone
                if (checkInBadge != null) restyle(checkInBadge, Ui.readyLabel(v), color);
                checkInText.setText("READY".equals(v) ? "Temperature is first. Your phone is ready."
                        : "TOO_WARM".equals(v) ? "Temperature is first. Let the phone cool a bit."
                        : "BLOCKED".equals(v) ? "Temperature is first. Unplug the charger."
                        : "Temperature is first. Let the phone settle.");
            } else {
                if (checkInBadge != null) restyle(checkInBadge, stepsDone() + " of 4", Ui.CORAL);
                checkInText.setText(STEPS[next] + " is next. " + STEP_TIMES[next] + ".");
            }
        }
        if (phoneText != null && !Double.isNaN(s.phoneC)) {
            phoneText.setText(String.format(Locale.US, "%.1f °C", s.phoneC));
            double d = s.driftCPerMin;
            phoneCaption.setText(Double.isNaN(d) ? "Its own sensors" : Math.abs(d) < 0.03 ? "Steady"
                    : String.format(Locale.US, "%s %.2f °C/min", d > 0 ? "Warming" : "Cooling", Math.abs(d)));
        }
        updateEyes();
        showFloors();
        if (watchStatus != null) {
            WatchLink w = WatchLink.get(this);
            watchStatus.setText(w.streaming() ? "Connected: " + w.deviceName() : "Optional · pair it to compare heart rate");
            watchStatus.setTextColor(w.streaming() ? Ui.GOOD : Ui.MUTED);
        }
    }

    private void restyle(TextView badge, String text, int color) {
        TextView fresh = Ui.badge(this, text, color);
        badge.setText(text);
        badge.setTextColor(fresh.getCurrentTextColor());
        badge.setBackground(fresh.getBackground());
    }

    private void settings() {
        ThermalMonitor.State s = monitor.state();
        String msg = String.format(Locale.US, "Everything stays on this phone: measurements and raw sensor sessions.%n%n"
                        + "Phone: %s, %s%nThermistors: %s%nWatch: %s%nData folder: %s%n%nResearch prototype, not a medical device. v0.2",
                android.os.Build.MODEL, s.powerMode.toLowerCase(Locale.US),
                Boolean.FALSE.equals(monitor.access()) ? "need setup (" + ThermalMonitor.SETUP + ")" : Double.isNaN(s.phoneC) ? "starting"
                        : String.format(Locale.US, "%.1f °C, %s", s.phoneC, s.message.toLowerCase(Locale.US)),
                WatchLink.get(this).streaming() ? "connected" : "not connected", Store.dir(this));
        new AlertDialog.Builder(this).setTitle("Apollo").setMessage(msg).setPositiveButton("Done", null).show();
    }

    // ---- Sensors --------------------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            stepsToday = DailyCounts.onStepCounter(this, (long) e.values[0]);   // also kept by AllDayService
            showSteps();
        }
    }

    private void showSteps() {
        if (stepsText == null || stepsToday < 0) return;
        stepsText.setText(String.format(Locale.US, "%,d", stepsToday));
        stepsBar.set((float) stepsToday / STEP_GOAL);
        stepsCaption.setText(String.format(Locale.US, "%.0f%% of your %,d goal", 100.0 * stepsToday / STEP_GOAL, STEP_GOAL));
    }

    /** Flights climbed today (AllDayService counts them; it writes within a minute of each flight). */
    private void showFloors() {
        if (floorsText == null) return;
        String today = DailyCounts.today();
        int up = DailyCounts.floorsUp(this, today), down = DailyCounts.floorsDown(this, today);
        floorsText.setText(String.valueOf(up));
        floorsCaption.setText(up == 0 && down == 0 ? (AllDayService.running ? "Today, on foot" : "Starts counting in the background")
                : String.format(Locale.US, "≈ %.0f m up today%s", up * FloorCounter.FLOOR_M, down > 0 ? " · " + down + " down" : ""));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---- Formatting -----------------------------------------------------------------------------

    /** Heart-rate results with a usable number (POOR ones show "--", so they're left out of summaries). */
    private static List<JSONObject> readable(List<JSONObject> rows) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : rows) if (!"POOR".equals(o.optString("verdict")) && o.optDouble("bpm", -1) > 0) out.add(o);
        return out;
    }

    private static String quality(String verdict) {
        return "GOOD".equals(verdict) ? "Good signal" : "WEAK".equals(verdict) ? "Weak signal" : "No clear reading";
    }

    private static boolean isToday(long ms) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(ms);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(ms));
    }

    /** "Today · 9:35 PM", "Yesterday · 8:10 PM", "Oct 12 · 7:02 AM". */
    private static String when(long ms) {
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ms);
        String day = isToday(ms) ? "Today" : y.get(Calendar.YEAR) == b.get(Calendar.YEAR) && y.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
                ? "Yesterday" : new SimpleDateFormat("MMM d", Locale.US).format(new Date(ms));
        return day + " · " + clock(ms);
    }
}
