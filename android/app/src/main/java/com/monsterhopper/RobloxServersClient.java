package com.monsterhopper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Direct client for Roblox's public server listing. It intentionally has no sample/fallback rows. */
public final class RobloxServersClient {
    public static final String PLACE_ID = "98664161516921";
    private static final String ENDPOINT = "https://games.roblox.com/v1/games/" + PLACE_ID + "/servers/Public";
    private volatile HttpURLConnection activeConnection;

    public static final class Page {
        public final List<JSONObject> servers;
        public final String nextCursor;
        public final String previousCursor;
        public final boolean hasMore;

        Page(List<JSONObject> servers, String nextCursor, String previousCursor) {
            this.servers = servers;
            this.nextCursor = nextCursor;
            this.previousCursor = previousCursor;
            this.hasMore = nextCursor != null && !nextCursor.isEmpty();
        }
    }

    public static final class ServerApiException extends IOException {
        public final int statusCode;
        public final long retryAfterMs;
        ServerApiException(String message, int statusCode, long retryAfterMs) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
        }
    }

    public Page fetchPage(String cursor) throws Exception {
        StringBuilder endpoint = new StringBuilder(ENDPOINT).append("?sortOrder=Asc&limit=100");
        if (cursor != null && !cursor.isEmpty()) endpoint.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.toString()).openConnection();
        activeConnection = connection;
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        try {
            int code = connection.getResponseCode();
            long retryAfter = retryAfterMs(connection);
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readBody(stream);
            if (code < 200 || code >= 300) {
                String message = "Roblox server API returned HTTP " + code;
                try { message = new JSONObject(body).optString("errors", message); } catch (Exception ignored) {}
                throw new ServerApiException(message, code, retryAfter);
            }
            JSONObject payload = new JSONObject(body);
            JSONArray raw = payload.optJSONArray("data");
            if (raw == null) throw new IOException("Roblox returned a response without a server list.");
            ArrayList<JSONObject> rows = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject item = raw.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                int players = item.optInt("playing", -1);
                int capacity = item.optInt("maxPlayers", -1);
                if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || players < 0 || capacity <= 0 || players >= capacity || !ids.add(id.toLowerCase(Locale.ROOT))) continue;
                item.put("jobId", id);
                item.put("players", players);
                item.put("maxPlayers", capacity);
                item.put("slots", capacity - players);
                rows.add(item);
            }
            sortByPlayers(rows);
            String next = payload.optString("nextPageCursor", "");
            String previous = payload.optString("previousPageCursor", "");
            return new Page(rows, next.isEmpty() || "null".equals(next) ? null : next,
                    previous.isEmpty() || "null".equals(previous) ? null : previous);
        } finally {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    public void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    public static void sortByPlayers(List<JSONObject> servers) {
        Collections.sort(servers, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject left, JSONObject right) {
                int count = Integer.compare(left.optInt("players", Integer.MAX_VALUE), right.optInt("players", Integer.MAX_VALUE));
                return count != 0 ? count : left.optString("jobId", "").compareTo(right.optString("jobId", ""));
            }
        });
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static long retryAfterMs(HttpURLConnection connection) {
        String header = connection.getHeaderField("Retry-After");
        if (header != null) {
            try { return Math.max(1000L, Long.parseLong(header.trim()) * 1000L); }
            catch (NumberFormatException ignored) {
                try {
                    SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                    format.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date date = format.parse(header);
                    if (date != null) return Math.max(1000L, date.getTime() - System.currentTimeMillis());
                } catch (ParseException ignoredDate) { }
            }
        }
        String reset = connection.getHeaderField("x-ratelimit-reset");
        if (reset != null) try { return Math.max(1000L, Long.parseLong(reset.trim()) * 1000L); }
        catch (NumberFormatException ignored) { }
        return 20000L;
    }
}
