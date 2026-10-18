package net.aethermc.ffa;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Staged (unsaved) settings per admin. Nothing touches the real config until Save succeeds validation. */
public final class AdminSession {
    private static final Map<UUID, AdminSession> SESSIONS = new ConcurrentHashMap<>();
    public final Map<String, Object> staged = new LinkedHashMap<>();
    private final Map<String, Setting> defs = new LinkedHashMap<>();

    public static AdminSession of(Player p) { return SESSIONS.computeIfAbsent(p.getUniqueId(), k -> new AdminSession()); }
    public static void drop(UUID id) { SESSIONS.remove(id); }

    public int pending() { return staged.size(); }

    public void stage(Setting s, Object value) { staged.put(s.key(), value); defs.put(s.key(), s); }
    public void unstage(Setting s) { staged.remove(s.key()); defs.remove(s.key()); }
    public void discard() { staged.clear(); defs.clear(); }

    /** The value the GUI should show: staged if present, else the live config (with fallback and default). */
    public Object current(AetherFFA plugin, Setting s) {
        if (staged.containsKey(s.key())) return staged.get(s.key());
        return live(plugin, s);
    }

    public static Object live(AetherFFA plugin, Setting s) {
        YamlConfiguration y = plugin.cfg().get(s.file);
        String path = s.path;
        if (!y.contains(path, true) && s.fallbackPath != null && y.contains(s.fallbackPath, true)) path = s.fallbackPath;
        switch (s.type) {
            case BOOL: return y.getBoolean(path, (Boolean) s.def);
            case LONG: return y.getLong(path, ((Number) s.def).longValue());
            case DOUBLE: return y.getDouble(path, ((Number) s.def).doubleValue());
            case LIST: return new ArrayList<>(y.getStringList(path));
            default: return y.getString(path, String.valueOf(s.def));
        }
    }

    /** Returns an error message for an invalid value, or null. Called both when staging and again at save time. */
    public static String validate(Setting s, Object v) {
        switch (s.type) {
            case BOOL: return v instanceof Boolean ? null : "Not a boolean";
            case LONG: case DOUBLE: {
                if (!(v instanceof Number n)) return "Not a number";
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) return "Not a finite number";
                if (d < s.min || d > s.max) return "Must be between " + fmt(s.min) + " and " + fmt(s.max);
                return null;
            }
            case ENUM: return v instanceof String str && s.options.contains(str) ? null : "Must be one of " + s.options;
            case LIST: if (!(v instanceof List<?>)) return "Not a list"; return null;
            default: {
                if (!(v instanceof String str)) return "Not text";
                if (str.length() > 512) return "Too long (512 max)";
                if (s.minimessage) { String e = Msg.validate(str); if (e != null) return "Invalid MiniMessage: " + e; }
                if (s.check != null && !s.check.test(str)) return s.checkMessage;
                return null;
            }
        }
    }

    private static String fmt(double d) { return d == Math.floor(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d); }

    /** Validates everything, writes it, saves each touched file atomically, and applies it live. Returns messages for the admin. */
    public List<String> save(AetherFFA plugin) {
        List<String> out = new ArrayList<>();
        for (var e : staged.entrySet()) {
            String err = validate(defs.get(e.getKey()), e.getValue());
            if (err != null) { out.add("<red>" + defs.get(e.getKey()).label + ": " + err); }
        }
        if (!out.isEmpty()) { out.add("<red>Nothing was saved. Fix or discard the invalid values."); return out; }
        Set<String> files = new LinkedHashSet<>();
        boolean restart = false;
        for (var e : staged.entrySet()) {
            Setting s = defs.get(e.getKey());
            Object v = e.getValue();
            if (s.type == Setting.Type.LONG) v = ((Number) v).longValue();
            if (s.type == Setting.Type.DOUBLE) v = ((Number) v).doubleValue();
            plugin.cfg().get(s.file).set(s.path, v);
            files.add(s.file);
            restart |= s.restart;
        }
        boolean failed = false;
        for (String f : files) {
            String err = plugin.cfg().save(f);
            if (err != null) { failed = true; out.add("<red>Save failed: " + err); plugin.cfg().load(f); }   // roll back memory to disk
        }
        if (!failed) { out.add("<green>Saved " + staged.size() + " change(s) to " + String.join(", ", files) + "."); staged.clear(); defs.clear(); }
        plugin.applyConfig();
        if (restart && !failed) out.add("<yellow>Some changes need a server restart to take effect.");
        return out;
    }
}
