package com.monsterhopper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static volatile boolean appVisible;
    private static final String PREFS = "hopper";
    private static final String PLACE_ID = RobloxServersClient.PLACE_ID;
    private static final int REQUEST_NOTIFICATIONS = 41;
    private static final int REQUEST_OVERLAY = 42;
    private static final int[] INTERVAL_OPTIONS = {5, 10, 15, 20, 30, 60};
    private static final int PAGE_SIZE = 20;
    private static final int BG = Color.rgb(7, 12, 22);
    private static final int SURFACE = Color.rgb(15, 23, 38);
    private static final int SURFACE_2 = Color.rgb(20, 31, 49);
    private static final int CYAN = Color.rgb(69, 213, 232);
    private static final int TEXT = Color.rgb(238, 243, 250);
    private static final int MUTED = Color.rgb(143, 158, 180);
    private static final int GREEN = Color.rgb(78, 218, 171);
    private static final int AMBER = Color.rgb(244, 188, 91);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RobloxServersClient serverClient = new RobloxServersClient();
    private final ArrayList<JSONObject> serverData = new ArrayList<>();
    private final Deque<RobloxServersClient.Page> previousPages = new ArrayDeque<>();
    private final Deque<Integer> previousPageNumbers = new ArrayDeque<>();
    private FrameLayout root;
    private LinearLayout content;
    private LinearLayout bottomNav;
    private TextView floatingControl;
    private TextView connectionLabel;
    private TextView autoStatus;
    private TextView startStopButton;
    private TextView nextHopLabel;
    private TextView candidateLabel;
    private TextView candidateIdLabel;
    private TextView candidateJoinAction;
    private TextView historyClockLabel;
    private TextView serverCountLabel;
    private TextView scheduleStatusLabel;
    private TextView activityStatsLabel;
    private LinearLayout serverList;
    private EditText searchField;
    private EditText privateInviteField;
    private TextView pagerLabel;
    private TextView pagerPrevious;
    private TextView pagerNext;
    private String activeTab = "Home";
    private String sortMode = "Fewest players";
    private String nextCursor;
    private int displayPage;
    private int apiPageNumber = 1;
    private int intervalIndex = 1;
    private boolean loading;
    private boolean hasLoaded;
    private boolean pendingStartAfterPermission;
    private String pendingServiceActionAfterPermission;
    private boolean activityResumed;
    private String lastError;
    private long fetchedAt;
    private int launchRequests;
    private int failedLaunches;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            ensureHistoryWindow();
            updateLiveLabels();
            if ("History".equals(activeTab)) renderTab();
            handler.postDelayed(this, 1000L);
        }
    };
    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateLiveLabels();
            if ("com.monsterhopper.REFRESH".equals(intent.getAction())) {
                fetchPage(null, true);
                return;
            }
            if ("com.monsterhopper.CANDIDATE".equals(intent.getAction()) && activityResumed
                    && prefs().getBoolean("autoHopEnabled", false)) {
                String id = intent.getStringExtra("serverId");
                if (id != null && !id.isEmpty()) joinServer(id);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(0);
        intervalIndex = indexForInterval(prefs().getInt("intervalSeconds", 10));
        sortMode = prefs().getString("sortMode", "Fewest players");
        launchRequests = prefs().getInt("launchRequests", 0);
        failedLaunches = prefs().getInt("failedLaunches", 0);
        buildShell();
        if (Build.VERSION.SDK_INT >= 21) root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        IntentFilter filter = new IntentFilter("com.monsterhopper.STATE");
        filter.addAction("com.monsterhopper.CANDIDATE"); filter.addAction("com.monsterhopper.REFRESH");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceReceiver, filter);
        handleJoinIntent(getIntent());
        if (getIntent() != null && getIntent().getBooleanExtra("openSettings", false)) activeTab = "Settings";
        renderTab();
        fetchPage(null, false);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams shellParams = new FrameLayout.LayoutParams(-1, -1);
        root.addView(shell, shellParams);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomNav = new LinearLayout(this);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(8), dp(8), dp(8), dp(10));
        bottomNav.setBackground(shape(Color.rgb(10, 16, 27), 0, 0, 0));
        shell.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(66)));
        addNavItem("Home", R.drawable.ic_home);
        addNavItem("Servers", R.drawable.ic_servers);
        addNavItem("History", R.drawable.ic_history);
        addNavItem("Settings", R.drawable.ic_settings);

        floatingControl = text("AUTO-HOP  ·  OFF", 11, TEXT, true);
        floatingControl.setGravity(Gravity.CENTER);
        floatingControl.setPadding(dp(16), dp(12), dp(16), dp(12));
        floatingControl.setBackground(ripple(shape(Color.rgb(28, 49, 67), CYAN, 24, 1)));
        floatingControl.setElevation(dp(8));
        floatingControl.setContentDescription("Open Monster Hopper floating controls");
        floatingControl.setOnClickListener(v -> showFloatingControlPanel());
        floatingControl.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY; boolean dragged;
            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = view.getX(); startY = view.getY(); dragged = false; return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(7)) dragged = true;
                    float x = Math.max(dp(8), Math.min(root.getWidth() - view.getWidth() - dp(8), startX + dx));
                    float y = Math.max(dp(8), Math.min(root.getHeight() - view.getHeight() - dp(8), startY + dy));
                    view.setX(x); view.setY(y); return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    if (dragged) prefs().edit().putInt("inAppFloatX", (int)view.getX()).putInt("inAppFloatY", (int)view.getY()).apply();
                    else view.performClick();
                    return true;
                }
                return true;
            }
        });
        FrameLayout.LayoutParams floatParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        floatParams.setMargins(0, 0, dp(16), dp(80));
        root.addView(floatingControl, floatParams);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (floatingControl == null || root.getWidth() <= 0) return;
            int savedX = prefs().getInt("inAppFloatX", -1), savedY = prefs().getInt("inAppFloatY", -1);
            if (savedX >= 0 && savedY >= 0) {
                floatingControl.setX(Math.min(savedX, root.getWidth() - floatingControl.getWidth() - dp(8)));
                floatingControl.setY(Math.min(savedY, root.getHeight() - floatingControl.getHeight() - dp(8)));
            }
        });
        setContentView(root);
    }

    private void addNavItem(String label, int iconResource) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(6), dp(4), dp(6), dp(3));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource); icon.setColorFilter(MUTED); icon.setContentDescription(label);
        item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(24)));
        TextView caption = text(label, 10, MUTED, true);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption, new LinearLayout.LayoutParams(-1, dp(17)));
        item.setOnClickListener(v -> { activeTab = label; renderTab(); });
        item.setTag(new View[]{icon, caption});
        bottomNav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void renderTab() {
        if (content == null) return;
        content.removeAllViews();
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) bottomNav.getChildAt(i);
            View[] labels = (View[]) item.getTag();
            boolean selected = activeTab.equals(((TextView)labels[1]).getText().toString());
            ((ImageView)labels[0]).setColorFilter(selected ? CYAN : MUTED);
            ((TextView)labels[1]).setTextColor(selected ? TEXT : MUTED);
        }
        if ("Servers".equals(activeTab)) buildServersPage();
        else if ("History".equals(activeTab)) buildHistoryPage();
        else if ("Settings".equals(activeTab)) buildSettingsPage();
        else buildDashboard();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        updateFloatingControl();
    }

    private void buildDashboard() {
        ScrollView scroll = scroll();
        LinearLayout page = pageColumn();
        header(page, "Monster Hopper", "PUBLIC SERVER BROWSER");
        LinearLayout hero = card(page, true);
        TextView tag = text("CATCH A MONSTER  /  ROBLOX", 10, CYAN, true); hero.addView(tag);
        TextView title = text("Find a quieter\nserver to play.", 27, TEXT, true);
        title.setPadding(0, dp(12), 0, dp(7)); hero.addView(title);
        hero.addView(text("Real public instances · Place " + PLACE_ID, 12, MUTED, false));
        LinearLayout stateRow = row(); stateRow.setPadding(0, dp(18), 0, 0);
        connectionLabel = text(connectionText(), 11, connectionColor(), true);
        stateRow.addView(dot(connectionColor()), new LinearLayout.LayoutParams(dp(8), dp(8)));
        LinearLayout.LayoutParams stateTextParams = new LinearLayout.LayoutParams(0, -2, 1); stateTextParams.leftMargin = dp(8);
        stateRow.addView(connectionLabel, stateTextParams);
        hero.addView(stateRow);

        LinearLayout hop = card(page, false);
        sectionHeading(hop, "AUTO-HOP", isServiceRunning() ? "ACTIVE" : "READY", isServiceRunning() ? GREEN : MUTED);
        autoStatus = text(isServiceRunning() ? "Looking for a low-population server" : "Choose a server automatically", 15, TEXT, true);
        autoStatus.setPadding(0, dp(8), 0, dp(3)); hop.addView(autoStatus);
        nextHopLabel = text("Starts with a " + intervalSeconds() + " second interval", 12, MUTED, false); hop.addView(nextHopLabel);
        LinearLayout controls = row(); controls.setPadding(0, dp(13), 0, 0);
        TextView interval = action("EVERY " + intervalSeconds() + " SEC", false, () -> { activeTab = "Settings"; renderTab(); });
        controls.addView(interval, new LinearLayout.LayoutParams(0, dp(46), 1));
        startStopButton = action(isServiceRunning() ? "STOP AUTO-HOP" : "START AUTO-HOP", !isServiceRunning(), this::toggleAutoHop);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(46), 1.35f); startParams.leftMargin = dp(8); controls.addView(startStopButton, startParams);
        hop.addView(controls);
        TextView pause = action(AutoHopService.isPaused() ? "RESUME AUTO-HOP" : "PAUSE AUTO-HOP", false,
                () -> serviceAction(AutoHopService.isPaused() ? "RESUME" : "PAUSE"));
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(-1, dp(40)); pauseParams.topMargin = dp(7); hop.addView(pause, pauseParams);
        TextView noBoss = action("NO BOSS · SKIP THIS SERVER", false, this::skipForNoBoss);
        LinearLayout.LayoutParams noBossParams = new LinearLayout.LayoutParams(-1, dp(40)); noBossParams.topMargin = dp(7);
        noBoss.setVisibility(isServiceRunning() && !prefs().getString("activeServer", "").isEmpty() ? View.VISIBLE : View.GONE);
        hop.addView(noBoss, noBossParams);

        LinearLayout schedule = card(page, false);
        boolean scheduleEnabled = prefs().getBoolean("auto15MinEnabled", false);
        sectionHeading(schedule, "AUTO 15 MIN START", scheduleEnabled ? "ON · IST" : "OFF", CYAN);
        scheduleStatusLabel = text(scheduleText(), 12, MUTED, false);
        scheduleStatusLabel.setPadding(0, dp(7), 0, dp(8)); schedule.addView(scheduleStatusLabel);
        schedule.addView(action(scheduleEnabled ? "TURN SCHEDULE OFF" : "SCHEDULE 5-MINUTE SESSIONS",
                scheduleEnabled, this::toggleAuto15MinSchedule));

        LinearLayout current = card(page, false);
        sectionHeading(current, "SELECTED SERVER", "JOIN NOT CONFIRMED", CYAN);
        String candidate = prefs().getString("activeServer", "");
        int players = prefs().getInt("activePlayers", -1);
        candidateLabel = text(candidate.isEmpty() ? "No server selected yet" : players >= 0 ? players + " players" : "Server ready", 16, TEXT, true);
        current.addView(candidateLabel);
        candidateIdLabel = text(candidate.isEmpty() ? "Start Auto-Hop or choose a server below." : shortId(candidate), 12, MUTED, false);
        candidateIdLabel.setPadding(0, dp(5), 0, dp(11)); current.addView(candidateIdLabel);
        candidateJoinAction = action("OPEN IN ROBLOX", true, () -> { String id = prefs().getString("activeServer", ""); if (!id.isEmpty()) joinServer(id); });
        candidateJoinAction.setVisibility(candidate.isEmpty() ? View.GONE : View.VISIBLE); current.addView(candidateJoinAction);
        current.addView(action("JOIN PRIVATE SERVER", false, this::joinPrivateServer));

        LinearLayout activity = card(page, false);
        sectionHeading(activity, "SESSION ACTIVITY", "REAL COUNTS", CYAN);
        activityStatsLabel = text("", 12, TEXT, false); activity.addView(activityStatsLabel);

        LinearLayout servers = card(page, false);
        LinearLayout heading = row();
        heading.addView(text("LIVE SERVERS", 11, CYAN, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(action("REFRESH", false, () -> fetchPage(null, true)), new LinearLayout.LayoutParams(dp(96), dp(38)));
        servers.addView(heading);
        TextView summary = text(hasLoaded ? serverSummary() : loading ? "Connecting directly to Roblox…" : errorSummary(), 12, MUTED, false);
        summary.setPadding(0, dp(10), 0, dp(8)); servers.addView(summary);
        List<JSONObject> preview = filteredSortedServers();
        if (loading && !hasLoaded) servers.addView(emptyState("Connecting to Roblox public servers…", "No sample data is shown while connecting."));
        else if (preview.isEmpty()) servers.addView(emptyState(hasLoaded ? "No eligible public servers on this page" : "Servers unavailable", hasLoaded ? "Try refreshing or move to another page." : errorSummary()));
        else for (int i = 0; i < Math.min(3, preview.size()); i++) addServerCard(servers, preview.get(i), false);
        if (preview.size() > 3) servers.addView(action("VIEW ALL SERVERS", false, () -> { activeTab = "Servers"; renderTab(); }));
        scroll.addView(page); content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private void buildServersPage() {
        ScrollView scroll = scroll();
        LinearLayout page = pageColumn();
        header(page, "Public servers", "LIVE DATA FROM ROBLOX");
        LinearLayout controls = card(page, false);
        LinearLayout top = row();
        top.addView(action("REFRESH", true, () -> fetchPage(null, true)), new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView sort = action(sortLabel(), false, this::cycleSort);
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(0, dp(44), 1.25f); sortParams.leftMargin = dp(8); top.addView(sort, sortParams);
        controls.addView(top);
        searchField = new EditText(this);
        searchField.setSingleLine(true); searchField.setTextSize(14); searchField.setTextColor(TEXT); searchField.setHintTextColor(MUTED);
        searchField.setHint("Filter by server ID"); searchField.setPadding(dp(13), dp(9), dp(13), dp(9));
        searchField.setBackground(shape(Color.rgb(9, 15, 26), Color.rgb(37, 52, 71), 12, 1));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderServerCards(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(46)); searchParams.topMargin = dp(10); controls.addView(searchField, searchParams);
        serverCountLabel = text(serverSummary(), 12, MUTED, false); serverCountLabel.setPadding(0, dp(10), 0, 0); controls.addView(serverCountLabel);
        page.addView(controls);
        serverList = new LinearLayout(this); serverList.setOrientation(LinearLayout.VERTICAL); serverList.setPadding(0, dp(5), 0, dp(8)); page.addView(serverList);
        LinearLayout paging = row();
        pagerPrevious = action("←  PREVIOUS", false, this::previousPage);
        pagerLabel = text("Page 1", 11, MUTED, true); pagerLabel.setGravity(Gravity.CENTER);
        pagerNext = action("NEXT  →", false, this::nextPage);
        paging.addView(pagerPrevious, new LinearLayout.LayoutParams(0, dp(44), 1));
        paging.addView(pagerLabel, new LinearLayout.LayoutParams(dp(78), dp(44)));
        paging.addView(pagerNext, new LinearLayout.LayoutParams(0, dp(44), 1)); page.addView(paging);
        scroll.addView(page); content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        renderServerCards();
    }

    private void renderServerCards() {
        if (serverList == null || !"Servers".equals(activeTab)) return;
        serverList.removeAllViews();
        List<JSONObject> rows = filteredSortedServers();
        if (loading && !hasLoaded && rows.isEmpty()) serverList.addView(emptyState("Loading real Roblox instances…", "Fetching public servers for place " + PLACE_ID + "."));
        else if (rows.isEmpty()) serverList.addView(emptyState(hasLoaded ? "No eligible servers in this result" : "Could not load public servers", hasLoaded ? "Roblox returned no open servers matching this filter." : errorSummary()));
        else {
            int pages = localPageCount(rows); displayPage = Math.min(displayPage, pages - 1);
            int from = displayPage * PAGE_SIZE, to = Math.min(rows.size(), from + PAGE_SIZE);
            for (int i = from; i < to; i++) addServerCard(serverList, rows.get(i), true);
        }
        if (serverCountLabel != null) serverCountLabel.setText(serverSummary());
        if (pagerLabel != null) pagerLabel.setText("Page " + apiPageNumber + (localPageCount(rows) > 1 ? "." + (displayPage + 1) : ""));
        if (pagerPrevious != null) setActionEnabled(pagerPrevious, displayPage > 0 || !previousPages.isEmpty());
        if (pagerNext != null) setActionEnabled(pagerNext, displayPage < localPageCount(rows) - 1 || (nextCursor != null && !nextCursor.isEmpty()));
    }

    private void buildHistoryPage() {
        ScrollView scroll = scroll(); LinearLayout page = pageColumn();
        header(page, "Visited history", "LOCAL DEVICE · RESETS EVERY QUARTER HOUR");
        LinearLayout reset = card(page, false); sectionHeading(reset, "NEXT RESET", "LOCAL TIME", CYAN);
        historyClockLabel = text(nextResetText(), 24, TEXT, true); historyClockLabel.setPadding(0, dp(7), 0, dp(3)); reset.addView(historyClockLabel);
        reset.addView(text("Visited and skipped IDs clear at :00, :15, :30 and :45 on your device clock. The activity log stays available.", 12, MUTED, false));
        LinearLayout stats = card(page, false); sectionHeading(stats, "ACTIVITY", "THIS WINDOW", MUTED);
        stats.addView(text(visitedIds().size() + " visited this window  ·  " + launchRequests + " launch requests  ·  " + failedLaunches + " failed handoffs", 14, TEXT, true));
        stats.addView(action("CLEAR ACTIVITY AND VISITED IDS", false, this::clearHistory));
        LinearLayout items = card(page, false); sectionHeading(items, "RECENT ACTIVITY", "LOCAL HISTORY", CYAN);
        List<JSONObject> entries = HopHistoryStore.recent(this);
        if (entries.isEmpty()) items.addView(emptyState("No activity recorded yet", "A launch request is not proof that Roblox joined the selected server."));
        else {
            int shown = 0;
            for (JSONObject entry : entries) {
                if (shown++ >= 60) break;
                String id = entry.optString("id", "");
                long at = entry.optLong("at", 0L);
                String outcome = entry.optString("outcome", "requested");
                int color = "failed".equals(outcome) ? Color.rgb(255, 130, 145) : "skipped".equals(outcome) ? AMBER : GREEN;
                LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.VERTICAL); line.setPadding(0, dp(9), 0, dp(9));
                LinearLayout head = row();
                TextView status = text(outcome.replace('_', ' ').toUpperCase(Locale.ROOT), 10, color, true);
                head.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
                head.addView(text(at > 0 ? new java.text.SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(new java.util.Date(at)) : "Time unavailable", 10, MUTED, false));
                line.addView(head);
                int players = entry.optInt("players", -1), capacity = entry.optInt("capacity", -1);
                line.addView(text(shortId(id) + "   ·   " + (players >= 0 && capacity > 0 ? players + " / " + capacity + " players" : "player count unavailable"), 12, TEXT, false));
                String detail = entry.optString("detail", ""); if (!detail.isEmpty()) line.addView(text(detail, 10, MUTED, false));
                LinearLayout actions = row(); actions.addView(line, new LinearLayout.LayoutParams(0, -2, 1));
                actions.addView(action("COPY ID", false, () -> copyId(id)), new LinearLayout.LayoutParams(dp(74), dp(36)));
                items.addView(actions); items.addView(separator());
            }
        }
        scroll.addView(page); content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private void buildSettingsPage() {
        ScrollView scroll = scroll(); LinearLayout page = pageColumn();
        header(page, "Settings", "PREFERENCES STORED ON THIS DEVICE");
        LinearLayout interval = card(page, false); sectionHeading(interval, "HOP INTERVAL", "AUTO-HOP", CYAN);
        interval.addView(text("Roblox may rate-limit rapid server scans. The app respects its retry window.", 12, MUTED, false));
        LinearLayout options = row(); options.setPadding(0, dp(13), 0, 0);
        for (int i = 0; i < INTERVAL_OPTIONS.length; i++) {
            final int index = i;
            TextView chip = action(INTERVAL_OPTIONS[i] + "s", INTERVAL_OPTIONS[i] == intervalSeconds(), () -> saveInterval(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1); params.setMargins(dp(2), 0, dp(2), 0); options.addView(chip, params);
        }
        interval.addView(options);
        LinearLayout custom = row(); custom.setPadding(0, dp(9), 0, 0);
        EditText customSeconds = new EditText(this); customSeconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        customSeconds.setSingleLine(true); customSeconds.setText(String.valueOf(intervalSeconds())); customSeconds.setTextColor(TEXT);
        customSeconds.setHintTextColor(MUTED); customSeconds.setHint("5–120 seconds");
        customSeconds.setBackground(shape(Color.rgb(9, 15, 26), Color.rgb(37, 52, 71), 10, 1));
        custom.addView(customSeconds, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView applyCustom = action("SAVE CUSTOM", false, () -> {
            int value; try { value = Integer.parseInt(customSeconds.getText().toString()); }
            catch (Exception error) { value = 10; }
            value = Math.max(5, Math.min(120, value));
            prefs().edit().putInt("intervalSeconds", value).apply();
            serviceAction("RECONFIGURE"); Toast.makeText(this, "Hop interval set to " + value + " seconds.", Toast.LENGTH_SHORT).show(); renderTab();
        });
        custom.addView(applyCustom, new LinearLayout.LayoutParams(dp(120), dp(44))); interval.addView(custom);

        LinearLayout preferences = card(page, false); sectionHeading(preferences, "AUTO-HOP PREFERENCES", "PERSISTENT", CYAN);
        addSettingToggle(preferences, "Hide visited servers", "Hide launch-requested IDs until the next local quarter-hour reset.", "hideVisited", true);
        addSettingToggle(preferences, "Skip visited on Auto-Hop", "Do not choose a previously attempted server during this reset window.", "skipVisited", true);
        addSettingToggle(preferences, "Floating control", "Show a draggable control over other apps when permission is granted.", "floatingEnabled", false);
        addSettingToggle(preferences, "Resume after Android process recovery", "Allow Android to restore Auto-Hop after reclaiming the process. Force-stop still ends it.", "resumeAutoHop", true);
        preferences.addView(text(Settings.canDrawOverlays(this) ? "Overlay permission is enabled." : "Without overlay permission, use the in-app floating control.", 11, MUTED, false));
        preferences.addView(action("CONFIGURE OVERLAY PERMISSION", false, this::requestOverlayPermission));

        LinearLayout automation = card(page, false); sectionHeading(automation, "WEBSITE SCHEDULE", "IST", CYAN);
        automation.addView(text("Run Auto-Hop for five minutes at each 00, 15, 30 and 45 minute boundary in India Standard Time. The schedule remains off until enabled.", 12, MUTED, false));
        automation.addView(text(scheduleText(), 12, TEXT, true));
        automation.addView(action(prefs().getBoolean("auto15MinEnabled", false) ? "TURN SCHEDULE OFF" : "ENABLE AUTO 15 MIN START",
                prefs().getBoolean("auto15MinEnabled", false), this::toggleAuto15MinSchedule));

        LinearLayout tuning = card(page, false); sectionHeading(tuning, "RETRY AND SORT", "PERSISTENT", CYAN);
        Spinner retries = new Spinner(this); String[] retryOptions = {"3 join attempts", "5 join attempts", "8 join attempts"};
        ArrayAdapter<String> retryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, retryOptions);
        retryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); retries.setAdapter(retryAdapter);
        int configuredRetries = prefs().getInt("maxJoinFailures", 5); retries.setSelection(configuredRetries <= 3 ? 0 : configuredRetries <= 5 ? 1 : 2);
        retries.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs().edit().putInt("maxJoinFailures", position == 0 ? 3 : position == 1 ? 5 : 8).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        tuning.addView(retries);
        tuning.addView(action("SERVER SORT · " + sortMode.toUpperCase(Locale.ROOT), false, this::cycleSort));

        LinearLayout privateServer = card(page, false); sectionHeading(privateServer, "PRIVATE SERVER", "INVITE LINK", CYAN);
        privateInviteField = new EditText(this); privateInviteField.setSingleLine(true); privateInviteField.setTextColor(TEXT); privateInviteField.setHintTextColor(MUTED);
        privateInviteField.setHint("Paste a Roblox private-server invite link"); privateInviteField.setText(prefs().getString("privateServerInvite", ""));
        privateInviteField.setPadding(dp(12), dp(9), dp(12), dp(9));
        privateInviteField.setBackground(shape(Color.rgb(9, 15, 26), Color.rgb(37, 52, 71), 12, 1)); privateServer.addView(privateInviteField);
        privateServer.addView(action("SAVE INVITE LINK", false, this::savePrivateInvite));
        privateServer.addView(action("OPEN PRIVATE SERVER", true, this::joinPrivateServer));

        LinearLayout data = card(page, false); sectionHeading(data, "LOCAL DATA", "THIS DEVICE", MUTED);
        data.addView(text("Public pages load live from Roblox. The local server page is memory-only; visited activity is stored on this device.", 12, MUTED, false));
        data.addView(action("CLEAR CACHED SERVER DATA", false, this::clearCachedData));
        data.addView(action("RESET VISITED AND ACTIVITY HISTORY", false, this::clearHistory));
        LinearLayout join = card(page, false); sectionHeading(join, "JOINING ROBLOX", "BEST EFFORT", CYAN);
        join.addView(text("The app requests Roblox to open a public Job ID or saved private-server invite. Android may block background app starts without your visible overlay permission. A successful handoff is not confirmation that Roblox joined the server.", 12, MUTED, false));
        join.addView(action("OPEN ROBLOX GAME PAGE", false, () -> openGamePage()));
        LinearLayout about = card(page, false); sectionHeading(about, "ABOUT AND DIAGNOSTICS", "ANDROID", MUTED);
        about.addView(text("Monster Hopper · " + appVersion() + "\nPlace ID " + PLACE_ID + "\nRoblox app " + (isRobloxInstalled() ? "detected" : "not detected")
                + " · Overlay " + (Settings.canDrawOverlays(this) ? "allowed" : "not allowed")
                + " · Notifications " + (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "allowed" : "not allowed"), 11, MUTED, false));
        about.addView(action("COPY GAME ID", false, () -> copyId(PLACE_ID)));
        scroll.addView(page); content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private void addSettingToggle(LinearLayout parent, String title, String detail, String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.addView(text(title, 14, TEXT, true));
        TextView description = text(detail, 11, MUTED, false); description.setPadding(0, dp(3), 0, 0); labels.addView(description);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox box = new CheckBox(this); box.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{CYAN, MUTED}));
        box.setChecked(prefs().getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((button, checked) -> {
            if ("floatingEnabled".equals(key) && checked && !Settings.canDrawOverlays(this)) {
                prefs().edit().putBoolean(key, false).apply(); box.setChecked(false); requestOverlayPermission();
                Toast.makeText(this, "Use the in-app floating control until overlay access is enabled.", Toast.LENGTH_LONG).show(); return;
            }
            prefs().edit().putBoolean(key, checked).apply();
            if ("floatingEnabled".equals(key)) serviceAction("RECONFIGURE");
            if ("hideVisited".equals(key) || "skipVisited".equals(key)) renderTab();
        });
        row.addView(box); parent.addView(row); parent.addView(separator());
    }

    private void addServerCard(LinearLayout parent, JSONObject server, boolean detailed) {
        String id = server.optString("jobId", "");
        int players = server.optInt("players", -1), capacity = server.optInt("maxPlayers", -1);
        if (id.isEmpty() || players < 0 || capacity <= 0 || players >= capacity) return;
        boolean visited = visitedIds().contains(id);
        LinearLayout card = card(parent, false);
        LinearLayout title = row(); title.setGravity(Gravity.CENTER_VERTICAL);
        View indicator = dot(players == 0 ? GREEN : (capacity - players <= 2 ? AMBER : CYAN));
        title.addView(indicator, new LinearLayout.LayoutParams(dp(8), dp(8)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = dp(9);
        title.addView(text(players + " / " + capacity + " players", 16, TEXT, true), titleParams);
        if (visited) title.addView(text("TRIED", 9, AMBER, true));
        card.addView(title);
        TextView slots = text((capacity - players) + " open slots", 12, players == 0 ? GREEN : MUTED, true); slots.setPadding(dp(17), dp(4), 0, dp(9)); card.addView(slots);
        View barTrack = new View(this); barTrack.setBackground(shape(Color.rgb(34, 47, 65), 0, 5, 0));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(4)); barParams.setMargins(dp(17), 0, dp(17), dp(10));
        FrameLayout bar = new FrameLayout(this); bar.addView(barTrack, new FrameLayout.LayoutParams(-1, -1));
        View fill = new View(this); fill.setBackground(shape(players == 0 ? GREEN : CYAN, 0, 5, 0));
        FrameLayout.LayoutParams fillParams = new FrameLayout.LayoutParams(Math.max(dp(4), (int)(getResources().getDisplayMetrics().widthPixels * .72f * players / (float)capacity)), -1, Gravity.START);
        bar.addView(fill, fillParams); card.addView(bar, barParams);
        LinearLayout idRow = row(); idRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView idText = text("ID  " + shortId(id), 11, MUTED, false); idRow.addView(idText, new LinearLayout.LayoutParams(0, dp(34), 1));
        idRow.addView(action("COPY", false, () -> copyId(id)), new LinearLayout.LayoutParams(dp(72), dp(34))); card.addView(idRow);
        LinearLayout actions = row(); actions.setPadding(0, dp(7), 0, 0);
        actions.addView(action("JOIN THIS SERVER", true, () -> joinServer(id)), new LinearLayout.LayoutParams(0, dp(42), 1));
        if (detailed) {
            TextView more = action("DETAILS", false, () -> showServerDetails(server));
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(90), dp(42)); moreParams.leftMargin = dp(8); actions.addView(more, moreParams);
        }
        card.addView(actions);
    }

    private void showServerDetails(JSONObject server) {
        String id = server.optString("jobId", "");
        double pingValue = server.optDouble("ping", Double.NaN);
        double fpsValue = server.optDouble("fps", Double.NaN);
        new AlertDialog.Builder(this).setTitle("Server details")
                .setMessage("Server ID\n" + id + "\n\nPlayers: " + server.optInt("players", -1) + " / " + server.optInt("maxPlayers", -1)
                        + "\nAvailable slots: " + server.optInt("slots", -1)
                        + "\nPing: " + (Double.isNaN(pingValue) ? "not reported" : Math.round(pingValue) + " ms")
                        + "\nFPS: " + (Double.isNaN(fpsValue) ? "not reported" : Math.round(fpsValue)))
                .setPositiveButton("Copy ID", (dialog, which) -> copyId(id)).setNegativeButton("Close", null).show();
    }

    private List<JSONObject> filteredSortedServers() {
        ArrayList<JSONObject> result = new ArrayList<>();
        String query = searchField == null ? "" : searchField.getText().toString().trim().toLowerCase(Locale.ROOT);
        Set<String> visited = visitedIds();
        Set<String> failed = new HashSet<>(prefs().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
        boolean hide = prefs().getBoolean("hideVisited", true);
        for (JSONObject item : serverData) {
            String id = item.optString("jobId", "");
            if (id.isEmpty() || item.optInt("players", -1) < 0 || item.optInt("maxPlayers", 0) <= item.optInt("players", -1)) continue;
            if (hide && visited.contains(id)) continue;
            if (failed.contains(id)) continue;
            if (!query.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(query)) continue;
            result.add(item);
        }
        final String mode = sortMode;
        Collections.sort(result, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                int players = Integer.compare(a.optInt("players", Integer.MAX_VALUE), b.optInt("players", Integer.MAX_VALUE));
                if ("Most players".equals(mode)) players = -players;
                if (players != 0) return players;
                if ("Lowest occupancy".equals(mode)) {
                    double x = a.optInt("players") / (double)a.optInt("maxPlayers");
                    double y = b.optInt("players") / (double)b.optInt("maxPlayers");
                    int occupancy = Double.compare(x, y); if (occupancy != 0) return occupancy;
                }
                if ("Best FPS".equals(mode)) {
                    int fps = compareMetric(b.optDouble("fps", Double.NaN), a.optDouble("fps", Double.NaN)); if (fps != 0) return fps;
                }
                if ("Lowest ping".equals(mode)) {
                    int ping = compareMetric(a.optDouble("ping", Double.NaN), b.optDouble("ping", Double.NaN)); if (ping != 0) return ping;
                }
                return a.optString("jobId").compareTo(b.optString("jobId"));
            }
        });
        return result;
    }

    private void fetchPage(String cursor, boolean force) {
        if (loading) return;
        loading = true; lastError = null; updateLiveLabels();
        if ("Servers".equals(activeTab)) renderServerCards();
        final String requestedCursor = cursor;
        executor.execute(() -> {
            try {
                RobloxServersClient.Page result = fetchWithRetries(requestedCursor);
                runOnUiThread(() -> {
                    if (requestedCursor != null && !requestedCursor.isEmpty()) {
                        previousPages.addLast(currentPage()); previousPageNumbers.addLast(apiPageNumber); apiPageNumber++;
                    } else if (force || !hasLoaded) {
                        previousPages.clear(); previousPageNumbers.clear(); apiPageNumber = 1;
                    }
                    serverData.clear(); serverData.addAll(result.servers); nextCursor = result.nextCursor;
                    displayPage = 0; hasLoaded = true; fetchedAt = System.currentTimeMillis(); loading = false; lastError = null;
                    saveCurrentPageCursor(requestedCursor);
                    updateLiveLabels();
                    if ("Servers".equals(activeTab)) renderServerCards(); else renderTab();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loading = false; lastError = readableError(error); updateLiveLabels();
                    if ("Servers".equals(activeTab)) renderServerCards(); else renderTab();
                });
            }
        });
    }

    private RobloxServersClient.Page fetchWithRetries(String cursor) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return serverClient.fetchPage(cursor); }
            catch (RobloxServersClient.ServerApiException error) {
                last = error;
                if (error.statusCode == 429) {
                    if (attempt == 2) break;
                    sleepInterruptibly(Math.min(60000L, Math.max(1000L, error.retryAfterMs)));
                } else if (error.statusCode >= 500 && attempt < 2) sleepInterruptibly(800L * (attempt + 1));
                else throw error;
            } catch (IOException error) {
                last = error;
                if (attempt == 2) break;
                sleepInterruptibly(700L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("Roblox server request failed.") : last;
    }

    private void sleepInterruptibly(long duration) throws InterruptedException { Thread.sleep(duration); }
    private String readableError(Exception error) {
        if (error instanceof RobloxServersClient.ServerApiException) {
            RobloxServersClient.ServerApiException api = (RobloxServersClient.ServerApiException)error;
            if (api.statusCode == 429) return "Roblox is rate-limiting server requests. Retry in about " + Math.max(1, api.retryAfterMs / 1000) + " seconds.";
            return "Roblox server request failed (HTTP " + api.statusCode + ").";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Network request to Roblox failed." : message;
    }

    private RobloxServersClient.Page currentPage() {
        ArrayList<JSONObject> copy = new ArrayList<>(serverData);
        return new RobloxServersClient.Page(copy, nextCursor, null);
    }

    private void nextPage() {
        List<JSONObject> visible = filteredSortedServers();
        int localPages = localPageCount(visible);
        if (displayPage < localPages - 1) { displayPage++; renderServerCards(); return; }
        if (nextCursor != null && !nextCursor.isEmpty()) fetchPage(nextCursor, false);
    }

    private void previousPage() {
        if (displayPage > 0) { displayPage--; renderServerCards(); return; }
        if (previousPages.isEmpty()) return;
        RobloxServersClient.Page previous = previousPages.removeLast();
        if (!previousPageNumbers.isEmpty()) apiPageNumber = previousPageNumbers.removeLast();
        serverData.clear(); serverData.addAll(previous.servers); nextCursor = previous.nextCursor;
        displayPage = Math.max(0, localPageCount(filteredSortedServers()) - 1); hasLoaded = true; renderServerCards();
    }

    private int localPageCount(List<JSONObject> rows) { return Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE); }
    private String serverSummary() {
        if (!hasLoaded) return loading ? "Contacting Roblox public servers…" : errorSummary();
        if (lastError != null) return "Showing last successful page · refresh failed";
        return serverData.size() + " eligible servers on this API page" + (nextCursor != null ? "  ·  more available" : "  ·  end of list");
    }
    private String errorSummary() { return lastError == null ? "No server results loaded." : lastError; }

    private int compareMetric(double a, double b) {
        boolean aKnown = Double.isFinite(a), bKnown = Double.isFinite(b);
        if (aKnown != bKnown) return aKnown ? -1 : 1;
        return aKnown ? Double.compare(a, b) : 0;
    }

    private void cycleSort() {
        String[] modes = {"Fewest players", "Most players", "Lowest occupancy", "Best FPS", "Lowest ping"};
        int index = 0; for (int i = 0; i < modes.length; i++) if (modes[i].equals(sortMode)) index = i;
        sortMode = modes[(index + 1) % modes.length];
        prefs().edit().putString("sortMode", sortMode).apply();
        if ("Servers".equals(activeTab)) renderTab();
    }
    private String sortLabel() { return "SORT  ·  " + sortMode.toUpperCase(Locale.ROOT); }

    private void toggleAutoHop() {
        if (isServiceRunning()) { serviceAction("STOP"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            pendingServiceActionAfterPermission = "START";
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        serviceAction("START");
    }

    private void serviceAction(String action) {
        Intent intent = new Intent(this, AutoHopService.class); intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26 && ("START".equals(action) || "TOGGLE".equals(action) || "SCHEDULE_ON".equals(action))) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Auto-Hop could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::updateLiveLabels, 250L);
    }

    private void toggleAuto15MinSchedule() {
        if (prefs().getBoolean("auto15MinEnabled", false)) { serviceAction("SCHEDULE_OFF"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true; pendingServiceActionAfterPermission = "SCHEDULE_ON";
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS); return;
        }
        serviceAction("SCHEDULE_ON");
    }

    private String scheduleText() {
        if (!prefs().getBoolean("auto15MinEnabled", false)) return "Schedule is off. Enable it to run a five-minute session at each quarter-hour boundary in IST.";
        long end = prefs().getLong("scheduledSessionEndAt", 0L);
        if (end > System.currentTimeMillis()) return "Scheduled session active · " + HopClock.countdown(System.currentTimeMillis(), end) + " remaining";
        long next = prefs().getLong("scheduledNextStartAt", 0L);
        if (next <= 0L) next = IstQuarterSchedule.nextStart(System.currentTimeMillis(), false);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm 'IST'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        return "Next start " + format.format(new java.util.Date(next)) + " · " + HopClock.countdown(System.currentTimeMillis(), next);
    }

    private void skipForNoBoss() {
        String id = prefs().getString("activeServer", "");
        if (!isServiceRunning() || id.isEmpty()) {
            Toast.makeText(this, "Start Auto-Hop and select a server first.", Toast.LENGTH_SHORT).show(); return;
        }
        Intent intent = new Intent(this, AutoHopService.class); intent.setAction("SKIP");
        intent.putExtra("skipReason", "No boss found in this server; moving on.");
        try { startService(intent); }
        catch (Exception error) { Toast.makeText(this, "Could not skip this server.", Toast.LENGTH_SHORT).show(); }
        Toast.makeText(this, "Marked this server No Boss and searching for another instance.", Toast.LENGTH_LONG).show();
    }

    private void showFloatingControlPanel() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(15), dp(18), dp(14)); panel.setBackground(shape(SURFACE, Color.rgb(45, 72, 94), 18, 1));
        panel.addView(text("MONSTER HOPPER CONTROLS", 10, CYAN, true));
        boolean running = isServiceRunning(), paused = AutoHopService.isPaused();
        panel.addView(text(paused ? "Paused" : running ? "Auto-Hop running" : "Auto-Hop stopped", 18, TEXT, true));
        String active = prefs().getString("activeServer", "");
        panel.addView(text(active.isEmpty() ? "No server selected" : shortId(active) + " · "
                + prefs().getInt("activePlayers", -1) + " / " + prefs().getInt("activeCapacity", -1)
                + " players · join not confirmed", 12, MUTED, false));
        long next = prefs().getLong("nextHopAt", 0L);
        panel.addView(text("Next scan · " + (running && !paused && next > 0 ? HopClock.countdown(System.currentTimeMillis(), next) : "—"), 12, MUTED, false));
        panel.addView(text("Servers in scan · " + prefs().getInt("autoServersFound", 0)
                + "   Visited · " + visitedIds().size() + "   Lowest · " + (prefs().getInt("lowestServerPlayers", -1) < 0 ? "—" : prefs().getInt("lowestServerPlayers", -1)), 11, MUTED, false));
        LinearLayout primary = row(); primary.setPadding(0, dp(12), 0, 0);
        primary.addView(action(running ? "STOP" : "START", !running, () -> { serviceAction(running ? "STOP" : "START"); }), new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView pause = action(paused ? "RESUME" : "PAUSE", false, () -> serviceAction(paused ? "RESUME" : "PAUSE"));
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(0, dp(44), 1); pauseParams.leftMargin = dp(7); primary.addView(pause, pauseParams);
        panel.addView(primary);
        LinearLayout secondary = row(); secondary.setPadding(0, dp(7), 0, 0);
        secondary.addView(action("NO BOSS / SKIP", false, this::skipForNoBoss), new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView refresh = action("REFRESH", false, () -> { serviceAction("REFRESH"); fetchPage(null, true); });
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(40), 1); refreshParams.leftMargin = dp(7); secondary.addView(refresh, refreshParams); panel.addView(secondary);
        CheckBox hide = new CheckBox(this); hide.setText("Hide visited servers"); hide.setTextColor(TEXT);
        hide.setButtonTintList(ColorStateList.valueOf(CYAN)); hide.setChecked(prefs().getBoolean("hideVisited", true));
        hide.setOnCheckedChangeListener((button, checked) -> { prefs().edit().putBoolean("hideVisited", checked).apply(); renderTab(); }); panel.addView(hide);
        LinearLayout intervals = row(); intervals.setPadding(0, dp(5), 0, 0);
        for (int seconds : INTERVAL_OPTIONS) {
            TextView chip = action(seconds + "s", seconds == intervalSeconds(), () -> {
                prefs().edit().putInt("intervalSeconds", seconds).apply(); serviceAction("RECONFIGURE"); renderTab();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1); params.setMargins(dp(1), 0, dp(1), 0); intervals.addView(chip, params);
        }
        panel.addView(intervals);
        LinearLayout links = row(); links.setPadding(0, dp(9), 0, 0);
        links.addView(action("SETTINGS", false, () -> { activeTab = "Settings"; renderTab(); }), new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView app = action("CLOSE PANEL", false, () -> { });
        LinearLayout.LayoutParams appParams = new LinearLayout.LayoutParams(0, dp(40), 1); appParams.leftMargin = dp(7); links.addView(app, appParams);
        panel.addView(links);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE", null).create();
        app.setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    private void savePrivateInvite() {
        String value = privateInviteField == null ? "" : privateInviteField.getText().toString().trim();
        String code = parsePrivateServerCode(value);
        if (code == null) { Toast.makeText(this, "Enter a valid Roblox private-server invite link or code.", Toast.LENGTH_LONG).show(); return; }
        prefs().edit().putString("privateServerInvite", value).apply();
        Toast.makeText(this, "Private-server invite saved on this device.", Toast.LENGTH_SHORT).show();
    }

    private void joinPrivateServer() {
        String invite = prefs().getString("privateServerInvite", "");
        if (invite.isEmpty()) { promptPrivateInvite(); return; }
        String code = parsePrivateServerCode(invite);
        if (code == null) { Toast.makeText(this, "Saved private-server invite is invalid. Update it in Settings.", Toast.LENGTH_LONG).show(); return; }
        String uri = "roblox://navigation/share_links?code=" + Uri.encode(code) + "&type=Server";
        if (openRobloxUri(uri)) Toast.makeText(this, "Private-server launch requested. Roblox does not confirm entry.", Toast.LENGTH_LONG).show();
        else {
            new AlertDialog.Builder(this).setTitle("Roblox app unavailable")
                    .setMessage("Install or update Roblox, then try the saved private-server invite again.")
                    .setPositiveButton("Install Roblox", (dialog, which) -> openUri("https://play.google.com/store/apps/details?id=com.roblox.client", null))
                    .setNegativeButton("Cancel", null).show();
        }
    }

    private void promptPrivateInvite() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Roblox private-server invite URL");
        new AlertDialog.Builder(this).setTitle("Save private-server invite").setView(input)
                .setPositiveButton("SAVE AND OPEN", (dialog, which) -> {
                    String value = input.getText().toString().trim(); String code = parsePrivateServerCode(value);
                    if (code == null) { Toast.makeText(this, "That private-server invite is invalid.", Toast.LENGTH_LONG).show(); return; }
                    prefs().edit().putString("privateServerInvite", value).apply(); joinPrivateServer();
                }).setNegativeButton("CANCEL", null).show();
    }

    private String parsePrivateServerCode(String input) {
        String value = String.valueOf(input == null ? "" : input).trim();
        if (value.matches("[A-Za-z0-9_-]{12,128}")) return value;
        try {
            Uri uri = Uri.parse(value); String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("roblox.com") || host.equalsIgnoreCase("www.roblox.com") || host.equalsIgnoreCase("ro.blox.com"))) return null;
            String type = uri.getQueryParameter("type");
            if (type != null && !"server".equalsIgnoreCase(type)) return null;
            String code = uri.getQueryParameter("code"); if (code == null) code = uri.getQueryParameter("privateServerLinkCode");
            return code != null && code.matches("[A-Za-z0-9_-]{12,128}") ? code : null;
        } catch (Exception error) { return null; }
    }

    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (Exception error) { return "unknown"; }
    }

    private boolean isRobloxInstalled() {
        try { getPackageManager().getPackageInfo("com.roblox.client", 0); return true; }
        catch (Exception error) { return false; }
    }

    private String sessionDuration() {
        long start = prefs().getLong("sessionStartedAt", 0L);
        if (start <= 0L) return "session not started";
        long end = prefs().getLong("sessionEndedAt", 0L);
        long seconds = Math.max(0L, ((end > 0L ? end : System.currentTimeMillis()) - start) / 1000L);
        return String.format(Locale.US, "session %02d:%02d", seconds / 3600L, (seconds / 60L) % 60L);
    }

    private void joinServer(String id) {
        if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            new AlertDialog.Builder(this).setTitle("Invalid server ID").setMessage("Roblox did not provide a valid public server identifier.").setPositiveButton("Close", null).show(); return;
        }
        int playerCount = playersFor(id); int capacity = capacityFor(id);
        prefs().edit().putString("activeServer", id).putInt("activePlayers", playerCount).putInt("activeCapacity", capacity).apply();
        String direct = "roblox://placeId=" + PLACE_ID + "&gameInstanceId=" + Uri.encode(id);
        String legacy = "roblox://experiences/start?placeId=" + PLACE_ID + "&gameInstanceId=" + Uri.encode(id);
        boolean launched = openRobloxUri(direct) || openRobloxUri(legacy);
        if (launched) {
            recordVisited(id); launchRequests++;
            prefs().edit().putInt("launchRequests", launchRequests).putString("joinState", "Join requested · not confirmed").apply();
            HopHistoryStore.record(this, id, playerCount, capacity, "requested", "Roblox app accepted the open request. Join is not confirmed.");
            removeFromStoredSet("queuedCandidates", id); removeFromStoredSet("failedLaunchIds", id); removeFromStoredSet("skippedServerIds", id);
            prefs().edit().putString("pendingCandidate", "").putInt("joinHandoffFailures", 0).apply();
            Toast.makeText(this, "Launch request sent. Roblox does not confirm the server join.", Toast.LENGTH_LONG).show();
            updateLiveLabels(); if ("Home".equals(activeTab)) renderTab(); return;
        }
        removeFromStoredSet("queuedCandidates", id);
        prefs().edit().putString("pendingCandidate", "").apply();
        HashSet<String> failed = new HashSet<>(prefs().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
        failed.add(id); prefs().edit().putStringSet("failedLaunchIds", failed).apply();
        int failures = prefs().getInt("joinHandoffFailures", 0) + 1;
        failedLaunches++;
        prefs().edit().putInt("joinHandoffFailures", failures).putInt("failedLaunches", failedLaunches)
                .putString("joinState", "Join request failed").apply();
        HopHistoryStore.record(this, id, playerCount, capacity, "failed", isRobloxInstalled()
                ? "Android could not open the Roblox server link." : "Roblox app is not installed.");
        int maxFailures = Math.max(1, Math.min(10, prefs().getInt("maxJoinFailures", 5)));
        if (failures >= maxFailures && isServiceRunning()) serviceAction("STOP");
        new AlertDialog.Builder(this).setTitle("Roblox app not available")
                .setMessage("Install Roblox to open this server. If it is already installed, open the game page and try again. A join cannot be confirmed by Monster Hopper.")
                .setPositiveButton("Install Roblox", (dialog, which) -> openUri("https://play.google.com/store/apps/details?id=com.roblox.client", null))
                .setNeutralButton("Open game page", (dialog, which) -> openGamePage())
                .setNegativeButton("Cancel", null).show();
    }

    private boolean openRobloxUri(String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.setPackage("com.roblox.client"); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent); return true;
        } catch (Exception ignored) { return false; }
    }

    private void openGamePage() {
        openUri("https://www.roblox.com/games/" + PLACE_ID + "/Catch-a-Monster", null);
    }

    private void openUri(String uri, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            if (packageName != null) intent.setPackage(packageName);
            startActivity(intent);
        } catch (Exception error) { Toast.makeText(this, "Could not open link on this device.", Toast.LENGTH_LONG).show(); }
    }

    private int playersFor(String id) {
        for (JSONObject server : serverData) if (id.equals(server.optString("jobId"))) return server.optInt("players", -1);
        return -1;
    }

    private int capacityFor(String id) {
        for (JSONObject server : serverData) if (id.equals(server.optString("jobId"))) return server.optInt("maxPlayers", -1);
        return prefs().getInt("activeCapacity", -1);
    }

    private void recordVisited(String id) {
        ensureHistoryWindow();
        HashSet<String> ids = new HashSet<>(visitedIds()); ids.add(id);
        prefs().edit().putStringSet("visited", ids).putString("historyWindow", historyWindowKey()).apply();
    }

    private void removeFromStoredSet(String key, String value) {
        HashSet<String> values = new HashSet<>(prefs().getStringSet(key, Collections.<String>emptySet()));
        values.remove(value); prefs().edit().putStringSet(key, values).apply();
    }

    private void clearHistory() {
        HopHistoryStore.clear(this); launchRequests = 0; failedLaunches = 0;
        prefs().edit().remove("visited").remove("queuedCandidates").remove("failedLaunchIds").remove("skippedServerIds")
                .putInt("launchRequests", 0).putInt("failedLaunches", 0).putInt("joinHandoffFailures", 0)
                .putString("historyWindow", historyWindowKey()).apply();
        updateLiveLabels(); renderTab();
    }

    private void clearCachedData() {
        serverData.clear(); previousPages.clear(); previousPageNumbers.clear(); nextCursor = null; hasLoaded = false; lastError = null; displayPage = 0; apiPageNumber = 1;
        updateLiveLabels(); renderTab(); fetchPage(null, true);
    }

    private Set<String> visitedIds() {
        return new HashSet<>(prefs().getStringSet("visited", Collections.<String>emptySet()));
    }

    private void ensureHistoryWindow() {
        String key = historyWindowKey();
        if (!key.equals(prefs().getString("historyWindow", ""))) {
            prefs().edit().remove("visited").remove("queuedCandidates").remove("failedLaunchIds").remove("skippedServerIds")
                    .putInt("joinHandoffFailures", 0).putString("historyWindow", key).apply();
            if ("History".equals(activeTab)) renderTab();
        }
    }

    private String historyWindowKey() {
        return HopClock.windowKey(System.currentTimeMillis(), Calendar.getInstance().getTimeZone());
    }

    private long nextResetMillis() {
        return HopClock.nextQuarterHour(System.currentTimeMillis(), Calendar.getInstance().getTimeZone());
    }

    private String nextResetText() {
        return HopClock.countdown(System.currentTimeMillis(), nextResetMillis());
    }

    private int intervalSeconds() { return HopClock.normalizeInterval(prefs().getInt("intervalSeconds", INTERVAL_OPTIONS[1])); }
    private int indexForInterval(int seconds) {
        for (int i = 0; i < INTERVAL_OPTIONS.length; i++) if (INTERVAL_OPTIONS[i] == seconds) return i;
        return 1;
    }
    private void saveInterval(int index) {
        intervalIndex = Math.max(0, Math.min(INTERVAL_OPTIONS.length - 1, index));
        prefs().edit().putInt("intervalSeconds", INTERVAL_OPTIONS[intervalIndex]).putInt("joinHandoffFailures", 0).apply();
        serviceAction("RECONFIGURE"); renderTab();
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) { Toast.makeText(this, "Overlay permission is already enabled.", Toast.LENGTH_SHORT).show(); return; }
        try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY); }
        catch (Exception error) { Toast.makeText(this, "Open Android Settings to enable display over other apps.", Toast.LENGTH_LONG).show(); }
    }

    private void handleJoinIntent(Intent intent) {
        if (intent != null && "JOIN_SERVER".equals(intent.getAction())) {
            String id = intent.getStringExtra("serverId");
            if (id != null) handler.postDelayed(() -> joinServer(id), 350L);
        }
    }

    private boolean isServiceRunning() { return AutoHopService.isRunning(); }
    public static boolean isVisibleToUser() { return appVisible; }

    @Override protected void onResume() { super.onResume(); activityResumed = true; appVisible = true; ensureHistoryWindow(); updateLiveLabels(); handler.removeCallbacks(clockTick); handler.post(clockTick); }
    @Override protected void onPause() { activityResumed = false; appVisible = false; handler.removeCallbacks(clockTick); super.onPause(); }

    private void updateLiveLabels() {
        if (floatingControl == null) return;
        boolean running = isServiceRunning();
        boolean paused = AutoHopService.isPaused();
        floatingControl.setText(paused ? "AUTO-HOP  ·  PAUSED" : running ? "AUTO-HOP  ·  ON" : "AUTO-HOP  ·  OFF");
        floatingControl.setTextColor(running && !paused ? Color.rgb(8, 28, 34) : TEXT);
        int controlColor = paused ? AMBER : running ? CYAN : Color.rgb(28, 49, 67);
        floatingControl.setBackground(ripple(shape(controlColor, paused ? AMBER : CYAN, 24, 1)));
        if (autoStatus != null) autoStatus.setText(running ? prefs().getString("autoStatus", "Searching public servers") : "Choose a server automatically");
        if (startStopButton != null) {
            startStopButton.setText(running ? "STOP AUTO-HOP" : "START AUTO-HOP");
            startStopButton.setBackground(ripple(shape(running ? Color.rgb(29, 43, 59) : CYAN, running ? Color.rgb(50, 70, 91) : 0, 12, 1)));
            startStopButton.setTextColor(running ? TEXT : Color.rgb(7, 24, 32));
        }
        if (candidateLabel != null) {
            String id = prefs().getString("activeServer", ""); int players = prefs().getInt("activePlayers", -1);
            candidateLabel.setText(id.isEmpty() ? "No server selected yet" : players >= 0 ? players + " players" : "Server ready");
            if (candidateIdLabel != null) candidateIdLabel.setText(id.isEmpty() ? "Start Auto-Hop or choose a server below." : shortId(id));
            if (candidateJoinAction != null) candidateJoinAction.setVisibility(id.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (nextHopLabel != null) {
            long at = prefs().getLong("nextHopAt", 0L); long seconds = at <= 0 || !running ? 0 : Math.max(0, (at - System.currentTimeMillis() + 999) / 1000);
            nextHopLabel.setText(paused ? "Auto-Hop is paused" : running ? (seconds > 0 ? "Next server scan in " + seconds + " seconds" : "Checking public servers…") : "Starts with a " + intervalSeconds() + " second interval");
        }
        if (scheduleStatusLabel != null) scheduleStatusLabel.setText(scheduleText());
        if (activityStatsLabel != null) activityStatsLabel.setText(serverData.size() + " servers on current Roblox page · "
                + visitedIds().size() + " visited this window\n" + launchRequests + " launch requests · "
                + failedLaunches + " failed handoffs · " + sessionDuration());
        if (connectionLabel != null) {
            connectionLabel.setText(loading ? "CONTACTING ROBLOX" : hasLoaded ? "ROBLOX API · " + timeAgo(fetchedAt) : lastError == null ? "WAITING FOR ROBLOX" : "ROBLOX CONNECTION ERROR");
            connectionLabel.setTextColor(loading ? AMBER : hasLoaded ? GREEN : lastError == null ? MUTED : Color.rgb(255, 130, 145));
        }
        if (historyClockLabel != null) historyClockLabel.setText(nextResetText());
        if ("Servers".equals(activeTab) && !loading && serverCountLabel != null) serverCountLabel.setText(serverSummary());
    }

    private String timeAgo(long millis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000L);
        return seconds < 3 ? "now" : seconds + "s ago";
    }

    private void saveCurrentPageCursor(String cursor) { prefs().edit().putString("pageCursor", cursor == null ? "" : cursor).apply(); }

    private void copyId(String id) {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Roblox server ID", id));
        Toast.makeText(this, "Full server ID copied", Toast.LENGTH_SHORT).show();
    }

    private void updateFloatingControl() { if (floatingControl != null) floatingControl.setVisibility(View.VISIBLE); }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_NOTIFICATIONS && pendingStartAfterPermission) {
            pendingStartAfterPermission = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                String action = pendingServiceActionAfterPermission == null ? "START" : pendingServiceActionAfterPermission;
                pendingServiceActionAfterPermission = null; serviceAction(action);
            }
            else Toast.makeText(this, "Allow notifications to use Auto-Hop join prompts.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_OVERLAY) {
            boolean granted = Settings.canDrawOverlays(this);
            prefs().edit().putBoolean("floatingEnabled", granted).apply();
            serviceAction("RECONFIGURE");
            Toast.makeText(this, granted ? "Draggable overlay enabled." : "Overlay not enabled. The in-app control remains available.", Toast.LENGTH_LONG).show();
            renderTab();
        }
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleJoinIntent(intent); }

    private ScrollView scroll() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(72)); scroll.setBackgroundColor(BG); return scroll;
    }
    private LinearLayout pageColumn() {
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(20)); return page;
    }
    private void header(LinearLayout page, String title, String eyebrow) {
        TextView small = text(eyebrow, 9, CYAN, true); small.setLetterSpacing(.12f); page.addView(small);
        TextView heading = text(title, 25, TEXT, true); heading.setPadding(0, dp(5), 0, dp(13)); page.addView(heading);
    }
    private LinearLayout card(LinearLayout parent, boolean hero) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(14));
        card.setBackground(shape(hero ? Color.rgb(17, 35, 52) : SURFACE, hero ? Color.rgb(34, 80, 101) : Color.rgb(31, 45, 64), 18, 1));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(11); parent.addView(card, params);
        return card;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private void sectionHeading(LinearLayout parent, String title, String badge, int tint) {
        LinearLayout row = row(); TextView left = text(title, 10, CYAN, true); left.setLetterSpacing(.1f);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(text(badge, 9, tint, true));
        parent.addView(row);
    }
    private View emptyState(String title, String detail) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        box.setPadding(dp(14), dp(22), dp(14), dp(22)); box.setBackground(shape(Color.rgb(10, 17, 29), Color.rgb(27, 41, 58), 14, 1));
        box.addView(text(title, 14, TEXT, true)); TextView sub = text(detail, 11, MUTED, false); sub.setGravity(Gravity.CENTER); sub.setPadding(0, dp(6), 0, 0); box.addView(sub);
        return box;
    }
    private TextView text(String value, int size, int tint, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(tint); view.setTextSize(size);
        view.setFontFeatureSettings("kern"); view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false); return view;
    }
    private TextView action(String label, boolean primary, Runnable click) {
        TextView view = text(label, 10, primary ? Color.rgb(7, 24, 32) : TEXT, true); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(7), dp(10), dp(7)); view.setSingleLine(true); view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setBackground(ripple(shape(primary ? CYAN : SURFACE_2, primary ? 0 : Color.rgb(43, 61, 81), 12, primary ? 0 : 1)));
        view.setOnClickListener(v -> { v.animate().scaleX(.97f).scaleY(.97f).setDuration(80).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start(); click.run(); });
        return view;
    }
    private void setActionEnabled(TextView view, boolean enabled) { view.setEnabled(enabled); view.setAlpha(enabled ? 1f : .42f); }
    private View dot(int color) { View dot = new View(this); dot.setBackground(shape(color, 0, 5, 0)); return dot; }
    private View separator() { View line = new View(this); line.setBackgroundColor(Color.rgb(36, 50, 68)); return line; }
    private android.graphics.drawable.Drawable ripple(GradientDrawable content) {
        if (Build.VERSION.SDK_INT >= 21) return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.argb(45, 104, 224, 239)), content, null);
        return content;
    }
    private GradientDrawable shape(int fill, int stroke, int radius, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(Math.max(1, strokeDp)), stroke); return drawable;
    }
    private int connectionColor() { return loading ? AMBER : hasLoaded ? GREEN : lastError == null ? MUTED : Color.rgb(255, 130, 145); }
    private String connectionText() { return loading ? "CONTACTING ROBLOX" : hasLoaded ? "ROBLOX API CONNECTED" : lastError == null ? "WAITING FOR ROBLOX" : "ROBLOX CONNECTION ERROR"; }
    private String shortId(String id) { return id.length() > 23 ? id.substring(0, 9) + "…" + id.substring(id.length() - 8) : id; }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); serverClient.cancel();
        appVisible = false;
        try { unregisterReceiver(serviceReceiver); } catch (Exception ignored) { }
        executor.shutdownNow(); super.onDestroy();
    }
}
