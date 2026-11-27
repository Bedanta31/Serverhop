package com.monsterhopper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AutoHopService extends Service {
    private static final String CHANNEL = "monster_hopper_auto";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "hopper";
    private static final int AUTO_SCAN_PAGES = 2;
    private static final int[] INTERVAL_OPTIONS = {5, 10, 15, 20, 30, 60};
    private static volatile boolean running;
    private static volatile boolean paused;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RobloxServersClient serversClient = new RobloxServersClient();
    private Runnable scanTask;
    private Runnable historyResetTask;
    private Runnable scheduledSessionTask;
    private final Runnable overlayTicker = new Runnable() {
        @Override public void run() {
            refreshOverlay();
            if (overlay != null) handler.postDelayed(this, 1000L);
        }
    };
    private Future<?> activeScan;
    private WindowManager windowManager;
    private View overlay;
    private LinearLayout overlayPanel;
    private TextView overlayStatus;
    private TextView overlayCurrent;
    private TextView overlayCountdown;
    private TextView overlayServers;
    private TextView overlayVisited;
    private int failureCount;
    private long nextHopAt;
    private boolean busy;
    private String skipReason = "Skipped from the floating controls.";

    public static boolean isRunning() { return running; }
    public static boolean isPaused() { return paused; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if ("STOP".equals(action)) { stopHop("Auto-Hop stopped"); return START_NOT_STICKY; }
        if ("SCHEDULE_ON".equals(action)) { enableSchedule(); return START_STICKY; }
        if ("SCHEDULE_OFF".equals(action)) { stopHop("Auto 15 Min Start schedule stopped"); return START_NOT_STICKY; }
        if ("PAUSE".equals(action)) { pauseHop(); return START_STICKY; }
        if ("RESUME".equals(action)) { resumeHop(); return START_STICKY; }
        if ("SKIP".equals(action)) {
            skipReason = intent.getStringExtra("skipReason");
            if (skipReason == null || skipReason.trim().isEmpty()) skipReason = "Skipped from the floating controls.";
            skipCurrentCandidate(); return START_STICKY;
        }
        if ("REFRESH".equals(action)) { requestRefresh(); return START_STICKY; }
        if ("SET_INTERVAL".equals(action)) {
            int seconds = HopClock.normalizeInterval(intent.getIntExtra("intervalSeconds", 10));
            preferences().edit().putInt("intervalSeconds", seconds).apply();
            reconfigure(); return START_STICKY;
        }
        if ("RECONFIGURE".equals(action)) {
            if (running) reconfigure();
            else if (overlayAllowed()) {
                startForeground(NOTIFICATION_ID, notification("Auto-Hop stopped · floating controls ready", null, true));
                showOverlayIfEnabled();
            } else stopSelf();
            return START_STICKY;
        }
        if (intent == null && !preferences().getBoolean("autoHopEnabled", false)
                && !preferences().getBoolean("auto15MinEnabled", false) && !overlayAllowed()) { stopSelf(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, notification("Preparing a live Roblox server scan", null, true));
        if ("TOGGLE".equals(action)) {
            if (running) stopHop("Auto-Hop stopped"); else startHop(false);
            return START_STICKY;
        }
        boolean scheduledMode = preferences().getBoolean("auto15MinEnabled", false);
        if (intent == null && scheduledMode) {
            scheduleNextQuarterSession();
            long sessionEnd = preferences().getLong("scheduledSessionEndAt", 0L);
            if (sessionEnd > System.currentTimeMillis() && !running) startHop(true);
        }
        if (!running && preferences().getBoolean("autoHopEnabled", false)
                && !scheduledMode && preferences().getBoolean("resumeAutoHop", true)) startHop();
        else if ("START".equals(action)) {
            if (!running) startHop(false);
        }
        showOverlayIfEnabled();
        return START_STICKY;
    }

    private void enableSchedule() {
        if (running) stopHop("Auto-Hop paused for scheduled sessions", true);
        long next = IstQuarterSchedule.nextStart(System.currentTimeMillis(), true);
        preferences().edit().putBoolean("auto15MinEnabled", true).putLong("scheduledNextStartAt", next)
                .putLong("scheduledSessionEndAt", 0L).apply();
        startForeground(NOTIFICATION_ID, notification("Auto 15 Min Start · next IST quarter", null, true));
        showOverlayIfEnabled(); scheduleNextQuarterSession(); broadcastState(null);
    }

    private void scheduleNextQuarterSession() {
        if (!preferences().getBoolean("auto15MinEnabled", false)) return;
        cancelScheduleTask();
        long now = System.currentTimeMillis();
        long endAt = preferences().getLong("scheduledSessionEndAt", 0L);
        if (endAt > 0L && now >= endAt) {
            preferences().edit().putLong("scheduledSessionEndAt", 0L).apply();
            stopHop("Scheduled 5-minute session ended", true);
            now = System.currentTimeMillis();
            endAt = 0L;
        }
        if (endAt > now) {
            long activeQuarter = IstQuarterSchedule.quarterStart(now);
            long next = now - activeQuarter < IstQuarterSchedule.SESSION_MILLIS
                    ? activeQuarter : IstQuarterSchedule.nextStart(now, false);
            preferences().edit().putLong("scheduledNextStartAt", next).apply();
            scheduleAt(endAt, true);
            refreshOverlay(); return;
        }
        long nextAt = preferences().getLong("scheduledNextStartAt", 0L);
        if (nextAt <= 0L) nextAt = IstQuarterSchedule.nextStart(now, false);
        if (now >= nextAt) {
            if (IstQuarterSchedule.withinStartWindow(nextAt, now)) {
                preferences().edit().putLong("scheduledSessionEndAt", now + IstQuarterSchedule.SESSION_MILLIS)
                        .putLong("scheduledNextStartAt", nextAt + IstQuarterSchedule.QUARTER_MILLIS).apply();
                if (!running) startHop(true);
                updateNotification("Scheduled Auto-Hop session · ends in 5 minutes", null, true);
                scheduleNextQuarterSession(); return;
            }
            nextAt = IstQuarterSchedule.nextStart(now, false);
            preferences().edit().putLong("scheduledNextStartAt", nextAt).apply();
        }
        long target = nextAt;
        scheduleAt(Math.min(target, now + 60000L), false);
        refreshOverlay();
    }

    private void scheduleAt(long targetAt, boolean sessionEnd) {
        cancelScheduleTask();
        scheduledSessionTask = () -> {
            if (!preferences().getBoolean("auto15MinEnabled", false)) return;
            if (sessionEnd) {
                preferences().edit().putLong("scheduledSessionEndAt", 0L).apply();
                stopHop("Scheduled 5-minute session ended", true);
            }
            scheduleNextQuarterSession();
        };
        handler.postDelayed(scheduledSessionTask, Math.max(1000L, targetAt - System.currentTimeMillis()));
    }

    private void cancelScheduleTask() {
        if (scheduledSessionTask != null) { handler.removeCallbacks(scheduledSessionTask); scheduledSessionTask = null; }
    }

    private void startHop() { startHop(false); }

    private void startHop(boolean scheduled) {
        if (running) return;
        if (!scheduled && preferences().getBoolean("auto15MinEnabled", false)) {
            preferences().edit().putBoolean("auto15MinEnabled", false).putLong("scheduledSessionEndAt", 0L)
                    .putLong("scheduledNextStartAt", 0L).apply();
            cancelScheduleTask();
        }
        running = true; paused = false; busy = false; failureCount = 0;
        SharedPreferences prefs = preferences();
        SharedPreferences.Editor edit = prefs.edit().putBoolean("autoHopEnabled", true).putBoolean("autoPaused", false)
                .putString("autoStatus", "Scanning live Roblox servers");
        edit.putLong("sessionStartedAt", System.currentTimeMillis()).putLong("sessionEndedAt", 0L);
        edit.apply();
        resetHistoryAtBoundary();
        showOverlayIfEnabled(); scheduleHistoryBoundaryReset(); broadcastState(null);
        updateNotification("Scanning real public servers", null);
        scanNow();
    }

    private void reconfigure() {
        if (!running) { showOverlayIfEnabled(); refreshOverlay(); return; }
        if (paused) { showOverlayIfEnabled(); refreshOverlay(); return; }
        cancelScheduledScan();
        if (activeScan != null) activeScan.cancel(true);
        serversClient.cancel(); busy = false;
        showOverlayIfEnabled();
        scheduleScan(0L, "Interval or floating control updated");
    }

    private void scanNow() {
        if (!running || paused || busy) return;
        busy = true; nextHopAt = 0L;
        long startedAt = System.currentTimeMillis();
        preferences().edit().putLong("nextHopAt", 0L).putString("autoStatus", "Scanning Roblox public servers").apply();
        broadcastState(null);
        activeScan = executor.submit(() -> {
            String currentCursor = preferences().getString("autoCursor", "");
            try {
                ArrayList<JSONObject> found = new ArrayList<>();
                String cursor = currentCursor;
                String next = cursor;
                for (int pageIndex = 0; pageIndex < AUTO_SCAN_PAGES; pageIndex++) {
                    RobloxServersClient.Page page = serversClient.fetchPage(cursor);
                    found.addAll(page.servers);
                    next = page.nextCursor == null ? "" : page.nextCursor;
                    if (next.isEmpty()) break;
                    cursor = next;
                }
                if (Thread.currentThread().isInterrupted() || !running || paused) return;
                RobloxServersClient.sortByPlayers(found);
                Set<String> used = new HashSet<>();
                if (preferences().getBoolean("skipVisited", true))
                    used.addAll(preferences().getStringSet("visited", Collections.<String>emptySet()));
                used.addAll(preferences().getStringSet("queuedCandidates", Collections.<String>emptySet()));
                used.addAll(preferences().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
                used.addAll(preferences().getStringSet("skippedServerIds", Collections.<String>emptySet()));
                JSONObject selected = null;
                for (JSONObject server : found) {
                    String id = server.optString("jobId", "");
                    if (id.isEmpty() || used.contains(id)) continue;
                    selected = server; break;
                }
                String nextCursor = next.isEmpty() ? "" : next;
                JSONObject lowest = found.isEmpty() ? null : found.get(0);
                preferences().edit().putString("autoCursor", nextCursor)
                        .putInt("autoServersFound", found.size())
                        .putString("lowestServerId", lowest == null ? "" : lowest.optString("jobId", ""))
                        .putInt("lowestServerPlayers", lowest == null ? -1 : lowest.optInt("players", -1)).apply();
                JSONObject candidate = selected;
                handler.post(() -> {
                    if (!running || paused) return;
                    busy = false; failureCount = 0;
                    if (candidate != null) announceCandidate(candidate);
                    else setAutoStatus(found.isEmpty() ? "No eligible public servers · waiting" : "No new server in this scan · checking another page");
                    refreshOverlay();
                    long elapsed = System.currentTimeMillis() - startedAt;
                    scheduleScan(Math.max(0L, intervalMs() - elapsed), candidate == null ? "Waiting for the next scan" : "Candidate ready · Android requires a tap to join");
                });
            } catch (Exception error) {
                if (!running || paused || Thread.currentThread().isInterrupted()) return;
                long retryAfter = error instanceof RobloxServersClient.ServerApiException
                        ? ((RobloxServersClient.ServerApiException)error).retryAfterMs : 0L;
                boolean limited = error instanceof RobloxServersClient.ServerApiException
                        && ((RobloxServersClient.ServerApiException)error).statusCode == 429;
                handler.post(() -> {
                    if (!running || paused) return;
                    busy = false;
                    if (!limited) failureCount++;
                    int maxFailures = Math.max(1, Math.min(10, preferences().getInt("maxJoinFailures", 5)));
                    if (!limited && failureCount >= maxFailures) {
                        setAutoStatus("Paused after " + maxFailures + " failed scans · tap Start to retry");
                        stopHop("Paused after " + maxFailures + " failed scans · tap Start to retry"); return;
                    }
                    String detail = limited ? "Roblox rate limit · respecting retry window" : "Roblox scan failed · retrying";
                    setAutoStatus(detail);
                    long backoff = limited ? Math.max(intervalMs(), retryAfter) : Math.max(intervalMs(), intervalMs() * (1L << Math.min(4, failureCount)));
                    scheduleScan(Math.min(300000L, backoff), detail);
                });
            }
        });
    }

    private void announceCandidate(JSONObject server) {
        String id = server.optString("jobId", "");
        int players = server.optInt("players", -1);
        preferences().edit().putString("activeServer", id).putInt("activePlayers", players)
                .putInt("activeCapacity", server.optInt("maxPlayers", -1))
                .putString("autoStatus", "New low-population server ready")
                .putString("pendingCandidate", id).apply();
        refreshOverlay();
        HashSet<String> queued = new HashSet<>(preferences().getStringSet("queuedCandidates", Collections.<String>emptySet()));
        queued.add(id); preferences().edit().putStringSet("queuedCandidates", queued).apply();
        if (!MainActivity.isVisibleToUser() && overlay != null && Settings.canDrawOverlays(this)) {
            if (launchFromService(id)) {
                HashSet<String> visited = new HashSet<>(preferences().getStringSet("visited", Collections.<String>emptySet()));
                visited.add(id);
                queued.remove(id);
                preferences().edit().putStringSet("visited", visited).putStringSet("queuedCandidates", queued)
                        .putString("historyWindow", historyWindowKey()).putString("pendingCandidate", "")
                        .putInt("launchRequests", preferences().getInt("launchRequests", 0) + 1)
                        .putString("autoStatus", "Roblox launch requested · join not verified").apply();
                HopHistoryStore.record(this, id, players, server.optInt("maxPlayers", -1), "requested", "Android accepted the Roblox app handoff; join not confirmed.");
                updateNotification("Roblox launch requested · join not verified", null);
                broadcastState(null);
                return;
            }
            HashSet<String> failed = new HashSet<>(preferences().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
            failed.add(id); queued.remove(id);
            int failures = preferences().getInt("joinHandoffFailures", 0) + 1;
            int maxFailures = Math.max(1, Math.min(10, preferences().getInt("maxJoinFailures", 5)));
            preferences().edit().putStringSet("failedLaunchIds", failed).putStringSet("queuedCandidates", queued)
                    .putString("pendingCandidate", "").putInt("joinHandoffFailures", failures).apply();
            HopHistoryStore.record(this, id, players, server.optInt("maxPlayers", -1), "failed", "Roblox app handoff was unavailable.");
            updateNotification("Roblox app unavailable · tap to review candidate", id, false);
            if (failures >= maxFailures) {
                stopHop("Paused after " + maxFailures + " failed Roblox launch handoffs");
                return;
            }
            broadcastState(null);
            return;
        }
        updateNotification("Server ready · " + players + " / " + server.optInt("maxPlayers", 0) + " players · tap to open Roblox", id, false);
        broadcastState(id);
    }

    private boolean launchFromService(String serverId) {
        String[] links = {
                "roblox://placeId=" + RobloxServersClient.PLACE_ID + "&gameInstanceId=" + Uri.encode(serverId),
                "roblox://experiences/start?placeId=" + RobloxServersClient.PLACE_ID + "&gameInstanceId=" + Uri.encode(serverId)
        };
        for (String link : links) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                intent.setPackage("com.roblox.client"); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent); return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private void scheduleScan(long delay, String status) {
        if (!running || paused) return;
        cancelScheduledScan();
        long safeDelay = Math.max(0L, delay);
        nextHopAt = System.currentTimeMillis() + safeDelay;
        preferences().edit().putLong("nextHopAt", nextHopAt).putString("autoStatus", status).apply();
        String candidate = preferences().getString("pendingCandidate", "");
        updateNotification(status + (safeDelay > 0 ? " · next scan in " + Math.max(1, safeDelay / 1000) + "s" : ""), candidate.isEmpty() ? null : candidate, true);
        scanTask = this::scanNow; handler.postDelayed(scanTask, safeDelay);
        broadcastState(null);
        refreshOverlay();
    }

    private void setAutoStatus(String status) {
        preferences().edit().putString("autoStatus", status).apply();
        updateNotification(status, null); broadcastState(null); refreshOverlay();
    }

    private long intervalMs() { return HopClock.normalizeInterval(preferences().getInt("intervalSeconds", 10)) * 1000L; }

    private void pauseHop() {
        if (!running || paused) return;
        paused = true; busy = false; nextHopAt = 0L;
        preferences().edit().putBoolean("autoPaused", true).putLong("nextHopAt", 0L).putString("autoStatus", "Auto-Hop paused").apply();
        cancelScheduledScan();
        if (activeScan != null) { activeScan.cancel(true); activeScan = null; }
        serversClient.cancel();
        updateNotification("Auto-Hop paused", preferences().getString("pendingCandidate", ""), true);
        broadcastState(null); refreshOverlay();
    }

    private void resumeHop() {
        if (!running) { startHop(); return; }
        if (!paused) return;
        paused = false;
        preferences().edit().putBoolean("autoPaused", false).putString("autoStatus", "Resuming Auto-Hop").apply();
        showOverlayIfEnabled(); scanNow(); broadcastState(null); refreshOverlay();
    }

    private void skipCurrentCandidate() {
        String id = preferences().getString("pendingCandidate", "");
        if (id.isEmpty()) id = preferences().getString("activeServer", "");
        if (!id.isEmpty()) {
            HashSet<String> skipped = new HashSet<>(preferences().getStringSet("skippedServerIds", Collections.<String>emptySet()));
            skipped.add(id);
            HashSet<String> queued = new HashSet<>(preferences().getStringSet("queuedCandidates", Collections.<String>emptySet()));
            queued.remove(id);
            preferences().edit().putStringSet("skippedServerIds", skipped).putStringSet("queuedCandidates", queued)
                    .putString("pendingCandidate", "").putString("autoStatus", "Skipped candidate · searching again").apply();
            HopHistoryStore.record(this, id, preferences().getInt("activePlayers", -1),
                    preferences().getInt("activeCapacity", -1), "skipped", skipReason);
            skipReason = "Skipped from the floating controls.";
        }
        broadcastState(null); refreshOverlay();
        if (running && !paused) { cancelScheduledScan(); scanNow(); }
        else updateNotification("Candidate skipped", null);
    }

    private void requestRefresh() {
        broadcastAction("com.monsterhopper.REFRESH");
        if (running && !paused) { cancelScheduledScan(); scanNow(); }
        else refreshOverlay();
    }

    private void broadcastAction(String action) {
        Intent state = new Intent(action); state.setPackage(getPackageName()); sendBroadcast(state);
    }

    private void cancelScheduledScan() {
        if (scanTask != null) { handler.removeCallbacks(scanTask); scanTask = null; }
    }

    private void stopHop(String status) { stopHop(status, false); }

    private void stopHop(String status, boolean keepSchedule) {
        running = false; busy = false; nextHopAt = 0L;
        paused = false;
        SharedPreferences.Editor state = preferences().edit().putBoolean("autoHopEnabled", false).putBoolean("autoPaused", false)
                .putLong("nextHopAt", 0L).putLong("sessionEndedAt", System.currentTimeMillis()).putString("autoStatus", status);
        if (!keepSchedule) state.putBoolean("auto15MinEnabled", false).putLong("scheduledSessionEndAt", 0L).putLong("scheduledNextStartAt", 0L);
        state.apply();
        if (!keepSchedule) cancelScheduleTask();
        cancelScheduledScan();
        if (historyResetTask != null) { handler.removeCallbacks(historyResetTask); historyResetTask = null; }
        if (activeScan != null) { activeScan.cancel(true); activeScan = null; }
        serversClient.cancel(); broadcastState(null);
        if (overlayAllowed() || keepSchedule) {
            showOverlayIfEnabled();
            updateNotification(keepSchedule ? "Scheduled Auto-Hop · next session at " + formatScheduledTime()
                    : "Auto-Hop stopped · floating controls ready", null, true);
            if (keepSchedule) scheduleNextQuarterSession();
            refreshOverlay();
        } else {
            removeOverlay(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Monster Hopper Auto-Hop", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Server scan status and join prompts");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String message, String serverId, boolean onlyAlertOnce) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MainActivity.class);
        open.setAction(serverId == null ? "OPEN_APP" : "JOIN_SERVER");
        if (serverId != null) open.putExtra("serverId", serverId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.setData(Uri.parse("monsterhopper://notification/" + (serverId == null ? "status" : Uri.encode(serverId))));
        PendingIntent openPending = PendingIntent.getActivity(this, serverId == null ? 3 : serverId.hashCode(), open, flags);

        Intent stop = new Intent(this, AutoHopService.class); stop.setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification).setContentTitle("Monster Hopper").setContentText(message)
                .setContentIntent(openPending).setOngoing(running).setOnlyAlertOnce(onlyAlertOnce);
        if (serverId != null) builder.addAction(android.R.drawable.ic_media_play, "Open server in Roblox", openPending);
        builder.addAction(android.R.drawable.ic_delete, "Stop", stopPending);
        return builder.build();
    }

    private void updateNotification(String message, String serverId) { updateNotification(message, serverId, true); }
    private void updateNotification(String message, String serverId, boolean onlyAlertOnce) {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message, serverId, onlyAlertOnce));
    }

    private void broadcastState(String serverId) {
        Intent state = new Intent(serverId == null ? "com.monsterhopper.STATE" : "com.monsterhopper.CANDIDATE");
        state.setPackage(getPackageName()); if (serverId != null) state.putExtra("serverId", serverId);
        sendBroadcast(state);
    }

    private void scheduleHistoryBoundaryReset() {
        if (historyResetTask != null) handler.removeCallbacks(historyResetTask);
        historyResetTask = () -> {
            resetHistoryAtBoundary();
            scheduleHistoryBoundaryReset();
        };
        Calendar next = Calendar.getInstance(); next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
        next.set(Calendar.MINUTE, (next.get(Calendar.MINUTE) / 15 + 1) * 15);
        handler.postDelayed(historyResetTask, Math.max(1000L, next.getTimeInMillis() - System.currentTimeMillis()));
    }

    private void resetHistoryAtBoundary() {
        String key = historyWindowKey();
        if (!key.equals(preferences().getString("historyWindow", ""))) {
            preferences().edit().remove("visited").remove("queuedCandidates").remove("failedLaunchIds").remove("skippedServerIds")
                    .putString("pendingCandidate", "").putInt("joinHandoffFailures", 0).putString("historyWindow", key).apply();
        }
    }

    private String historyWindowKey() {
        Calendar now = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY), (now.get(Calendar.MINUTE) / 15) * 15);
    }

    private void showOverlayIfEnabled() {
        if (!overlayAllowed()) { removeOverlay(); return; }
        if (overlay != null) return;
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        LinearLayout window = new LinearLayout(this);
        window.setOrientation(LinearLayout.VERTICAL); window.setGravity(Gravity.END);
        TextView bubble = new TextView(this); bubble.setTextSize(12);
        bubble.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); bubble.setGravity(Gravity.CENTER);
        bubble.setContentDescription("Monster Hopper floating controls. Tap to expand, drag to move.");
        bubble.setPadding(dp(17), dp(12), dp(17), dp(12)); bubble.setElevation(dp(9));
        window.addView(bubble, new LinearLayout.LayoutParams(-2, dp(48)));
        overlayPanel = buildOverlayPanel();
        overlayPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(dp(290), -2);
        panelParams.topMargin = dp(8); window.addView(overlayPanel, panelParams);
        window.setTag(bubble);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = preferences().getInt("overlayX", dp(16));
        params.y = preferences().getInt("overlayY", dp(150));
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int originX, originY; boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); originX = params.x; originY = params.y; dragged = false; return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(7)) dragged = true;
                    int height = getResources().getDisplayMetrics().heightPixels;
                    int reserve = overlayPanel.getVisibility() == View.VISIBLE ? dp(520) : dp(64);
                    params.x = Math.max(0, originX - (int)dx);
                    params.y = Math.max(dp(12), Math.min(Math.max(dp(12), height - reserve), originY + (int)dy));
                    try { windowManager.updateViewLayout(window, params); } catch (Exception ignored) { }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragged) bubble.performClick();
                    preferences().edit().putInt("overlayX", params.x).putInt("overlayY", params.y).apply();
                    return true;
                }
                return true;
            }
        });
        bubble.setOnClickListener(v -> {
            overlayPanel.setVisibility(overlayPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            refreshOverlay();
            try { windowManager.updateViewLayout(window, params); } catch (Exception ignored) { }
        });
        overlay = window;
        try {
            windowManager.addView(window, params);
            handler.removeCallbacks(overlayTicker); handler.post(overlayTicker); refreshOverlay();
        } catch (Exception error) { overlay = null; overlayPanel = null; }
    }

    private LinearLayout buildOverlayPanel() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(13), dp(14), dp(13)); panel.setBackground(overlayPanelBackground()); panel.setElevation(dp(10));
        LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = overlayText("MONSTER HOPPER", 10, Color.rgb(69, 213, 232), true);
        title.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = overlayAction("MINIMIZE", () -> overlayPanel.setVisibility(View.GONE));
        title.addView(close, new LinearLayout.LayoutParams(-2, dp(34))); panel.addView(title);

        overlayStatus = overlayText("Stopped", 15, Color.WHITE, true);
        overlayStatus.setPadding(0, dp(9), 0, dp(4)); panel.addView(overlayStatus);
        overlayCurrent = overlayText("No server selected", 12, Color.LTGRAY, false); panel.addView(overlayCurrent);
        overlayCountdown = overlayText("No countdown", 12, Color.LTGRAY, false); panel.addView(overlayCountdown);
        overlayServers = overlayText("Servers found: 0", 11, Color.LTGRAY, false); panel.addView(overlayServers);
        overlayVisited = overlayText("Visited: 0", 11, Color.LTGRAY, false); panel.addView(overlayVisited);

        LinearLayout primary = new LinearLayout(this); primary.setOrientation(LinearLayout.HORIZONTAL); primary.setPadding(0, dp(10), 0, 0);
        primary.addView(overlayAction("START / STOP", () -> { if (running) stopHop("Auto-Hop stopped"); else startHop(); }), new LinearLayout.LayoutParams(0, dp(42), 1));
        primary.addView(overlayAction("PAUSE / RESUME", () -> { if (paused) resumeHop(); else pauseHop(); }), withStartMargin(1, dp(42)));
        panel.addView(primary);

        LinearLayout secondary = new LinearLayout(this); secondary.setOrientation(LinearLayout.HORIZONTAL); secondary.setPadding(0, dp(6), 0, 0);
        secondary.addView(overlayAction("SKIP SERVER", this::skipCurrentCandidate), new LinearLayout.LayoutParams(0, dp(38), 1));
        secondary.addView(overlayAction("REFRESH", this::requestRefresh), withStartMargin(1, dp(38)));
        panel.addView(secondary);

        CheckBox hide = new CheckBox(this); hide.setText("Hide visited servers"); hide.setTextSize(11);
        hide.setTextColor(Color.WHITE); hide.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(69, 213, 232)));
        hide.setChecked(preferences().getBoolean("hideVisited", true));
        hide.setOnCheckedChangeListener((button, checked) -> {
            preferences().edit().putBoolean("hideVisited", checked).apply();
            broadcastAction("com.monsterhopper.STATE");
        });
        panel.addView(hide);

        TextView intervalLabel = overlayText("HOP INTERVAL", 9, Color.rgb(69, 213, 232), true);
        intervalLabel.setPadding(0, dp(2), 0, dp(4)); panel.addView(intervalLabel);
        LinearLayout intervals = new LinearLayout(this); intervals.setOrientation(LinearLayout.HORIZONTAL);
        for (int seconds : INTERVAL_OPTIONS) {
            TextView item = overlayAction(seconds + "s", () -> {
                preferences().edit().putInt("intervalSeconds", seconds).apply();
                reconfigure(); refreshOverlay();
            });
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(34), 1);
            itemParams.setMargins(dp(1), 0, dp(1), 0); intervals.addView(item, itemParams);
        }
        panel.addView(intervals);

        LinearLayout links = new LinearLayout(this); links.setOrientation(LinearLayout.HORIZONTAL); links.setPadding(0, dp(6), 0, 0);
        links.addView(overlayAction("SETTINGS", () -> openMainActivity(true)), new LinearLayout.LayoutParams(0, dp(38), 1));
        links.addView(overlayAction("OPEN APP", () -> openMainActivity(false)), withStartMargin(1, dp(38)));
        panel.addView(links);
        return panel;
    }

    private LinearLayout.LayoutParams withStartMargin(float weight, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, weight);
        params.leftMargin = dp(6); return params;
    }

    private TextView overlayText(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false); return view;
    }

    private TextView overlayAction(String label, Runnable action) {
        TextView button = overlayText(label, 9, Color.WHITE, true); button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), dp(4), dp(5), dp(4));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(30, 45, 63)); background.setCornerRadius(dp(9));
        background.setStroke(dp(1), Color.rgb(50, 72, 92)); button.setBackground(background); button.setClickable(true);
        button.setOnClickListener(v -> { v.animate().alpha(.65f).setDuration(70).withEndAction(() -> v.animate().alpha(1f).setDuration(100).start()).start(); action.run(); });
        return button;
    }

    private void refreshOverlay() {
        if (!(overlay instanceof LinearLayout)) return;
        TextView bubble = (TextView)((LinearLayout)overlay).getTag();
        if (bubble != null) {
            bubble.setText(paused ? "HOP  ·  PAUSED" : running ? "HOP  ·  ON" : "HOP  ·  OFF");
            bubble.setTextColor(running && !paused ? Color.rgb(6, 29, 37) : Color.WHITE);
            bubble.setBackground(overlayBackground(running && !paused ? Color.rgb(69, 213, 232) : paused ? Color.rgb(244, 188, 91) : Color.rgb(31, 48, 67)));
        }
        if (overlayStatus == null) return;
        SharedPreferences prefs = preferences();
        overlayStatus.setText(paused ? "PAUSED" : running ? "AUTO-HOP RUNNING" : "AUTO-HOP STOPPED");
        overlayCurrent.setText(prefs.getString("activeServer", "").isEmpty() ? "No server selected" :
                "Selected · " + prefs.getInt("activePlayers", -1) + " / " + prefs.getInt("activeCapacity", -1) + " players · join not confirmed");
        long at = prefs.getLong("nextHopAt", 0L);
        overlayCountdown.setText(running && !paused ? (at > 0 ? "Next scan · " + HopClock.countdown(System.currentTimeMillis(), at) : "Scanning live servers…") : "Next scan · paused");
        overlayServers.setText("Servers found · " + prefs.getInt("autoServersFound", 0) + "   Lowest · " +
                (prefs.getInt("lowestServerPlayers", -1) < 0 ? "—" : prefs.getInt("lowestServerPlayers", -1) + " players"));
        overlayVisited.setText("Visited this window · " + prefs.getStringSet("visited", Collections.<String>emptySet()).size()
                + "   Interval · " + intervalMs() / 1000 + "s");
    }

    private void openMainActivity(boolean settings) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (settings) intent.putExtra("openSettings", true);
        try { startActivity(intent); } catch (Exception ignored) { }
    }

    private boolean overlayAllowed() {
        return preferences().getBoolean("floatingEnabled", false) && Settings.canDrawOverlays(this);
    }

    private String formatScheduledTime() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm 'IST'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        return format.format(new java.util.Date(preferences().getLong("scheduledNextStartAt", 0L)));
    }

    private GradientDrawable overlayBackground(int color) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), Color.rgb(171, 243, 249)); return bg;
    }

    private GradientDrawable overlayPanelBackground() {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(12, 19, 31)); bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(47, 72, 93)); return bg;
    }

    private void removeOverlay() {
        handler.removeCallbacks(overlayTicker);
        if (overlay != null && windowManager != null) try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        overlay = null; overlayPanel = null; overlayStatus = null; overlayCurrent = null;
        overlayCountdown = null; overlayServers = null; overlayVisited = null;
    }

    private SharedPreferences preferences() { return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onTaskRemoved(Intent rootIntent) { /* The user-visible foreground service stays active by design. */ super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        boolean wasRunning = running;
        running = false; busy = false;
        if (wasRunning) preferences().edit().putBoolean("autoHopEnabled", false).putLong("nextHopAt", 0L)
            .putLong("sessionEndedAt", System.currentTimeMillis()).putString("autoStatus", "Auto-Hop stopped by Android").apply();
        handler.removeCallbacksAndMessages(null);
        cancelScheduleTask();
        if (activeScan != null) activeScan.cancel(true);
        serversClient.cancel(); removeOverlay(); executor.shutdownNow(); broadcastState(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
