package com.monsterhopper;

import java.util.Calendar;
import java.util.TimeZone;

/** The website's opt-in Auto 15 Min Start schedule: five minutes at each IST quarter. */
public final class IstQuarterSchedule {
    public static final long QUARTER_MILLIS = 15L * 60L * 1000L;
    public static final long SESSION_MILLIS = 5L * 60L * 1000L;
    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");

    private IstQuarterSchedule() { }

    public static long quarterStart(long nowMillis) {
        Calendar calendar = Calendar.getInstance(IST);
        calendar.setTimeInMillis(nowMillis);
        calendar.set(Calendar.MINUTE, (calendar.get(Calendar.MINUTE) / 15) * 15);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static long nextStart(long nowMillis, boolean includeCurrent) {
        long current = quarterStart(nowMillis);
        return includeCurrent && current == nowMillis ? current : current + QUARTER_MILLIS;
    }

    public static boolean withinStartWindow(long scheduledStartMillis, long nowMillis) {
        return quarterStart(nowMillis) == scheduledStartMillis
                && nowMillis >= scheduledStartMillis
                && nowMillis - scheduledStartMillis < SESSION_MILLIS;
    }
}
