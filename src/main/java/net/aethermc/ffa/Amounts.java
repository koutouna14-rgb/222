package net.aethermc.ffa;

import java.util.Locale;
import java.util.OptionalLong;

/** Pure helpers for parsing and clamping currency amounts. No Bukkit dependencies. */
public final class Amounts {
    private Amounts() {}

    /** Parses "100", "1.5k", "2m", "1b". Returns empty for negatives, NaN, junk, or overflow. */
    public static OptionalLong parse(String raw) {
        if (raw == null) return OptionalLong.empty();
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (s.isEmpty()) return OptionalLong.empty();
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1_000d;
        else if (last == 'm') mult = 1_000_000d;
        else if (last == 'b') mult = 1_000_000_000d;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            if (mult == 1 && s.matches("\\d{1,18}")) return OptionalLong.of(Long.parseLong(s));
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 9.0e15) return OptionalLong.empty();
            return OptionalLong.of(Math.round(v));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }

    /** Overflow-safe addition. */
    public static long safeAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) return b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return r;
    }

    public static String format(long v) { return String.format(Locale.US, "%,d", v); }

    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
