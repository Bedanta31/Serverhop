package com.monsterhopper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Device-local, bounded history. A launch request is recorded as requested, never as joined. */
public final class HopHistoryStore {
    private static final String PREFS = "hopper";
    private static final String KEY = "historyEntries";
    private static final int MAX_ENTRIES = 200;

    private HopHistoryStore() { }

    public static void record(Context context, String id, int players, int capacity,
                              String outcome, String detail) {
        if (id == null || id.trim().isEmpty()) return;
        JSONArray rows = read(context);
        JSONArray next = new JSONArray();
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("players", players);
            entry.put("capacity", capacity);
            entry.put("at", System.currentTimeMillis());
            entry.put("outcome", outcome);
            entry.put("detail", detail == null ? "" : detail);
            next.put(entry);
            int first = Math.max(0, rows.length() - (MAX_ENTRIES - 1));
            for (int i = first; i < rows.length(); i++) {
                JSONObject old = rows.optJSONObject(i);
                if (old != null) next.put(old);
            }
            preferences(context).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Most recent first. Entries are copied so callers cannot mutate persisted state. */
    public static List<JSONObject> recent(Context context) {
        JSONArray rows = read(context);
        ArrayList<JSONObject> result = new ArrayList<>();
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) result.add(row);
        }
        return Collections.unmodifiableList(result);
    }

    public static void clear(Context context) {
        preferences(context).edit().remove(KEY).apply();
    }

    private static JSONArray read(Context context) {
        try { return new JSONArray(preferences(context).getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
