package net.aethermc.ffa;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Read-only WorldEdit bridge: turns a player's current WorldEdit selection into a capture-zone shape.
 * Only instantiated when WorldEdit (or FastAsyncWorldEdit) is enabled. It never edits blocks or selections.
 */
public final class WeHook {

    /** Cuboid selections become CUBOID zones, circular cylinder selections (//sel cyl) become CYLINDER zones. */
    ZoneSel selection(Player p) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(p));
        if (session == null) return ZoneSel.error("zones.need-selection");
        com.sk89q.worldedit.world.World weWorld = session.getSelectionWorld();
        if (weWorld == null) return ZoneSel.error("zones.need-selection");
        Region r;
        try { r = session.getSelection(weWorld); }
        catch (IncompleteRegionException e) { return ZoneSel.error("zones.need-selection"); }
        World world = BukkitAdapter.adapt(weWorld);
        if (world == null) return ZoneSel.error("zones.need-selection");

        if (r instanceof CylinderRegion cyl) {
            double rx = cyl.getRadius().getX(), rz = cyl.getRadius().getZ();
            if (Math.abs(rx - rz) > 0.01) return ZoneSel.error("zones.we-elliptical");
            // WorldEdit's radius counts block centres; +0.5 makes the zone cover the outermost blocks too.
            return ZoneSel.circle(world, cyl.getCenter().getX(), cyl.getCenter().getZ(), rx + 0.5, cyl.getMinimumY(), cyl.getMaximumY());
        }
        if (r instanceof CuboidRegion) {
            BlockVector3 a = r.getMinimumPoint(), b = r.getMaximumPoint();
            return ZoneSel.box(world, a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
        }
        return ZoneSel.error("zones.we-unsupported");
    }
}
