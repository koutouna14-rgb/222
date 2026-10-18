package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Kit definitions (kits.yml), ownership, cooldowns, purchases and safe delivery. */
public final class KitManager {
    public enum State { AVAILABLE, COOLDOWN, LOCKED_PRICE, LOCKED_PERMISSION, UNAVAILABLE_WORLD }

    private static final String[] ARMOR = {"helmet", "chestplate", "leggings", "boots"};
    private final AetherFFA plugin;

    public KitManager(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().kits(); }
    private String p(String id) { return "kits." + id; }

    public List<String> ids() { ConfigurationSection s = c().getConfigurationSection("kits"); return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false)); }
    public boolean exists(String id) { return id != null && c().isConfigurationSection(p(id)); }
    public String display(String id) { return c().getString(p(id) + ".display-name", id); }
    public Material icon(String id) { return Items.mat(c().getString(p(id) + ".icon"), Material.CHEST); }
    public List<String> description(String id) { return c().getStringList(p(id) + ".description"); }
    public long price(String id) { return Math.max(0, c().getLong(p(id) + ".price", 0)); }
    public String permission(String id) { return c().getString(p(id) + ".permission", ""); }
    public long cooldown(String id) { return Math.max(0, c().getLong(p(id) + ".cooldown-seconds", 0)); }
    public boolean timed(String id) { return c().getString(p(id) + ".unlock", "PERMANENT").equalsIgnoreCase("TIMED"); }
    public long timedHours(String id) { return Math.max(1, c().getLong(p(id) + ".timed-hours", 24)); }
    public boolean enabled(String id) { return c().getBoolean(p(id) + ".enabled", true); }
    public int maxLevel(String id) {
        ConfigurationSection s = c().getConfigurationSection(p(id) + ".upgrades");
        int max = 1;
        if (s != null) for (String k : s.getKeys(false)) try { max = Math.max(max, Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
        return max;
    }
    public long upgradePrice(String id, int level) { return Math.max(0, c().getLong(p(id) + ".upgrades." + level + ".price", 0)); }

    public boolean worldAllowed(String id, String world) {
        List<String> w = c().getStringList(p(id) + ".worlds");
        return w.isEmpty() || w.stream().anyMatch(x -> x.equalsIgnoreCase(world));
    }

    // ---- ownership ----

    /** 0 = permanent, -1 = not unlocked, otherwise epoch-millis expiry. */
    public long unlockExpiry(Player pl, String id) {
        PlayerData d = plugin.store().get(pl);
        if (d == null) return -1;
        String v = d.kv.get("kit.u." + id);
        if (v == null) return -1;
        try {
            long exp = Long.parseLong(v);
            if (exp > 0 && exp < System.currentTimeMillis()) return -1;
            return exp;
        } catch (NumberFormatException e) { return -1; }
    }

    public boolean owns(Player pl, String id) {
        String perm = permission(id);
        if (!perm.isEmpty() && pl.hasPermission(perm)) return true;
        if (price(id) <= 0 && perm.isEmpty()) return true;
        return unlockExpiry(pl, id) != -1;
    }

    public int level(Player pl, String id) {
        PlayerData d = plugin.store().get(pl);
        long l = d == null ? 1 : d.getLong("kit.lvl." + id, 1);
        return (int) Math.max(1, Math.min(l, maxLevel(id)));
    }

    public long cooldownLeftSeconds(Player pl, String id) {
        PlayerData d = plugin.store().get(pl);
        if (d == null) return 0;
        long until = d.getLong("kit.cd." + id, 0);
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    public State state(Player pl, String id) {
        if (!worldAllowed(id, pl.getWorld().getName()) || !plugin.worlds().enabled(pl.getWorld(), Feature.KITS)) return State.UNAVAILABLE_WORLD;
        if (!owns(pl, id)) return price(id) > 0 ? State.LOCKED_PRICE : State.LOCKED_PERMISSION;
        if (cooldownLeftSeconds(pl, id) > 0) return State.COOLDOWN;
        return State.AVAILABLE;
    }

    // ---- content ----

    private String contentPath(String id, int level) {
        if (level > 1 && c().isConfigurationSection(p(id) + ".upgrades." + level)) return p(id) + ".upgrades." + level;
        return p(id) + ".contents";
    }

    public static boolean validItem(ItemStack it, Set<Material> banned) {
        if (it == null || it.getType().isAir() || !it.getType().isItem()) return false;
        if (banned.contains(it.getType())) return false;
        return it.getAmount() >= 1 && it.getAmount() <= it.getMaxStackSize();
    }

    private Set<Material> banned() {
        Set<Material> out = new HashSet<>();
        for (String s : plugin.cfg().kits().getStringList("settings.banned-materials")) { Material m = Material.matchMaterial(s); if (m != null) out.add(m); }
        return out;
    }

    public List<ItemStack> items(String id, int level) {
        List<ItemStack> out = new ArrayList<>();
        Set<Material> ban = banned();
        for (Object o : c().getList(contentPath(id, level) + ".items", List.of())) if (o instanceof ItemStack it && validItem(it, ban)) out.add(it.clone());
        return out;
    }

    /** Armor in order helmet, chestplate, leggings, boots, offhand (null where unset). */
    public ItemStack[] armor(String id, int level) {
        ItemStack[] out = new ItemStack[5];
        Set<Material> ban = banned();
        String base = contentPath(id, level) + ".armor.";
        String[] keys = {"helmet", "chestplate", "leggings", "boots", "offhand"};
        for (int i = 0; i < 5; i++) {
            ItemStack it = c().getItemStack(base + keys[i]);
            if (it != null && validItem(it, ban)) out[i] = it.clone();
        }
        return out;
    }

    public List<String> effects(String id, int level) { return c().getStringList(contentPath(id, level) + ".effects"); }

    /** All displayable stacks for previews (armor first, then items). */
    public List<ItemStack> previewStacks(String id, int level) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack a : armor(id, level)) if (a != null) out.add(a);
        out.addAll(items(id, level));
        return out;
    }

    // ---- delivery ----

    private boolean deliver(Player target, String id, int level) {
        PlayerInventory pi = target.getInventory();
        boolean clear = c().getBoolean(p(id) + ".clear-inventory", false) && c().getBoolean("settings.allow-clear-inventory", false);
        List<ItemStack> toInv = items(id, level);
        ItemStack[] armor = armor(id, level);
        ItemStack[] current = {pi.getHelmet(), pi.getChestplate(), pi.getLeggings(), pi.getBoots()};
        ItemStack[] equip = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            if (armor[i] == null) continue;
            if (clear || current[i] == null || current[i].getType().isAir()) equip[i] = armor[i]; else toInv.add(armor[i]);
        }
        boolean offhandFree = clear || pi.getItemInOffHand().getType().isAir();
        ItemStack offhand = null;
        if (armor[4] != null) { if (offhandFree) offhand = armor[4]; else toInv.add(armor[4]); }
        Inventory sim = Bukkit.createInventory(null, 36);
        if (!clear) {
            ItemStack[] storage = pi.getStorageContents();
            for (int i = 0; i < storage.length && i < 36; i++) if (storage[i] != null) sim.setItem(i, storage[i].clone());
        }
        for (ItemStack it : toInv) if (!sim.addItem(it.clone()).isEmpty()) return false;     // not enough room: nothing is given
        if (clear) { pi.clear(); }
        for (ItemStack it : toInv) pi.addItem(it.clone());
        if (equip[0] != null) pi.setHelmet(equip[0].clone());
        if (equip[1] != null) pi.setChestplate(equip[1].clone());
        if (equip[2] != null) pi.setLeggings(equip[2].clone());
        if (equip[3] != null) pi.setBoots(equip[3].clone());
        if (offhand != null) pi.setItemInOffHand(offhand.clone());
        for (String e : effects(id, level)) applyEffect(target, e);
        return true;
    }

    @SuppressWarnings("deprecation")
    private void applyEffect(Player p, String spec) {
        String[] parts = spec.split(":");
        if (parts.length < 3) return;
        PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase(Locale.ROOT));
        if (type == null) return;
        try {
            int amp = Math.max(0, Math.min(10, Integer.parseInt(parts[1].trim())));
            int secs = Math.max(1, Math.min(3600, Integer.parseInt(parts[2].trim())));
            p.addPotionEffect(new PotionEffect(type, secs * 20, amp));
        } catch (NumberFormatException ignored) { }
    }

    /** Player claims a kit they own. */
    public boolean claim(Player pl, String id) {
        PlayerData d = plugin.store().get(pl);
        if (d == null || !exists(id) || !enabled(id)) { plugin.msg().send(pl, "kits.unknown"); return false; }
        if (!plugin.worlds().enabled(pl.getWorld(), Feature.KITS) || !worldAllowed(id, pl.getWorld().getName())) { plugin.msg().send(pl, "kits.unavailable-world"); return false; }
        if (plugin.cfg().kits().getBoolean("settings.block-while-combat-tagged", true) && plugin.combat().isTagged(pl.getUniqueId())) { plugin.msg().send(pl, "kits.in-combat"); return false; }
        if (!owns(pl, id)) { plugin.msg().send(pl, "kits.locked"); return false; }
        long left = cooldownLeftSeconds(pl, id);
        if (left > 0) { plugin.msg().send(pl, "kits.cooldown", "time", Amounts.duration(left)); return false; }
        if (!deliver(pl, id, level(pl, id))) { plugin.msg().send(pl, "kits.no-space"); return false; }
        long cd = cooldown(id);
        if (cd > 0 && !pl.hasPermission("aetherffa.bypass.kitcooldown")) d.setLong("kit.cd." + id, System.currentTimeMillis() + cd * 1000L);
        plugin.quests().progress(pl, "KIT_CLAIMS", 1);
        plugin.msg().send(pl, "kits.claimed", "kit", Msg.plain(plugin.msg().mm(display(id))));
        return true;
    }

    /** Staff delivery: ignores ownership, cooldown and world rules, but still refuses when there is no room. */
    public boolean give(Player target, String id) {
        if (!exists(id)) return false;
        return deliver(target, id, level(target, id));
    }

    public boolean purchase(Player pl, String id) {
        PlayerData d = plugin.store().get(pl);
        if (d == null || !exists(id)) return false;
        if (owns(pl, id)) { plugin.msg().send(pl, "kits.already-owned"); return false; }
        if (price(id) <= 0) { plugin.msg().send(pl, "kits.locked"); return false; }
        long expiry = timed(id) ? System.currentTimeMillis() + timedHours(id) * 3_600_000L : 0L;
        Economy.Result r = plugin.economy().withdraw(pl.getUniqueId(), price(id), "kit:" + id, null, () -> d.kv.put("kit.u." + id, Long.toString(expiry)));
        if (r == Economy.Result.INSUFFICIENT) { plugin.msg().send(pl, "economy.insufficient"); return false; }
        if (r != Economy.Result.OK) { plugin.msg().send(pl, "general.error"); return false; }
        plugin.msg().send(pl, "kits.purchased", "kit", Msg.plain(plugin.msg().mm(display(id))), "price", plugin.economy().fmt(price(id)));
        return true;
    }

    public boolean upgrade(Player pl, String id) {
        PlayerData d = plugin.store().get(pl);
        if (d == null || !exists(id) || !owns(pl, id)) return false;
        int cur = level(pl, id);
        if (cur >= maxLevel(id)) { plugin.msg().send(pl, "kits.max-level"); return false; }
        int next = cur + 1;
        Economy.Result r = plugin.economy().withdraw(pl.getUniqueId(), upgradePrice(id, next), "kit-upgrade:" + id + ":" + next, null, () -> d.setLong("kit.lvl." + id, next));
        if (r == Economy.Result.INSUFFICIENT) { plugin.msg().send(pl, "economy.insufficient"); return false; }
        if (r != Economy.Result.OK) { plugin.msg().send(pl, "general.error"); return false; }
        plugin.msg().send(pl, "kits.upgraded", "kit", Msg.plain(plugin.msg().mm(display(id))), "level", next);
        return true;
    }

    // ---- admin management ----

    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9_\\-]{2,24}"); }

    public boolean create(Player admin, String id) {
        id = id.toLowerCase(Locale.ROOT);
        if (!validId(id)) { plugin.msg().send(admin, "kits.invalid-name"); return false; }
        if (exists(id)) { plugin.msg().send(admin, "kits.exists"); return false; }
        c().set(p(id) + ".display-name", "<#4DA6FF>" + id);
        c().set(p(id) + ".icon", "CHEST");
        c().set(p(id) + ".price", 0);
        c().set(p(id) + ".permission", "");
        c().set(p(id) + ".unlock", "PERMANENT");
        c().set(p(id) + ".timed-hours", 24);
        c().set(p(id) + ".cooldown-seconds", 300);
        c().set(p(id) + ".worlds", new ArrayList<String>());
        c().set(p(id) + ".enabled", true);
        return captureContents(admin, id);
    }

    /** Replaces a kit's level-1 contents with the admin's current inventory (validated). */
    public boolean captureContents(Player admin, String id) {
        if (!exists(id)) return false;
        Set<Material> ban = banned();
        PlayerInventory pi = admin.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : pi.getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (!validItem(it, ban)) { plugin.msg().send(admin, "kits.invalid-item", "item", it.getType().name()); return false; }
            items.add(it.clone());
        }
        ItemStack[] arm = {pi.getHelmet(), pi.getChestplate(), pi.getLeggings(), pi.getBoots(), pi.getItemInOffHand()};
        String[] keys = {"helmet", "chestplate", "leggings", "boots", "offhand"};
        for (ItemStack a : arm) if (a != null && !a.getType().isAir() && !validItem(a, ban)) { plugin.msg().send(admin, "kits.invalid-item", "item", a.getType().name()); return false; }
        String base = p(id) + ".contents";
        c().set(base + ".items", items);
        for (int i = 0; i < 5; i++) c().set(base + ".armor." + keys[i], arm[i] == null || arm[i].getType().isAir() ? null : arm[i].clone());
        String err = plugin.cfg().save(Cfg.KITS);
        if (err != null) { plugin.msg().send(admin, "general.save-failed", "error", err); return false; }
        plugin.msg().send(admin, "kits.saved", "kit", id);
        return true;
    }

    public boolean delete(String id) {
        if (!exists(id)) return false;
        c().set(p(id), null);
        return plugin.cfg().save(Cfg.KITS) == null;
    }
}
