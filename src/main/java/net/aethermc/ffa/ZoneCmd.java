package net.aethermc.ffa;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /capturezone create|redefine (WorldEdit selection) | createcircle|delete|enable|disable|info|list|status|set|gui */
final class ZoneCmd extends Cmd {
    ZoneCmd(AetherFFA plugin) { super(plugin); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            Player p = player(s);
            if (p == null) return true;
            ZoneManager.Zone z = plugin.zones().zoneAt(p);
            if (z == null) { plugin.msg().send(s, "zones.not-in-zone"); return true; }
            ZoneManager.State st = plugin.zones().state(z.id);
            plugin.msg().send(s, "zones.status-line", "zone", plugin.zones().displayName(z.id), "status", st.status.name(), "occupants", st.occupants, "eligible", st.eligible);
            return true;
        }
        if (!perm(s, "aetherffa.admin.zones")) return true;
        ZoneManager zm = plugin.zones();
        switch (sub) {
            case "pos1", "pos2" -> {
                Player p = player(s);
                if (p == null) return true;
                if (zm.usesWorldEdit()) { plugin.msg().send(s, "zones.use-worldedit"); return true; }   // WorldEdit's own //pos1 //pos2 //wand are used instead
                zm.select(p, sub.equals("pos1") ? 0 : 1);
                plugin.msg().send(s, "zones.pos-set", "n", sub.substring(3));
            }
            case "create" -> { Player p = player(s); if (p != null) result(s, a.length < 2 ? "zones.usage" : zm.createFromSelection(p, a[1]), a.length < 2 ? "" : a[1]); }
            case "redefine" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 2 || zm.get(a[1]) == null) { plugin.msg().send(s, "zones.unknown"); return true; }
                result(s, zm.applySelection(p, a[1]), zm.get(a[1]).id);
            }
            case "createcircle" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 3) { plugin.msg().send(s, "zones.usage"); return true; }
                double r;
                try { r = Double.parseDouble(a[2]); } catch (NumberFormatException e) { plugin.msg().send(s, "general.invalid-amount"); return true; }
                result(s, zm.createCylinder(p, a[1], r, 2, 6), a[1]);
            }
            case "delete" -> result(s, a.length < 2 ? "zones.usage" : zm.remove(a[1]), a.length < 2 ? "" : a[1]);
            case "enable", "disable" -> result(s, a.length < 2 ? "zones.usage" : zm.setEnabled(a[1].toLowerCase(Locale.ROOT), sub.equals("enable")), a.length < 2 ? "" : a[1]);
            case "info" -> {
                if (a.length < 2 || zm.get(a[1]) == null) { plugin.msg().send(s, "zones.unknown"); return true; }
                for (String line : zm.info(zm.get(a[1]).id)) plugin.msg().sendRaw(s, line);
            }
            case "list" -> { for (ZoneManager.Zone z : zm.zones()) plugin.msg().send(s, "zones.list-line", "zone", z.id, "world", z.world, "status", zm.statusOf(z.id).name()); }
            case "gui" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 2 || zm.get(a[1]) == null) { plugin.msg().send(s, "zones.unknown"); return true; }
                new ZoneEditMenu(plugin, p, zm.get(a[1]).id, null).open();
            }
            case "set" -> set(s, a);
            default -> plugin.msg().send(s, "zones.usage");
        }
        return true;
    }

    private void result(CommandSender s, String errKey, String id) {
        plugin.msg().send(s, errKey == null ? "zones.done" : errKey, "zone", id);
    }

    /** /capturezone set <id> <key> <value>: only keys exposed by the settings catalog, validated like the GUI. */
    private void set(CommandSender s, String[] a) {
        if (a.length < 4 || plugin.zones().get(a[1]) == null) { plugin.msg().send(s, "zones.usage"); return; }
        String id = plugin.zones().get(a[1]).id;
        Setting target = null;
        for (Setting st : SettingsCatalog.zone("zones." + id + ".", true)) if (st.path.equals("zones." + id + "." + a[2])) target = st;
        if (target == null) { plugin.msg().send(s, "zones.bad-key"); return; }
        String raw = String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length));
        Object v;
        try {
            v = switch (target.type) {
                case BOOL -> { if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) throw new NumberFormatException(); yield Boolean.parseBoolean(raw); }
                case LONG -> (Object) Long.parseLong(raw);
                case DOUBLE -> (Object) Double.parseDouble(raw);
                case LIST -> throw new NumberFormatException();
                default -> raw;
            };
        } catch (NumberFormatException e) { plugin.msg().send(s, "general.invalid-amount"); return; }
        String err = AdminSession.validate(target, v);
        if (err != null) { plugin.msg().sendRaw(s, "<prefix><red>" + err.replace("<", "\\<")); return; }
        plugin.cfg().zones().set(target.path, v);
        String se = plugin.cfg().save(Cfg.ZONES);
        plugin.applyConfig();
        plugin.msg().send(s, se == null ? "zones.done" : "general.save-failed", "zone", id, "error", String.valueOf(se));
    }

    @Override protected List<String> tab(CommandSender s, String label, String[] a) {
        if (a.length == 1) { List<String> o = new ArrayList<>(List.of("status")); if (s.hasPermission("aetherffa.admin.zones")) o.addAll(List.of("pos1", "pos2", "create", "redefine", "createcircle", "delete", "enable", "disable", "info", "list", "set", "gui")); return match(o, a[0]); }
        if (!s.hasPermission("aetherffa.admin.zones")) return List.of();
        if (a.length == 2 && List.of("delete", "redefine", "enable", "disable", "info", "set", "gui").contains(a[0].toLowerCase())) { List<String> ids = new ArrayList<>(); for (ZoneManager.Zone z : plugin.zones().zones()) ids.add(z.id); return match(ids, a[1]); }
        if (a.length == 3 && a[0].equalsIgnoreCase("set") && plugin.zones().get(a[1]) != null) {
            List<String> keys = new ArrayList<>(); String pre = "zones." + plugin.zones().get(a[1]).id + ".";
            for (Setting st : SettingsCatalog.zone(pre, true)) if (st.type != Setting.Type.LIST) keys.add(st.path.substring(pre.length()));
            return match(keys, a[2]);
        }
        return List.of();
    }
}
