package net.aethermc.ffa;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** One-shot chat prompts for text/number input from GUIs. Input never reaches public chat. */
public final class Prompts implements Listener {
    private record Pending(Consumer<String> onInput, Runnable onCancel, long expires) { }
    private final AetherFFA plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public Prompts(AetherFFA plugin) { this.plugin = plugin; }

    /** Closes any open menu and asks the player to type a value. Type "cancel" to abort. */
    public void ask(Player p, String promptMiniMessage, Consumer<String> onInput, Runnable onCancel) {
        p.closeInventory();
        plugin.msg().sendRaw(p, promptMiniMessage);
        plugin.msg().send(p, "general.prompt-cancel-hint");
        pending.put(p.getUniqueId(), new Pending(onInput, onCancel, System.currentTimeMillis() + 60_000L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending cur = pending.get(p.getUniqueId());
            if (cur != null && cur.expires() <= System.currentTimeMillis()) {
                pending.remove(p.getUniqueId());
                plugin.msg().send(p, "general.prompt-expired");
                if (cur.onCancel() != null) cur.onCancel().run();
            }
        }, 20L * 61);
    }

    public boolean isPrompting(UUID id) { return pending.containsKey(id); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pending pe = pending.remove(p.getUniqueId());
        if (pe == null) return;
        e.setCancelled(true);
        String text = Msg.plain(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                plugin.msg().send(p, "general.prompt-cancelled");
                if (pe.onCancel() != null) pe.onCancel().run();
            } else pe.onInput().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { pending.remove(e.getPlayer().getUniqueId()); }
}
