
package com.monsterhopper;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {

    private static final String PLACE_ID = "98664161516921";
    private static final String PREFS = "hopper";

    private ExecutorService executor =
        Executors.newSingleThreadExecutor();

    private LinearLayout list;
    private TextView status, stats;
    private EditText intervalInput;

    private String cursor = "";
    private boolean loading = false;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUI();
        fetchServers(false);
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density);
    }

    private void buildUI() {

        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(8, 10, 20));

        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MONSTER HOPPER");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(180, 130, 255));
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("CATCH A MONSTER · SERVER BROWSER");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setPadding(0, dp(5), 0, dp(15));
        root.addView(subtitle);

        status = new TextView(this);
        status.setText("Ready");
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        root.addView(status);

        stats = new TextView(this);
        stats.setTextColor(Color.rgb(170, 180, 220));
        stats.setPadding(0, dp(8), 0, dp(15));
        root.addView(stats);

        TextView settingLabel = new TextView(this);
        settingLabel.setText("HOP INTERVAL (SECONDS)");
        settingLabel.setTextColor(Color.WHITE);
        root.addView(settingLabel);

        intervalInput = new EditText(this);
        intervalInput.setSingleLine(true);
        intervalInput.setInputType(2);
        intervalInput.setTextColor(Color.WHITE);
        intervalInput.setHintTextColor(Color.GRAY);
        intervalInput.setHint("15");
        intervalInput.setText(String.valueOf(
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong("interval", 15000L) / 1000L
        ));

        root.addView(intervalInput);

        Button save = button("SAVE INTERVAL");
        save.setOnClickListener(v -> saveInterval());
        root.addView(save);

        Button start = button("▶ START / RESUME AUTO-HOP");
        start.setOnClickListener(v -> startHop());
        root.addView(start);

        Button pause = button("Ⅱ PAUSE AUTO-HOP");
        pause.setOnClickListener(v -> serviceAction("PAUSE"));
        root.addView(pause);

        Button stop = button("■ STOP AUTO-HOP");
        stop.setOnClickListener(v -> serviceAction("STOP"));
        root.addView(stop);

        Button clear = button("CLEAR VISITED SERVERS");
        clear.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove("visited")
                .putLong("resetAt", 0)
                .apply();

            status.setText("Visited server history cleared");
        });
        root.addView(clear);

        Button refresh = button("↻ REFRESH SERVERS");
        refresh.setOnClickListener(v -> fetchServers(false));
        root.addView(refresh);

        Button more = button("LOAD MORE");
        more.setOnClickListener(v -> fetchServers(true));
        root.addView(more);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    private Button button(String text) {

        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                Color.rgb(76, 43, 145)
            )
        );

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(-1, -2);

        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);

        return b;
    }

    private void saveInterval() {

        try {

            int seconds = Integer.parseInt(
                intervalInput.getText().toString().trim()
            );

            seconds = Math.max(5, Math.min(300, seconds));

            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putLong("interval", seconds * 1000L)
                .apply();

            intervalInput.setText(String.valueOf(seconds));
            status.setText("Interval saved: " + seconds + " seconds");

        } catch (Exception e) {
            status.setText("Enter a valid interval");
        }
    }

    private void startHop() {

        saveInterval();

        Intent i = new Intent(this, AutoHopService.class);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }

        status.setText("Auto-Hop started");
    }

    private void serviceAction(String action) {

        Intent i = new Intent(this, AutoHopService.class);
        i.setAction(action);

        if ("STOP".equals(action)) {
            stopService(new Intent(this, AutoHopService.class));
            status.setText("Auto-Hop stopped");
        } else {
            startService(i);
            status.setText("Auto-Hop paused");
        }
    }

    private void fetchServers(boolean more) {

        if (loading) return;

        loading = true;
        status.setText("Fetching Roblox servers...");

        if (!more) {
            cursor = "";
            list.removeAllViews();
        }

        executor.execute(() -> {

            HttpURLConnection c = null;

            try {

                String url =
                    "https://games.roblox.com/v1/games/" +
                    PLACE_ID +
                    "/servers/Public?sortOrder=Asc&limit=100";

                if (more && !cursor.isEmpty()) {
                    url += "&cursor=" +
                        URLEncoder.encode(cursor, "UTF-8");
                }

                c = (HttpURLConnection)
                    new URL(url).openConnection();

                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");

                int code = c.getResponseCode();

                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream())
                );

                StringBuilder data = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    data.append(line);
                }

                reader.close();

                JSONObject json = new JSONObject(data.toString());
                JSONArray servers = json.getJSONArray("data");

                cursor = json.optString("nextPageCursor", "");

                runOnUiThread(() -> {

                    int available = 0;
                    int lowest = 9999;

                    for (int n = 0; n < servers.length(); n++) {

                        JSONObject obj = servers.optJSONObject(n);
                        if (obj == null) continue;

                        String id = obj.optString("id");
                        int players = obj.optInt("playing");
                        int max = obj.optInt("maxPlayers");

                        if (id.isEmpty() || max <= 0 ||
                                players >= max) continue;

                        available++;
                        lowest = Math.min(lowest, players);

                        addServerCard(id, players, max);
                    }

                    stats.setText(
                        "Servers found: " + available +
                        " · Lowest players: " +
                        (lowest == 9999 ? "—" : lowest)
                    );

                    status.setText(
                        cursor.isEmpty()
                            ? "Server list loaded"
                            : "More servers available"
                    );

                    loading = false;
                });

            } catch (Exception e) {

                runOnUiThread(() -> {
                    status.setText("Server error: " + e.getMessage());
                    loading = false;
                });

            } finally {

                if (c != null) c.disconnect();
            }
        });
    }

    private void addServerCard(String id, int players, int max) {

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundColor(Color.rgb(24, 25, 42));

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(-1, -2);

        p.setMargins(0, dp(8), 0, dp(8));
        card.setLayoutParams(p);

        TextView info = new TextView(this);
        info.setText(
            "👥 " + players + " / " + max +
            " players\n\nServer ID:\n" + id
        );

        info.setTextColor(Color.WHITE);
        info.setTextSize(14);

        card.addView(info);

        Button join = button("JOIN SERVER");

        join.setOnClickListener(v -> joinServer(id));

        card.addView(join);
        list.addView(card);
    }

    private void joinServer(String id) {

        try {

            Intent i = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "roblox://experiences/start?placeId=" +
                    PLACE_ID +
                    "&gameInstanceId=" + id
                )
            );

            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);

            status.setText("Opening Roblox...");

        } catch (Exception e) {
            status.setText("Could not open Roblox");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
