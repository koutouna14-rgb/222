package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Cached top lists. One bounded query per category per refresh interval, always off the main thread. */
public final class Leaderboards {
    public record Entry(UUID uuid, String name, long value) { }
    /** Whitelisted sortable columns; config can only choose among these, so no SQL is built from user text. */
    private static final Set<String> COLUMNS = Set.of("kills", "deaths", "best_streak", "streak", "balance", "bounty_earned", "bounties_claimed");

    private final AetherFFA plugin;
    private volatile Map<String, List<Entry>> cache = new LinkedHashMap<>();
    private volatile long refreshedAt;
    private volatile boolean running;

    public Leaderboards(AetherFFA plugin) { this.plugin = plugin; }

    public List<String> categories() {
        ConfigurationSection s = plugin.cfg().boards().getConfigurationSection("categories");
        List<String> out = new ArrayList<>();
        if (s != null) for (String k : s.getKeys(false)) if (s.getBoolean(k + ".enabled", true) && COLUMNS.contains(s.getString(k + ".column", ""))) out.add(k);
        return out;
    }

    /** Tab icon: categories.<id>.icon if set and valid, otherwise a default that matches the sorted column. */
    public org.bukkit.Material icon(String cat) {
        String col = plugin.cfg().boards().getString("categories." + cat + ".column", "kills");
        org.bukkit.Material def = switch (col) {
            case "kills" -> org.bukkit.Material.DIAMOND_SWORD;
            case "deaths" -> org.bukkit.Material.SKELETON_SKULL;
            case "best_streak", "streak" -> org.bukkit.Material.BLAZE_POWDER;
            case "balance" -> Items.currency();
            case "bounty_earned", "bounties_claimed" -> org.bukkit.Material.TARGET;
            default -> org.bukkit.Material.BOOK;
        };
        return Items.mat(plugin.cfg().boards().getString("categories." + cat + ".icon"), def);
    }

    public String title(String cat) { return plugin.cfg().boards().getString("categories." + cat + ".title", cat); }
    public List<Entry> top(String cat) { return cache.getOrDefault(cat, List.of()); }
    public long refreshedAt() { return refreshedAt; }

    public int rankOf(String cat, UUID id) {
        List<Entry> l = top(cat);
        for (int i = 0; i < l.size(); i++) if (l.get(i).uuid().equals(id)) return i + 1;
        return -1;
    }

    /** Main-thread entry point: flushes dirty players first (same DB queue, so ordering is guaranteed), then queries. */
    public void refresh() {
        if (running) return;
        running = true;
        plugin.store().flushDirty();
        List<String> cats = categories();
        int limit = Math.max(1, Math.min(100, plugin.cfg().boards().getInt("display-limit", 10)));
        Map<String, String> cols = new LinkedHashMap<>();
        for (String c : cats) cols.put(c, plugin.cfg().boards().getString("categories." + c + ".column", "kills").toLowerCase(Locale.ROOT));
        plugin.db().supply(conn -> {
            Map<String, List<Entry>> out = new LinkedHashMap<>();
            for (var e : cols.entrySet()) {
                String col = e.getValue();
                if (!COLUMNS.contains(col)) continue;
                List<Entry> list = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT uuid,name," + col + " FROM players WHERE " + col + " > 0 ORDER BY " + col + " DESC LIMIT ?")) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3))); }
                }
                out.put(e.getKey(), list);
            }
            return out;
        }).whenComplete((res, t) -> {
            running = false;
            if (t != null) { plugin.getLogger().warning("Leaderboard refresh failed: " + t.getMessage()); return; }
            cache = res; refreshedAt = System.currentTimeMillis();
        });
    }
}
