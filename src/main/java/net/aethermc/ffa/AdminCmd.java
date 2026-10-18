package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /aetherffa admin | settings | reload | alerts | version */
final class AdminCmd extends Cmd {
    AdminCmd(AetherFFA plugin) { super(plugin); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "version" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "admin" -> { Player p = player(s); if (p != null && perm(s, "aetherffa.admin")) new AdminDashboard(plugin, p).open(); }
            case "settings" -> {
                Player p = player(s);
                if (p == null || !perm(s, "aetherffa.admin.settings")) return true;
                new SettingsMenu(plugin, p, "General Settings", SettingsCatalog.general(), new AdminDashboard(plugin, p)).open();
            }
            case "reload" -> {
                if (!perm(s, "aetherffa.admin.reload")) return true;
                List<String> problems = plugin.reloadAll();
                if (problems.isEmpty()) plugin.msg().send(s, "admin.reloaded");
                else for (String p : problems) plugin.msg().sendRaw(s, "<prefix><red>" + p.replace("<", "\\<"));
            }
            case "alerts" -> {
                if (!perm(s, "aetherffa.alerts")) return true;
                plugin.db().supply(conn -> {
                    List<String> out = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement("SELECT ts,type,detail FROM alerts ORDER BY id DESC LIMIT 10"); ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(rs.getLong(1))) + " [" + rs.getString(2) + "] " + rs.getString(3));
                    }
                    return out;
                }).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (list.isEmpty()) plugin.msg().send(s, "alerts.none");
                    for (String l : list) plugin.msg().send(s, "alerts.line", "line", l);
                }));
            }
            default -> plugin.msg().send(s, "admin.version", "version", plugin.version());
        }
        return true;
    }

    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (a.length != 1) return List.of();
        List<String> o = new ArrayList<>();
        if (s.hasPermission("aetherffa.admin")) o.add("admin");
        if (s.hasPermission("aetherffa.admin.settings")) o.add("settings");
        if (s.hasPermission("aetherffa.admin.reload")) o.add("reload");
        if (s.hasPermission("aetherffa.alerts")) o.add("alerts");
        return match(o, a[0]);
    }
}
