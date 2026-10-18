package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between AetherFFA and JustCombat. JustCombat is the combat system: it owns combat tagging, spawn/respawn
 * protection, blocked commands/items/elytra/teleports and the combat-log punishment (configure all of that in
 * plugins/JustCombat/config.yml). This class keeps the old AetherFFA API (isTagged, untag, isProtected ...) so kits,
 * capture zones, the kill pipeline and the admin menu keep working, and adds the two things JustCombat knows nothing
 * about: team friendly-fire and the Aether side of a combat logout (fine, staff alert, kill credit to the attacker).
 */
public final class CombatManager implements Listener {
    private final AetherFFA plugin;
    private final JustCombatHook jc;
    private final Map<UUID, UUID> logoutKills = new ConcurrentHashMap<>();
    private final Map<UUID, Long> penalized = new ConcurrentHashMap<>();
    private final Map<UUID, Long> msgThrottle = new ConcurrentHashMap<>();

    public CombatManager(AetherFFA plugin) {
        this.plugin = plugin;
        this.jc = JustCombatHook.create(plugin);
    }

    /** False when JustCombat is missing or incompatible; every query then answers "not tagged / not protected". */
    public boolean available() { return jc != null; }

    // ---------------- queries (delegated to JustCombat) ----------------

    public boolean isTagged(UUID id) { return jc != null && jc.inCombat(id); }
    public long tagSecondsLeft(UUID id) { return jc == null ? 0 : jc.secondsLeft(id); }
    public void untag(UUID id, boolean notify) { if (jc != null) jc.untag(id, notify); }
    public boolean isProtected(UUID id) { return jc != null && jc.isProtected(id); }
    public void endProtection(Player p) { if (jc != null) jc.endProtection(p.getUniqueId()); }
    public UUID consumeLogoutAttacker(UUID victim) { return logoutKills.remove(victim); }

    /** Housekeeping, called from the existing periodic cleanup. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        penalized.values().removeIf(t -> now - t > 60_000L);
        msgThrottle.values().removeIf(t -> now - t > 10_000L);
    }

    private boolean throttled(UUID id) {
        long now = System.currentTimeMillis();
        Long last = msgThrottle.get(id);
        if (last != null && now - last < 1500) return true;
        msgThrottle.put(id, now);
        return false;
    }

    private static Player damagerPlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // ---------------- team friendly-fire ----------------

    /**
     * Runs at LOW so a blocked teammate hit is cancelled before JustCombat's later handlers see it (a cancelled hit
     * must not tag anyone).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = damagerPlayer(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (plugin.teams().sameTeam(attacker.getUniqueId(), victim.getUniqueId())
                && plugin.worlds().enabled(victim.getWorld(), Feature.TEAMS)
                && !plugin.cfg().teams().getBoolean("friendly-fire", false)) {
            e.setCancelled(true);
            if (!throttled(attacker.getUniqueId())) plugin.msg().send(attacker, "teams.friendly-fire");
        }
    }

    // ---------------- combat logout: the Aether side ----------------

    /**
     * Runs at LOWEST, i.e. before JustCombat's own quit handler clears the tag and applies its punishment. We only
     * read the tag here; JustCombat decides and performs the actual penalty.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        msgThrottle.remove(id);
        if (jc == null || plugin.isShuttingDown()) return;
        if (!plugin.worlds().enabled(p.getWorld(), Feature.COMBAT_TAG)) return;
        if (!jc.inCombat(id)) return;                                                    // no valid tag: never punish
        var c = plugin.cfg().combat();
        if (e.getReason() == PlayerQuitEvent.QuitReason.KICKED && c.getBoolean("logout.ignore-kicks", true)) return;
        if (penalized.putIfAbsent(id, System.currentTimeMillis()) != null) return;       // no duplicate handling

        long finePct = Math.max(0, Math.min(100, c.getLong("logout.aether-fine-percent", 0)));
        if (finePct > 0) {
            long fine = plugin.economy().balance(id) * finePct / 100;
            if (fine > 0) plugin.economy().withdraw(id, fine, "combat-log-fine", null);
        }
        String action = jc.logoutAction();
        plugin.guard().alert("combat-log", id, p.getName() + " logged out while combat-tagged (JustCombat action " + action + ")");

        // Only a KILL logout produces a PlayerDeathEvent we can attach the attacker to.
        if (action.equals("KILL") && c.getBoolean("logout.credit-kill-to-attacker", true)) {
            UUID attacker = jc.opponent(id);
            if (attacker != null && !attacker.equals(id)) {
                logoutKills.put(id, attacker);
                Bukkit.getScheduler().runTaskLater(plugin, () -> logoutKills.remove(id), 40L);
            }
        }
    }
}
