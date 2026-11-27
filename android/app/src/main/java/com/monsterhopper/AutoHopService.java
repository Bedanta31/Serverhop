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
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Collections;
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
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RobloxServersClient serversClient = new RobloxServersClient();
    private Runnable scanTask;
    private Runnable historyResetTask;
    private Future<?> activeScan;
    private WindowManager windowManager;
    private View overlay;
    private int failureCount;
    private long nextHopAt;
    private boolean busy;

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if ("STOP".equals(action)) { stopHop("Auto-Hop stopped"); return START_NOT_STICKY; }
        if ("RECONFIGURE".equals(action)) { if (running) reconfigure(); else stopSelf(); return START_NOT_STICKY; }
        if ("TOGGLE".equals(action)) { if (running) stopHop("Auto-Hop stopped"); else startHop(); return START_STICKY; }
        if (intent == null && !preferences().getBoolean("autoHopEnabled", false)) { stopSelf(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, notification("Preparing a live Roblox server scan", null, true));
        if (!running) startHop();
        return START_STICKY;
    }

    private void startHop() {
        if (running) return;
        running = true; busy = false; failureCount = 0;
        preferences().edit().putBoolean("autoHopEnabled", true).putString("autoStatus", "Scanning live Roblox servers").apply();
        resetHistoryAtBoundary();
        showOverlayIfEnabled(); scheduleHistoryBoundaryReset(); broadcastState(null);
        updateNotification("Scanning real public servers", null);
        scanNow();
    }

    private void reconfigure() {
        if (!running) { showOverlayIfEnabled(); return; }
        cancelScheduledScan();
        if (activeScan != null) activeScan.cancel(true);
        serversClient.cancel(); busy = false;
        showOverlayIfEnabled();
        scheduleScan(0L, "Interval or floating control updated");
    }

    private void scanNow() {
        if (!running || busy) return;
        busy = true; nextHopAt = 0L;
        long startedAt = System.currentTimeMillis();
        preferences().edit().putLong("nextHopAt", 0L).putString("autoStatus", "Scanning Roblox public servers").apply();
        broadcastState(null);
        activeScan = executor.submit(() -> {
            String currentCursor = preferences().getString("autoCursor", "");
            try {
                RobloxServersClient.Page page = serversClient.fetchPage(currentCursor);
                if (Thread.currentThread().isInterrupted() || !running) return;
                Set<String> used = new HashSet<>(preferences().getStringSet("visited", Collections.<String>emptySet()));
                used.addAll(preferences().getStringSet("queuedCandidates", Collections.<String>emptySet()));
                used.addAll(preferences().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
                JSONObject selected = null;
                for (JSONObject server : page.servers) {
                    String id = server.optString("jobId", "");
                    if (id.isEmpty() || used.contains(id)) continue;
                    selected = server; break;
                }
                String next = page.nextCursor == null ? "" : page.nextCursor;
                if (next.isEmpty()) next = "";
                preferences().edit().putString("autoCursor", next).apply();
                JSONObject candidate = selected;
                handler.post(() -> {
                    if (!running) return;
                    busy = false; failureCount = 0;
                    if (candidate != null) announceCandidate(candidate);
                    else setAutoStatus(page.servers.isEmpty() ? "No eligible public servers · waiting" : "No new server on this page · scanning next page");
                    long elapsed = System.currentTimeMillis() - startedAt;
                    scheduleScan(Math.max(0L, intervalMs() - elapsed), candidate == null ? "Waiting for the next scan" : "Candidate ready · Android requires a tap to join");
                });
            } catch (Exception error) {
                if (!running || Thread.currentThread().isInterrupted()) return;
                long retryAfter = error instanceof RobloxServersClient.ServerApiException
                        ? ((RobloxServersClient.ServerApiException)error).retryAfterMs : 0L;
                boolean limited = error instanceof RobloxServersClient.ServerApiException
                        && ((RobloxServersClient.ServerApiException)error).statusCode == 429;
                handler.post(() -> {
                    if (!running) return;
                    busy = false;
                    if (!limited) failureCount++;
                    if (!limited && failureCount >= MAX_CONSECUTIVE_FAILURES) {
                        setAutoStatus("Paused after " + MAX_CONSECUTIVE_FAILURES + " failed scans · tap Start to retry");
                        stopHop("Paused after " + MAX_CONSECUTIVE_FAILURES + " failed scans · tap Start to retry"); return;
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
                .putString("autoStatus", "New low-population server ready")
                .putString("pendingCandidate", id).apply();
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
                updateNotification("Roblox launch requested · join not verified", null);
                broadcastState(null);
                return;
            }
            HashSet<String> failed = new HashSet<>(preferences().getStringSet("failedLaunchIds", Collections.<String>emptySet()));
            failed.add(id); queued.remove(id);
            int failures = preferences().getInt("joinHandoffFailures", 0) + 1;
            preferences().edit().putStringSet("failedLaunchIds", failed).putStringSet("queuedCandidates", queued)
                    .putString("pendingCandidate", "").putInt("joinHandoffFailures", failures).apply();
            updateNotification("Roblox app unavailable · tap to review candidate", id, false);
            if (failures >= 3) {
                stopHop("Paused after 3 failed Roblox launch handoffs");
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
        if (!running) return;
        cancelScheduledScan();
        long safeDelay = Math.max(0L, delay);
        nextHopAt = System.currentTimeMillis() + safeDelay;
        preferences().edit().putLong("nextHopAt", nextHopAt).putString("autoStatus", status).apply();
        String candidate = preferences().getString("pendingCandidate", "");
        updateNotification(status + (safeDelay > 0 ? " · next scan in " + Math.max(1, safeDelay / 1000) + "s" : ""), candidate.isEmpty() ? null : candidate, true);
        scanTask = this::scanNow; handler.postDelayed(scanTask, safeDelay);
        broadcastState(null);
    }

    private void setAutoStatus(String status) {
        preferences().edit().putString("autoStatus", status).apply();
        updateNotification(status, null); broadcastState(null);
    }

    private long intervalMs() { return Math.max(5, Math.min(60, preferences().getInt("intervalSeconds", 10))) * 1000L; }

    private void cancelScheduledScan() {
        if (scanTask != null) { handler.removeCallbacks(scanTask); scanTask = null; }
    }

    private void stopHop(String status) {
        running = false; busy = false; nextHopAt = 0L;
        preferences().edit().putBoolean("autoHopEnabled", false).putLong("nextHopAt", 0L).putString("autoStatus", status).apply();
        cancelScheduledScan();
        if (historyResetTask != null) { handler.removeCallbacks(historyResetTask); historyResetTask = null; }
        if (activeScan != null) { activeScan.cancel(true); activeScan = null; }
        serversClient.cancel(); removeOverlay(); broadcastState(null);
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
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
            preferences().edit().remove("visited").remove("queuedCandidates").remove("failedLaunchIds")
                    .putString("pendingCandidate", "").putInt("joinHandoffFailures", 0).putString("historyWindow", key).apply();
        }
    }

    private String historyWindowKey() {
        Calendar now = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY), (now.get(Calendar.MINUTE) / 15) * 15);
    }

    private void showOverlayIfEnabled() {
        if (!preferences().getBoolean("floatingEnabled", false) || !Settings.canDrawOverlays(this)) { removeOverlay(); return; }
        if (overlay != null) return;
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        TextView bubble = new TextView(this); bubble.setText("HOP  ·  ON"); bubble.setTextColor(Color.rgb(6, 29, 37));
        bubble.setTextSize(12); bubble.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); bubble.setGravity(Gravity.CENTER);
        bubble.setContentDescription("Draggable Auto-Hop stop control");
        bubble.setPadding(dp(17), dp(12), dp(17), dp(12)); bubble.setBackground(overlayBackground()); bubble.setElevation(dp(9));
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END; params.x = dp(16); params.y = dp(180);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int originX, originY; boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); originX = params.x; originY = params.y; dragged = false; return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(7)) dragged = true;
                    int width = getResources().getDisplayMetrics().widthPixels;
                    int height = getResources().getDisplayMetrics().heightPixels;
                    params.x = Math.min(Math.max(0, width - dp(88)), Math.max(0, originX - (int)dx));
                    params.y = Math.min(Math.max(0, height - dp(70)), Math.max(0, originY + (int)dy));
                    try { windowManager.updateViewLayout(bubble, params); } catch (Exception ignored) { }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP && !dragged) { if (running) stopHop("Auto-Hop stopped"); else startHop(); return true; }
                return true;
            }
        });
        overlay = bubble;
        try { windowManager.addView(overlay, params); } catch (Exception error) { overlay = null; }
    }

    private GradientDrawable overlayBackground() {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(69, 213, 232)); bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), Color.rgb(171, 243, 249)); return bg;
    }

    private void removeOverlay() {
        if (overlay == null || windowManager == null) return;
        try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        overlay = null;
    }

    private SharedPreferences preferences() { return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onTaskRemoved(Intent rootIntent) { /* The user-visible foreground service stays active by design. */ super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        boolean wasRunning = running;
        running = false; busy = false;
        if (wasRunning) preferences().edit().putBoolean("autoHopEnabled", false).putLong("nextHopAt", 0L)
                .putString("autoStatus", "Auto-Hop stopped by Android").apply();
        handler.removeCallbacksAndMessages(null);
        if (activeScan != null) activeScan.cancel(true);
        serversClient.cancel(); removeOverlay(); executor.shutdownNow(); broadcastState(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
