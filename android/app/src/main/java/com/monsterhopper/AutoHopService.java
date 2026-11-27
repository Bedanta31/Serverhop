
package com.monsterhopper;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class AutoHopService extends Service {

    private static final String CHANNEL = "monster_hopper_auto";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PLACE_ID = "98664161516921";
    private static final String PREFS = "hopper";
    private static final int DAILY_LIMIT = 50;
    private static final long RESET_WINDOW = 15 * 60 * 1000L;

    private Handler handler;
    private Runnable task;
    private volatile boolean running;
    private volatile boolean busy;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent == null ? "" : intent.getAction();

        if ("STOP".equals(action)) {
            stopHop();
            return START_NOT_STICKY;
        }

        if ("PAUSE".equals(action)) {
            pauseHop();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Starting Auto-Hop"));

        if (!running) {
            running = true;
            attemptHop();
        }

        return START_NOT_STICKY;
    }

    private long getInterval() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong("interval", 15000L);
    }

    private void attemptHop() {

        if (!running || busy) return;

        if (getAttemptsToday() >= DAILY_LIMIT) {
            updateNotification("Daily limit reached");
            stopHop();
            return;
        }

        busy = true;

        new Thread(() -> {

            String chosen = null;
            int players = Integer.MAX_VALUE;

            try {

                clearExpiredHistory();

                String url =
                    "https://games.roblox.com/v1/games/" +
                    PLACE_ID +
                    "/servers/Public?sortOrder=Asc&limit=100";

                HttpURLConnection c =
                    (HttpURLConnection) new URL(url).openConnection();

                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");

                int code = c.getResponseCode();

                if (code != 200) {
                    throw new IOException("Roblox API: HTTP " + code);
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream()));

                StringBuilder body = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }

                reader.close();
                c.disconnect();

                JSONArray servers =
                    new JSONObject(body.toString()).getJSONArray("data");

                for (int i = 0; i < servers.length(); i++) {

                    JSONObject s = servers.getJSONObject(i);

                    String id = s.optString("id", "");
                    int playing = s.optInt("playing", 0);
                    int maximum = s.optInt("maxPlayers", 0);

                    if (id.isEmpty() || maximum <= 0 ||
                            playing >= maximum) continue;

                    if (wasVisited(id)) continue;

                    if (playing < players) {
                        players = playing;
                        chosen = id;
                    }
                }

            } catch (Exception e) {

                busy = false;

                handler.post(() -> {
                    updateNotification("Error: " + e.getMessage());
                    scheduleNext();
                });

                return;
            }

            String selected = chosen;
            int playerCount = players;

            handler.post(() -> {

                busy = false;

                if (!running) return;

                if (selected == null) {
                    updateNotification("No new servers. Waiting...");
                    scheduleNext();
                    return;
                }

                launchServer(selected, playerCount);
            });

        }).start();
    }

    private void launchServer(String id, int playerCount) {

        if (!running) return;

        try {

            Intent join = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "roblox://experiences/start?placeId=" +
                    PLACE_ID +
                    "&gameInstanceId=" + id
                )
            );

            join.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(join);

            // Track only after the launch intent was issued.
            markVisited(id);
            incrementAttempts();

            updateNotification(
                "Opened server · " + playerCount + " players"
            );

        } catch (Exception e) {

            updateNotification("Couldn't open Roblox");
        }

        scheduleNext();
    }

    private void scheduleNext() {

        if (!running) return;

        if (task != null) handler.removeCallbacks(task);

        long interval = Math.max(5000L, getInterval());

        task = this::attemptHop;

        handler.postDelayed(task, interval);
    }

    // 15-minute history expiry

    private void clearExpiredHistory() {

        SharedPreferences p =
            getSharedPreferences(PREFS, MODE_PRIVATE);

        long resetAt = p.getLong("resetAt", 0);

        if (resetAt == 0 || System.currentTimeMillis() >= resetAt) {

            p.edit()
                .remove("visited")
                .putLong("resetAt", 0)
                .apply();
        }
    }

    private boolean wasVisited(String id) {

        clearExpiredHistory();

        Set<String> visited =
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet("visited", Collections.emptySet());

        return visited.contains(id);
    }

    private void markVisited(String id) {

        SharedPreferences p =
            getSharedPreferences(PREFS, MODE_PRIVATE);

        Set<String> visited = new HashSet<>(
            p.getStringSet("visited", Collections.emptySet())
        );

        visited.add(id);

        long resetAt = p.getLong("resetAt", 0);

        if (resetAt == 0) {
            resetAt = System.currentTimeMillis() + RESET_WINDOW;
        }

        p.edit()
            .putStringSet("visited", visited)
            .putLong("resetAt", resetAt)
            .apply();
    }

    // Daily counter

    private int getAttemptsToday() {

        SharedPreferences p =
            getSharedPreferences(PREFS, MODE_PRIVATE);

        String today = new SimpleDateFormat(
            "yyyy-MM-dd", Locale.US
        ).format(new Date());

        if (!today.equals(p.getString("day", ""))) {

            p.edit()
                .putString("day", today)
                .putInt("count", 0)
                .apply();
        }

        return p.getInt("count", 0);
    }

    private void incrementAttempts() {

        SharedPreferences p =
            getSharedPreferences(PREFS, MODE_PRIVATE);

        getAttemptsToday();

        p.edit()
            .putInt("count", p.getInt("count", 0) + 1)
            .apply();
    }

    // Controls

    private void pauseHop() {

        running = false;

        if (task != null) {
            handler.removeCallbacks(task);
            task = null;
        }

        updateNotification("Auto-Hop paused");
    }

    private void stopHop() {

        running = false;
        busy = false;

        if (handler != null && task != null) {
            handler.removeCallbacks(task);
            task = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "Monster Hopper",
                NotificationManager.IMPORTANCE_LOW
            );

            getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
        }
    }

    private Notification notification(String message) {

        Intent stop = new Intent(this, AutoHopService.class);
        stop.setAction("STOP");

        PendingIntent stopPending = PendingIntent.getService(
            this,
            1,
            stop,
            PendingIntent.FLAG_UPDATE_CURRENT |
            PendingIntent.FLAG_IMMUTABLE
        );

        Intent pause = new Intent(this, AutoHopService.class);
        pause.setAction("PAUSE");

        PendingIntent pausePending = PendingIntent.getService(
            this,
            2,
            pause,
            PendingIntent.FLAG_UPDATE_CURRENT |
            PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b =
            Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return b.setContentTitle("Monster Hopper")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(running)
            .addAction(android.R.drawable.ic_media_pause,
                "Pause", pausePending)
            .addAction(android.R.drawable.ic_delete,
                "Stop", stopPending)
            .build();
    }

    private void updateNotification(String text) {

        NotificationManager nm =
            (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);

        nm.notify(NOTIFICATION_ID, notification(text));
    }

    @Override
    public void onDestroy() {

        running = false;

        if (handler != null && task != null) {
            handler.removeCallbacks(task);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
