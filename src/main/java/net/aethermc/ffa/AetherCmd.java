package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** /aether, /aether balance|pay|top, /aether admin give|take|set */
final class AetherCmd extends Cmd {
    AetherCmd(AetherFFA plugin) { super(plugin); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!perm(s, "aetherffa.aether.use")) return true;
        if (a.length == 0) { Player p = player(s); if (p != null) new AetherHubMenu(plugin, p).open(); return true; }
        switch (a[0].toLowerCase()) {
            case "menu", "hub", "help" -> { Player p = player(s); if (p != null) new AetherHubMenu(plugin, p).open(); }
            case "wallet" -> { Player p = player(s); if (p != null) new EconomyMenu(plugin, p).open(); }
            case "bounty", "bounties" -> open(s, "aetherffa.bounty.use", p -> new BountyMenu(plugin, p).open());
            case "lb", "leaderboard", "leaderboards" -> open(s, "aetherffa.leaderboard.use", p -> new LeaderboardMenu(plugin, p, a.length > 1 ? a[1].toLowerCase() : null).open());
            case "quests", "quest" -> open(s, "aetherffa.quests.use", p -> new QuestMenu(plugin, p).open());
            case "kits", "kit" -> open(s, "aetherffa.kit.use", p -> new KitMenu(plugin, p).open());
            case "cosmetics", "shop", "effects" -> open(s, "aetherffa.cosmetics.use", p -> new CosmeticMenu(plugin, p, true).open());
            case "messages", "killmessages" -> open(s, "aetherffa.cosmetics.use", p -> new CosmeticMenu(plugin, p, false).open());
            case "team" -> open(s, "aetherffa.team.use", p -> new TeamMenu(plugin, p).open());
            case "balance", "bal" -> balance(s, a);
            case "pay" -> pay(s, a);
            case "top" -> top(s);
            case "admin" -> admin(s, a);
            default -> plugin.msg().send(s, "economy.usage");
        }
        return true;
    }

    private void open(CommandSender s, String perm, java.util.function.Consumer<Player> action) {
        Player p = player(s);
        if (p == null || !perm(s, perm)) return;
        action.accept(p);
    }

    private void balance(CommandSender s, String[] a) {
        if (a.length < 2) {
            Player p = player(s);
            if (p != null) plugin.msg().send(p, "economy.balance", "balance", plugin.economy().fmt(plugin.economy().balance(p.getUniqueId())));
            return;
        }
        if (!perm(s, "aetherffa.aether.balance.others")) return;
        resolve(s, a[1], (id, name) -> {
            if (plugin.store().get(id) != null) plugin.msg().send(s, "economy.balance-other", "player", name, "balance", plugin.economy().fmt(plugin.economy().balance(id)));
            else plugin.economy().balanceOffline(id).thenAccept(b -> Bukkit.getScheduler().runTask(plugin, () -> plugin.msg().send(s, "economy.balance-other", "player", name, "balance", plugin.economy().fmt(Math.max(0, b)))));
        });
    }

    private void pay(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null || !perm(s, "aetherffa.aether.pay")) return;
        if (a.length < 3) { plugin.msg().send(s, "economy.usage"); return; }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { plugin.msg().send(s, "general.player-offline", "player", a[1]); return; }
        OptionalLong amt = Amounts.parse(a[2]);
        if (amt.isEmpty() || amt.getAsLong() <= 0) { plugin.msg().send(s, "general.invalid-amount"); return; }
        Economy.Result r = plugin.economy().transfer(p.getUniqueId(), target.getUniqueId(), amt.getAsLong());
        switch (r) {
            case OK -> {
                plugin.msg().send(p, "economy.paid", "player", target.getName(), "amount", plugin.economy().fmt(amt.getAsLong()));
                plugin.msg().send(target, "economy.received", "player", p.getName(), "amount", plugin.economy().fmt(amt.getAsLong()));
            }
            case INSUFFICIENT -> plugin.msg().send(p, "economy.insufficient");
            case LIMIT -> plugin.msg().send(p, "economy.transfer-limit");
            default -> plugin.msg().send(p, "economy.transfer-failed");
        }
    }

    private void top(CommandSender s) {
        Player p = s instanceof Player pl ? pl : null;
        if (p != null) { new LeaderboardMenu(plugin, p, "balance").open(); return; }
        List<Leaderboards.Entry> l = plugin.boards().top("balance");
        for (int i = 0; i < l.size(); i++) plugin.msg().send(s, "economy.top-line", "rank", i + 1, "player", l.get(i).name(), "amount", Amounts.format(l.get(i).value()));
    }

    private void admin(CommandSender s, String[] a) {
        if (!perm(s, "aetherffa.admin.economy")) return;
        if (a.length < 4) { plugin.msg().send(s, "economy.admin-usage"); return; }
        String op = a[1].toLowerCase();
        OptionalLong amt = Amounts.parse(a[3]);
        if (amt.isEmpty() || (!op.equals("set") && amt.getAsLong() <= 0)) { plugin.msg().send(s, "general.invalid-amount"); return; }
        long v = amt.getAsLong();
        if (!op.equals("give") && !op.equals("take") && !op.equals("set")) { plugin.msg().send(s, "economy.admin-usage"); return; }
        resolve(s, a[2], (id, name) -> {
            String reason = "admin-" + op + ":" + s.getName();
            if (plugin.store().get(id) != null) {
                Economy.Result r = switch (op) {
                    case "give" -> plugin.economy().deposit(id, v, reason, null);
                    case "take" -> plugin.economy().withdraw(id, v, reason, null);
                    default -> plugin.economy().set(id, v, reason);
                };
                done(s, name, r, plugin.economy().balance(id));
            } else {
                plugin.economy().balanceOffline(id).thenCompose(cur -> {
                    long delta = op.equals("give") ? v : op.equals("take") ? -v : v - cur;
                    return plugin.economy().adjustOffline(id, delta, reason, null);
                }).whenComplete((r, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (t != null) { plugin.msg().send(s, "economy.admin-failed", "player", name, "balance", "?"); return; }
                    plugin.economy().balanceOffline(id).thenAccept(b -> Bukkit.getScheduler().runTask(plugin, () -> done(s, name, r, b)));
                }));
            }
        });
    }

    private void done(CommandSender s, String name, Economy.Result r, long balance) {
        plugin.msg().send(s, r == Economy.Result.OK ? "economy.admin-done" : "economy.admin-failed", "player", name, "balance", plugin.economy().fmt(balance));
    }

    interface Resolved { void run(UUID id, String name); }

    /** Online players resolve instantly; known offline players are looked up asynchronously. Unknown names never create accounts. */
    private void resolve(CommandSender s, String name, Resolved cb) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) { cb.run(online.getUniqueId(), online.getName()); return; }
        plugin.store().lookupUuid(name).whenComplete((opt, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (t != null || opt.isEmpty()) { plugin.msg().send(s, "general.unknown-player", "player", name); return; }
            OfflinePlayer op = Bukkit.getOfflinePlayer(opt.get());
            cb.run(opt.get(), op.getName() == null ? name : op.getName());
        }));
    }

    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (a.length == 1) { List<String> o = new ArrayList<>(List.of("balance", "pay", "top", "wallet", "bounty", "lb", "quests", "kits", "shop", "messages", "team", "menu")); if (s.hasPermission("aetherffa.admin.economy")) o.add("admin"); return match(o, a[0]); }
        if (a.length == 2 && a[0].equalsIgnoreCase("admin") && s.hasPermission("aetherffa.admin.economy")) return match(List.of("give", "take", "set"), a[1]);
        if (a.length == 2 && (a[0].equalsIgnoreCase("balance") || a[0].equalsIgnoreCase("pay"))) return online(a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("admin")) return online(a[2]);
        if ((a.length == 3 && a[0].equalsIgnoreCase("pay")) || (a.length == 4 && a[0].equalsIgnoreCase("admin"))) return List.of("100", "1k", "10k");
        return List.of();
    }
}
