package net.aethermc.ffa;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made kits. Installed once on a fresh server (no kits yet) and on demand with /kit premade, which only ever adds
 * kits that are missing - it never overwrites or resurrects anything an admin edited or deleted afterwards.
 * Everything is edited like any other kit (/kit edit, the Kit Management GUI or kits.yml).
 */
final class PremadeKits {
    private PremadeKits() {}

    private record Def(String id, String name, Material icon, long price, long cooldown, List<String> description,
                       List<ItemStack> items, ItemStack[] armor, List<String> effects) { }

    /** Item with optional enchantments given as (Enchantment, Integer) pairs. */
    private static ItemStack it(Material m, int amount, Object... ench) {
        ItemStack s = new ItemStack(m, amount);
        for (int i = 0; i + 1 < ench.length; i += 2) s.addUnsafeEnchantment((Enchantment) ench[i], (Integer) ench[i + 1]);
        return s;
    }

    /** helmet, chestplate, leggings, boots, offhand (null = empty). */
    private static ItemStack[] armor(ItemStack h, ItemStack c, ItemStack l, ItemStack b, ItemStack off) { return new ItemStack[]{h, c, l, b, off}; }

    /** Full armor set of one material; every piece gets the same enchantments (helmet/boots may get extras). */
    private static ItemStack[] set(Material h, Material c, Material l, Material b, ItemStack off, Object... ench) {
        return armor(it(h, 1, ench), it(c, 1, ench), it(l, 1, ench), it(b, 1, ench), off);
    }

    private static final Material[] NETHERITE = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
    private static final Material[] DIAMOND = {Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS};

    private static String tag(String name) { return "<#B57BFF>" + name; }

    /*
     * Prices follow the kit list on kits.unstable.sh. New players start with 500 Aether and a valid kill pays 20, so these
     * are a few hours of play each. Contents are our own interpretation (the site does not publish item lists): edit them
     * with /kit edit <name> or replace them by capturing your inventory with /kit create.
     */
    private static List<Def> defs() {
        List<Def> d = new ArrayList<>();
        // ---------------- Tier 0-3: the classic six ----------------
        // ---------------- Unstable line: diamond -> netherite -> maxed ----------------
        // ---------------- Unstable SMP kits (names/prices from kits.unstable.sh; item lists are our interpretation) ----------------
        d.add(new Def("clownpierce", tag("ClownPierce"), Material.COBWEB, 5500, 2400,
                List.of("Chaos kit: trap, launch and confuse.", "Wind charges, cobwebs, 12 pearls and a totem."),
                List.of(it(Material.NETHERITE_SWORD, 1, Enchantment.SHARPNESS, 4, Enchantment.KNOCKBACK, 2, Enchantment.FIRE_ASPECT, 1, Enchantment.UNBREAKING, 3),
                        it(Material.WIND_CHARGE, 24), it(Material.COBWEB, 16), it(Material.ENDER_PEARL, 12),
                        it(Material.GOLDEN_APPLE, 8), it(Material.COOKED_BEEF, 16)),
                armor(it(NETHERITE[0], 1, Enchantment.PROTECTION, 3, Enchantment.UNBREAKING, 3), it(NETHERITE[1], 1, Enchantment.PROTECTION, 3, Enchantment.UNBREAKING, 3),
                        it(NETHERITE[2], 1, Enchantment.PROTECTION, 3, Enchantment.UNBREAKING, 3), it(NETHERITE[3], 1, Enchantment.PROTECTION, 3, Enchantment.FEATHER_FALLING, 4, Enchantment.UNBREAKING, 3),
                        it(Material.TOTEM_OF_UNDYING, 1)),
                List.of("SPEED:1:60")));
        d.add(new Def("parrotx2", tag("PARROTX2"), Material.FIREWORK_ROCKET, 3250, 1800,
                List.of("Aerial kit: elytra, rockets and ranged damage.", "Dive in with fireworks, finish with Power V."),
                List.of(it(Material.NETHERITE_SWORD, 1, Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 3),
                        it(Material.BOW, 1, Enchantment.POWER, 5, Enchantment.PUNCH, 1, Enchantment.UNBREAKING, 3), it(Material.ARROW, 64),
                        it(Material.CROSSBOW, 1, Enchantment.QUICK_CHARGE, 3, Enchantment.MULTISHOT, 1, Enchantment.UNBREAKING, 3),
                        it(Material.FIREWORK_ROCKET, 32), it(Material.GOLDEN_APPLE, 6), it(Material.COOKED_BEEF, 16)),
                armor(it(NETHERITE[0], 1, Enchantment.PROTECTION, 3, Enchantment.UNBREAKING, 3), it(Material.ELYTRA, 1, Enchantment.UNBREAKING, 3),
                        it(NETHERITE[2], 1, Enchantment.PROTECTION, 3, Enchantment.UNBREAKING, 3), it(NETHERITE[3], 1, Enchantment.PROTECTION, 3, Enchantment.FEATHER_FALLING, 4, Enchantment.UNBREAKING, 3), null),
                List.of("SPEED:0:30")));
        d.add(new Def("wemmbu", tag("Wemmbu (Invis Knight)"), Material.NETHERITE_AXE, 11000, 3000,
                List.of("Frontline bruiser: hard to kill, hits hard.", "Protection IV netherite, Sharpness V axe, shield and totem."),
                List.of(it(Material.NETHERITE_AXE, 1, Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 4, Enchantment.UNBREAKING, 3),
                        it(Material.NETHERITE_SWORD, 1, Enchantment.SHARPNESS, 4, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3),
                        it(Material.SHIELD, 1, Enchantment.UNBREAKING, 3), it(Material.ENDER_PEARL, 4),
                        it(Material.GOLDEN_APPLE, 12), it(Material.COOKED_BEEF, 16)),
                armor(it(NETHERITE[0], 1, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3), it(NETHERITE[1], 1, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.THORNS, 3),
                        it(NETHERITE[2], 1, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3), it(NETHERITE[3], 1, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.FEATHER_FALLING, 4),
                        it(Material.TOTEM_OF_UNDYING, 1)),
                List.of("ABSORPTION:2:60", "REGENERATION:0:10")));
        return d;
    }

    /** Adds every premade kit that does not exist yet. Returns how many were added, or -1 if kits.yml could not be saved. */
    static int installMissing(AetherFFA plugin) {
        YamlConfiguration c = plugin.cfg().kits();
        String[] keys = {"helmet", "chestplate", "leggings", "boots", "offhand"};
        int added = 0;
        for (Def k : defs()) {
            String b = "kits." + k.id();
            if (c.isConfigurationSection(b)) continue;
            c.set(b + ".enabled", true);
            c.set(b + ".display-name", k.name());
            c.set(b + ".icon", k.icon().name());
            c.set(b + ".description", k.description());
            c.set(b + ".price", k.price());
            c.set(b + ".permission", "");
            c.set(b + ".unlock", "PERMANENT");
            c.set(b + ".timed-hours", 24);
            c.set(b + ".cooldown-seconds", k.cooldown());
            c.set(b + ".worlds", new ArrayList<String>());
            c.set(b + ".clear-inventory", false);
            c.set(b + ".contents.items", k.items());
            for (int i = 0; i < 5; i++) c.set(b + ".contents.armor." + keys[i], k.armor()[i]);
            c.set(b + ".contents.effects", k.effects());
            added++;
        }
        if (added == 0) return 0;
        String err = plugin.cfg().save(Cfg.KITS);
        if (err != null) { plugin.getLogger().warning("Could not save premade kits: " + err); return -1; }
        return added;
    }

    /** First start only: if the server has no kits and premade kits were never offered, install them. */
    static void installIfFresh(AetherFFA plugin) {
        YamlConfiguration c = plugin.cfg().kits();
        if (c.getBoolean("settings.premade-installed", false)) return;
        boolean hasKits = c.getConfigurationSection("kits") != null && !c.getConfigurationSection("kits").getKeys(false).isEmpty();
        int n = hasKits ? 0 : installMissing(plugin);
        c.set("settings.premade-installed", true);
        plugin.cfg().save(Cfg.KITS);
        if (n > 0) plugin.getLogger().info("Installed " + n + " premade kits (see kits.yml, edit with /kit edit <name>).");
    }
}
