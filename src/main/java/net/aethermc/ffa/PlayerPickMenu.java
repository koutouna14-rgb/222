package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Paged list of online players; the callback receives the one that was clicked. */
public final class PlayerPickMenu extends Menu {
    private final String titleText;
    private final Predicate<Player> filter;
    private final Consumer<Player> onPick;
    private final Menu back;

    public PlayerPickMenu(AetherFFA plugin, Player viewer, String titleText, Predicate<Player> filter, Menu back, Consumer<Player> onPick) {
        super(plugin, viewer);
        this.titleText = titleText; this.filter = filter; this.onPick = onPick; this.back = back;
    }

    @Override protected Component title() { return plugin.msg().item(titleText); }
    @Override protected int rows() { return 6; }

    @Override protected void build() {
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (filter.test(p)) players.add(p);
        players.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        paged(players, p -> Items.head(p, "<white>" + p.getName(), List.of("<gray>Click to select")), (p, t) -> {
            if (p.isOnline()) onPick.accept(p);
        });
        navBack(back);
        navRefresh();
    }
}
