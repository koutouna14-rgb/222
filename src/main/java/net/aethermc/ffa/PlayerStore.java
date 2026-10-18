package net.aethermc.ffa;

import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Loads player data during async pre-login, caches it while online, and persists it safely. */
public final class PlayerStore {
    private final AetherFFA plugin;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingTs = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerData> pending = new ConcurrentHashMap<>();

    public PlayerStore(AetherFFA plugin) { this.plugin = plugin; }

    /** Blocking load; call from async threads (pre-login) or startup only. */
    public PlayerData load(UUID uuid, String name) throws SQLException {
        PlayerData d = plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    PlayerData p = new PlayerData(uuid, name);
                    p.balance = rs.getLong("balance"); p.kills = rs.getInt("kills"); p.deaths = rs.getInt("deaths");
                    p.streak = rs.getInt("streak"); p.bestStreak = rs.getInt("best_streak");
                    p.bountyEarned = rs.getLong("bounty_earned"); p.bountiesClaimed = rs.getInt("bounties_claimed");
                    String e = rs.getString("effect"); p.effect = e == null ? "" : e;
                    String dm = rs.getString("death_msg"); p.deathMsg = dm == null ? "" : dm;
                    p.effectsOn = rs.getInt("effects_on") != 0;
                    p.created = rs.getLong("created"); p.lastSeen = rs.getLong("last_seen");
                    try (PreparedStatement kv = c.prepareStatement("SELECT k,v FROM player_kv WHERE uuid=?")) {
                        kv.setString(1, uuid.toString());
                        try (ResultSet r2 = kv.executeQuery()) { while (r2.next()) p.kv.put(r2.getString(1), r2.getString(2)); }
                    }
                    return p;
                }
            }
        });
        if (d == null) {
            d = new PlayerData(uuid, name);
            d.balance = plugin.economy().startingBalance();
            d.created = System.currentTimeMillis();
            d.dirty = true;
            PlayerData fresh = d;
            plugin.db().write("create-player", c -> { saveSnap(c, fresh.snap()); return null; });
        }
        d.name = name;
        return d;
    }

    public void stash(PlayerData d) { pending.put(d.uuid, d); pendingTs.put(d.uuid, System.currentTimeMillis()); }

    /** Called on join (main thread): promotes stashed data, or loads synchronously as a fallback. */
    public PlayerData activate(Player p) {
        PlayerData d = pending.remove(p.getUniqueId());
        pendingTs.remove(p.getUniqueId());
        if (d == null) {
            try { d = load(p.getUniqueId(), p.getName()); }
            catch (SQLException e) {
                plugin.getLogger().severe("Could not load data for " + p.getName() + ": " + e.getMessage());
                d = new PlayerData(p.getUniqueId(), p.getName());
                d.balance = plugin.economy().startingBalance();
                plugin.markDegraded(p.getUniqueId());
            }
        }
        d.name = p.getName();
        d.lastSeen = System.currentTimeMillis();
        online.put(p.getUniqueId(), d);
        return d;
    }

    public PlayerData get(UUID id) { return online.get(id); }
    public PlayerData get(Player p) { return online.get(p.getUniqueId()); }
    public Iterable<PlayerData> all() { return online.values(); }

    /** Removes from cache and queues a final save (data is saved even if the DB thread is busy). */
    public void release(UUID id) {
        PlayerData d = online.remove(id);
        if (d != null && !plugin.isDegraded(id)) { d.lastSeen = System.currentTimeMillis(); saveAsync(d); }
    }

    public void saveAsync(PlayerData d) {
        PlayerData.Snap s = d.snap();
        d.dirty = false;
        plugin.db().write("save-player", c -> { saveSnap(c, s); return null; });
    }

    /** Queues saves for every dirty online player. Cheap to call frequently. */
    public void flushDirty() {
        for (PlayerData d : online.values()) if (d.dirty && !plugin.isDegraded(d.uuid)) saveAsync(d);
    }

    public void flushAll() { for (PlayerData d : online.values()) if (!plugin.isDegraded(d.uuid)) saveAsync(d); }

    public void purgePending() {
        long now = System.currentTimeMillis();
        pendingTs.entrySet().removeIf(e -> {
            if (now - e.getValue() > 120_000) { pending.remove(e.getKey()); return true; }
            return false;
        });
    }

    public static void saveSnap(Connection c, PlayerData.Snap s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players(uuid,name,balance,kills,deaths,streak,best_streak,bounty_earned,bounties_claimed,effect,death_msg,effects_on,created,last_seen)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,balance=excluded.balance,"
                        + "kills=excluded.kills,deaths=excluded.deaths,streak=excluded.streak,best_streak=excluded.best_streak,"
                        + "bounty_earned=excluded.bounty_earned,bounties_claimed=excluded.bounties_claimed,effect=excluded.effect,"
                        + "death_msg=excluded.death_msg,effects_on=excluded.effects_on,last_seen=excluded.last_seen")) {
            ps.setString(1, s.uuid().toString()); ps.setString(2, s.name()); ps.setLong(3, s.balance());
            ps.setInt(4, s.kills()); ps.setInt(5, s.deaths()); ps.setInt(6, s.streak()); ps.setInt(7, s.bestStreak());
            ps.setLong(8, s.bountyEarned()); ps.setInt(9, s.bountiesClaimed()); ps.setString(10, s.effect());
            ps.setString(11, s.deathMsg()); ps.setInt(12, s.effectsOn() ? 1 : 0);
            ps.setLong(13, s.created() == 0 ? System.currentTimeMillis() : s.created()); ps.setLong(14, s.lastSeen());
            ps.executeUpdate();
        }
        try (PreparedStatement del = c.prepareStatement("DELETE FROM player_kv WHERE uuid=?")) {
            del.setString(1, s.uuid().toString()); del.executeUpdate();
        }
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO player_kv(uuid,k,v) VALUES(?,?,?)")) {
            for (Map.Entry<String, String> e : s.kv().entrySet()) {
                ins.setString(1, s.uuid().toString()); ins.setString(2, e.getKey()); ins.setString(3, e.getValue());
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    /** Async lookup of a known player's UUID by (case-insensitive) name. */
    public CompletableFuture<Optional<UUID>> lookupUuid(String name) {
        return plugin.db().supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM players WHERE name=? COLLATE NOCASE LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.<UUID>empty(); }
            }
        });
    }

    public CompletableFuture<List<String>> knownNames(String prefix, int limit) {
        return plugin.db().supply(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM players WHERE name LIKE ? ESCAPE '\\' ORDER BY last_seen DESC LIMIT ?")) {
                ps.setString(1, prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
            }
            return out;
        });
    }
}
