package net.aethermc.ffa;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only WorldGuard bridge. Only ever instantiated when WorldGuard is enabled, so the WorldGuard classes are never
 * loaded otherwise. This class never creates, edits, or deletes regions and never touches flags.
 */
public final class WgHook {
    private final RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();

    public boolean inAnyRegion(Location loc, Collection<String> ids) {
        Set<String> wanted = ids.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        ApplicableRegionSet set = container.createQuery().getApplicableRegions(BukkitAdapter.adapt(loc));
        for (ProtectedRegion r : set.getRegions()) if (wanted.contains(r.getId().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    public boolean regionExists(World world, String id) {
        RegionManager rm = container.get(BukkitAdapter.adapt(world));
        return rm != null && rm.hasRegion(id);
    }

    public int regionCount(World world) {
        RegionManager rm = container.get(BukkitAdapter.adapt(world));
        return rm == null ? 0 : rm.size();
    }

    @SuppressWarnings("deprecation")
    public String version() {
        org.bukkit.plugin.Plugin wg = org.bukkit.Bukkit.getPluginManager().getPlugin("WorldGuard");
        return wg == null ? "?" : wg.getDescription().getVersion();
    }
}
