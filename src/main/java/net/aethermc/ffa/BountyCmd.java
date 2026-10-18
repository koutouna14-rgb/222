package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** /bounty, set, top, info, and staff remove. */
final class BountyCmd extends Cmd {
    BountyCmd(AetherFFA plugin) { super(plugin); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!perm(s, "aetherffa.bounty.use")) return true;
        if (a.length == 0) { Player p = player(s); if (p != null) new BountyMenu(plugin, p).open(); return true; }
        switch (a[0].toLowerCase()) {
            case "set" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 3) { plugin.msg().send(s, "bounty.usage"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { plugin.msg().send(s, "general.player-offline", "player", a[1]); return true; }
                OptionalLong amt = Amounts.parse(a[2]);
                if (amt.isEmpty() || amt.getAsLong() <= 0) { plugin.msg().send(s, "general.invalid-amount"); return true; }
                plugin.bounties().place(p, t.getUniqueId(), t.getName(), amt.getAsLong());
            }
            case "top" -> {
                List<Map.Entry<UUID, Long>> top = plugin.bounties().top(10);
                if (top.isEmpty()) { plugin.msg().send(s, "bounty.none"); return true; }
                int i = 1;
                for (var e : top) { OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey()); plugin.msg().send(s, "bounty.top-line", "rank", i++, "player", op.getName() == null ? "?" : op.getName(), "amount", plugin.economy().fmt(e.getValue())); }
            }
            case "info" -> {
                if (a.length < 2) { plugin.msg().send(s, "bounty.usage"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (op == null) { plugin.msg().send(s, "general.unknown-player", "player", a[1]); return true; }
                long total = plugin.bounties().total(op.getUniqueId());
                plugin.msg().send(s, "bounty.info", "player", a[1], "amount", plugin.economy().fmt(total), "count", plugin.bounties().contributions(op.getUniqueId()).size());
                if (s.hasPermission("aetherffa.admin.bounty"))
                    for (BountyManager.Contribution b : plugin.bounties().contributions(op.getUniqueId()))
                        plugin.msg().send(s, "bounty.info-admin-line", "placer", plugin.teams().nameOf(b.placer()), "amount", plugin.economy().fmt(b.amount()),
                                "expires", b.expires() == 0 ? "never" : Amounts.duration((b.expires() - System.currentTimeMillis()) / 1000));
            }
            case "remove" -> {
                if (!perm(s, "aetherffa.admin.bounty")) return true;
                if (a.length < 2) { plugin.msg().send(s, "bounty.usage"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (op == null) { plugin.msg().send(s, "general.unknown-player", "player", a[1]); return true; }
                int n = plugin.bounties().adminRemove(op.getUniqueId(), 100);
                plugin.msg().send(s, "bounty.removed", "count", n, "player", a[1]);
            }
            default -> plugin.msg().send(s, "bounty.usage");
        }
        return true;
    }

    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (a.length == 1) { List<String> o = new ArrayList<>(List.of("set", "top", "info")); if (s.hasPermission("aetherffa.admin.bounty")) o.add("remove"); return match(o, a[0]); }
        if (a.length == 2 && List.of("set", "info", "remove").contains(a[0].toLowerCase())) return online(a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("set")) return List.of("100", "500", "1k");
        return List.of();
    }
}
