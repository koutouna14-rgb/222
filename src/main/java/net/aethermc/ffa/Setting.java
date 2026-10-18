package net.aethermc.ffa;

import org.bukkit.Material;

import java.util.List;
import java.util.function.Predicate;

/** Describes one editable configuration value shown in the admin GUI. */
public final class Setting {
    public enum Type { BOOL, LONG, DOUBLE, STRING, ENUM, LIST }

    public final String file, path, label, desc;
    public final Type type;
    public final Object def;
    public double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY, step = 1;
    public List<String> options = List.of();
    public boolean restart;
    public boolean minimessage;
    public String fallbackPath;                 // read from here when `path` is unset (e.g. zone override -> defaults.*)
    public Predicate<String> check;             // extra string validation
    public String checkMessage = "Invalid value";

    private Setting(String file, String path, String label, String desc, Type type, Object def) {
        this.file = file; this.path = path; this.label = label; this.desc = desc; this.type = type; this.def = def;
    }

    public static Setting bool(String file, String path, String label, String desc, boolean def) { return new Setting(file, path, label, desc, Type.BOOL, def); }
    public static Setting num(String file, String path, String label, String desc, long def, long min, long max, long step) {
        Setting s = new Setting(file, path, label, desc, Type.LONG, def); s.min = min; s.max = max; s.step = step; return s; }
    public static Setting dec(String file, String path, String label, String desc, double def, double min, double max, double step) {
        Setting s = new Setting(file, path, label, desc, Type.DOUBLE, def); s.min = min; s.max = max; s.step = step; return s; }
    public static Setting text(String file, String path, String label, String desc, String def) { return new Setting(file, path, label, desc, Type.STRING, def); }
    public static Setting choice(String file, String path, String label, String desc, String def, String... opts) {
        Setting s = new Setting(file, path, label, desc, Type.ENUM, def); s.options = List.of(opts); return s; }
    public static Setting list(String file, String path, String label, String desc) { return new Setting(file, path, label, desc, Type.LIST, List.of()); }

    public Setting restart() { this.restart = true; return this; }
    public Setting mm() { this.minimessage = true; return this; }
    public Setting fallback(String p) { this.fallbackPath = p; return this; }
    public Setting check(Predicate<String> p, String message) { this.check = p; this.checkMessage = message; return this; }

    public String key() { return file + "|" + path; }

    public Material icon(Object value) {
        return switch (type) {
            case BOOL -> Boolean.TRUE.equals(value) ? Material.LIME_DYE : Material.GRAY_DYE;
            case LONG, DOUBLE -> Material.REPEATER;
            case STRING -> Material.NAME_TAG;
            case ENUM -> Material.ENDER_EYE;
            case LIST -> Material.BOOK;
        };
    }
}
