package net.aethermc.ffa;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory copy of one player's persistent data. Mutations to balance must go through {@link Economy}. */
public final class PlayerData {
    public final UUID uuid;
    public volatile String name;
    public long balance;
    public int kills, deaths, streak, bestStreak, bountiesClaimed;
    public long bountyEarned;
    public String effect = "";
    public String deathMsg = "";
    public boolean effectsOn = true;
    public long created, lastSeen;
    /** Generic key/value store: kit unlocks, cooldowns, cosmetics, quest progress, misc timestamps. */
    public final Map<String, String> kv = new ConcurrentHashMap<>();
    public volatile boolean dirty;

    public PlayerData(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    public record Snap(UUID uuid, String name, long balance, int kills, int deaths, int streak, int bestStreak,
                       long bountyEarned, int bountiesClaimed, String effect, String deathMsg, boolean effectsOn,
                       long created, long lastSeen, Map<String, String> kv) { }

    public synchronized Snap snap() {
        return new Snap(uuid, name, balance, kills, deaths, streak, bestStreak, bountyEarned, bountiesClaimed,
                effect, deathMsg, effectsOn, created, lastSeen, new HashMap<>(kv));
    }

    public long getLong(String key, long def) {
        String v = kv.get(key);
        if (v == null) return def;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return def; }
    }
    public void setLong(String key, long v) { kv.put(key, Long.toString(v)); dirty = true; }
    public void remove(String key) { kv.remove(key); dirty = true; }
    public boolean has(String key) { return kv.containsKey(key); }
}
