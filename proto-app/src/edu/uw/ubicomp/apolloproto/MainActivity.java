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
    private double basePressure = Double.NaN, pressure = Double.NaN;
    private long stepsToday = -1;
    private CarePage care;
    private ChatPage chat;
    private ScrollView page;
    private int keepScroll = -1;

    // Live views on the current page (null when it isn't showing)
    private TextView stepsText, stepsCaption, baroText, baroCaption, phoneText, phoneCaption, tempStatus, watchStatus,
            checkInBadge, checkInText;
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
        Sensor baro = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (steps != null) sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
        if (baro != null) sensors.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL);
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
        if (code == CarePage.REQ_LOCATION) {
            care.locationResult(granted);
        } else if (granted) {
            Sensor steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (steps != null) sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
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
        stepsText = stepsCaption = baroText = baroCaption = phoneText = phoneCaption = tempStatus = watchStatus = null;
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
        showBaro();
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
                    : "calibrate".equals(action) ? CalibrateActivity.class : "calm".equals(action) ? CalmSenseActivity.class : null;
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
        stack.addView(new Ui.Sunset(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 400)));
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
        names.addView(Ui.label(this, new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date())));
        TextView title = Ui.add(names, Ui.title(this, "Today at a glance"), 8);
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
        Ui.add(col, Ui.text(this, greeting() + " Here's your day.", 15, Ui.MUTED, false), 6);

        // ---- Tests you take: nothing happens until you start one.
        sectionHead(col, "Tests you take", "Quick checks you start yourself. A minute or two each.", 26);

        LinearLayout check = Ui.add(col, Ui.card(this), 14);
        LinearLayout checkRow = Ui.add(check, Ui.row(this), 0);
        LinearLayout checkText = Ui.column(this);
        LinearLayout titleRow = Ui.add(checkText, Ui.row(this), 0);
        titleRow.addView(Ui.styled(this, "Your check-in", 19, Ui.TEXT, 700));
        checkInBadge = Ui.badge(this, "Checking", Ui.MUTED);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.leftMargin = Ui.dp(this, 10);
        titleRow.addView(checkInBadge, bl);
        checkInText = Ui.add(checkText, Ui.text(this, "", 14.5f, Ui.MUTED, false), 8);
        LinearLayout go = Ui.add(checkText, Ui.row(this), 12);
        go.addView(Ui.styled(this, "Start check-in", 15, Ui.CORAL_DARK, 600));
        View arrow = Ui.icon(this, "arrow", Ui.CORAL_DARK, 17);
        ((LinearLayout.LayoutParams) arrow.getLayoutParams()).leftMargin = Ui.dp(this, 6);
        go.addView(arrow);
        checkRow.addView(checkText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View face = Ui.mascot(this, "hello", 84);   // straight on the card's white
        ((LinearLayout.LayoutParams) face.getLayoutParams()).leftMargin = Ui.dp(this, 10);
        checkRow.addView(face);
        Ui.tappable(check, v -> startActivity(new Intent(this, FeverActivity.class)));

        LinearLayout tests = Ui.add(col, Ui.row(this), 12);
        tests.setBaselineAligned(false);
        tests.setGravity(Gravity.TOP);
        List<JSONObject> fh = new ArrayList<>();
        for (JSONObject o : Store.history(this, "forehead")) if (o.optDouble("core_c_est", -1) > 0) fh.add(o);
        LinearLayout temp = testTile(tests, "thermo", "Temperature", 0, v -> startActivity(new Intent(this, FeverActivity.class)));
        if (fh.isEmpty()) Ui.add(temp, Ui.styled(this, "Not yet", 24, Ui.TEXT, 600), 6);
        else Ui.add(temp, reading(String.format(Locale.US, "%.1f", fh.get(0).optDouble("core_c_est")), "°C", 32), 6);
        tempStatus = Ui.wrap(temp, Ui.chip(this, "Checking", Ui.FAINT), 10);
        tempStatus.setBackground(null);
        tempStatus.setPadding(0, 0, 0, 0);

        List<JSONObject> hr = readable(Store.history(this, "heart_rate"));
        LinearLayout heart = testTile(tests, "heart", "Heart rate", 12, v -> startActivity(new Intent(this, HeartRateActivity.class)));
        if (hr.isEmpty()) {
            Ui.add(heart, Ui.styled(this, "Not yet", 24, Ui.TEXT, 600), 6);
            Ui.add(heart, Ui.text(this, "20-second check", 13, Ui.MUTED, false), 10);
        } else {
            JSONObject last = hr.get(0);
            Ui.add(heart, reading(String.format(Locale.US, "%.0f", last.optDouble("bpm")), "bpm", 32), 6);
            if (hr.size() >= 2) {
                SparkView spark = new SparkView(this);
                double[] vals = new double[Math.min(10, hr.size())];
                for (int i = 0; i < vals.length; i++) vals[i] = hr.get(vals.length - 1 - i).optDouble("bpm");
                spark.setValues(vals);
                Ui.add(heart, spark, 6).getLayoutParams().height = Ui.dp(this, 30);
            }
            Ui.add(heart, Ui.text(this, ("GOOD".equals(last.optString("verdict")) ? "Good signal · " : "Weak signal · ")
                    + clock(last.optLong("id")), 13, Ui.MUTED, false), 6);
        }
        Ui.add(col, Ui.text(this, "Taking both? Start with temperature: the camera flash warms the phone.", 13, Ui.MUTED, false), 12);

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
        cardTop(elev, "layers", Ui.LEAF_DARK, "Elevation", null);
        baroText = Ui.add(elev, Ui.number(this, "–", 26), 8);
        baroCaption = Ui.add(elev, Ui.text(this, "Since this screen opened", 12.5f, Ui.MUTED, false), 4);
        pair.addView(elev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout warm = Ui.card(this);
        cardTop(warm, "thermo", Ui.LEAF_DARK, "Phone warmth", null);
        phoneText = Ui.add(warm, Ui.number(this, "–", 26), 8);
        phoneCaption = Ui.add(warm, Ui.text(this, "Its own sensors", 12.5f, Ui.MUTED, false), 4);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        wl.leftMargin = Ui.dp(this, 12);
        pair.addView(warm, wl);

        watchStatus = panelRow(panel, "watch", "Reference watch", "", v -> startActivity(new Intent(this, WatchActivity.class)));
        panelRow(panel, "trends", "Your trends", "Heart rate, temperature and steps over time", v -> {
            sub = "trends";
            render();
        });

        // ---- Extras
        sectionHead(col, "More to try", "Setup and the research experiments.", 30);
        int runs = Store.history(this, "calibration").size();
        extraTile(col, "plus", Ui.CORAL, "Calibrate temperature", runs == 0 ? "Teach Apollo how your forehead warms the phone"
                : runs + " run" + (runs == 1 ? "" : "s") + " saved · forehead runs and controls", CalibrateActivity.class);
        extraTile(col, "waves", Ui.LEAF, "Calm sensing", "Inaudible sonar notices agitation and suggests a breath · RageSense",
                CalmSenseActivity.class);
        extraTile(col, "play", Ui.LEAF, "Ball rescue", "Your hands on the screen become sand · Magical Touch", TouchLabActivity.class);

        Ui.add(col, Ui.text(this, "Research prototype, not a medical device. Everything stays on this phone.", 12, Ui.FAINT, false), 22)
                .setGravity(Gravity.CENTER);
    }

    /** A test tile (half width): icon, name, then the latest result is added by the caller. */
    private LinearLayout testTile(LinearLayout row, String icon, String name, int leftDp, View.OnClickListener go) {
        LinearLayout t = Ui.card(this);
        Ui.tappable(t, go);
        LinearLayout top = Ui.add(t, Ui.row(this), 0);
        top.addView(Ui.iconBadge(this, icon, Ui.CORAL, 36));
        top.addView(Ui.space(this));
        top.addView(Ui.chevron(this));
        Ui.add(t, Ui.styled(this, name, 15, Ui.TEXT, 600), 12);
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

    private static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 5 ? "Hello, night owl." : h < 12 ? "Good morning." : h < 18 ? "Good afternoon." : "Good evening.";
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
        if (checkInBadge != null) {
            List<JSONObject> fh = Store.history(this, "forehead"), hr = Store.history(this, "heart_rate");
            boolean tempToday = !fh.isEmpty() && isToday(fh.get(0).optLong("id"));
            boolean hrToday = !hr.isEmpty() && isToday(hr.get(0).optLong("id"));
            if (tempToday && hrToday) {
                restyle(checkInBadge, "Done", Ui.GOOD);
                checkInText.setText("Both check-ins are in for today. Nicely done.");
            } else {
                restyle(checkInBadge, Ui.readyLabel(v), color);
                checkInText.setText(tempToday ? "Temperature is done. Next, a 20-second heart-rate check."
                        : "READY".equals(v) ? "Your phone is still and steady: a good moment for temperature, then heart rate."
                        : "TOO_WARM".equals(v) ? "Your phone is a little warm. Give it a few minutes on the table, then start."
                        : "BLOCKED".equals(v) ? "Unplug the charger first. Charging warms the phone."
                        : "Temperature first, then heart rate. Leave the phone flat for a minute so it can settle.");
            }
        }
        if (phoneText != null && !Double.isNaN(s.phoneC)) {
            phoneText.setText(String.format(Locale.US, "%.1f °C", s.phoneC));
            double d = s.driftCPerMin;
            phoneCaption.setText(Double.isNaN(d) ? "Its own sensors" : Math.abs(d) < 0.03 ? "Steady"
                    : String.format(Locale.US, "%s %.2f °C/min", d > 0 ? "Warming" : "Cooling", Math.abs(d)));
        }
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
            // The counter runs since boot; keep the first value seen each day as that day's baseline.
            long total = (long) e.values[0];
            SharedPreferences p = Store.prefs(this);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            long base = p.getLong("steps_base", -1);
            if (!today.equals(p.getString("steps_day", "")) || base < 0 || total < base) {
                base = total;
                p.edit().putString("steps_day", today).putLong("steps_base", base).apply();
            }
            long now = total - base;
            if (now != stepsToday) p.edit().putLong("steps_" + today, now).apply();   // for the Trends bars
            stepsToday = now;
            showSteps();
        } else if (e.sensor.getType() == Sensor.TYPE_PRESSURE) {
            pressure = e.values[0];
            if (Double.isNaN(basePressure)) basePressure = pressure;
            showBaro();
        }
    }

    private void showSteps() {
        if (stepsText == null || stepsToday < 0) return;
        stepsText.setText(String.format(Locale.US, "%,d", stepsToday));
        stepsBar.set((float) stepsToday / STEP_GOAL);
        stepsCaption.setText(String.format(Locale.US, "%.0f%% of your %,d goal", 100.0 * stepsToday / STEP_GOAL, STEP_GOAL));
    }

    private void showBaro() {
        if (baroText == null || Double.isNaN(pressure)) return;
        double dh = SensorManager.getAltitude((float) basePressure, (float) pressure);
        if (Math.abs(dh) < 0.05) dh = 0;   // no "-0.0 m"
        baroText.setText(String.format(Locale.US, "%+.1f m", dh));
        baroCaption.setText(String.format(Locale.US, "≈ %.0f floors since this screen opened", Math.abs(dh) / 3.0));
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
