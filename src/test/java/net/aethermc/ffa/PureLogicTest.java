package net.aethermc.ffa;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the Bukkit-free logic: amount parsing, repeat-kill windows and the quest-day rollover. */
class PureLogicTest {

    @Test void parsesPlainAndSuffixedAmounts() {
        assertEquals(100, Amounts.parse("100").getAsLong());
        assertEquals(1500, Amounts.parse("1.5k").getAsLong());
        assertEquals(2_000_000, Amounts.parse("2m").getAsLong());
        assertEquals(1_000, Amounts.parse("1,000").getAsLong());
        assertEquals(0, Amounts.parse("0").getAsLong());
    }

    @Test void rejectsBadAmounts() {
        assertFalse(Amounts.parse("-5").isPresent());
        assertFalse(Amounts.parse("abc").isPresent());
        assertFalse(Amounts.parse("").isPresent());
        assertFalse(Amounts.parse(null).isPresent());
        assertFalse(Amounts.parse("NaN").isPresent());
        assertFalse(Amounts.parse("Infinity").isPresent());
        assertFalse(Amounts.parse("1e400").isPresent());
        assertFalse(Amounts.parse("99999999999999999999").isPresent());
    }

    @Test void additionSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, Amounts.safeAdd(Long.MAX_VALUE, 1));
        assertEquals(Long.MIN_VALUE, Amounts.safeAdd(Long.MIN_VALUE, -1));
        assertEquals(5, Amounts.safeAdd(2, 3));
        assertEquals(10, Amounts.clamp(50, 0, 10));
        assertEquals(0, Amounts.clamp(-50, 0, 10));
    }

    @Test void formatsDurations() {
        assertEquals("1m 5s", Amounts.duration(65));
        assertEquals("0s", Amounts.duration(0));
        assertEquals("1d 1h", Amounts.duration(90000));
        assertEquals("0s", Amounts.duration(-4));
    }

    @Test void repeatTrackerCountsInsideWindowOnly() {
        RepeatTracker t = new RepeatTracker();
        assertEquals(1, t.record("a>b", 1_000, 10_000));
        assertEquals(2, t.record("a>b", 2_000, 10_000));
        assertEquals(3, t.record("a>b", 3_000, 10_000));
        assertEquals(1, t.record("a>c", 3_000, 10_000));          // different pair is independent
        assertEquals(1, t.record("a>b", 20_000, 10_000));         // earlier events fell out of the window
        assertEquals(1, t.count("a>b", 20_500, 10_000));
        assertEquals(20_000, t.lastEvent("a>b"));
        assertEquals(0, t.lastEvent("nobody"));
    }

    @Test void repeatTrackerCleanupBoundsMemory() {
        RepeatTracker t = new RepeatTracker();
        for (int i = 0; i < 500; i++) t.record("k" + i, 1_000, 10_000);
        assertEquals(500, t.size());
        t.cleanup(50_000, 10_000);
        assertEquals(0, t.size());
    }

    @Test void questDayRollsOverAtResetTimeNotMidnight() {
        ZoneId utc = ZoneId.of("UTC");
        LocalTime reset = LocalTime.of(4, 0);
        assertEquals("2026-10-17", DayKey.of(Instant.parse("2026-10-18T03:59:59Z"), utc, reset));
        assertEquals("2026-10-18", DayKey.of(Instant.parse("2026-10-18T04:00:00Z"), utc, reset));
        assertEquals("2026-10-18", DayKey.of(Instant.parse("2026-10-18T23:59:00Z"), utc, reset));
    }

    @Test void questDayHonoursTimezone() {
        ZoneId manila = ZoneId.of("Asia/Manila");           // UTC+8
        LocalTime midnight = LocalTime.MIDNIGHT;
        assertEquals("2026-10-19", DayKey.of(Instant.parse("2026-10-18T16:30:00Z"), manila, midnight));
        assertEquals("2026-10-18", DayKey.of(Instant.parse("2026-10-18T15:59:00Z"), manila, midnight));
    }

    @Test void secondsUntilReset() {
        ZoneId utc = ZoneId.of("UTC");
        LocalTime reset = LocalTime.of(4, 0);
        assertEquals(3600, DayKey.secondsUntilReset(Instant.parse("2026-10-18T03:00:00Z"), utc, reset));
        assertEquals(23 * 3600, DayKey.secondsUntilReset(Instant.parse("2026-10-18T05:00:00Z"), utc, reset));
    }
}
