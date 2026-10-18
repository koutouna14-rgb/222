package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /team ... and /tc <message>. Staff: /team admin list|info|disband. */
final class TeamCmd extends Cmd {
    TeamCmd(AetherFFA plugin) { super(plugin); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("tc")) {
            Player p = player(s);
            if (p == null || !perm(s, "aetherffa.team.use")) return true;
            if (a.length == 0) { plugin.msg().send(p, "teams.usage-tc"); return true; }
            plugin.teams().chat(p, String.join(" ", a));
            return true;
        }
        if (a.length == 0) { Player p = player(s); if (p != null && perm(s, "aetherffa.team.use")) new TeamMenu(plugin, p).open(); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) { admin(s, a); return true; }
        Player p = player(s);
        if (p == null || !perm(s, "aetherffa.team.use")) return true;
        TeamManager tm = plugin.teams();
        switch (sub) {
            case "create" -> { if (!perm(s, "aetherffa.team.manage")) return true; if (a.length < 2) plugin.msg().send(p, "teams.usage"); else tm.create(p, a[1]); }
            case "invite" -> { if (!perm(s, "aetherffa.team.manage")) return true; Player t = a.length < 2 ? null : Bukkit.getPlayerExact(a[1]); if (t == null) plugin.msg().send(p, "general.player-offline", "player", a.length < 2 ? "?" : a[1]); else tm.invite(p, t); }
            case "accept" -> { if (a.length < 2) plugin.msg().send(p, "teams.usage"); else tm.accept(p, a[1]); }
            case "deny", "reject" -> { if (a.length < 2) plugin.msg().send(p, "teams.usage"); else tm.deny(p, a[1]); }
            case "kick" -> { if (!perm(s, "aetherffa.team.manage")) return true; if (a.length < 2) plugin.msg().send(p, "teams.usage"); else tm.kick(p, a[1]); }
            case "promote" -> { if (!perm(s, "aetherffa.team.manage")) return true; if (a.length < 2) plugin.msg().send(p, "teams.usage"); else tm.promote(p, a[1]); }
            case "leave" -> tm.leave(p);
            case "disband" -> { if (!perm(s, "aetherffa.team.manage")) return true; tm.disband(p); }
            case "chat" -> { if (a.length < 2) tm.toggleChat(p); else tm.chat(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length))); }
            case "info" -> info(p, a.length < 2 ? null : a[1]);
            default -> plugin.msg().send(p, "teams.usage");
        }
        return true;
    }

    private void info(CommandSender s, String who) {
        Team t = null;
        if (who == null) { if (s instanceof Player p) t = plugin.teams().teamOf(p.getUniqueId()); }
        else {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(who);
            if (op != null) t = plugin.teams().teamOf(op.getUniqueId());
            if (t == null) t = plugin.teams().byName(who);
        }
        if (t == null) { plugin.msg().send(s, "teams.info-none"); return; }
        List<String> names = new ArrayList<>();
        synchronized (t.members) { for (UUID u : t.members) names.add((t.isLeader(u) ? "★" : "") + plugin.teams().nameOf(u)); }
        plugin.msg().send(s, "teams.info", "team", t.name, "count", t.members.size(), "max", plugin.cfg().teams().getInt("max-size", 4), "members", String.join(", ", names));
    }

    private void admin(CommandSender s, String[] a) {
        if (!perm(s, "aetherffa.admin.teams")) return;
        if (a.length < 2) { plugin.msg().send(s, "teams.usage-admin"); return; }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "list" -> { for (Team t : plugin.teams().all()) plugin.msg().send(s, "teams.admin-line", "team", t.name, "count", t.members.size(), "leader", plugin.teams().nameOf(t.leader)); }
            case "info" -> { if (a.length < 3) plugin.msg().send(s, "teams.usage-admin"); else info(s, a[2]); }
            case "disband" -> {
                Team t = a.length < 3 ? null : plugin.teams().byName(a[2]);
                if (t == null) { plugin.msg().send(s, "teams.info-none"); return; }
                plugin.teams().disbandTeam(t, "teams.disbanded-by-staff");
                plugin.msg().send(s, "admin.done");
            }
            default -> plugin.msg().send(s, "teams.usage-admin");
        }
    }

    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (label.equalsIgnoreCase("tc")) return List.of();
        if (a.length == 1) { List<String> o = new ArrayList<>(List.of("create", "invite", "accept", "deny", "kick", "promote", "leave", "disband", "chat", "info")); if (s.hasPermission("aetherffa.admin.teams")) o.add("admin"); return match(o, a[0]); }
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "invite", "info" -> { return online(a[1]); }
                case "accept", "deny" -> { List<String> o = new ArrayList<>(); if (s instanceof Player p) for (Team t : plugin.teams().pendingInvites(p.getUniqueId())) o.add(t.name); return match(o, a[1]); }
                case "kick", "promote" -> { List<String> o = new ArrayList<>(); if (s instanceof Player p) { Team t = plugin.teams().teamOf(p.getUniqueId()); if (t != null) synchronized (t.members) { for (UUID u : t.members) o.add(plugin.teams().nameOf(u)); } } return match(o, a[1]); }
                case "admin" -> { return match(List.of("list", "info", "disband"), a[1]); }
                default -> { return List.of(); }
            }
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("admin")) { List<String> o = new ArrayList<>(); for (Team t : plugin.teams().all()) o.add(t.name); return match(o, a[2]); }
        return List.of();
    }
}
