package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared command helpers. */
abstract class Cmd implements CommandExecutor, TabCompleter {
    protected final AetherFFA plugin;
    Cmd(AetherFFA plugin) { this.plugin = plugin; }

    protected Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        plugin.msg().send(s, "general.players-only");
        return null;
    }

    protected boolean perm(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        plugin.msg().send(s, "general.no-permission");
        return false;
    }

    protected static List<String> match(List<String> options, String arg) {
        List<String> out = new ArrayList<>();
        String a = arg.toLowerCase(Locale.ROOT);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(a)) out.add(o);
        return out;
    }

    protected static List<String> online(String arg) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return match(names, arg);
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        try { return tab(s, l, a); } catch (RuntimeException e) { return List.of(); }
    }

    protected abstract List<String> tab(CommandSender s, String label, String[] a);
}
