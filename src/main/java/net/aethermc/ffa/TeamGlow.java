package net.aethermc.ffa;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.UUID;

/**
 * Teammate glow that ONLY teammates can see. Every player is compared against every other player each interval (and
 * immediately after any team change): player T glows for player R only when R and T are on the same AetherFFA team, in the
 * same enabled world and within range. Players without a team never glow for anyone and never see anyone glow.
 * Needs the PacketEvents plugin (per-viewer glow is impossible with the plain Bukkit API). Without it glow stays OFF:
 * we deliberately do not fall back to a server-side glow, because that would be visible to everybody.
 */
public final class TeamGlow implements Listener {
    private final AetherFFA plugin;
    private PacketGlow pg;
    private BukkitTask task;
    private boolean queued;

    public TeamGlow(AetherFFA plugin) { this.plugin = plugin; }

    /** Called once from onEnable after the listener is registered. */
    public void start() {
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            plugin.getLogger().warning("PacketEvents not found: team glow is DISABLED (it is only ever shown to teammates, which needs PacketEvents).");
            return;
        }
        try {
            pg = new PacketGlow(plugin);
            pg.register();
        } catch (Throwable t) {
            plugin.getLogger().warning("PacketEvents is present but could not be used (" + t + "): team glow is DISABLED.");
            pg = null;
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) pg.track(p);
        long interval = Math.max(5, plugin.cfg().teams().getLong("glow.check-interval", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshNow, 40L, interval);
        plugin.getLogger().info("Team glow active (per-viewer, via PacketEvents): only teammates see it.");
    }

    public boolean active() { return pg != null; }

    private NamedTextColor color() {
        String n = plugin.cfg().teams().getString("glow.color", "AQUA").toUpperCase(Locale.ROOT);
        NamedTextColor c = NamedTextColor.NAMES.value(n.toLowerCase(Locale.ROOT));
        return c == null ? NamedTextColor.AQUA : c;
    }

    /** True when {@code target} should glow on {@code receiver}'s screen. Teammates only. */
    private boolean visibleTo(Player receiver, Player target) {
        if (receiver.equals(target)) return false;
        Team rt = plugin.teams().teamOf(receiver.getUniqueId());
        if (rt == null || !rt.members.contains(target.getUniqueId())) return false;           // not on the same team: never
        if (!receiver.getWorld().equals(target.getWorld())) return false;
        if (!plugin.worlds().enabled(receiver.getWorld(), Feature.TEAMS)) return false;
        double range = plugin.cfg().teams().getDouble("glow.range", 30);
        return range <= 0 || receiver.getLocation().distanceSquared(target.getLocation()) <= range * range;
    }

    /** Recomputes who sees whom and sends only the differences. Main thread. */
    private void refreshNow() {
        if (pg == null) return;
        boolean on = plugin.cfg().teams().getBoolean("glow.enabled", true) && plugin.teams().enabled();
        NamedTextColor col = color();
        if (on) pg.updateColor(col);
        var online = Bukkit.getOnlinePlayers();
        for (Player r : online) {
            for (Player t : online) {
                if (r == t) continue;
                boolean want = on && visibleTo(r, t);
                boolean has = pg.isShown(r, t);
                if (want && !has) pg.set(t, r, col);
                else if (!want && has) pg.unset(t, r);
            }
        }
    }

    /** Coalesces any number of requests in one tick into a single refresh, and hops to the main thread if needed. */
    private void requestRefresh() {
        if (pg == null) return;
        if (queued) return;
        queued = true;
        Bukkit.getScheduler().runTask(plugin, () -> { queued = false; refreshNow(); });
    }

    // ---- API used by TeamManager / AetherFFA (names unchanged) ----

    public void apply(Player p) { requestRefresh(); }
    public void clear(Player p) { requestRefresh(); }
    public void refreshTeam(Team t) { requestRefresh(); }
    public void refreshAll() { requestRefresh(); }
    /** Nothing server-side to unregister any more; kept so callers need no change. */
    public void dropScoreboardTeam(Team t) { requestRefresh(); }

    public void clearAll() {
        if (task != null) { task.cancel(); task = null; }
        if (pg != null) { try { pg.shutdown(); } catch (Throwable ignored) { } pg = null; }
    }

    // ---- events ----

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        if (pg == null) return;
        pg.track(e.getPlayer());
        Bukkit.getScheduler().runTaskLater(plugin, this::requestRefresh, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (pg == null) return;
        pg.forget(e.getPlayer());
        requestRefresh();
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { Bukkit.getScheduler().runTaskLater(plugin, this::requestRefresh, 5L); }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) { Bukkit.getScheduler().runTaskLater(plugin, this::requestRefresh, 5L); }
}
