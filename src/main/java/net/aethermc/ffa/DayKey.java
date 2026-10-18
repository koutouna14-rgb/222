package net.aethermc.ffa;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Computes the "quest day" identifier for a configurable timezone and reset time. */
public final class DayKey {
    private DayKey() {}

    public static String of(Instant now, ZoneId zone, LocalTime resetAt) {
        ZonedDateTime z = now.atZone(zone);
        // Shift back by the reset time so the day rolls over at resetAt, not at midnight.
        ZonedDateTime shifted = z.minusHours(resetAt.getHour()).minusMinutes(resetAt.getMinute());
        LocalDate d = shifted.toLocalDate();
        return d.toString();
    }

    /** Seconds until the next reset. */
    public static long secondsUntilReset(Instant now, ZoneId zone, LocalTime resetAt) {
        ZonedDateTime z = now.atZone(zone);
        ZonedDateTime next = z.toLocalDate().atTime(resetAt).atZone(zone);
        if (!next.isAfter(z)) next = next.plusDays(1);
        return Math.max(0, next.toEpochSecond() - z.toEpochSecond());
    }
}
