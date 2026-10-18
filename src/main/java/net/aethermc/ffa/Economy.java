package net.aethermc.ffa;

import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal Aether currency. Online accounts are mutated in memory under a per-account lock and persisted in a
 * single SQL transaction together with the ledger row. Offline accounts are changed with an atomic SQL transaction.
 * Reward references ({@code ref}) are de-duplicated in memory and by a UNIQUE ledger column.
 */
public final class Economy {
    public enum Result { OK, INSUFFICIENT, LIMIT, DUPLICATE, NO_ACCOUNT, INVALID, ERROR }
    public record Tx(long ts, long delta, long balanceAfter, String reason) { }

    private final AetherFFA plugin;
    private final Map<String, Long> recentRefs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransfer = new ConcurrentHashMap<>();

    public Economy(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().eco(); }
    public long startingBalance() { return Math.max(0, c().getLong("starting-balance", 500)); }
    public long min() { return c().getBoolean("allow-negative", false) ? c().getLong("min-balance", -1000) : Math.max(0, c().getLong("min-balance", 0)); }
    public long max() { long m = c().getLong("max-balance", 1_000_000_000L); return m <= 0 ? Long.MAX_VALUE / 4 : m; }
    public String currency() { return c().getString("currency.name", "Aether"); }
    public String symbol() { return c().getString("currency.symbol", "◆"); }
    public String fmt(long v) { return Amounts.format(v) + " " + symbol(); }

    public long balance(UUID id) { PlayerData d = plugin.store().get(id); return d == null ? 0 : d.balance; }

    private boolean seen(String ref) {
        if (ref == null) return false;
        long now = System.currentTimeMillis();
        Long prev = recentRefs.putIfAbsent(ref, now);
        return prev != null;
    }
    private void unsee(String ref) { if (ref != null) recentRefs.remove(ref); }
    public void cleanupRefs() { long cut = System.currentTimeMillis() - 24L * 3600_000L; recentRefs.values().removeIf(t -> t < cut); }

    /** Adds Aether to an online account. Caps at max balance (partial payout) and returns LIMIT only if nothing could be paid. */
    public Result deposit(UUID id, long amount, String reason, String ref) {
        if (amount <= 0) return Result.INVALID;
        PlayerData d = plugin.store().get(id);
        if (d == null) return Result.NO_ACCOUNT;
        if (plugin.isDegraded(id)) return Result.ERROR;
        if (seen(ref)) return Result.DUPLICATE;
        long applied;
        long after;
        synchronized (d) {
            long room = max() - d.balance;
            applied = Math.min(amount, room);
            if (applied <= 0) { unsee(ref); return Result.LIMIT; }
            d.balance = Amounts.safeAdd(d.balance, applied);
            after = d.balance;
        }
        persist(d, applied, after, reason, ref);
        return Result.OK;
    }

    /**
     * Removes Aether from an online account. {@code inTx} runs under the account lock after the balance changed and
     * before the snapshot is taken, so state it mutates (e.g. a kit unlock) is persisted atomically with the charge.
     */
    public Result withdraw(UUID id, long amount, String reason, String ref, Runnable inTx) {
        return withdraw(id, amount, reason, ref, inTx, null);
    }

    /** As above, and {@code extra} SQL runs inside the same database transaction as the balance write (e.g. escrow rows). */
    public Result withdraw(UUID id, long amount, String reason, String ref, Runnable inTx, Db.SqlFn<?> extra) {
        if (amount < 0) return Result.INVALID;
        PlayerData d = plugin.store().get(id);
        if (d == null) return Result.NO_ACCOUNT;
        if (plugin.isDegraded(id)) return Result.ERROR;
        if (seen(ref)) return Result.DUPLICATE;
        long after;
        synchronized (d) {
            if (d.balance - amount < min()) { unsee(ref); return Result.INSUFFICIENT; }
            d.balance -= amount;
            after = d.balance;
            if (inTx != null) inTx.run();
        }
        persist(d, -amount, after, reason, ref, extra);
        return Result.OK;
    }

    public Result withdraw(UUID id, long amount, String reason, String ref) { return withdraw(id, amount, reason, ref, null, null); }

    /** Sets an online balance (admin). Clamped to configured limits. */
    public Result set(UUID id, long amount, String reason) {
        PlayerData d = plugin.store().get(id);
        if (d == null) return Result.NO_ACCOUNT;
        long delta, after;
        synchronized (d) {
            long target = Amounts.clamp(amount, min(), max());
            delta = target - d.balance;
            d.balance = target;
            after = target;
        }
        persist(d, delta, after, reason, null);
        return Result.OK;
    }

    /** Player-to-player transfer between two online accounts; both sides are applied atomically. */
    public Result transfer(UUID from, UUID to, long amount) {
        if (!c().getBoolean("transfers.enabled", true)) return Result.INVALID;
        if (from.equals(to) || amount <= 0) return Result.INVALID;
        if (amount < c().getLong("transfers.min", 1) || amount > c().getLong("transfers.max", 100000)) return Result.LIMIT;
        long cd = c().getLong("transfers.cooldown-seconds", 3) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastTransfer.get(from);
        if (last != null && now - last < cd) return Result.LIMIT;
        PlayerData a = plugin.store().get(from), b = plugin.store().get(to);
        if (a == null || b == null) return Result.NO_ACCOUNT;
        if (plugin.isDegraded(from) || plugin.isDegraded(to)) return Result.ERROR;
        long tax = amount * Math.max(0, Math.min(100, c().getLong("transfers.tax-percent", 0))) / 100;
        long received = amount - tax;
        PlayerData first = a.uuid.compareTo(b.uuid) < 0 ? a : b, second = first == a ? b : a;
        long aAfter, bAfter;
        synchronized (first) { synchronized (second) {
            if (a.balance - amount < min()) return Result.INSUFFICIENT;
            if (b.balance + received > max()) return Result.LIMIT;
            a.balance -= amount; b.balance += received;
            aAfter = a.balance; bAfter = b.balance;
        } }
        lastTransfer.put(from, now);
        PlayerData.Snap sa = a.snap(), sb = b.snap();
        a.dirty = false; b.dirty = false;
        long ts = System.currentTimeMillis();
        plugin.db().write("transfer", conn -> {
            PlayerStore.saveSnap(conn, sa); PlayerStore.saveSnap(conn, sb);
            insertTx(conn, from, -amount, aAfter, "pay:" + to, null, ts);
            insertTx(conn, to, received, bAfter, "pay-from:" + from, null, ts);
            return null;
        });
        return Result.OK;
    }

    private void persist(PlayerData d, long delta, long after, String reason, String ref) { persist(d, delta, after, reason, ref, null); }

    private void persist(PlayerData d, long delta, long after, String reason, String ref, Db.SqlFn<?> extra) {
        PlayerData.Snap s = d.snap();
        d.dirty = false;
        long ts = System.currentTimeMillis();
        plugin.db().write("economy", conn -> {
            PlayerStore.saveSnap(conn, s);
            insertTx(conn, d.uuid, delta, after, reason, ref, ts);
            if (ref != null) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM pending_payouts WHERE ref=?")) { ps.setString(1, ref); ps.executeUpdate(); }
            }
            if (extra != null) extra.apply(conn);
            return null;
        });
    }

    private static void insertTx(java.sql.Connection conn, UUID id, long delta, long after, String reason, String ref, long ts) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO transactions(uuid,delta,balance_after,reason,ref,ts) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id.toString()); ps.setLong(2, delta); ps.setLong(3, after); ps.setString(4, reason);
            ps.setString(5, ref); ps.setLong(6, ts); ps.executeUpdate();
        }
    }

    /** Atomic SQL adjustment for accounts that are not online. Delta may be negative. */
    public CompletableFuture<Result> adjustOffline(UUID id, long delta, String reason, String ref) {
        return plugin.db().supplyTx(conn -> {
            long bal;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) return Result.NO_ACCOUNT; bal = rs.getLong(1); }
            }
            if (ref != null) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM transactions WHERE ref=?")) {
                    ps.setString(1, ref);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) {
                        try (PreparedStatement del = conn.prepareStatement("DELETE FROM pending_payouts WHERE ref=?")) { del.setString(1, ref); del.executeUpdate(); }
                        return Result.DUPLICATE; } }
                }
            }
            long target = Amounts.safeAdd(bal, delta);
            if (target < min()) return Result.INSUFFICIENT;
            if (target > max()) { if (delta > 0) target = max(); if (target <= bal) return Result.LIMIT; }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET balance=? WHERE uuid=?")) {
                ps.setLong(1, target); ps.setString(2, id.toString()); ps.executeUpdate();
            }
            insertTx(conn, id, target - bal, target, reason, ref, System.currentTimeMillis());
            if (ref != null) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM pending_payouts WHERE ref=?")) { ps.setString(1, ref); ps.executeUpdate(); }
            }
            return Result.OK;
        });
    }

    public CompletableFuture<Long> balanceOffline(UUID id) {
        return plugin.db().supply(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : -1L; }
            }
        });
    }

    public CompletableFuture<List<Tx>> history(UUID id, int limit) {
        return plugin.db().supply(conn -> {
            List<Tx> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT ts,delta,balance_after,reason FROM transactions WHERE uuid=? ORDER BY ts DESC, id DESC LIMIT ?")) {
                ps.setString(1, id.toString()); ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(new Tx(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4))); }
            }
            return out;
        });
    }

    /**
     * Durable payout: pays an account that may be offline. The caller must already have recorded the payout in
     * pending_payouts (inside the same SQL transaction that removed the source, e.g. the bounty rows).
     */
    public void payPending(String ref, UUID id, long amount, String reason) {
        if (plugin.store().get(id) != null) {
            Result r = deposit(id, amount, reason, ref);
            if (r == Result.OK || r == Result.DUPLICATE) return;
            if (r == Result.LIMIT) { /* at max balance: payout stays pending and is retried on next startup */ return; }
        }
        adjustOffline(id, amount, reason, ref).whenComplete((r, t) -> {
            if (t != null) plugin.getLogger().severe("Pending payout " + ref + " failed: " + t.getMessage());
        });
    }

    /** Pays any payouts left over from a crash or shutdown. Runs once at startup. */
    public void recoverPending() {
        try {
            List<Object[]> rows = plugin.db().query(conn -> {
                List<Object[]> out = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT ref,uuid,amount,reason FROM pending_payouts");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Object[]{rs.getString(1), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getString(4)});
                }
                return out;
            });
            for (Object[] r : rows) {
                plugin.getLogger().warning("Recovering pending payout " + r[0] + " (" + r[2] + ")");
                adjustOffline((UUID) r[1], (Long) r[2], String.valueOf(r[3]), (String) r[0]);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not recover pending payouts: " + e.getMessage());
        }
    }
}
