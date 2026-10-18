package net.aethermc.ffa;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-world configuration (worlds.yml). AetherFFA never moves players between worlds; it only decides whether its own
 * features apply in the world a player is currently in. Safe zones are WorldGuard regions listed per world.
 */
public final class Worlds {
    private final AetherFFA plugin;

    public Worlds(AetherFFA plugin) { this.plugin = plugin; }

    private ConfigurationSection sec(String world) {
        return plugin.cfg().worlds().getConfigurationSection("worlds." + world);
    }

    public boolean isConfigured(String world) { return sec(world) != null; }

    public List<String> configured() {
        ConfigurationSection s = plugin.cfg().worlds().getConfigurationSection("worlds");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    /** True if the world is configured, enabled, and the feature is on there. Unlisted worlds follow unlisted-worlds-enabled. */
    public boolean enabled(World w, Feature f) { return w != null && enabled(w.getName(), f); }

    public boolean enabled(String world, Feature f) {
        ConfigurationSection s = sec(world);
        if (s == null) return plugin.cfg().worlds().getBoolean("unlisted-worlds-enabled", false);
        if (!s.getBoolean("enabled", true)) return false;
        return s.getBoolean("features." + f.key, true);
    }

    public boolean enabled(Player p, Feature f) { return enabled(p.getWorld(), f); }

    public List<String> safeRegions(String world) {
        ConfigurationSection s = sec(world);
        return s == null ? List.of() : s.getStringList("safe-regions");
    }

    public boolean safeZoneCaptureRewards(String world) {
        ConfigurationSection s = sec(world);
        return s != null && s.getBoolean("safe-zone-capture-rewards", false);
    }

    /** True if the location lies inside one of the configured safe-zone WorldGuard regions of its world. */
    public boolean inSafeZone(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        WgHook wg = plugin.wg();
        if (wg == null) return false;
        List<String> regions = safeRegions(loc.getWorld().getName());
        if (regions.isEmpty()) return false;
        return wg.inAnyRegion(loc, regions);
    }

    public boolean inSafeZone(Player p) { return inSafeZone(p.getLocation()); }

    public Set<String> featureKeys() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Feature f : Feature.values()) out.add(f.key);
        return out;
    }
}
