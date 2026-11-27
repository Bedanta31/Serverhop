
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
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private String cursor = "";
    private boolean loading = false;
    private final String placeId = "98664161516921";

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUI();
        fetchServers(false);
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.rgb(10, 16, 32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MONSTER HOPPER");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(100, 180, 255));
        title.setPadding(0, 10, 0, 15);
        root.addView(title);

        status = new TextView(this);
        status.setText("Ready");
        status.setTextColor(Color.WHITE);
        root.addView(status);

        Button refresh = button("Refresh servers");
        refresh.setOnClickListener(v -> fetchServers(false));
        root.addView(refresh);

        Button next = button("Load more servers");
        next.setOnClickListener(v -> fetchServers(true));
        root.addView(next);

        Button auto = button("START AUTO-HOP");
        auto.setOnClickListener(v -> {
            Intent i = new Intent(this, AutoHopService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            status.setText("Auto-Hop started");
        });
        root.addView(auto);

        Button stop = button("STOP AUTO-HOP");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, AutoHopService.class);
            i.setAction("STOP");
            startService(i);
            stopService(i);
            status.setText("Auto-Hop stopped");
        });
        root.addView(stop);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30, 75, 145)));
        return b;
    }

    private void fetchServers(boolean more) {
        if (loading) return;
        loading = true;
        status.setText("Fetching servers...");
        if (!more) {
            cursor = "";
            list.removeAllViews();
        }

        executor.execute(() -> {
            try {
                String url = "https://games.roblox.com/v1/games/" + placeId +
                        "/servers/Public?sortOrder=Asc&limit=100";
                if (more && !cursor.isEmpty())
                    url += "&cursor=" + URLEncoder.encode(cursor, "UTF-8");

                HttpURLConnection c = (HttpURLConnection)
                        new URL(url).openConnection();
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);

                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream()));
                StringBuilder s = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) s.append(line);
                r.close();
                c.disconnect();

                JSONObject data = new JSONObject(s.toString());
                JSONArray servers = data.getJSONArray("data");
                cursor = data.optString("nextPageCursor", "");

                runOnUiThread(() -> {
                    for (int n = 0; n < servers.length(); n++) {
                        JSONObject obj = servers.optJSONObject(n);
                        if (obj == null) continue;

                        String id = obj.optString("id");
                        int playing = obj.optInt("playing");
                        int max = obj.optInt("maxPlayers");

                        if (id.isEmpty() || playing >= max) continue;

                        LinearLayout card = new LinearLayout(this);
                        card.setOrientation(LinearLayout.VERTICAL);
                        card.setPadding(12, 12, 12, 12);
                        card.setBackgroundColor(Color.rgb(22, 32, 52));

                        TextView info = new TextView(this);
                        info.setText("Players: " + playing + "/" + max +
                                "\nServer: " + id.substring(0, Math.min(12, id.length())) + "...");
                        info.setTextColor(Color.WHITE);
                        card.addView(info);

                        Button join = button("JOIN SERVER");
                        join.setOnClickListener(v -> joinServer(id));
                        card.addView(join);

                        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                                -1, -2);
                        p.setMargins(0, 10, 0, 10);
                        list.addView(card, p);
                    }
                    status.setText("Servers loaded: " + servers.length() +
                            (cursor.isEmpty() ? " (end)" : ""));
                    loading = false;
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Error: " + e.getMessage());
                    loading = false;
                });
            }
        });
    }

    private void joinServer(String jobId) {
        try {
            Uri uri = Uri.parse("roblox://experiences/start?placeId=" +
                    placeId + "&gameInstanceId=" + jobId);
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            status.setText("Opening Roblox...");
        } catch (Exception e) {
            status.setText("Roblox app not found. Install Roblox first.");
        }
    }
}
