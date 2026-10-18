package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/** Edits a string-list setting: left-click an entry to change it, right-click to remove it, button to add. Changes are staged. */
final class ListEditMenu extends Menu {
    private final Setting setting; private final Menu back; private final String perm;

    ListEditMenu(AetherFFA plugin, Player viewer, Setting setting, Menu back, String perm) {
        super(plugin, viewer); this.setting = setting; this.back = back; this.perm = perm;
    }

    @Override protected Component title() { return plugin.msg().item("<dark_gray>" + setting.label); }
    @Override protected int rows() { return 6; }
    @Override protected String permission() { return perm; }

    @SuppressWarnings("unchecked")
    private List<String> current() { return new ArrayList<>((List<String>) AdminSession.of(viewer).current(plugin, setting)); }

    private void commit(List<String> list) {
        AdminSession ses = AdminSession.of(viewer);
        if (list.equals(AdminSession.live(plugin, setting))) ses.unstage(setting); else ses.stage(setting, list);
    }

    @Override protected void build() {
        List<String> list = current();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) idx.add(i);
        paged(idx, i -> Items.of(Items.mat(list.get(i), Material.OAK_SIGN), "<white>" + list.get(i).replace("<", "\\<"), "<dark_gray>Left: edit  Right: remove"), (i, type) -> {
            if (!viewer.hasPermission(perm)) return;
            List<String> l = current();
            if (i >= l.size()) return;
            if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) { l.remove((int) i); commit(l); render(); return; }
            plugin.prompts().ask(viewer, "<prefix><gray>Type the new text for this entry:", text -> {
                List<String> l2 = current();
                if (i < l2.size() && !text.isBlank()) { l2.set(i, text); commit(l2); }
                open();
            }, this::open);
        });
        set(navRow() + 4, Items.of(Material.LIME_DYE, "<green>Add entry", "<gray>Type the new entry in chat."), perm, t ->
                plugin.prompts().ask(viewer, "<prefix><gray>Type the entry to add:", text -> {
                    List<String> l = current();
                    if (!text.isBlank() && l.size() < 200 && !l.contains(text)) { l.add(text); commit(l); }
                    open();
                }, this::open));
        navBack(back);
    }
}
