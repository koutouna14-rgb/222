package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /quests and /quests reset <player> */
final class QuestsCmd extends Cmd {
    QuestsCmd(AetherFFA plugin) { super(plugin); }
    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("reset")) {
            if (!perm(s, "aetherffa.admin.quests")) return true;
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { plugin.msg().send(s, "general.player-offline", "player", a[1]); return true; }
            plugin.quests().reset(t);
            plugin.msg().send(s, "admin.done");
            return true;
        }
        if (!perm(s, "aetherffa.quests.use")) return true;
        Player p = player(s);
        if (p == null) return true;
        if (!plugin.worlds().enabled(p.getWorld(), Feature.QUESTS)) { plugin.msg().send(p, "general.world-disabled"); return true; }
        new QuestMenu(plugin, p).open();
        return true;
    }
    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (!s.hasPermission("aetherffa.admin.quests")) return List.of();
        if (a.length == 1) return match(List.of("reset"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("reset")) return online(a[1]);
        return List.of();
    }
}
