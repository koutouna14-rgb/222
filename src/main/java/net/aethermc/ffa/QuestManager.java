package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Daily quests. The day's quest set is chosen deterministically from the day key (same for everyone, nothing to store);
 * per-player progress and claim flags live in PlayerData.kv and are persisted with the player snapshot.
 */
public final class QuestManager {
    public record Quest(String id, String name, String type, long amount, long reward, String difficulty) { }

    /** Hard limit: a player can have at most this many quests per day, whatever quests.yml selection says. */
    public static final int MAX_DAILY = 3;

    private final AetherFFA plugin;

    public QuestManager(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().quests(); }
    public boolean enabled() { return c().getBoolean("enabled", true); }

    public ZoneId zone() {
        try { return ZoneId.of(c().getString("reset.timezone", "UTC")); } catch (RuntimeException e) { return ZoneId.of("UTC"); }
    }
    public LocalTime resetTime() {
        try { return LocalTime.parse(c().getString("reset.time", "00:00")); } catch (RuntimeException e) { return LocalTime.MIDNIGHT; }
    }
    public String dayKey() { return DayKey.of(Instant.now(), zone(), resetTime()); }
    public long secondsUntilReset() { return DayKey.secondsUntilReset(Instant.now(), zone(), resetTime()); }

    private List<Quest> pool(String difficulty) {
        List<Quest> out = new ArrayList<>();
        ConfigurationSection s = c().getConfigurationSection("pool");
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            ConfigurationSection q = s.getConfigurationSection(id);
            if (q == null || !q.getBoolean("enabled", true)) continue;
            String diff = q.getString("difficulty", "EASY").toUpperCase(Locale.ROOT);
            if (difficulty != null && !diff.equals(difficulty)) continue;
            long amount = Math.max(1, q.getLong("amount", 1));
            out.add(new Quest(id, q.getString("name", id), q.getString("type", "KILLS").toUpperCase(Locale.ROOT), amount, Math.max(0, q.getLong("reward", 0)), diff));
        }
        return out;
    }

    /** Today's quests: N per difficulty (at most {@link #MAX_DAILY} in total), chosen with a seeded shuffle so every player sees the same set. */
    public List<Quest> today() {
        List<Quest> chosen = new ArrayList<>();
        ConfigurationSection sel = c().getConfigurationSection("selection");
        if (sel == null) return chosen;
        String key = dayKey();
        for (String diff : sel.getKeys(false)) {
            int n = sel.getInt(diff, 0);
            List<Quest> p = pool(diff.toUpperCase(Locale.ROOT));
            Collections.shuffle(p, new Random((key + ":" + diff).hashCode()));
            for (int i = 0; i < Math.min(n, p.size()); i++) chosen.add(p.get(i));
        }
        return chosen.size() > MAX_DAILY ? new ArrayList<>(chosen.subList(0, MAX_DAILY)) : chosen;
    }

    /** Clears stale progress when the quest day changed. */
    private void ensureDay(PlayerData d) {
        String key = dayKey();
        String cur = d.kv.get("quest.day");
        if (key.equals(cur)) return;
        d.kv.keySet().removeIf(k -> k.startsWith("quest."));
        d.kv.put("quest.day", key);
        d.dirty = true;
    }

    public long progress(Player p, Quest q) {
        PlayerData d = plugin.store().get(p);
        if (d == null) return 0;
        ensureDay(d);
        return Math.min(q.amount(), d.getLong("quest.p." + q.id(), 0));
    }

    public boolean claimed(Player p, Quest q) {
        PlayerData d = plugin.store().get(p);
        if (d == null) return false;
        ensureDay(d);
        return d.has("quest.c." + q.id());
    }

    /** Adds progress for matching quest types. Only valid, reward-eligible activity should call this. */
    public void progress(Player p, String type, long delta) { advance(p, type, delta, false); }

    /** For "reach N" objectives such as a kill streak: keeps the maximum seen today. */
    public void progressMax(Player p, String type, long value) { advance(p, type, value, true); }

    private void advance(Player p, String type, long value, boolean max) {
        if (!enabled() || value <= 0 || !plugin.worlds().enabled(p.getWorld(), Feature.QUESTS)) return;
        PlayerData d = plugin.store().get(p);
        if (d == null) return;
        synchronized (d) {
            ensureDay(d);
            for (Quest q : today()) {
                if (!q.type().equals(type) || d.has("quest.c." + q.id())) continue;
                long cur = d.getLong("quest.p." + q.id(), 0);
                long next = max ? Math.max(cur, value) : cur + value;
                next = Math.min(next, q.amount());
                if (next == cur) continue;
                d.setLong("quest.p." + q.id(), next);
                if (next >= q.amount() && cur < q.amount()) plugin.msg().send(p, "quests.completed", "quest", q.name());
            }
        }
    }

    /** Claims a finished quest. The claimed flag is set only if the payout succeeded, under the account lock. */
    public boolean claim(Player p, Quest q) {
        PlayerData d = plugin.store().get(p);
        if (d == null) return false;
        synchronized (d) {
            ensureDay(d);
            if (d.has("quest.c." + q.id())) { plugin.msg().send(p, "quests.already-claimed"); return false; }
            if (d.getLong("quest.p." + q.id(), 0) < q.amount()) { plugin.msg().send(p, "quests.not-complete"); return false; }
            String ref = "quest:" + p.getUniqueId() + ":" + dayKey() + ":" + q.id();
            Economy.Result r = q.reward() <= 0 ? Economy.Result.OK : plugin.economy().deposit(p.getUniqueId(), q.reward(), "quest:" + q.id(), ref);
            if (r == Economy.Result.DUPLICATE) { d.setLong("quest.c." + q.id(), 1); plugin.msg().send(p, "quests.already-claimed"); return false; }
            if (r != Economy.Result.OK) { plugin.msg().send(p, r == Economy.Result.LIMIT ? "economy.max-balance" : "general.error"); return false; }
            d.setLong("quest.c." + q.id(), 1);
            plugin.store().saveAsync(d);
        }
        plugin.msg().send(p, "quests.claimed", "quest", q.name(), "amount", plugin.economy().fmt(q.reward()));
        return true;
    }

    public Map<String, String> describeTypes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("KILLS", "Valid kills"); m.put("STREAK", "Reach a kill streak"); m.put("CAPTURE_SECONDS", "Seconds in capture zones (eligible)");
        m.put("CAPTURE_PAYOUTS", "Capture-zone payouts"); m.put("BOUNTY_CLAIMS", "Bounties claimed"); m.put("KIT_CLAIMS", "Kits claimed");
        return m;
    }

    /** Resets a player's quests for the day (staff). */
    public void reset(Player p) {
        PlayerData d = plugin.store().get(p);
        if (d == null) return;
        synchronized (d) { d.kv.keySet().removeIf(k -> k.startsWith("quest.")); d.kv.put("quest.day", dayKey()); d.dirty = true; }
    }

    public void announceUnclaimed(Player p) {
        if (!enabled() || !plugin.worlds().enabled(p.getWorld(), Feature.QUESTS)) return;
        int n = 0;
        for (Quest q : today()) if (!claimed(p, q) && progress(p, q) >= q.amount()) n++;
        if (n > 0) plugin.msg().send(p, "quests.unclaimed", "count", n);
    }

    public void onlineTick() { for (Player p : Bukkit.getOnlinePlayers()) { PlayerData d = plugin.store().get(p); if (d != null) ensureDay(d); } }
}
