
package com.monsterhopper;

import android.app.*;
import android.content.*;
import android.os.*;
import android.net.Uri;
import java.net.*;
import java.io.*;
import org.json.*;
import java.util.*;

public class AutoHopService extends Service {

    private static final String CHANNEL = "monster_hopper_auto";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PLACE_ID = "98664161516921";
    private static final int DAILY_LIMIT = 50;
    private static final long INTERVAL = 15000L;

    private Handler handler;
    private Runnable task;
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && "STOP".equals(intent.getAction())) {
            stopHop();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Auto-Hop running"));

        if (!running) {
            running = true;
            attemptHop();
        }

        return START_NOT_STICKY;
    }

    private void attemptHop() {
        if (!running) return;

        if (getAttemptsToday() >= DAILY_LIMIT) {
            updateNotification("Daily limit reached (50)");
            stopHop();
            return;
        }

        new Thread(() -> {
            try {
                String url = "https://games.roblox.com/v1/games/" +
                        PLACE_ID +
                        "/servers/Public?sortOrder=Asc&limit=100";

                HttpURLConnection c = (HttpURLConnection)
                        new URL(url).openConnection();

                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");

                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream()));

                StringBuilder s = new StringBuilder();
                String line;

                while ((line = r.readLine()) != null) {
                    s.append(line);
                }

                r.close();
                c.disconnect();

                JSONArray servers = new JSONObject(s.toString())
                        .getJSONArray("data");

                String chosen = null;
                int lowest = Integer.MAX_VALUE;

                for (int i = 0; i < servers.length(); i++) {
                    JSONObject server = servers.getJSONObject(i);

                    String id = server.optString("id");
                    int players = server.optInt("playing");
                    int maximum = server.optInt("maxPlayers");

                    if (id.isEmpty() || players >= maximum) continue;
                    if (wasAttempted(id)) continue;

                    if (players < lowest) {
                        lowest = players;
                        chosen = id;
                    }
                }

                if (chosen == null) {
                    updateNotification("No new available server found");
                    scheduleNext();
                    return;
                }

                String finalId = chosen;
                markAttempted(finalId);
                incrementAttempts();

                handler.post(() -> {
                    updateNotification("Joining server (" + lowest + " players)");

                    try {
                        Intent join = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("roblox://experiences/start?placeId=" +
                                        PLACE_ID +
                                        "&gameInstanceId=" + finalId));

                        join.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(join);

                    } catch (Exception e) {
                        updateNotification("Unable to open Roblox");
                    }

                    scheduleNext();
                });

            } catch (Exception e) {
                updateNotification("Network error; retrying");
                scheduleNext();
            }
        }).start();
    }

    private void scheduleNext() {
        if (!running) return;

        if (task != null) handler.removeCallbacks(task);

        task = this::attemptHop;
        handler.postDelayed(task, INTERVAL);
    }

    private boolean wasAttempted(String id) {
        return getSharedPreferences("hopper", MODE_PRIVATE)
                .getStringSet("attempted", new HashSet<>())
                .contains(id);
    }

    private void markAttempted(String id) {
        SharedPreferences p = getSharedPreferences("hopper", MODE_PRIVATE);
        Set<String> old = new HashSet<>(
                p.getStringSet("attempted", new HashSet<>()));

        old.add(id);
        p.edit().putStringSet("attempted", old).apply();
    }

    private int getAttemptsToday() {
        SharedPreferences p = getSharedPreferences("hopper", MODE_PRIVATE);

        String today = new java.text.SimpleDateFormat(
                "yyyy-MM-dd", Locale.US)
                .format(new Date());

        if (!today.equals(p.getString("day", ""))) {
            p.edit().putString("day", today)
                    .putInt("count", 0)
                    .apply();
        }

        return p.getInt("count", 0);
    }

    private void incrementAttempts() {
        SharedPreferences p = getSharedPreferences("hopper", MODE_PRIVATE);
        getAttemptsToday();
        p.edit().putInt("count", p.getInt("count", 0) + 1).apply();
    }

    private void stopHop() {
        running = false;

        if (handler != null && task != null)
            handler.removeCallbacks(task);

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "Monster Hopper Auto-Hop",
                    NotificationManager.IMPORTANCE_LOW);

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent stop = new Intent(this, AutoHopService.class);
        stop.setAction("STOP");

        PendingIntent pendingStop = PendingIntent.getService(
                this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return b.setContentTitle("Monster Hopper")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause,
                        "STOP", pendingStop)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nm.notify(NOTIFICATION_ID, notification(text));
    }

    @Override
    public void onDestroy() {
        stopHop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
