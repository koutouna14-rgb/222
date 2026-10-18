package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

// Admin menus. Every button carries a permission that Menu re-checks on each click; destructive actions use ConfirmMenu.

/** Small menu of labelled shortcuts, used when one category has several sub-areas. */
final class HubMenu extends Menu {
    record Entry(Material icon, String name, String lore, String perm, Runnable action) { }
    private final String titleText; private final List<Entry> entries; private final Menu back;
    HubMenu(AetherFFA plugin, Player viewer, String titleText, Menu back, List<Entry> entries) { super(plugin, viewer); this.titleText = titleText; this.entries = entries; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>" + titleText); }
    @Override protected int rows() { return 3; }
    @Override protected String permission() { return "aetherffa.admin"; }
    @Override protected void build() {
        int[] slots = {10, 12, 14, 16, 11, 13, 15};
        for (int i = 0; i < entries.size() && i < slots.length; i++) {
            Entry e = entries.get(i);
            set(slots[i], Items.of(e.icon(), "<#4DA6FF>" + e.name(), "<gray>" + e.lore()), e.perm(), t -> e.action().run());
        }
        navBack(back);
        navHome();
    }
}

/** Lists the sub-sections under a config path; click to edit, right-click to delete, button to add. */
final class CollectionMenu extends Menu {
    String titleText, perm = "aetherffa.admin.settings", file, base, addPrompt = "Type an id for the new entry:";
    Function<String, List<Setting>> settingsFor = id -> List.of();
    Function<String, List<String>> loreFor = id -> List.of();
    Function<String, Material> iconFor = id -> Material.BOOK;
    Function<String, String> idCheck = id -> null;
    Function<String, Map<String, Object>> defaults = id -> Map.of();
    BiConsumer<String, ClickType> clickOverride;
    java.util.function.Consumer<String> customAdd;
    Predicate<String> deleter;
    Comparator<String> order = Comparator.naturalOrder();
    final Menu back;

    CollectionMenu(AetherFFA plugin, Player viewer, String titleText, String file, String base, Menu back) {
        super(plugin, viewer); this.titleText = titleText; this.file = file; this.base = base; this.back = back;
    }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>" + titleText); }
    @Override protected int rows() { return 6; }
    @Override protected String permission() { return perm; }

    List<String> ids() {
        ConfigurationSection s = plugin.cfg().get(file).getConfigurationSection(base);
        List<String> out = s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
        out.sort(order);
        return out;
    }

    @Override protected void build() {
        paged(ids(), id -> {
            List<String> lore = new ArrayList<>(loreFor.apply(id));
            lore.add(" ");
            lore.add("<dark_gray>Left: edit  Right: delete");
            return Items.of(iconFor.apply(id), "<white>" + id, lore);
        }, (id, type) -> {
            if (!viewer.hasPermission(perm)) return;
            if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
                new ConfirmMenu(plugin, viewer, "<red>Delete " + id + "?", List.of("<gray>This removes it from " + file + "."), perm, this, () -> {
                    boolean ok;
                    if (deleter != null) ok = deleter.test(id);
                    else { plugin.cfg().get(file).set(base + "." + id, null); ok = plugin.cfg().save(file) == null; }
                    plugin.applyConfig();
                    plugin.msg().send(viewer, ok ? "admin.deleted" : "general.error", "name", id);
                    new CollectionMenu(plugin, viewer, titleText, file, base, back).copyFrom(this).open();
                }).open();
                return;
            }
            if (clickOverride != null) clickOverride.accept(id, type);
            else new SettingsMenu(plugin, viewer, titleText + " / " + id, settingsFor.apply(id), this, perm).open();
        });
        set(navRow() + 4, Items.of(Material.LIME_DYE, "<green>Add new", "<gray>Type an id in chat."), perm, t ->
                plugin.prompts().ask(viewer, "<prefix><gray>" + addPrompt, text -> {
                    String id = text.trim();
                    String err = id.matches("[A-Za-z0-9_\\-]{1,24}") ? idCheck.apply(id) : "Use 1-24 letters, numbers, - or _";
                    if (err == null && ids().stream().anyMatch(x -> x.equalsIgnoreCase(id))) err = "That id already exists";
                    if (err != null) { plugin.msg().sendRaw(viewer, "<prefix><red>" + err); open(); return; }
                    if (customAdd != null) customAdd.accept(id);
                    else {
                        YamlConfiguration y = plugin.cfg().get(file);
                        for (var e : defaults.apply(id).entrySet()) y.set(base + "." + id + "." + e.getKey(), e.getValue());
                        plugin.cfg().save(file);
                        plugin.applyConfig();
                    }
                    open();
                }, this::open));
        navBack(back);
        navHome();
    }

    CollectionMenu copyFrom(CollectionMenu o) {
        perm = o.perm; addPrompt = o.addPrompt; settingsFor = o.settingsFor; loreFor = o.loreFor; iconFor = o.iconFor; idCheck = o.idCheck;
        defaults = o.defaults; clickOverride = o.clickOverride; customAdd = o.customAdd; deleter = o.deleter; order = o.order; return this;
    }
}

final class AdminDashboard extends Menu {
    private record Cat(String key, String name, Material icon, int slot) { }
    private static final List<Cat> CATS = List.of(
            new Cat("general", "General Settings", Material.COMPARATOR, 10), new Cat("worlds", "Worlds & Maps", Material.GRASS_BLOCK, 11),
            new Cat("economy", "Aether Economy", Items.currency(), 12), new Cat("kits", "Kit Management", Material.CHEST, 13),
            new Cat("zones", "Capture Zones", Material.BEACON, 14), new Cat("bounty", "Bounty System", Material.TARGET, 15),
            new Cat("team", "Team System", Material.WHITE_BANNER, 19), new Cat("streaks", "Kill Streaks", Material.BLAZE_POWDER, 20),
            new Cat("quests", "Daily Quests", Material.WRITABLE_BOOK, 21), new Cat("effects", "Kill Effects", Material.FIREWORK_STAR, 22),
            new Cat("deathmsgs", "Death Messages", Material.OAK_SIGN, 23), new Cat("combat", "Combat (JustCombat link)", Material.IRON_SWORD, 24),
            new Cat("antifarm", "Anti-Farming & Anti-Dupe", Material.BARRIER, 28),
            new Cat("boards", "Leaderboards", Material.NETHER_STAR, 29), new Cat("players", "Player Management", Material.PLAYER_HEAD, 30),
            new Cat("diag", "Database & Diagnostics", Material.REDSTONE_TORCH, 31), new Cat("messages", "Messages & GUI Customization", Material.NAME_TAG, 32));

    AdminDashboard(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("admin", "<dark_gray>AetherFFA <#4DA6FF>Admin"); }
    @Override protected int rows() { return 5; }
    @Override protected String permission() { return "aetherffa.admin"; }

    @Override protected void build() {
        AdminSession ses = AdminSession.of(viewer);
        for (Cat c : CATS) {
            Material m = Items.mat(plugin.cfg().gui().getString("dashboard." + c.key() + ".icon"), c.icon());
            String name = plugin.cfg().gui().getString("dashboard." + c.key() + ".name", c.name());
            set(c.slot(), Items.of(m, "<#4DA6FF>" + name, "<gray>Click to open"), "aetherffa.admin", t -> open(c.key()));
        }
        display(4, Items.of(Material.NETHER_STAR, "<#4DA6FF>AetherFFA " + plugin.version(),
                "<gray>Unsaved changes: " + (ses.pending() == 0 ? "<green>none" : "<yellow>" + ses.pending()),
                "<gray>WorldGuard: " + (plugin.wg() != null ? "<green>connected" : "<red>not found")));
        set(36, Items.of(Material.BARRIER, "<gray>Close"), t -> viewer.closeInventory());
        set(44, Items.of(Material.SUNFLOWER, "<yellow>Reload config files", "<gray>Reloads YAML only. Balances, teams,", "<gray>bounties and quests are untouched."), "aetherffa.admin.reload", t -> {
            List<String> problems = plugin.reloadAll();
            if (problems.isEmpty()) plugin.msg().send(viewer, "admin.reloaded");
            else for (String p : problems) plugin.msg().sendRaw(viewer, "<prefix><red>" + p.replace("<", "\\<"));
        });
    }

    private void open(String key) {
        Menu me = this;
        switch (key) {
            case "general" -> new SettingsMenu(plugin, viewer, "General Settings", SettingsCatalog.general(), me).open();
            case "worlds" -> worlds().open();
            case "economy" -> new HubMenu(plugin, viewer, "Aether Economy", me, List.of(
                    new HubMenu.Entry(Items.currency(), "Currency & rewards", "Limits, transfers, kill reward.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Aether Economy", SettingsCatalog.economy(), me).open()),
                    new HubMenu.Entry(Material.PLAYER_HEAD, "Player balances", "Adjust online players' balances.", "aetherffa.admin.players", () -> new PlayerAdminMenu(plugin, viewer, me).open()))).open();
            case "kits" -> new HubMenu(plugin, viewer, "Kit Management", me, List.of(
                    new HubMenu.Entry(Material.CHEST, "Kits", "Create, edit, delete kits.", "aetherffa.admin.kits", () -> kitList(me).open()),
                    new HubMenu.Entry(Material.COMPARATOR, "Kit rules", "Banned items, clear-inventory, combat rule.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Kit rules", SettingsCatalog.kitGlobal(), me).open()))).open();
            case "zones" -> new HubMenu(plugin, viewer, "Capture Zones", me, List.of(
                    new HubMenu.Entry(Material.BEACON, "Zones", "Create and edit zones.", "aetherffa.admin.zones", () -> zoneList(me).open()),
                    new HubMenu.Entry(Material.COMPARATOR, "Global & defaults", "AFK rules and default zone settings.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Zone defaults", SettingsCatalog.zoneGlobal(), me).open()))).open();
            case "bounty" -> new SettingsMenu(plugin, viewer, "Bounty System", SettingsCatalog.bounty(), me).open();
            case "team" -> new TeamAdminMenu(plugin, viewer, me).open();
            case "streaks" -> new HubMenu(plugin, viewer, "Kill Streaks", me, List.of(
                    new HubMenu.Entry(Material.COMPARATOR, "Settings", "Resets and shutdown bonus.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Kill Streaks", SettingsCatalog.streaks(), me).open()),
                    new HubMenu.Entry(Material.BLAZE_POWDER, "Milestones", "Rewards at specific streaks.", "aetherffa.admin.settings", () -> milestones(me).open()))).open();
            case "quests" -> new HubMenu(plugin, viewer, "Daily Quests", me, List.of(
                    new HubMenu.Entry(Material.CLOCK, "Schedule & selection", "Reset time, timezone, per-difficulty counts.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Daily Quests", SettingsCatalog.quests(), me).open()),
                    new HubMenu.Entry(Material.WRITABLE_BOOK, "Quest pool", "Objectives, amounts and rewards.", "aetherffa.admin.settings", () -> questPool(me).open()))).open();
            case "effects" -> new HubMenu(plugin, viewer, "Kill Effects", me, List.of(
                    new HubMenu.Entry(Material.COMPARATOR, "Cosmetics settings", "Master switch and particle cap.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Cosmetics", SettingsCatalog.cosmeticsGlobal(), me).open()),
                    new HubMenu.Entry(Material.FIREWORK_STAR, "Effects", "Particles, sounds, prices.", "aetherffa.admin.settings", () -> effects(me).open()))).open();
            case "deathmsgs" -> deathMessages(me).open();
            case "combat" -> new SettingsMenu(plugin, viewer, "Combat (JustCombat link)", SettingsCatalog.combat(), me).open();
            case "antifarm" -> new SettingsMenu(plugin, viewer, "Anti-Farming & Anti-Dupe", SettingsCatalog.antiFarm(), me).open();
            case "boards" -> new HubMenu(plugin, viewer, "Leaderboards", me, List.of(
                    new HubMenu.Entry(Material.COMPARATOR, "Refresh & size", "Interval and entries per board.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "Leaderboards", SettingsCatalog.boards(), me).open()),
                    new HubMenu.Entry(Material.NETHER_STAR, "Boards", "Titles, sort column, enable/disable.", "aetherffa.admin.settings", () -> boardCats(me).open()))).open();
            case "players" -> new PlayerAdminMenu(plugin, viewer, me).open();
            case "diag" -> new DiagMenu(plugin, viewer, me).open();
            case "messages" -> new HubMenu(plugin, viewer, "Messages & GUI", me, List.of(
                    new HubMenu.Entry(Material.NAME_TAG, "Messages", "Edit messages.yml entries.", "aetherffa.admin.messages", () -> new MessagesMenu(plugin, viewer, me).open()),
                    new HubMenu.Entry(Material.PAINTING, "GUI appearance", "Titles, colours, filler, navigation.", "aetherffa.admin.settings", () -> new SettingsMenu(plugin, viewer, "GUI Customization", SettingsCatalog.guiCustom(), me).open()))).open();
            default -> { }
        }
    }

    private CollectionMenu worlds() {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Worlds & Maps", Cfg.WORLDS, "worlds", this);
        m.settingsFor = SettingsCatalog::world;
        m.iconFor = w -> Bukkit.getWorld(w) != null ? Material.GRASS_BLOCK : Material.BARRIER;
        m.addPrompt = "Type the exact name of an existing, loaded world:";
        m.idCheck = w -> Bukkit.getWorld(w) == null ? "No loaded world with that name." : null;
        m.defaults = w -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("enabled", true);
            for (Feature f : Feature.values()) d.put("features." + f.key, true);
            d.put("safe-regions", new ArrayList<String>());
            d.put("safe-zone-capture-rewards", false);
            return d;
        };
        m.loreFor = w -> {
            List<String> l = new ArrayList<>();
            l.add(plugin.worlds().enabled(w, Feature.KILL_REWARDS) ? "<green>Active" : "<red>Disabled");
            var world = Bukkit.getWorld(w);
            l.add("<gray>Loaded: " + (world != null ? "<green>yes" : "<red>no"));
            l.add("<gray>WorldGuard: " + (plugin.wg() != null ? "<green>connected" : "<red>not found"));
            int on = 0; for (Feature f : Feature.values()) if (plugin.worlds().enabled(w, f)) on++;
            l.add("<gray>Features on: <white>" + on + "/" + Feature.values().length);
            List<String> regs = plugin.worlds().safeRegions(w);
            l.add("<gray>Safe regions: <white>" + (regs.isEmpty() ? "none" : regs.size()));
            if (plugin.wg() != null && world != null) for (String r : regs) { if (l.size() > 12) break; l.add("  <gray>" + r + ": " + (plugin.wg().regionExists(world, r) ? "<green>found" : "<red>missing")); }
            long zc = plugin.zones().zones().stream().filter(z -> z.world.equals(w)).count();
            l.add("<gray>Capture zones: <white>" + zc);
            return l;
        };
        return m;
    }

    private CollectionMenu kitList(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Kits", Cfg.KITS, "kits", back);
        m.perm = "aetherffa.admin.kits";
        m.iconFor = id -> plugin.kits().icon(id);
        m.loreFor = id -> List.of("<gray>Price: <white>" + plugin.kits().price(id), "<gray>Cooldown: <white>" + plugin.kits().cooldown(id) + "s", "<gray>Enabled: " + Items.onOff(plugin.kits().enabled(id)));
        m.addPrompt = "Type a kit id (a-z, 0-9, - _). Your CURRENT INVENTORY becomes its contents:";
        m.idCheck = id -> KitManager.validId(id) ? null : "Kit ids are lowercase a-z, 0-9, - or _ (2-24).";
        m.customAdd = id -> { plugin.kits().create(viewer, id.toLowerCase(Locale.ROOT)); };
        m.deleter = id -> plugin.kits().delete(id);
        m.clickOverride = (id, t) -> new KitEditMenu(plugin, viewer, id, m).open();
        return m;
    }

    private CollectionMenu zoneList(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Capture Zones", Cfg.ZONES, "zones", back);
        m.perm = "aetherffa.admin.zones";
        m.iconFor = id -> { var z = plugin.zones().get(id); return z != null && z.enabled ? Material.BEACON : Material.GLASS; };
        m.loreFor = id -> plugin.zones().info(id).stream().limit(5).toList();
        boolean we = plugin.zones().usesWorldEdit();
        m.addPrompt = we ? "Type a zone id. It is created from your WorldEdit selection (//wand, //pos1, //pos2, //sel cyl):"
                         : "Type a zone id. A 8-block-radius circle is created around YOU:";
        m.customAdd = id -> {
            String err = we ? plugin.zones().createFromSelection(viewer, id) : plugin.zones().createCylinder(viewer, id, 8, 2, 6);
            plugin.msg().send(viewer, err == null ? "zones.created" : err, "zone", id);
        };
        m.deleter = id -> plugin.zones().remove(id) == null;
        m.clickOverride = (id, t) -> new ZoneEditMenu(plugin, viewer, id, m).open();
        return m;
    }

    private CollectionMenu milestones(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Streak Milestones", Cfg.STREAKS, "milestones", back);
        m.order = Comparator.comparingInt(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return Integer.MAX_VALUE; } });
        m.iconFor = id -> { try { return Items.streakIcon(Integer.parseInt(id)); } catch (NumberFormatException e) { return Material.BLAZE_POWDER; } };
        m.loreFor = id -> List.of("<gray>Name: <white>" + plugin.cfg().streaks().getString("milestones." + id + ".name", "-"), "<gray>Reward: <white>" + plugin.cfg().streaks().getLong("milestones." + id + ".reward", 0),
                "<gray>Broadcast: " + Items.onOff(plugin.cfg().streaks().getBoolean("milestones." + id + ".broadcast", false)));
        m.addPrompt = "Type the streak number for the new milestone:";
        m.idCheck = id -> id.matches("\\d{1,4}") && Integer.parseInt(id) >= 2 ? null : "Enter a number from 2 to 9999.";
        m.defaults = id -> Map.of("name", "Kill Streak", "reward", 10, "broadcast", true, "message", "", "sound", "", "particle", "");
        m.settingsFor = id -> SettingsCatalog.milestone("milestones." + id);
        return m;
    }

    private CollectionMenu questPool(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Quest Pool", Cfg.QUESTS, "pool", back);
        m.iconFor = id -> Items.questIcon(plugin.cfg().quests().getString("pool." + id + ".type", "KILLS"));
        m.loreFor = id -> { var y = plugin.cfg().quests(); return List.of("<gray>" + y.getString("pool." + id + ".type", "KILLS") + " x" + y.getLong("pool." + id + ".amount", 1), "<gray>Reward: <white>" + y.getLong("pool." + id + ".reward", 0), "<gray>" + y.getString("pool." + id + ".difficulty", "EASY")); };
        m.defaults = id -> { Map<String, Object> d = new LinkedHashMap<>(); d.put("enabled", true); d.put("name", id); d.put("type", "KILLS"); d.put("amount", 5); d.put("reward", 50); d.put("difficulty", "EASY"); return d; };
        m.settingsFor = SettingsCatalog::quest;
        return m;
    }

    private CollectionMenu effects(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Kill Effects", Cfg.COSMETICS, "kill-effects", back);
        m.iconFor = id -> plugin.cosmetics().iconOf(true, id);
        m.loreFor = id -> List.of("<gray>Name: <white>" + plugin.cosmetics().richName(true, id), "<gray>Style: <white>" + plugin.cosmetics().shapeOf(id), "<gray>Particle: <white>" + plugin.cosmetics().particleOf(id), "<gray>Price: <white>" + plugin.cosmetics().priceOf(true, id));
        m.defaults = id -> { Map<String, Object> d = new LinkedHashMap<>(); d.put("name", id); d.put("price", 500); d.put("permission", ""); d.put("icon", "BLAZE_POWDER"); d.put("shape", "burst"); d.put("particle", "FLAME"); d.put("count", 20); d.put("spread", 0.4); d.put("speed", 0.05); d.put("sound", ""); return d; };
        m.settingsFor = SettingsCatalog::effect;
        return m;
    }

    private CollectionMenu deathMessages(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Death Messages", Cfg.COSMETICS, "death-messages", back);
        m.iconFor = id -> plugin.cosmetics().iconOf(false, id);
        m.loreFor = id -> List.of("<gray>Name: <white>" + plugin.cosmetics().richName(false, id), "<gray>Price: <white>" + plugin.cosmetics().priceOf(false, id));
        m.defaults = id -> { Map<String, Object> d = new LinkedHashMap<>(); d.put("name", id); d.put("price", 300); d.put("icon", "NAME_TAG"); d.put("permission", ""); d.put("format", "<killer> obliterated <victim>"); return d; };
        m.settingsFor = SettingsCatalog::deathMessage;
        return m;
    }

    private CollectionMenu boardCats(Menu back) {
        CollectionMenu m = new CollectionMenu(plugin, viewer, "Leaderboard Boards", Cfg.BOARDS, "categories", back);
        m.iconFor = id -> plugin.boards().icon(id);
        m.loreFor = id -> List.of("<gray>Sorted by <white>" + plugin.cfg().boards().getString("categories." + id + ".column", "kills"));
        m.defaults = id -> Map.of("enabled", true, "title", id, "column", "kills");
        m.settingsFor = SettingsCatalog::boardCategory;
        return m;
    }
}

final class KitEditMenu extends Menu {
    private final String id; private final Menu back;
    KitEditMenu(AetherFFA plugin, Player viewer, String id, Menu back) { super(plugin, viewer); this.id = id; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Kit <#4DA6FF>" + id); }
    @Override protected int rows() { return 3; }
    @Override protected String permission() { return "aetherffa.admin.kits"; }
    @Override protected void build() {
        set(10, Items.of(Material.COMPARATOR, "<#4DA6FF>Settings", "<gray>Price, cooldown, permission, worlds..."), t -> new SettingsMenu(plugin, viewer, "Kit " + id, SettingsCatalog.kit(id), this, "aetherffa.admin.kits").open());
        set(11, Items.of(Material.CHEST_MINECART, "<#4DA6FF>Set contents from my inventory", "<gray>Replaces the kit with what you carry", "<gray>and wear right now (validated)."), "aetherffa.admin.kits", t ->
                new ConfirmMenu(plugin, viewer, "<#4DA6FF>Overwrite kit contents?", List.of("<gray>Uses your current inventory."), "aetherffa.admin.kits", this, () -> { plugin.kits().captureContents(viewer, id); this.open(); }).open());
        set(12, Items.of(Material.ENDER_EYE, "<#4DA6FF>Preview", "<gray>See what players receive."), t -> new KitPreviewMenu(plugin, viewer, id, this).open());
        set(13, Items.of(Material.CHEST, "<#4DA6FF>Give to me", "<gray>Ignores cooldown and ownership."), "aetherffa.admin.kits", t -> { if (!plugin.kits().give(viewer, id)) plugin.msg().send(viewer, "kits.no-space"); });
        set(15, Items.of(Material.TNT, "<red>Delete kit"), "aetherffa.admin.kits", t ->
                new ConfirmMenu(plugin, viewer, "<red>Delete " + id + "?", List.of("<gray>Players keep nothing from it."), "aetherffa.admin.kits", this, () -> { plugin.kits().delete(id); plugin.msg().send(viewer, "admin.deleted", "name", id); if (back != null) back.open(); else viewer.closeInventory(); }).open());
        navBack(back);
    }
}

final class ZoneEditMenu extends Menu {
    private final String id; private final Menu back;
    ZoneEditMenu(AetherFFA plugin, Player viewer, String id, Menu back) { super(plugin, viewer); this.id = id; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Zone <#4DA6FF>" + id); }
    @Override protected int rows() { return 4; }
    @Override protected String permission() { return "aetherffa.admin.zones"; }
    @Override protected void build() {
        String perm = "aetherffa.admin.zones";
        List<String> info = new ArrayList<>(plugin.zones().info(id));
        display(4, Items.of(Material.BEACON, "<#4DA6FF>" + id, info));
        set(10, Items.of(Material.COMPARATOR, "<#4DA6FF>Settings", "<gray>Reward, interval, mode, contest..."), t ->
                new SettingsMenu(plugin, viewer, "Zone " + id, SettingsCatalog.zone("zones." + id + ".", true), this, perm).open());
        if (plugin.zones().usesWorldEdit()) {
            display(11, Items.of(Material.WOODEN_AXE, "<#4DA6FF>Select with WorldEdit", "<gray>Use <white>//wand<gray> (cuboid) or <white>//sel cyl<gray> (circle),", "<gray>then press Apply."));
            set(12, Items.of(Material.LIME_DYE, "<green>Apply WorldEdit selection", "<gray>Replaces this zone's shape."), perm, t -> { String e = plugin.zones().applySelection(viewer, id); plugin.msg().send(viewer, e == null ? "zones.updated" : e); render(); });
        } else {
            set(11, Items.of(Material.WOODEN_AXE, "<#4DA6FF>Set corner 1 here", "<gray>Your current block."), perm, t -> { plugin.zones().select(viewer, 0); plugin.msg().send(viewer, "zones.pos-set", "n", 1); });
            set(12, Items.of(Material.STONE_AXE, "<#4DA6FF>Set corner 2 here", "<gray>Your current block."), perm, t -> { plugin.zones().select(viewer, 1); plugin.msg().send(viewer, "zones.pos-set", "n", 2); });
            set(13, Items.of(Material.LIME_DYE, "<green>Apply corners as cuboid", "<gray>Replaces this zone's shape."), perm, t -> { String e = plugin.zones().applySelection(viewer, id); plugin.msg().send(viewer, e == null ? "zones.updated" : e); render(); });
        }
        set(14, Items.of(Material.TARGET, "<green>Re-center circle here", "<gray>Moves the circle to your position."), perm, t -> { String e = plugin.zones().recenter(viewer, id); plugin.msg().send(viewer, e == null ? "zones.updated" : e); render(); });
        var z = plugin.zones().get(id);
        boolean on = z != null && z.enabled;
        set(15, Items.of(on ? Material.LIME_CONCRETE : Material.RED_CONCRETE, "<#4DA6FF>Zone: " + Items.onOff(on), "<gray>Click to toggle."), perm, t -> { String e = plugin.zones().setEnabled(id, !on); if (e != null) plugin.msg().send(viewer, e); render(); });
        set(16, Items.of(Material.ENDER_PEARL, "<#4DA6FF>Teleport me to zone", "<gray>Moves only you (admin tool)."), perm, t -> {
            Location c = z == null ? null : plugin.zones().center(z);
            if (c != null) { viewer.closeInventory(); viewer.teleport(c); } else plugin.msg().send(viewer, "zones.unknown");
        });
        navBack(back);
    }
}

final class PlayerAdminMenu extends Menu {
    private final Menu back;
    PlayerAdminMenu(AetherFFA plugin, Player viewer, Menu back) { super(plugin, viewer); this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Player Management"); }
    @Override protected int rows() { return 6; }
    @Override protected String permission() { return "aetherffa.admin.players"; }
    @Override protected void build() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        paged(players, p -> {
            PlayerData d = plugin.store().get(p);
            return Items.head(p, "<white>" + p.getName(), List.of("<gray>Balance: <#B57BFF>" + (d == null ? "?" : Amounts.format(d.balance)), "<gray>Kills/Deaths: <white>" + (d == null ? "?" : d.kills + "/" + d.deaths), "<gray>Click to manage"));
        }, (p, t) -> { if (p.isOnline()) new PlayerDetailMenu(plugin, viewer, p, this).open(); });
        navBack(back); navHome(); navRefresh();
    }
}

final class PlayerDetailMenu extends Menu {
    private final Player target; private final Menu back;
    PlayerDetailMenu(AetherFFA plugin, Player viewer, Player target, Menu back) { super(plugin, viewer); this.target = target; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Manage <#4DA6FF>" + target.getName()); }
    @Override protected int rows() { return 4; }
    @Override protected String permission() { return "aetherffa.admin.players"; }

    private void adjust(long delta) {
        if (!target.isOnline()) return;
        String reason = "admin:" + viewer.getName();
        Economy.Result r = delta >= 0 ? plugin.economy().deposit(target.getUniqueId(), delta, reason, null) : plugin.economy().withdraw(target.getUniqueId(), -delta, reason, null);
        plugin.msg().send(viewer, r == Economy.Result.OK ? "economy.admin-done" : "economy.admin-failed", "player", target.getName(), "balance", plugin.economy().fmt(plugin.economy().balance(target.getUniqueId())));
        render();
    }

    @Override protected void build() {
        String perm = "aetherffa.admin.players";
        PlayerData d = plugin.store().get(target);
        if (d == null) { navBack(back); return; }
        display(4, Items.head(target, "<white>" + target.getName(), List.of("<gray>Balance: <#B57BFF>" + plugin.economy().fmt(d.balance), "<gray>Kills: <white>" + d.kills + " <gray>Deaths: <white>" + d.deaths,
                "<gray>Streak: <white>" + d.streak + " <gray>Best: <white>" + d.bestStreak, "<gray>Bounty: <#B57BFF>" + plugin.economy().fmt(plugin.bounties().total(target.getUniqueId())))));
        set(9, Items.of(Material.RED_CONCRETE, "<red>-1,000"), perm, t -> adjust(-1000));
        set(10, Items.of(Material.ORANGE_CONCRETE, "<red>-100"), perm, t -> adjust(-100));
        set(11, Items.of(Material.LIME_CONCRETE, "<green>+100"), perm, t -> adjust(100));
        set(12, Items.of(Material.GREEN_CONCRETE, "<green>+1,000"), perm, t -> adjust(1000));
        set(13, Items.of(Material.WRITABLE_BOOK, "<#4DA6FF>Set balance", "<gray>Type an exact amount."), perm, t ->
                plugin.prompts().ask(viewer, "<prefix><gray>Type the new balance:", text -> {
                    var a = Amounts.parse(text);
                    if (a.isEmpty()) plugin.msg().send(viewer, "general.invalid-amount");
                    else { plugin.economy().set(target.getUniqueId(), a.getAsLong(), "admin-set:" + viewer.getName()); plugin.msg().send(viewer, "economy.admin-done", "player", target.getName(), "balance", plugin.economy().fmt(plugin.economy().balance(target.getUniqueId()))); }
                    open();
                }, this::open));
        set(14, Items.of(Material.BOOK, "<#4DA6FF>Transactions", "<gray>Last entries in chat."), perm, t -> plugin.economy().history(target.getUniqueId(), 8).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            viewer.closeInventory();
            for (Economy.Tx tx : list) plugin.msg().send(viewer, "economy.history-line", "delta", (tx.delta() >= 0 ? "+" : "") + Amounts.format(tx.delta()), "balance", Amounts.format(tx.balanceAfter()), "reason", String.valueOf(tx.reason()));
        })));
        set(18, Items.of(Material.BLAZE_POWDER, "<#4DA6FF>Reset kill streak"), perm, t -> { plugin.streaks().reset(d); plugin.msg().send(viewer, "admin.done"); });
        set(19, Items.of(Material.IRON_SWORD, "<#4DA6FF>Clear combat tag"), perm, t -> { plugin.combat().untag(target.getUniqueId(), true); plugin.msg().send(viewer, "admin.done"); });
        set(20, Items.of(Material.SHIELD, "<#4DA6FF>End spawn protection"), perm, t -> { plugin.combat().endProtection(target); plugin.msg().send(viewer, "admin.done"); });
        set(21, Items.of(Material.WRITABLE_BOOK, "<#4DA6FF>Reset today's quests"), perm, t -> { plugin.quests().reset(target); plugin.msg().send(viewer, "admin.done"); });
        set(22, Items.of(Material.CLOCK, "<#4DA6FF>Reset kit cooldowns"), perm, t -> { d.kv.keySet().removeIf(k -> k.startsWith("kit.cd.")); d.dirty = true; plugin.msg().send(viewer, "admin.done"); });
        set(23, Items.of(Material.TARGET, "<red>Remove bounty (refund)", "<gray>Refunds every contributor."), perm, t ->
                new ConfirmMenu(plugin, viewer, "<red>Remove bounty on " + target.getName() + "?", List.of("<gray>Contributors are refunded."), perm, this, () -> { plugin.bounties().adminRemove(target.getUniqueId(), 100); plugin.msg().send(viewer, "admin.done"); }).open());
        boolean immune = target.hasMetadata("aetherffa_no_rewards");
        set(24, Items.of(immune ? Material.RED_DYE : Material.GRAY_DYE, "<#4DA6FF>Kill rewards blocked: " + Items.onOff(immune), "<gray>Until restart. Moderation tool."), perm, t -> {
            if (target.hasMetadata("aetherffa_no_rewards")) target.removeMetadata("aetherffa_no_rewards", plugin);
            else target.setMetadata("aetherffa_no_rewards", new FixedMetadataValue(plugin, true));
            render();
        });
        navBack(back);
    }
}

final class TeamAdminMenu extends Menu {
    private final Menu back;
    TeamAdminMenu(AetherFFA plugin, Player viewer, Menu back) { super(plugin, viewer); this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Team System"); }
    @Override protected int rows() { return 6; }
    @Override protected String permission() { return "aetherffa.admin"; }
    @Override protected void build() {
        List<Team> list = plugin.teams().all();
        list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        paged(list, t -> Items.head(Bukkit.getOfflinePlayer(t.leader), "<white>" + t.name, List.of("<gray>Leader: <white>" + plugin.teams().nameOf(t.leader), "<gray>Members: <white>" + t.members.size(), "<dark_gray>Click to manage")),
                (t, c) -> { if (viewer.hasPermission("aetherffa.admin.teams")) new TeamDetailAdmin(plugin, viewer, t, this).open(); });
        set(navRow() + 4, Items.of(Material.COMPARATOR, "<#4DA6FF>Team settings", "<gray>Size, glow, friendly fire, chat, rules."), "aetherffa.admin.settings", t -> new SettingsMenu(plugin, viewer, "Team System", SettingsCatalog.team(), this).open());
        navBack(back); navRefresh();
    }
}

final class TeamDetailAdmin extends Menu {
    private final Team team; private final Menu back;
    TeamDetailAdmin(AetherFFA plugin, Player viewer, Team team, Menu back) { super(plugin, viewer); this.team = team; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Team <#4DA6FF>" + team.name); }
    @Override protected int rows() { return 3; }
    @Override protected String permission() { return "aetherffa.admin.teams"; }
    @Override protected void build() {
        int slot = 9;
        List<UUID> ids;
        synchronized (team.members) { ids = new ArrayList<>(team.members); }
        for (UUID u : ids) {
            if (slot > 15) break;
            set(slot++, Items.head(Bukkit.getOfflinePlayer(u), "<white>" + plugin.teams().nameOf(u), List.of(team.isLeader(u) ? "<#FFC83D>Leader" : "<gray>Member", "<dark_gray>Click to remove")), "aetherffa.admin.teams", t ->
                    new ConfirmMenu(plugin, viewer, "<red>Remove " + plugin.teams().nameOf(u) + "?", List.of("<gray>From team " + team.name), "aetherffa.admin.teams", back, () -> { plugin.teams().forceRemove(team, u); back.open(); }).open());
        }
        set(17, Items.of(Material.TNT, "<red>Disband team"), "aetherffa.admin.teams", t ->
                new ConfirmMenu(plugin, viewer, "<red>Disband " + team.name + "?", List.of("<gray>All members are removed."), "aetherffa.admin.teams", back, () -> { plugin.teams().disbandTeam(team, "teams.disbanded-by-staff"); back.open(); }).open());
        navBack(back);
    }
}

final class DiagMenu extends Menu {
    private final Menu back;
    DiagMenu(AetherFFA plugin, Player viewer, Menu back) { super(plugin, viewer); this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Database & Diagnostics"); }
    @Override protected int rows() { return 4; }
    @Override protected String permission() { return "aetherffa.admin.diagnostics"; }
    @Override protected void build() {
        Db db = plugin.db();
        boolean ok = db.healthy();
        display(10, Items.of(ok ? Material.EMERALD : Material.REDSTONE, "<#4DA6FF>Database (SQLite)",
                "<gray>Status: " + (ok ? "<green>healthy" : "<red>unreachable"), "<gray>Last query: <white>" + db.latencyMs() + " ms",
                "<gray>File size: <white>" + (db.sizeBytes() / 1024) + " KB", "<gray>Failures since start: <white>" + db.failures(),
                "<gray>Last error: <white>" + (db.lastError().isEmpty() ? "none" : db.lastError().replace("<", "\\<")), "<dark_gray>Credentials: none stored"));
        long loaded = 0; for (PlayerData ignored : plugin.store().all()) loaded++;
        display(11, Items.of(Material.COMPARATOR, "<#4DA6FF>Runtime", "<gray>Players cached: <white>" + loaded, "<gray>Teams: <white>" + plugin.teams().all().size(),
                "<gray>Bounty targets: <white>" + plugin.bounties().top(10000).size(), "<gray>Zones: <white>" + plugin.zones().zones().size(), "<gray>Boards cached: <white>" + plugin.boards().categories().size()));
        display(12, Items.of(Material.GRASS_BLOCK, "<#4DA6FF>Integrations (read-only)", "<gray>WorldGuard: " + (plugin.wg() != null ? "<green>" + plugin.wg().version() : "<red>not found"),
                "<gray>PlaceholderAPI: " + (plugin.hasPapi() ? "<green>registered" : "<red>not found"), "<gray>FancyNpcs: " + (plugin.hasFancyNpcs() ? "<green>installed" : "<gray>not installed"), "<dark_gray>AetherFFA never edits NPCs."));
        set(14, Items.of(Material.CHEST, "<#4DA6FF>Back up database now", "<gray>Writes a consistent copy to", "<gray>plugins/AetherFFA/backups/."), "aetherffa.admin.diagnostics", t -> backup());
        set(15, Items.of(Material.SUNFLOWER, "<#4DA6FF>Save all players now", "<gray>Queues a save for every online player."), "aetherffa.admin.diagnostics", t -> { plugin.store().flushAll(); plugin.msg().send(viewer, "admin.done"); });
        navBack(back); navHome(); navRefresh();
    }

    private void backup() {
        File dir = new File(plugin.getDataFolder(), "backups");
        dir.mkdirs();
        File target = new File(dir, "aetherffa-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".db");
        String path = target.getAbsolutePath().replace("'", "''");
        plugin.store().flushAll();
        plugin.db().supply(c -> { try (Statement s = c.createStatement()) { s.execute("VACUUM INTO '" + path + "'"); } return true; })
                .whenComplete((v, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (t != null) plugin.msg().sendRaw(viewer, "<prefix><red>Backup failed: " + String.valueOf(t.getMessage()).replace("<", "\\<"));
                    else plugin.msg().send(viewer, "admin.backup-done", "file", target.getName());
                }));
    }
}

final class MessagesMenu extends Menu {
    private final Menu back;
    MessagesMenu(AetherFFA plugin, Player viewer, Menu back) { super(plugin, viewer); this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Messages"); }
    @Override protected int rows() { return 6; }
    @Override protected String permission() { return "aetherffa.admin.messages"; }
    @Override protected void build() {
        YamlConfiguration y = plugin.cfg().messages();
        List<String> keys = new ArrayList<>();
        for (String k : y.getKeys(true)) if (y.isString(k)) keys.add(k);
        paged(keys, k -> Items.of(Items.messageIcon(k), "<#4DA6FF>" + k, "<gray>" + y.getString(k, "").replace("<", "\\<"), " ", "<dark_gray>Click to edit (saved immediately)"), (k, t) -> {
            if (!viewer.hasPermission("aetherffa.admin.messages")) return;
            plugin.prompts().ask(viewer, "<prefix><gray>Type the new text for <white>" + k + "<gray> (MiniMessage; <white>cancel<gray> to abort):", text -> {
                String err = Msg.validate(text);
                if (err != null) plugin.msg().sendRaw(viewer, "<prefix><red>Invalid MiniMessage: " + err.replace("<", "\\<"));
                else {
                    y.set(k, text);
                    String se = plugin.cfg().save(Cfg.MESSAGES);
                    plugin.msg().send(viewer, se == null ? "admin.done" : "general.save-failed", "error", String.valueOf(se));
                }
                open();
            }, this::open);
        });
        navBack(back); navHome();
    }
}
