package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounties with escrow. Contributions are withdrawn from the placer in the same SQL transaction that stores them.
 * A claim removes the contributions from memory atomically, then a single SQL transaction deletes them and records a
 * pending payout; the payout is paid (and recovered after a crash) exactly once via the pending_payouts table.
 */
public final class BountyManager {
    public record Contribution(String id, UUID target, UUID placer, long amount, long created, long expires) { }

    private final AetherFFA plugin;
    private final Map<UUID, List<Contribution>> byTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPlace = new ConcurrentHashMap<>();

    public BountyManager(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().bounties(); }
    public boolean enabled() { return c().getBoolean("enabled", true); }

    public void load() throws SQLException {
        plugin.db().query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,target,placer,amount,created,expires FROM bounties"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Contribution b = new Contribution(rs.getString(1), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6));
                    byTarget.computeIfAbsent(b.target(), k -> new ArrayList<>()).add(b);
                }
            }
            return null;
        });
    }

    public long total(UUID target) {
        List<Contribution> l = byTarget.get(target);
        if (l == null) return 0;
        long t = 0;
        synchronized (l) { for (Contribution b : l) t += b.amount(); }
        return t;
    }

    public List<Contribution> contributions(UUID target) {
        List<Contribution> l = byTarget.get(target);
        if (l == null) return List.of();
        synchronized (l) { return new ArrayList<>(l); }
    }

    /** Targets sorted by total, descending. */
    public List<Map.Entry<UUID, Long>> top(int limit) {
        Map<UUID, Long> totals = new HashMap<>();
        for (UUID t : byTarget.keySet()) { long v = total(t); if (v > 0) totals.put(t, v); }
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(totals.entrySet());
        list.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public boolean place(Player placer, UUID target, String targetName, long amount) {
        if (!enabled()) { plugin.msg().send(placer, "bounty.disabled"); return false; }
        if (!plugin.worlds().enabled(placer.getWorld(), Feature.BOUNTIES)) { plugin.msg().send(placer, "general.world-disabled"); return false; }
        if (placer.getUniqueId().equals(target)) { plugin.msg().send(placer, "bounty.self"); return false; }
        Player tp = Bukkit.getPlayer(target);
        if (c().getBoolean("target-must-be-online", true) && tp == null) { plugin.msg().send(placer, "bounty.target-offline"); return false; }
        if (c().getBoolean("block-team-bounties", true) && plugin.teams().sameTeam(placer.getUniqueId(), target)) { plugin.msg().send(placer, "bounty.teammate"); return false; }
        long min = c().getLong("min-amount", 50), max = c().getLong("max-amount", 100000);
        if (amount < min || amount > max) { plugin.msg().send(placer, "bounty.amount-range", "min", Amounts.format(min), "max", Amounts.format(max)); return false; }
        long cap = c().getLong("max-total-per-target", 1_000_000);
        if (cap > 0 && total(target) + amount > cap) { plugin.msg().send(placer, "bounty.target-cap", "max", Amounts.format(cap)); return false; }
        long cd = c().getLong("place-cooldown-seconds", 5) * 1000L;
        Long last = lastPlace.get(placer.getUniqueId());
        if (cd > 0 && last != null && System.currentTimeMillis() - last < cd) { plugin.msg().send(placer, "bounty.cooldown"); return false; }
        long fee = amount * Math.max(0, Math.min(100, c().getLong("place-fee-percent", 0))) / 100;
        long hours = c().getLong("expiry-hours", 168);
        long now = System.currentTimeMillis();
        Contribution b = new Contribution(UUID.randomUUID().toString(), target, placer.getUniqueId(), amount, now, hours <= 0 ? 0 : now + hours * 3_600_000L);
        Economy.Result r = plugin.economy().withdraw(placer.getUniqueId(), amount + fee, "bounty-place:" + target, null,
                () -> byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(b),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bounties(id,target,placer,amount,created,expires) VALUES(?,?,?,?,?,?)")) {
                        ps.setString(1, b.id()); ps.setString(2, target.toString()); ps.setString(3, placer.getUniqueId().toString());
                        ps.setLong(4, b.amount()); ps.setLong(5, b.created()); ps.setLong(6, b.expires()); ps.executeUpdate();
                    }
                    return null;
                });
        if (r == Economy.Result.INSUFFICIENT) { plugin.msg().send(placer, "economy.insufficient"); return false; }
        if (r != Economy.Result.OK) { plugin.msg().send(placer, "general.error"); return false; }
        lastPlace.put(placer.getUniqueId(), now);
        plugin.msg().send(placer, "bounty.placed", "player", targetName, "amount", plugin.economy().fmt(amount), "total", plugin.economy().fmt(total(target)));
        if (tp != null) plugin.msg().send(tp, "bounty.on-you", "player", placer.getName(), "total", plugin.economy().fmt(total(target)));
        if (c().getBoolean("broadcast-on-place", true) && amount >= c().getLong("broadcast-min-amount", 1000))
            plugin.msg().broadcast("bounty.broadcast", "player", placer.getName(), "target", targetName, "amount", plugin.economy().fmt(amount), "total", plugin.economy().fmt(total(target)));
        return true;
    }

    /** Called for a kill that already passed {@link Guard#judgeKill}. Returns the amount queued for payout. */
    public long onKill(Player killer, Player victim) {
        if (!enabled() || !plugin.worlds().enabled(victim.getWorld(), Feature.BOUNTIES)) return 0;
        List<Contribution> list = byTarget.get(victim.getUniqueId());
        if (list == null) return 0;
        boolean contribBlock = !c().getBoolean("allow-claim-by-contributor", false);
        List<Contribution> claimed = new ArrayList<>();
        synchronized (list) {
            for (var it = list.iterator(); it.hasNext(); ) {
                Contribution b = it.next();
                if (contribBlock && (b.placer().equals(killer.getUniqueId()) || plugin.teams().sameTeam(b.placer(), killer.getUniqueId()))) continue;
                claimed.add(b); it.remove();
            }
            if (list.isEmpty()) byTarget.remove(victim.getUniqueId(), list);
        }
        if (claimed.isEmpty()) return 0;
        long total = 0;
        for (Contribution b : claimed) total += b.amount();
        long payout = total * Math.max(0, Math.min(100, c().getLong("payout-percent", 100))) / 100;
        String ref = "bounty:" + claimed.get(0).id();
        UUID killerId = killer.getUniqueId(); String victimName = victim.getName(); UUID victimId = victim.getUniqueId();
        long finalTotal = total;
        plugin.db().supplyTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bounties WHERE id=?")) {
                for (Contribution b : claimed) { ps.setString(1, b.id()); ps.addBatch(); }
                ps.executeBatch();
            }
            if (payout > 0) try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pending_payouts(ref,uuid,amount,reason,created) VALUES(?,?,?,?,?)")) {
                ps.setString(1, ref); ps.setString(2, killerId.toString()); ps.setLong(3, payout); ps.setString(4, "bounty:" + victimId); ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (t != null) {
                plugin.getLogger().severe("Bounty claim could not be stored, restoring bounty: " + t.getMessage());
                byTarget.computeIfAbsent(victimId, k -> new ArrayList<>()).addAll(claimed);
                return;
            }
            if (payout > 0) plugin.economy().payPending(ref, killerId, payout, "bounty:" + victimId);
            PlayerData kd = plugin.store().get(killerId);
            if (kd != null) { kd.bountyEarned += payout; kd.bountiesClaimed++; kd.dirty = true; }
            Player kp = Bukkit.getPlayer(killerId);
            if (kp != null) plugin.quests().progress(kp, "BOUNTY_CLAIMS", 1);
            plugin.msg().broadcast("bounty.claimed", "player", kp != null ? kp.getName() : "?", "target", victimName, "amount", plugin.economy().fmt(payout));
        }));
        return payout;
    }

    /** Refunds and removes contributions. {@code percent} is the share returned to each placer. */
    private void refund(List<Contribution> list, long percent, String reason) {
        if (list.isEmpty()) return;
        plugin.db().supplyTx(conn -> {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM bounties WHERE id=?");
                 PreparedStatement ins = conn.prepareStatement("INSERT OR IGNORE INTO pending_payouts(ref,uuid,amount,reason,created) VALUES(?,?,?,?,?)")) {
                for (Contribution b : list) {
                    del.setString(1, b.id()); del.addBatch();
                    long amt = b.amount() * percent / 100;
                    if (amt > 0) { ins.setString(1, "refund:" + b.id()); ins.setString(2, b.placer().toString()); ins.setLong(3, amt); ins.setString(4, reason); ins.setLong(5, System.currentTimeMillis()); ins.addBatch(); }
                }
                del.executeBatch(); ins.executeBatch();
            }
            return null;
        }).whenComplete((v, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (t != null) {
                plugin.getLogger().severe("Bounty refund failed, restoring: " + t.getMessage());
                for (Contribution b : list) byTarget.computeIfAbsent(b.target(), k -> new ArrayList<>()).add(b);
                return;
            }
            for (Contribution b : list) {
                long amt = b.amount() * percent / 100;
                if (amt > 0) plugin.economy().payPending("refund:" + b.id(), b.placer(), amt, reason);
                Player p = Bukkit.getPlayer(b.placer());
                if (p != null) plugin.msg().send(p, "bounty.refunded", "amount", plugin.economy().fmt(amt));
            }
        }));
    }

    /** Periodic: expires old contributions and refunds them per config. */
    public void tick() {
        long now = System.currentTimeMillis();
        long pct = Math.max(0, Math.min(100, c().getLong("refund-on-expiry-percent", 100)));
        List<Contribution> expired = new ArrayList<>();
        for (List<Contribution> l : byTarget.values()) {
            synchronized (l) {
                for (var it = l.iterator(); it.hasNext(); ) {
                    Contribution b = it.next();
                    if (b.expires() > 0 && b.expires() < now) { expired.add(b); it.remove(); }
                }
            }
        }
        byTarget.entrySet().removeIf(e -> e.getValue().isEmpty());
        refund(expired, pct, "bounty-expired");
    }

    /** Staff: remove every bounty on a target and refund the given percentage to placers. */
    public int adminRemove(UUID target, long refundPercent) {
        List<Contribution> l = byTarget.remove(target);
        if (l == null) return 0;
        List<Contribution> copy;
        synchronized (l) { copy = new ArrayList<>(l); }
        refund(copy, refundPercent, "bounty-admin-removed");
        return copy.size();
    }
}
