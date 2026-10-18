package net.aethermc.ffa;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads, validates and safely saves all YAML files. Bundled files act as defaults, so missing keys
 * always fall back to a sane value. Saves are atomic (temp file + move) and keep a .bak copy.
 */
public final class Cfg {
    public static final String MAIN = "config.yml", MESSAGES = "messages.yml", WORLDS = "worlds.yml",
            ECONOMY = "economy.yml", KITS = "kits.yml", ZONES = "capture-zones.yml", TEAMS = "teams.yml",
            BOUNTIES = "bounties.yml", STREAKS = "killstreaks.yml", QUESTS = "quests.yml",
            COSMETICS = "cosmetics.yml", COMBAT = "combat.yml", BOARDS = "leaderboards.yml",
            GUI = "gui.yml", DATABASE = "database.yml";
    public static final String[] ALL = {MAIN, MESSAGES, WORLDS, ECONOMY, KITS, ZONES, TEAMS, BOUNTIES, STREAKS,
            QUESTS, COSMETICS, COMBAT, BOARDS, GUI, DATABASE};

    private final AetherFFA plugin;
    private final Map<String, YamlConfiguration> files = new ConcurrentHashMap<>();

    public Cfg(AetherFFA plugin) { this.plugin = plugin; }

    /** Loads every file. Returns human-readable problems (empty when everything loaded cleanly). */
    public List<String> loadAll() {
        List<String> problems = new ArrayList<>();
        plugin.getDataFolder().mkdirs();
        for (String name : ALL) {
            String p = load(name);
            if (p != null) problems.add(p);
        }
        return problems;
    }

    /** Loads a single file; returns an error string or null. On a YAML error the previous in-memory copy is kept. */
    public String load(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) plugin.saveResource(name, false);
        YamlConfiguration yaml = new YamlConfiguration();
        String error = null;
        try {
            yaml.load(f);
        } catch (IOException | InvalidConfigurationException e) {
            error = name + ": " + e.getMessage();
            plugin.getLogger().severe("Could not parse " + name + ": " + e.getMessage());
            try { Files.copy(f.toPath(), new File(plugin.getDataFolder(), name + ".broken").toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
            YamlConfiguration old = files.get(name);
            if (old != null) return error; // keep the last good config in memory
            yaml = new YamlConfiguration(); // fall through to bundled defaults only
        }
        // Bundled defaults are applied ONLY to messages.yml (flat strings). User-managed collections (kits, zones, quest pool,
        // worlds, cosmetics) must never be resurrected from defaults after an admin deletes them; code supplies scalar defaults.
        InputStream in = name.equals(MESSAGES) ? plugin.getResource(name) : null;
        if (in != null) {
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            yaml.setDefaults(def);
            if (name.equals(MESSAGES) && !yaml.getKeys(true).containsAll(def.getKeys(true))) {
                // New message keys from an update are appended to the admin's file (comments and edits are preserved).
                yaml.options().copyDefaults(true);
                files.put(name, yaml);
                String err = save(name);
                yaml.options().copyDefaults(false);
                if (err != null) plugin.getLogger().warning("Could not add new default messages: " + err);
            }
        }
        files.put(name, yaml);
        return error;
    }

    public YamlConfiguration get(String name) {
        YamlConfiguration y = files.get(name);
        if (y == null) { load(name); y = files.get(name); }
        return y;
    }

    public YamlConfiguration main() { return get(MAIN); }
    public YamlConfiguration messages() { return get(MESSAGES); }
    public YamlConfiguration worlds() { return get(WORLDS); }
    public YamlConfiguration eco() { return get(ECONOMY); }
    public YamlConfiguration kits() { return get(KITS); }
    public YamlConfiguration zones() { return get(ZONES); }
    public YamlConfiguration teams() { return get(TEAMS); }
    public YamlConfiguration bounties() { return get(BOUNTIES); }
    public YamlConfiguration streaks() { return get(STREAKS); }
    public YamlConfiguration quests() { return get(QUESTS); }
    public YamlConfiguration cosmetics() { return get(COSMETICS); }
    public YamlConfiguration combat() { return get(COMBAT); }
    public YamlConfiguration boards() { return get(BOARDS); }
    public YamlConfiguration gui() { return get(GUI); }
    public YamlConfiguration dbc() { return get(DATABASE); }

    /** Atomic save: write temp, keep .bak of previous, move into place. Returns null on success, else an error. */
    public synchronized String save(String name) {
        YamlConfiguration y = get(name);
        File target = new File(plugin.getDataFolder(), name);
        File tmp = new File(plugin.getDataFolder(), name + ".tmp");
        try {
            y.save(tmp);
            if (target.exists()) Files.copy(target.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save " + name + ": " + e.getMessage());
            return name + ": " + e.getMessage();
        }
    }

    /** Sets a value and saves the file. */
    public String set(String name, String path, Object value) {
        get(name).set(path, value);
        return save(name);
    }
}
