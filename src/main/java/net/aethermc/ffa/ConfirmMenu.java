package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Generic confirmation screen for destructive actions. */
public final class ConfirmMenu extends Menu {
    private final String heading;
    private final List<String> details;
    private final Runnable onConfirm;
    private final Menu back;
    private final String permission;

    public ConfirmMenu(AetherFFA plugin, Player viewer, String heading, List<String> details, String permission, Menu back, Runnable onConfirm) {
        super(plugin, viewer);
        this.heading = heading; this.details = details; this.onConfirm = onConfirm; this.back = back; this.permission = permission;
    }

    @Override protected Component title() { return titleOf("confirm", "<dark_gray>Confirm"); }
    @Override protected int rows() { return 3; }

    @Override protected void build() {
        display(4, Items.of(Material.OAK_SIGN, heading, details));
        set(11, Items.of(Material.LIME_CONCRETE, "<green>Confirm", "<gray>This cannot be undone."), permission, t -> {
            viewer.closeInventory();
            onConfirm.run();
        });
        set(15, Items.of(Material.RED_CONCRETE, "<red>Cancel"), t -> { if (back != null) back.open(); else viewer.closeInventory(); });
    }
}
