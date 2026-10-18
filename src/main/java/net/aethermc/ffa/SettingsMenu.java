package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic editor for a list of {@link Setting}s. Every click is permission-checked again; values are validated when
 * staged and once more on save. Controls: bool = click; numbers = left/right +-step (shift x10, Q = type value);
 * enum = left/right cycles; text = click to type; list = click to edit.
 */
public final class SettingsMenu extends Menu {
    private final String titleText;
    private final List<Setting> settings;
    private final Menu back;
    private final String perm;

    public SettingsMenu(AetherFFA plugin, Player viewer, String titleText, List<Setting> settings, Menu back) {
        this(plugin, viewer, titleText, settings, back, "aetherffa.admin.settings");
    }

    public SettingsMenu(AetherFFA plugin, Player viewer, String titleText, List<Setting> settings, Menu back, String perm) {
        super(plugin, viewer);
        this.titleText = titleText; this.settings = settings; this.back = back; this.perm = perm;
    }

    @Override protected Component title() { return plugin.msg().item("<dark_gray>" + titleText); }
    @Override protected int rows() { return 6; }
    @Override protected String permission() { return perm; }

    @Override protected void build() {
        AdminSession ses = AdminSession.of(viewer);
        paged(settings, s -> icon(ses, s), (s, type) -> click(ses, s, type));
        navBack(back);
        navHome();
        navRefresh();
        int n = ses.pending();
        set(navRow() + 5, Items.of(n > 0 ? Material.EMERALD_BLOCK : Material.GRAY_DYE, n > 0 ? "<green>Save changes <white>(" + n + ")" : "<gray>Save changes", "<gray>Validates, writes the YAML and applies live."), perm, t -> {
            if (ses.pending() == 0) return;
            for (String line : ses.save(plugin)) plugin.msg().sendRaw(viewer, "<prefix>" + line);
            render();
        });
        set(navRow() + 1, Items.of(n > 0 ? Material.REDSTONE_BLOCK : Material.GRAY_DYE, n > 0 ? "<red>Discard changes" : "<gray>Discard changes", "<gray>Throws away every unsaved change."), perm, t -> {
            ses.discard();
            plugin.msg().send(viewer, "admin.discarded");
            render();
        });
    }

    private ItemStack icon(AdminSession ses, Setting s) {
        Object cur = ses.current(plugin, s);
        boolean changed = ses.staged.containsKey(s.key());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + s.desc);
        lore.add(" ");
        lore.add("<gray>Value: " + show(s, cur) + (changed ? " <yellow>*unsaved" : ""));
        if (changed) lore.add("<gray>Saved:  " + show(s, AdminSession.live(plugin, s)));
        lore.add(" ");
        switch (s.type) {
            case BOOL -> lore.add("<dark_gray>Click: toggle");
            case LONG, DOUBLE -> { lore.add("<dark_gray>Left/Right: -/+ " + fmt(s.step) + "  (Shift x10)"); lore.add("<dark_gray>Q: type a value"); }
            case ENUM -> lore.add("<dark_gray>Left/Right: next/previous");
            case STRING -> lore.add("<dark_gray>Click: type a new value");
            case LIST -> lore.add("<dark_gray>Click: edit entries");
        }
        if (s.restart) lore.add("<yellow>Restart required");
        return Items.of(s.icon(cur), (changed ? "<yellow>" : "<#4DA6FF>") + s.label, lore);
    }

    private String show(Setting s, Object v) {
        if (s.type == Setting.Type.BOOL) return Items.onOff(Boolean.TRUE.equals(v));
        if (s.type == Setting.Type.LIST) return "<white>" + ((List<?>) v).size() + " entries";
        String str = String.valueOf(v);
        if (str.length() > 40) str = str.substring(0, 37) + "...";
        // value text is shown as plain text, never parsed
        return "<white>" + str.replace("<", "\\<");
    }

    private static String fmt(double d) { return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d); }

    private void stage(AdminSession ses, Setting s, Object value) {
        String err = AdminSession.validate(s, value);
        if (err != null) { plugin.msg().sendRaw(viewer, "<prefix><red>" + s.label + ": " + err.replace("<", "\\<")); return; }
        Object live = AdminSession.live(plugin, s);
        if (java.util.Objects.equals(live, value) || (live instanceof Number a && value instanceof Number b && a.doubleValue() == b.doubleValue())) ses.unstage(s);
        else ses.stage(s, value);
    }

    private void click(AdminSession ses, Setting s, ClickType type) {
        if (!viewer.hasPermission(perm)) return;      // re-check on every action
        Object cur = ses.current(plugin, s);
        switch (s.type) {
            case BOOL -> stage(ses, s, !Boolean.TRUE.equals(cur));
            case LONG, DOUBLE -> {
                if (type == ClickType.DROP || type == ClickType.CONTROL_DROP) {
                    plugin.prompts().ask(viewer, "<prefix><gray>Type a value for <white>" + s.label + "<gray> (" + fmt(s.min) + " to " + fmt(s.max) + "):", text -> {
                        var parsed = s.type == Setting.Type.LONG ? Amounts.parse(text) : java.util.OptionalLong.empty();
                        Object v = null;
                        if (s.type == Setting.Type.LONG && parsed.isPresent()) v = parsed.getAsLong();
                        else if (s.type == Setting.Type.LONG && text.matches("-?\\d{1,15}")) v = Long.parseLong(text);
                        else if (s.type == Setting.Type.DOUBLE) { try { v = Double.parseDouble(text); } catch (NumberFormatException ignored) { } }
                        if (v == null) plugin.msg().send(viewer, "general.invalid-amount"); else stage(ses, s, v);
                        open();
                    }, this::open);
                    return;
                }
                double mult = type.isShiftClick() ? 10 : 1;
                double dir = (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) ? 1 : (type == ClickType.LEFT || type == ClickType.SHIFT_LEFT) ? -1 : 0;
                if (dir == 0) return;
                double next = ((Number) cur).doubleValue() + dir * s.step * mult;
                next = Math.max(s.min, Math.min(s.max, next));
                stage(ses, s, s.type == Setting.Type.LONG ? (Object) Math.round(next) : (Object) (Math.round(next * 1000.0) / 1000.0));
            }
            case ENUM -> {
                int i = Math.max(0, s.options.indexOf(String.valueOf(cur)));
                int d = (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) ? -1 : 1;
                stage(ses, s, s.options.get(Math.floorMod(i + d, s.options.size())));
            }
            case STRING -> {
                plugin.prompts().ask(viewer, "<prefix><gray>Type a new value for <white>" + s.label + "<gray>:", text -> { stage(ses, s, text); open(); }, this::open);
                return;
            }
            case LIST -> { new ListEditMenu(plugin, viewer, s, this, perm).open(); return; }
        }
        render();
    }
}
