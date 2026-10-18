package net.aethermc.ffa;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One-time content merge for cosmetics.yml and quests.yml. When the bundled file carries a higher "content-version" than the
 * admin's file, entries that are MISSING are added (existing ones are never touched) and the version is recorded, so an
 * entry an admin deletes later is not brought back. Fresh installs already match and are skipped.
 */
final class ContentUpdate {
    private ContentUpdate() {}

    static void run(AetherFFA plugin) {
        merge(plugin, Cfg.COSMETICS, plugin.cfg().cosmetics(), "kill-effects", "death-messages");
        upgradeStreaks(plugin);
        merge(plugin, Cfg.STREAKS, plugin.cfg().streaks(), "milestones");
        merge(plugin, Cfg.QUESTS, plugin.cfg().quests(), "pool");
        migrateCurrencyColour(plugin);
        Cosmetics.audit(plugin);
    }

    /**
     * One-time (content-version 2): old killstreaks.yml files had broadcast off for the small milestones, so only the
     * killer ever saw them. Existing milestones are switched to broadcast to everyone and given a name; the new milestones
     * are then added by merge(). Runs once; after that every setting is the admin's own.
     */
    private static void upgradeStreaks(AetherFFA plugin) {
        YamlConfiguration disk = plugin.cfg().streaks();
        if (disk.getInt("content-version", 0) >= 2) return;
        ConfigurationSection ms = disk.getConfigurationSection("milestones");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                ConfigurationSection m = ms.getConfigurationSection(k);
                if (m == null) continue;
                m.set("broadcast", true);
                if (!m.isSet("name")) m.set("name", "Kill Streak");
            }
        }
        if (!disk.isSet("broadcast-format") || disk.getString("broadcast-format", "").contains("is on a <streak> kill streak!") && !disk.getString("broadcast-format", "").contains("<white>"))
            disk.set("broadcast-format", "<prefix><#4DA6FF><player></#4DA6FF> <gray>is on a <white><streak></white> kill streak!");
        plugin.getLogger().info("killstreaks.yml: milestone broadcasts now go to every player.");
    }

    private static final String OLD_GOLD = "(?i)#FFC83D", NEW_PURPLE = "#B57BFF";

    /**
     * One-time: the currency colour changed from gold to purple. Existing servers have the old gold saved in their own
     * gui.yml / messages.yml (those files are never overwritten by updates), so swap it there once. After the flag is set,
     * any colour an admin picks later is left alone.
     */
    private static void migrateCurrencyColour(AetherFFA plugin) {
        YamlConfiguration gui = plugin.cfg().gui();
        if (gui.getBoolean("currency-colour-migrated", false)) return;
        int changed = 0;
        for (String file : new String[]{Cfg.GUI, Cfg.MESSAGES}) {
            YamlConfiguration y = plugin.cfg().get(file);
            boolean touched = false;
            for (String key : y.getKeys(true)) {
                Object v = y.get(key);
                if (v instanceof String str && str.toUpperCase(java.util.Locale.ROOT).contains("#FFC83D")) {
                    y.set(key, str.replaceAll(OLD_GOLD, NEW_PURPLE));
                    touched = true; changed++;
                }
            }
            if (touched && !file.equals(Cfg.GUI)) {
                String err = plugin.cfg().save(file);
                if (err != null) plugin.getLogger().warning("Could not save " + file + " after recolouring: " + err);
            }
        }
        gui.set("currency-colour-migrated", true);
        String err = plugin.cfg().save(Cfg.GUI);
        if (err != null) plugin.getLogger().warning("Could not save gui.yml after recolouring: " + err);
        else if (changed > 0) plugin.getLogger().info("Currency colour updated from gold to purple in " + changed + " place(s) (gui.yml / messages.yml).");
    }

    private static void merge(AetherFFA plugin, String file, YamlConfiguration disk, String... sections) {
        try (InputStream in = plugin.getResource(file)) {
            if (in == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            int want = bundled.getInt("content-version", 0);
            if (disk.getInt("content-version", 0) >= want) return;
            int added = 0;
            for (String sec : sections) {
                ConfigurationSection src = bundled.getConfigurationSection(sec);
                if (src == null) continue;
                for (String id : src.getKeys(false)) {
                    ConfigurationSection entry = src.getConfigurationSection(id);
                    if (entry == null || disk.contains(sec + "." + id)) continue;
                    for (Map.Entry<String, Object> e : entry.getValues(true).entrySet())
                        if (!(e.getValue() instanceof ConfigurationSection)) disk.set(sec + "." + id + "." + e.getKey(), e.getValue());
                    added++;
                }
            }
            disk.set("content-version", want);
            String err = plugin.cfg().save(file);
            if (err != null) plugin.getLogger().warning("Could not save " + file + " after adding new content: " + err);
            else if (added > 0) plugin.getLogger().info("Added " + added + " new entries to " + file + ".");
        } catch (Exception ex) {
            plugin.getLogger().warning("Content update for " + file + " failed: " + ex);
        }
    }
}
