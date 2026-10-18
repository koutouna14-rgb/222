package net.aethermc.ffa;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.UUID;

/** Connects the systems: data lifecycle, the single kill pipeline, team chat routing and world changes. */
public final class FfaListener implements Listener {
    private final AetherFFA plugin;

    public FfaListener(AetherFFA plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try { plugin.store().stash(plugin.store().load(e.getUniqueId(), e.getName())); }
        catch (SQLException ex) { plugin.getLogger().severe("Pre-login load failed for " + e.getName() + ": " + ex.getMessage()); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.store().activate(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) plugin.quests().announceUnclaimed(p); }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        PlayerData d = plugin.store().get(id);
        if (d != null && plugin.cfg().streaks().getBoolean("reset-on.quit", false)) plugin.streaks().reset(d);
        plugin.zones().onQuit(id);
        plugin.teams().onQuit(id);
        plugin.guard().forget(id);
        // Saved on the next tick so combat-log handling (which may kill the player) is included.
        Bukkit.getScheduler().runTask(plugin, () -> { plugin.store().release(id); plugin.clearDegraded(id); });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        PlayerData d = plugin.store().get(e.getPlayer());
        if (d != null && plugin.cfg().streaks().getBoolean("reset-on.disabled-world", true)
                && !plugin.worlds().enabled(e.getPlayer().getWorld(), Feature.KILLSTREAKS)) plugin.streaks().reset(d);
    }

    /** Anything the player does in a zone counts as activity so fighting on the spot is not treated as AFK. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) plugin.zones().touch(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.teams().chatMode(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = Msg.plain(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> plugin.teams().chat(p, text));
    }

    /** The one kill pipeline. Streaks, bounties, quests, rewards and cosmetics all share a single guard verdict. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        PlayerData vd = plugin.store().get(victim);
        if (vd == null) return;
        Player killer = victim.getKiller();
        UUID logoutAttacker = plugin.combat().consumeLogoutAttacker(victim.getUniqueId());
        if (killer == null && logoutAttacker != null) killer = Bukkit.getPlayer(logoutAttacker);
        plugin.combat().untag(victim.getUniqueId(), false);
        boolean worldOn = plugin.worlds().enabled(victim.getWorld(), Feature.KILL_REWARDS);
        if (worldOn) { synchronized (vd) { vd.deaths++; vd.dirty = true; } }

        Guard.Verdict verdict = killer == null ? null : plugin.guard().judgeKill(killer, victim);
        Player validKiller = verdict != null && verdict.valid() ? killer : null;
        plugin.streaks().onDeath(victim, vd, validKiller);
        if (killer != null) plugin.cosmetics().applyDeathMessage(e, killer, victim);
        if (killer == null) return;
        plugin.cosmetics().playKillEffect(killer, victim.getLocation());
        if (validKiller == null) { plugin.guard().reportInvalid(killer, victim, verdict); return; }

        PlayerData kd = plugin.store().get(validKiller);
        if (kd == null) return;
        synchronized (kd) { kd.kills++; kd.dirty = true; }
        plugin.streaks().onValidKill(validKiller, kd);
        plugin.quests().progress(validKiller, "KILLS", 1);
        long base = Math.max(0, plugin.cfg().eco().getLong("rewards.kill.base", 20));
        long cd = plugin.cfg().eco().getLong("rewards.kill.cooldown-seconds", 3) * 1000L;
        if (base > 0 && System.currentTimeMillis() - kd.getLong("reward.lastkill", 0) >= cd) {
            kd.setLong("reward.lastkill", System.currentTimeMillis());
            plugin.economy().deposit(validKiller.getUniqueId(), base, "kill:" + victim.getName(), "kill:" + validKiller.getUniqueId() + ":" + kd.kills);
            plugin.msg().send(validKiller, "economy.kill-reward", "amount", plugin.economy().fmt(base), "player", victim.getName());
        }
        plugin.bounties().onKill(validKiller, victim);
    }
}
