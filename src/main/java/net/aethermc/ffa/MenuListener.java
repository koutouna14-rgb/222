package net.aethermc.ffa;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Blocks every form of item movement in AetherFFA menus and routes clicks to the owning {@link Menu}. */
public final class MenuListener implements Listener {
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    private static Menu menuOf(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return null;
        InventoryHolder h = inv.getHolder(false);
        return h instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Menu m = menuOf(e.getView().getTopInventory());
        if (m == null) return;
        // Cancel everything while one of our menus is open: shift-click from the player inventory, number keys,
        // double-click collect, drop, swap-offhand, creative clicks. Nothing may move.
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.equals(m.viewer())) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 120) return; // click spam / double-click exploits
        lastClick.put(p.getUniqueId(), now);
        m.handle(e.getRawSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (menuOf(e.getView().getTopInventory()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (menuOf(e.getDestination()) != null || menuOf(e.getSource()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Menu m = menuOf(e.getInventory());
        if (m != null) m.onClose();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastClick.remove(e.getPlayer().getUniqueId()); }
}
