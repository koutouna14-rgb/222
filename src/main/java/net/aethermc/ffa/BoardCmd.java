package net.aethermc.ffa;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /leaderboard [category] */
final class BoardCmd extends Cmd {
    BoardCmd(AetherFFA plugin) { super(plugin); }
    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!perm(s, "aetherffa.leaderboard.use")) return true;
        String cat = a.length > 0 ? a[0].toLowerCase() : null;
        if (cat != null && !plugin.boards().categories().contains(cat)) { plugin.msg().send(s, "general.unknown-board"); return true; }
        if (s instanceof Player p) { new LeaderboardMenu(plugin, p, cat).open(); return true; }
        String use = cat != null ? cat : plugin.boards().categories().isEmpty() ? null : plugin.boards().categories().get(0);
        if (use == null) return true;
        List<Leaderboards.Entry> l = plugin.boards().top(use);
        for (int i = 0; i < l.size(); i++) plugin.msg().send(s, "economy.top-line", "rank", i + 1, "player", l.get(i).name(), "amount", Amounts.format(l.get(i).value()));
        return true;
    }
    @Override protected List<String> tab(CommandSender s, String label, String[] a) { return a.length == 1 ? match(plugin.boards().categories(), a[0]) : List.of(); }
}
