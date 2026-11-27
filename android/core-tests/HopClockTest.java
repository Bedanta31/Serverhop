import com.monsterhopper.HopClock;
import com.monsterhopper.IstQuarterSchedule;

import java.util.TimeZone;

public final class HopClockTest {
    private static int assertions;

    public static void main(String[] args) {
        TimeZone zone = TimeZone.getTimeZone("Asia/Kolkata");
        eq("2026-11-27T14:45", HopClock.windowKey(at(zone, 2026, 11, 27, 14, 59, 59), zone));
        eq(at(zone, 2026, 11, 27, 15, 0, 0), HopClock.nextQuarterHour(at(zone, 2026, 11, 27, 14, 45, 0), zone));
        eq(at(zone, 2026, 11, 28, 0, 0, 0), HopClock.nextQuarterHour(at(zone, 2026, 11, 27, 23, 59, 59), zone));
        eq("00:01", HopClock.countdown(0, 1000));
        eq("01:00:00", HopClock.countdown(0, 3600000));
        eq(5, HopClock.normalizeInterval(1));
        eq(37, HopClock.normalizeInterval(37));
        eq(90, HopClock.normalizeInterval(90));
        eq(120, HopClock.normalizeInterval(180));
        long quarter = at(zone, 2026, 11, 27, 15, 0, 0);
        eq(quarter, IstQuarterSchedule.nextStart(quarter, true));
        eq(quarter + IstQuarterSchedule.QUARTER_MILLIS, IstQuarterSchedule.nextStart(quarter, false));
        eq(true, IstQuarterSchedule.withinStartWindow(quarter, quarter + 299999L));
        eq(false, IstQuarterSchedule.withinStartWindow(quarter, quarter + IstQuarterSchedule.SESSION_MILLIS));
        eq(at(zone, 2026, 11, 27, 15, 15, 0), IstQuarterSchedule.nextStart(at(zone, 2026, 11, 27, 15, 14, 59), false));
        System.out.println("HopClock and IST schedule: " + assertions + " assertions passed");
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute, int second) {
        java.util.Calendar c = java.util.Calendar.getInstance(zone);
        c.clear(); c.set(year, month - 1, day, hour, minute, second);
        return c.getTimeInMillis();
    }

    private static void eq(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
