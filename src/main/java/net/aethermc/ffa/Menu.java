package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Base class for every GUI. All clicks in an open Menu are cancelled by {@link MenuListener}; only buttons registered
 * here run, and each button can carry its own permission that is re-checked on every click.
 */
public abstract class Menu implements InventoryHolder {
    @FunctionalInterface public interface Action { void run(ClickType type); }
    private record Button(Action action, String permission) { }

    protected final AetherFFA plugin;
    protected final Player viewer;
    protected Inventory inv;
    protected int page = 0;
    private final Map<Integer, Button> buttons = new HashMap<>();

    protected Menu(AetherFFA plugin, Player viewer) { this.plugin = plugin; this.viewer = viewer; }

    protected abstract Component title();
    protected abstract int rows();
    protected abstract void build();
    /** Permission required to even open/click this menu; null for none. */
    protected String permission() { return null; }
    public void onClose() { }

    public final Player viewer() { return viewer; }

    public void open() {
        if (permission() != null && !viewer.hasPermission(permission())) { plugin.msg().send(viewer, "general.no-permission"); return; }
        inv = Bukkit.createInventory(this, rows() * 9, title());
        render();
        viewer.openInventory(inv);
    }

    /** Rebuilds the contents in place (keeps the window open). */
    public void render() {
        if (inv == null) return;
        buttons.clear();
        inv.clear();
        build();
        ItemStack f = Items.filler();
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, f);
    }

    protected void set(int slot, ItemStack item, Action action) { set(slot, item, null, action); }

    protected void set(int slot, ItemStack item, String perm, Action action) {
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, item);
        if (action != null) buttons.put(slot, new Button(action, perm));
        else buttons.remove(slot);
    }

    protected void display(int slot, ItemStack item) { set(slot, item, null, null); }

    /** Called by the listener after all safety checks. Re-validates permissions on every click. */
    public final void handle(int slot, ClickType type) {
        if (!viewer.isOnline()) return;
        if (permission() != null && !viewer.hasPermission(permission())) { viewer.closeInventory(); return; }
        Button b = buttons.get(slot);
        if (b == null) return;
        if (b.permission() != null && !viewer.hasPermission(b.permission())) { plugin.msg().send(viewer, "general.no-permission"); return; }
        try { b.action().run(type); }
        catch (RuntimeException e) {
            plugin.getLogger().severe("GUI action failed in " + getClass().getSimpleName() + ": " + e);
            plugin.msg().send(viewer, "general.error");
        }
    }

    // ---- shared navigation (bottom row of a 6-row menu, or the equivalent in smaller menus) ----

    protected int navRow() { return (rows() - 1) * 9; }

    protected void navBack(Menu parent) {
        set(navRow(), Items.nav("back", Material.ARROW, "<gray>« Back"), t -> { if (parent != null) parent.open(); else viewer.closeInventory(); });
    }

    protected void navHome() {
        set(navRow() + 4, Items.nav("home", Material.NETHER_STAR, "<#4DA6FF>Home"), "aetherffa.admin", t -> new AdminDashboard(plugin, viewer).open());
    }

    protected void navRefresh() {
        set(navRow() + 8, Items.nav("refresh", Material.SUNFLOWER, "<yellow>Refresh"), t -> render());
    }

    /** Fills the first 45 slots with a page of items and adds prev/next buttons in the nav row. */
    protected <T> void paged(List<T> items, Function<T, ItemStack> icon, BiConsumer<T, ClickType> click) {
        int per = 45;
        int pages = Math.max(1, (items.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= items.size()) break;
            T item = items.get(idx);
            set(i, icon.apply(item), t -> click.accept(item, t));
        }
        if (page > 0) set(navRow() + 2, Items.nav("previous", Material.SPECTRAL_ARROW, "<gray>‹ Previous"), t -> { page--; render(); });
        if (page < pages - 1) set(navRow() + 6, Items.nav("next", Material.SPECTRAL_ARROW, "<gray>Next ›"), t -> { page++; render(); });
        display(navRow() + 3, Items.of(Material.BOOK, "<gray>Page " + (page + 1) + "/" + pages));
    }

    protected Component titleOf(String key, String def) {
        return plugin.msg().item(plugin.cfg().gui().getString("titles." + key, def));
    }

    @Override public Inventory getInventory() { return inv; }

    protected static List<String> lines(String... s) { return new ArrayList<>(List.of(s)); }
}
