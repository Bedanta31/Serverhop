package com.monsterhopper;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Pure time rules shared by the UI and hopping service. */
public final class HopClock {
    private HopClock() { }

    public static long nextQuarterHour(long nowMillis, TimeZone zone) {
        Calendar next = Calendar.getInstance(zone);
        next.setTimeInMillis(nowMillis);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        int nextMinute = (next.get(Calendar.MINUTE) / 15 + 1) * 15;
        next.set(Calendar.MINUTE, nextMinute);
        return next.getTimeInMillis();
    }

    public static String windowKey(long nowMillis, TimeZone zone) {
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowMillis);
        int minute = now.get(Calendar.MINUTE) / 15 * 15;
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY), minute);
    }

    public static String countdown(long nowMillis, long targetMillis) {
        long seconds = Math.max(0L, (targetMillis - nowMillis + 999L) / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        return hours > 0
                ? String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.US, "%02d:%02d", minutes, remainder);
    }

    public static int normalizeInterval(int seconds) {
        return Math.max(5, Math.min(120, seconds));
    }
}
