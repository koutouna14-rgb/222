package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Small helpers for building consistent GUI icons. */
public final class Items {
    private Items() {}

    public static Material mat(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.trim());
        return m == null || !m.isItem() || m.isAir() ? fallback : m;
    }

    /** The Aether currency item (economy.yml currency.icon, default amethyst shard). */
    public static Material currency() {
        return mat(AetherFFA.get().cfg().eco().getString("currency.icon", "AMETHYST_SHARD"), Material.AMETHYST_SHARD);
    }

    /** Icon for a quest objective type (see quests.yml). Unknown types get a writable book. */
    public static Material questIcon(String type) {
        if (type == null) return Material.WRITABLE_BOOK;
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "KILLS" -> Material.DIAMOND_SWORD;
            case "STREAK" -> Material.BLAZE_POWDER;
            case "CAPTURE_SECONDS" -> Material.BEACON;
            case "CAPTURE_PAYOUTS" -> currency();
            case "BOUNTY_CLAIMS" -> Material.TARGET;
            case "KIT_CLAIMS" -> Material.CHEST;
            default -> Material.WRITABLE_BOOK;
        };
    }

    /** Icon for a kill effect, picked from its particle so every effect looks different in menus. */
    public static Material particleIcon(String particle) {
        if (particle == null) return Material.FIREWORK_STAR;
        return switch (particle.toUpperCase(java.util.Locale.ROOT)) {
            case "FLAME", "SMALL_FLAME" -> Material.BLAZE_POWDER;
            case "SOUL_FIRE_FLAME" -> Material.SOUL_LANTERN;
            case "CLOUD" -> Material.WHITE_WOOL;
            case "END_ROD" -> Material.END_ROD;
            case "HEART" -> Material.RED_DYE;
            case "CRIT", "DAMAGE_INDICATOR" -> Material.IRON_SWORD;
            case "HAPPY_VILLAGER" -> Material.EMERALD;
            case "ANGRY_VILLAGER" -> Material.REDSTONE;
            case "LARGE_SMOKE", "SMOKE", "CAMPFIRE_COSY_SMOKE" -> Material.COAL;
            case "NOTE" -> Material.NOTE_BLOCK;
            case "ENCHANTED_HIT", "ENCHANT" -> Material.ENCHANTED_BOOK;
            case "WITCH" -> Material.FERMENTED_SPIDER_EYE;
            case "SOUL", "SCULK_SOUL" -> Material.SOUL_SAND;
            case "SNOWFLAKE" -> Material.SNOWBALL;
            case "CHERRY_LEAVES" -> Material.CHERRY_SAPLING;
            case "GLOW", "GLOW_SQUID_INK" -> Material.GLOW_INK_SAC;
            case "LAVA", "DRIPPING_LAVA" -> Material.LAVA_BUCKET;
            case "FIREWORK" -> Material.FIREWORK_ROCKET;
            case "ELECTRIC_SPARK" -> Material.LIGHTNING_ROD;
            case "PORTAL", "REVERSE_PORTAL" -> Material.ENDER_PEARL;
            case "TOTEM_OF_UNDYING" -> Material.TOTEM_OF_UNDYING;
            case "EXPLOSION", "EXPLOSION_EMITTER" -> Material.TNT;
            case "SPORE_BLOSSOM_AIR" -> Material.SPORE_BLOSSOM;
            case "CRIMSON_SPORE" -> Material.CRIMSON_FUNGUS;
            case "WARPED_SPORE" -> Material.WARPED_FUNGUS;
            case "ASH", "WHITE_ASH" -> Material.GUNPOWDER;
            case "SONIC_BOOM" -> Material.ECHO_SHARD;
            case "BUBBLE_POP", "SPLASH" -> Material.HEART_OF_THE_SEA;
            case "WAX_ON", "WAX_OFF" -> Material.HONEYCOMB;
            case "SWEEP_ATTACK" -> Material.NETHERITE_SWORD;
            default -> Material.FIREWORK_STAR;
        };
    }

    /** Icon for a kill-streak milestone: bigger streak, bigger icon. */
    public static Material streakIcon(int streak) {
        if (streak >= 100) return Material.NETHER_STAR;
        if (streak >= 50) return Material.DRAGON_HEAD;
        if (streak >= 30) return Material.WITHER_SKELETON_SKULL;
        if (streak >= 20) return Material.NETHERITE_SWORD;
        if (streak >= 15) return Material.DIAMOND_SWORD;
        if (streak >= 10) return Material.TOTEM_OF_UNDYING;
        if (streak >= 7) return Material.BLAZE_ROD;
        if (streak >= 5) return Material.BLAZE_POWDER;
        return Material.IRON_SWORD;
    }

    /** Icon for an entry in the admin messages editor, by the file section it belongs to. */
    public static Material messageIcon(String key) {
        String top = key == null ? "" : key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        return switch (top) {
            case "economy" -> currency();
            case "kits" -> Material.CHEST;
            case "bounty" -> Material.TARGET;
            case "teams", "team" -> Material.WHITE_BANNER;
            case "quests" -> Material.CLOCK;
            case "cosmetics" -> Material.FIREWORK_STAR;
            case "zones", "capture" -> Material.BEACON;
            case "admin" -> Material.COMPARATOR;
            case "general" -> Material.PAPER;
            case "streaks", "killstreak" -> Material.BLAZE_POWDER;
            case "combat" -> Material.IRON_SWORD;
            default -> Material.WRITABLE_BOOK;
        };
    }

    /** Icons rotated for death messages that have no explicit icon, so a list never shows a wall of identical items. */
    private static final Material[] MESSAGE_ICONS = {
            Material.OAK_SIGN, Material.NAME_TAG, Material.PAPER, Material.FEATHER, Material.WRITABLE_BOOK, Material.SKELETON_SKULL,
            Material.GOLDEN_SWORD, Material.CROSSBOW, Material.TNT, Material.ENDER_EYE, Material.GOLDEN_HELMET, Material.AMETHYST_SHARD,
            Material.BELL, Material.LANTERN, Material.SHIELD, Material.TRIDENT};

    public static Material rotatingIcon(String id) {
        return MESSAGE_ICONS[Math.floorMod(id == null ? 0 : id.hashCode(), MESSAGE_ICONS.length)];
    }

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, name, List.of(lore));
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        AetherFFA pl = AetherFFA.get();
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.msg().item(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(pl.msg().item(s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack head(OfflinePlayer p, String name, List<String> lore) {
        AetherFFA pl = AetherFFA.get();
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(p);
        meta.displayName(pl.msg().item(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(pl.msg().item(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack filler() {
        YamlConfiguration g = AetherFFA.get().cfg().gui();
        return of(mat(g.getString("filler.material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE), " ");
    }

    public static ItemStack nav(String key, Material fallback, String defName) {
        YamlConfiguration g = AetherFFA.get().cfg().gui();
        return of(mat(g.getString("navigation." + key + ".material"), fallback), g.getString("navigation." + key + ".name", defName));
    }

    /** ON/OFF indicator line used by every boolean setting. */
    public static String onOff(boolean v) { return v ? "<green>ON" : "<red>OFF"; }

    public static String accent() { return AetherFFA.get().cfg().gui().getString("accent", "#4DA6FF"); }
    public static String gold() { return AetherFFA.get().cfg().gui().getString("currency-accent", "#B57BFF"); }
}
