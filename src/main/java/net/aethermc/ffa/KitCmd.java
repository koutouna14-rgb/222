package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /kits and /kit <name> | preview | create | edit | delete | give | reload */
final class KitCmd extends Cmd {
    KitCmd(AetherFFA plugin) { super(plugin); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("kits") || a.length == 0) {
            if (!perm(s, "aetherffa.kit.use")) return true;
            Player p = player(s);
            if (p != null) new KitMenu(plugin, p).open();
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "preview" -> {
                Player p = player(s);
                if (p == null || !perm(s, "aetherffa.kit.use")) return true;
                if (a.length < 2 || !plugin.kits().exists(a[1])) { plugin.msg().send(s, "kits.unknown"); return true; }
                new KitPreviewMenu(plugin, p, a[1].toLowerCase(Locale.ROOT), null).open();
            }
            case "create" -> {
                Player p = player(s);
                if (p == null || !perm(s, "aetherffa.admin.kits")) return true;
                if (a.length < 2) { plugin.msg().send(s, "kits.usage-admin"); return true; }
                plugin.kits().create(p, a[1]);
            }
            case "edit" -> {
                Player p = player(s);
                if (p == null || !perm(s, "aetherffa.admin.kits")) return true;
                if (a.length < 2 || !plugin.kits().exists(a[1])) { plugin.msg().send(s, "kits.unknown"); return true; }
                new KitEditMenu(plugin, p, a[1].toLowerCase(Locale.ROOT), null).open();
            }
            case "delete" -> {
                if (!perm(s, "aetherffa.admin.kits")) return true;
                if (a.length < 2 || !plugin.kits().exists(a[1])) { plugin.msg().send(s, "kits.unknown"); return true; }
                plugin.msg().send(s, plugin.kits().delete(a[1].toLowerCase(Locale.ROOT)) ? "admin.deleted" : "general.error", "name", a[1]);
            }
            case "give" -> {
                if (!perm(s, "aetherffa.admin.kits")) return true;
                if (a.length < 3) { plugin.msg().send(s, "kits.usage-admin"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { plugin.msg().send(s, "general.player-offline", "player", a[1]); return true; }
                if (!plugin.kits().exists(a[2])) { plugin.msg().send(s, "kits.unknown"); return true; }
                if (plugin.kits().give(t, a[2].toLowerCase(Locale.ROOT))) plugin.msg().send(s, "kits.given", "player", t.getName(), "kit", a[2]);
                else plugin.msg().send(s, "kits.no-space-other", "player", t.getName());
            }
            case "premade" -> {
                if (!perm(s, "aetherffa.admin.kits")) return true;
                int n = PremadeKits.installMissing(plugin);
                plugin.msg().send(s, n < 0 ? "general.error" : n == 0 ? "kits.premade-none" : "kits.premade-installed", "count", Math.max(0, n));
            }
            case "reload" -> {
                if (!perm(s, "aetherffa.admin.reload")) return true;
                String err = plugin.cfg().load(Cfg.KITS);
                plugin.msg().send(s, err == null ? "kits.reloaded" : "general.error");
            }
            default -> {
                if (!perm(s, "aetherffa.kit.use")) return true;
                Player p = player(s);
                if (p == null) return true;
                if (!plugin.kits().exists(a[0])) { plugin.msg().send(s, "kits.unknown"); return true; }
                plugin.kits().claim(p, a[0].toLowerCase(Locale.ROOT));
            }
        }
        return true;
    }

    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (label.equalsIgnoreCase("kits")) return List.of();
        boolean admin = s.hasPermission("aetherffa.admin.kits");
        if (a.length == 1) {
            List<String> o = new ArrayList<>(plugin.kits().ids());
            o.add("preview");
            if (admin) o.addAll(List.of("create", "edit", "delete", "give", "premade"));
            if (s.hasPermission("aetherffa.admin.reload")) o.add("reload");
            return match(o, a[0]);
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("preview") || (admin && (a[0].equalsIgnoreCase("edit") || a[0].equalsIgnoreCase("delete"))))) return match(plugin.kits().ids(), a[1]);
        if (a.length == 2 && admin && a[0].equalsIgnoreCase("give")) return online(a[1]);
        if (a.length == 3 && admin && a[0].equalsIgnoreCase("give")) return match(plugin.kits().ids(), a[2]);
        return List.of();
    }
}
