package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central anti-farming gate. Every system that pays for a kill asks {@link #judgeKill} once; the verdict is shared so
 * kill streaks, bounties, quests and Aether rewards can never disagree. Suspicious events raise staff alerts only; the
 * plugin never punishes automatically.
 */
public final class Guard {
    public enum Verdict {
        VALID, SELF, SAME_TEAM, SAFE_ZONE, WORLD_DISABLED, REPEAT_KILL, PAIR_COOLDOWN, SAME_IP, SPAWN_KILL, TEAM_RECENT_CHANGE, DISABLED_BY_ADMIN;
        public boolean valid() { return this == VALID; }
    }

    private final AetherFFA plugin;
    private final RepeatTracker repeat = new RepeatTracker();
    private final RepeatTracker invalid = new RepeatTracker();
    private final RepeatTracker alerts = new RepeatTracker();
    private final Map<UUID, Long> respawnAt = new ConcurrentHashMap<>();

    public Guard(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().main(); }

    public void noteRespawn(UUID id) { respawnAt.put(id, System.currentTimeMillis()); }
    public void forget(UUID id) { respawnAt.remove(id); }

    public Verdict judgeKill(Player killer, Player victim) {
        long now = System.currentTimeMillis();
        if (killer.getUniqueId().equals(victim.getUniqueId())) return Verdict.SELF;
        if (!plugin.worlds().enabled(victim.getWorld(), Feature.KILL_REWARDS)) return Verdict.WORLD_DISABLED;
        if (!killer.getWorld().equals(victim.getWorld())) return Verdict.WORLD_DISABLED;
        if (killer.hasMetadata("aetherffa_no_rewards")) return Verdict.DISABLED_BY_ADMIN;
        boolean safeBlock = c().getBoolean("anti-farm.block-safe-zone-kills", true);
        if (safeBlock && (plugin.worlds().inSafeZone(killer) || plugin.worlds().inSafeZone(victim))) return Verdict.SAFE_ZONE;
        if (c().getBoolean("anti-farm.block-team-kills", true) && plugin.teams().sameTeam(killer.getUniqueId(), victim.getUniqueId())) return Verdict.SAME_TEAM;
        long lockout = plugin.cfg().teams().getLong("rules.reward-lockout-after-change-seconds", 120) * 1000L;
        if (lockout > 0 && (plugin.teams().recentlyChanged(killer.getUniqueId(), lockout) || plugin.teams().recentlyChanged(victim.getUniqueId(), lockout))) return Verdict.TEAM_RECENT_CHANGE;
        if (c().getBoolean("anti-farm.block-same-ip", true) && sameIp(killer, victim)) return Verdict.SAME_IP;
        long spawnMs = c().getLong("anti-farm.min-seconds-after-victim-respawn", 5) * 1000L;
        Long rs = respawnAt.get(victim.getUniqueId());
        if (spawnMs > 0 && rs != null && now - rs < spawnMs) return Verdict.SPAWN_KILL;
        String pair = killer.getUniqueId() + ">" + victim.getUniqueId();
        long pairCd = c().getLong("anti-farm.pair-cooldown-seconds", 30) * 1000L;
        if (pairCd > 0) {
            long last = repeat.lastEvent(pair);
            if (last != 0 && now - last < pairCd) { repeat.record(pair, now, 3_600_000L); return Verdict.PAIR_COOLDOWN; }
        }
        long window = c().getLong("anti-farm.repeat-kill-window-seconds", 600) * 1000L;
        int max = c().getInt("anti-farm.repeat-kill-max", 3);
        int n = repeat.record(pair, now, Math.max(window, pairCd));
        if (max > 0 && repeat.count(pair, now, window) > max) return Verdict.REPEAT_KILL;
        return Verdict.VALID;
    }

    private boolean sameIp(Player a, Player b) {
        if (a.getAddress() == null || b.getAddress() == null) return false;
        var x = a.getAddress().getAddress(); var y = b.getAddress().getAddress();
        if (x == null || y == null) return false;
        if (x.isLoopbackAddress() || x.isSiteLocalAddress()) return false; // local testing / proxies: never flag
        return x.equals(y);
    }

    /** Records an invalid kill and alerts staff when the configured threshold is reached. */
    public void reportInvalid(Player killer, Player victim, Verdict v) {
        if (v == Verdict.WORLD_DISABLED || v == Verdict.SELF || v == Verdict.SAFE_ZONE) return;
        long now = System.currentTimeMillis();
        long window = c().getLong("anti-farm.alert-window-seconds", 600) * 1000L;
        int threshold = c().getInt("anti-farm.alert-threshold", 5);
        int n = invalid.record(killer.getUniqueId().toString(), now, window);
        if (threshold > 0 && n >= threshold && alerts.count(killer.getUniqueId().toString(), now, window) == 0) {
            alerts.record(killer.getUniqueId().toString(), now, window);
            alert("kill-farming", killer.getUniqueId(), killer.getName() + " had " + n + " rejected kills (last: " + v + " vs " + victim.getName() + ")");
        }
    }

    /** Logs to the alerts table, console, and every online staff member with aetherffa.alerts. */
    public void alert(String type, UUID subject, String detail) {
        plugin.getLogger().warning("[Alert:" + type + "] " + detail);
        long ts = System.currentTimeMillis();
        plugin.db().write("alert", conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO alerts(ts,type,uuid,detail) VALUES(?,?,?,?)")) {
                ps.setLong(1, ts); ps.setString(2, type); ps.setString(3, subject == null ? "" : subject.toString()); ps.setString(4, detail);
                ps.executeUpdate();
            }
            return null;
        });
        if (!c().getBoolean("anti-farm.alert-staff", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("aetherffa.alerts")) plugin.msg().send(p, "alerts.staff", "type", type, "detail", detail);
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        repeat.cleanup(now, 3_600_000L);
        invalid.cleanup(now, 3_600_000L);
        alerts.cleanup(now, 3_600_000L);
        respawnAt.values().removeIf(t -> now - t > 600_000L);
    }
}
