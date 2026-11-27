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
    private static final int MAX_JOIN_HANDOFF_FAILURES = 3;
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
    private LinearLayout serverList;
    private EditText searchField;
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
    private boolean activityResumed;
    private String lastError;
    private long fetchedAt;
    private int launchRequests;

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
        buildShell();
        if (Build.VERSION.SDK_INT >= 21) root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        IntentFilter filter = new IntentFilter("com.monsterhopper.STATE"); filter.addAction("com.monsterhopper.CANDIDATE");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceReceiver, filter);
        handleJoinIntent(getIntent());
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
        floatingControl.setOnClickListener(v -> toggleAutoHop());
        FrameLayout.LayoutParams floatParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        floatParams.setMargins(0, 0, dp(16), dp(80));
        root.addView(floatingControl, floatParams);
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

        LinearLayout current = card(page, false);
        sectionHeading(current, "CURRENT PICK", "LOWEST PLAYERS", CYAN);
        String candidate = prefs().getString("activeServer", "");
        int players = prefs().getInt("activePlayers", -1);
        candidateLabel = text(candidate.isEmpty() ? "No server selected yet" : players >= 0 ? players + " players" : "Server ready", 16, TEXT, true);
        current.addView(candidateLabel);
        candidateIdLabel = text(candidate.isEmpty() ? "Start Auto-Hop or choose a server below." : shortId(candidate), 12, MUTED, false);
        candidateIdLabel.setPadding(0, dp(5), 0, dp(11)); current.addView(candidateIdLabel);
        candidateJoinAction = action("OPEN IN ROBLOX", true, () -> { String id = prefs().getString("activeServer", ""); if (!id.isEmpty()) joinServer(id); });
        candidateJoinAction.setVisibility(candidate.isEmpty() ? View.GONE : View.VISIBLE); current.addView(candidateJoinAction);

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
        reset.addView(text("History clears at :00, :15, :30 and :45 on your device clock.", 12, MUTED, false));
        LinearLayout stats = card(page, false); sectionHeading(stats, "ACTIVITY", "THIS WINDOW", MUTED);
        stats.addView(text(visitedIds().size() + " tried this window  ·  " + launchRequests + " launch requests overall", 15, TEXT, true));
        stats.addView(action("CLEAR VISITED HISTORY", false, this::clearHistory));
        LinearLayout items = card(page, false); sectionHeading(items, "SERVER IDS", "REAL IDS", CYAN);
        Set<String> ids = visitedIds();
        if (ids.isEmpty()) items.addView(emptyState("No servers tried in this window", "A server is added after Roblox accepts the open request."));
        else {
            ArrayList<String> sorted = new ArrayList<>(ids); Collections.sort(sorted);
            for (String id : sorted) {
                LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(0, dp(7), 0, dp(7));
                TextView label = text(shortId(id), 12, MUTED, false); line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(action("COPY", false, () -> copyId(id)), new LinearLayout.LayoutParams(dp(76), dp(38)));
                items.addView(line); items.addView(separator());
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
            TextView chip = action(INTERVAL_OPTIONS[i] + "s", i == intervalIndex, () -> saveInterval(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1); params.setMargins(dp(2), 0, dp(2), 0); options.addView(chip, params);
        }
        interval.addView(options);

        LinearLayout preferences = card(page, false); sectionHeading(preferences, "AUTO-HOP PREFERENCES", "PERSISTENT", CYAN);
        addSettingToggle(preferences, "Hide tried servers", "Hide IDs in the current 15-minute history window.", "hideVisited", true);
        addSettingToggle(preferences, "Floating control", "Show a draggable control over other apps when permission is granted.", "floatingEnabled", false);
        preferences.addView(text(Settings.canDrawOverlays(this) ? "Overlay permission is enabled." : "Without overlay permission, use the in-app floating control.", 11, MUTED, false));
        preferences.addView(action("CONFIGURE OVERLAY PERMISSION", false, this::requestOverlayPermission));

        LinearLayout data = card(page, false); sectionHeading(data, "LOCAL DATA", "THIS DEVICE", MUTED);
        data.addView(text("Server results are fetched live. Clearing cached data removes the visible result page and reloads from Roblox.", 12, MUTED, false));
        data.addView(action("CLEAR CACHED SERVER DATA", false, this::clearCachedData));
        data.addView(action("RESET VISITED HISTORY", false, this::clearHistory));
        LinearLayout join = card(page, false); sectionHeading(join, "JOINING ROBLOX", "BEST EFFORT", CYAN);
        join.addView(text("With the visible overlay enabled, Auto-Hop can request Roblox to open a candidate in the background. Without it, use the notification's Join action. Roblox does not confirm that you joined the selected instance. If Roblox is missing, install it from Google Play.", 12, MUTED, false));
        join.addView(action("OPEN ROBLOX GAME PAGE", false, () -> openGamePage()));
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
            if ("hideVisited".equals(key)) renderTab();
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

    private void cycleSort() {
        if ("Fewest players".equals(sortMode)) sortMode = "Most players";
        else if ("Most players".equals(sortMode)) sortMode = "Lowest occupancy";
        else sortMode = "Fewest players";
        prefs().edit().putString("sortMode", sortMode).apply();
        if ("Servers".equals(activeTab)) renderTab();
    }
    private String sortLabel() { return "SORT  ·  " + sortMode.toUpperCase(Locale.ROOT); }

    private void toggleAutoHop() {
        if (isServiceRunning()) { serviceAction("STOP"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        serviceAction("START");
    }

    private void serviceAction(String action) {
        Intent intent = new Intent(this, AutoHopService.class); intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26 && ("START".equals(action) || "TOGGLE".equals(action))) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Auto-Hop could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::updateLiveLabels, 250L);
    }

    private void joinServer(String id) {
        if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            new AlertDialog.Builder(this).setTitle("Invalid server ID").setMessage("Roblox did not provide a valid public server identifier.").setPositiveButton("Close", null).show(); return;
        }
        prefs().edit().putString("activeServer", id).putInt("activePlayers", playersFor(id)).apply();
        String direct = "roblox://placeId=" + PLACE_ID + "&gameInstanceId=" + Uri.encode(id);
        String legacy = "roblox://experiences/start?placeId=" + PLACE_ID + "&gameInstanceId=" + Uri.encode(id);
        boolean launched = openRobloxUri(direct) || openRobloxUri(legacy);
        if (launched) {
            recordVisited(id); launchRequests++; prefs().edit().putInt("launchRequests", launchRequests).apply();
            removeFromStoredSet("queuedCandidates", id); removeFromStoredSet("failedLaunchIds", id);
            prefs().edit().putString("pendingCandidate", "").putInt("joinHandoffFailures", 0).apply();
            Toast.makeText(this, "Launch request sent. Roblox does not confirm the server join.", Toast.LENGTH_LONG).show();
            updateLiveLabels(); if ("Home".equals(activeTab)) renderTab(); return;
        }
        removeFromStoredSet("queuedCandidates", id);
        prefs().edit().putString("pendingCandidate", "").apply();
        HashSet<String> failed = new HashSet<>(prefs().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
        failed.add(id); prefs().edit().putStringSet("failedLaunchIds", failed).apply();
        int failures = prefs().getInt("joinHandoffFailures", 0) + 1;
        prefs().edit().putInt("joinHandoffFailures", failures).apply();
        if (failures >= MAX_JOIN_HANDOFF_FAILURES && isServiceRunning()) serviceAction("STOP");
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
        prefs().edit().remove("visited").remove("queuedCandidates").remove("failedLaunchIds")
                .putInt("joinHandoffFailures", 0).putString("historyWindow", historyWindowKey()).apply();
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
            prefs().edit().remove("visited").remove("queuedCandidates").remove("failedLaunchIds")
                    .putInt("joinHandoffFailures", 0).putString("historyWindow", key).apply();
            if ("History".equals(activeTab)) renderTab();
        }
    }

    private String historyWindowKey() {
        Calendar now = Calendar.getInstance(); int minute = (now.get(Calendar.MINUTE) / 15) * 15;
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY), minute);
    }

    private long nextResetMillis() {
        Calendar next = Calendar.getInstance(); next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
        int nextQuarter = (next.get(Calendar.MINUTE) / 15 + 1) * 15;
        next.set(Calendar.MINUTE, nextQuarter); return next.getTimeInMillis();
    }

    private String nextResetText() {
        long remaining = Math.max(0, nextResetMillis() - System.currentTimeMillis());
        long minutes = remaining / 60000L, seconds = (remaining / 1000L) % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private int intervalSeconds() { return INTERVAL_OPTIONS[intervalIndex]; }
    private int indexForInterval(int seconds) {
        for (int i = 0; i < INTERVAL_OPTIONS.length; i++) if (INTERVAL_OPTIONS[i] == seconds) return i;
        return 1;
    }
    private void saveInterval(int index) {
        intervalIndex = Math.max(0, Math.min(INTERVAL_OPTIONS.length - 1, index));
        prefs().edit().putInt("intervalSeconds", intervalSeconds()).putInt("joinHandoffFailures", 0).apply();
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
        floatingControl.setText(running ? "AUTO-HOP  ·  ON" : "AUTO-HOP  ·  OFF");
        floatingControl.setTextColor(running ? Color.rgb(8, 28, 34) : TEXT);
        floatingControl.setBackground(ripple(shape(running ? CYAN : Color.rgb(28, 49, 67), running ? CYAN : CYAN, 24, 1)));
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
            nextHopLabel.setText(running ? (seconds > 0 ? "Next server scan in " + seconds + " seconds" : "Checking public servers…") : "Starts with a " + intervalSeconds() + " second interval");
        }
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
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) serviceAction("START");
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
