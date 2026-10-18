package net.aethermc.ffa;

import org.bukkit.World;

/**
 * A resolved zone shape, independent of where it came from (WorldEdit selection or the built-in pos1/pos2 corners).
 * Holds no WorldEdit types so {@link ZoneManager} never loads WorldEdit classes.
 */
record ZoneSel(String err, World world, boolean circle, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
               double cx, double cz, double radius) {

    static ZoneSel error(String messageKey) {
        return new ZoneSel(messageKey, null, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    static ZoneSel box(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new ZoneSel(null, w, false, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), 0, 0, 0);
    }

    static ZoneSel circle(World w, double cx, double cz, double radius, int minY, int maxY) {
        return new ZoneSel(null, w, true, 0, Math.min(minY, maxY), 0, 0, Math.max(minY, maxY), 0, cx, cz, radius);
    }
}
