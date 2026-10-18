package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection bridge to JustCombat 1.3.x (no compile-time dependency, same approach JustCombat itself uses for JustTeams).
 * JustCombat owns combat tagging, spawn/respawn protection, command/item/elytra/teleport restrictions and the logout
 * punishment. AetherFFA only asks it questions ("is this player tagged?") and ties the results into its own economy.
 * Every call fails safe: on any reflection problem the answer is "not tagged / not protected" and one warning is logged.
 */
final class JustCombatHook {
    private final AetherFFA plugin;
    private final Plugin jc;
    private final Object combatMgr;
    private final Object newbieMgr;
    private final Object respawnListener;
    private final Field respawnMapField;

    private final Method isInCombat, getCombatTag, untagPlayer, untagSilently, getOpponent;
    private final Method newbieProtected, newbieDisable;
    private boolean warned;

    private JustCombatHook(AetherFFA plugin, Plugin jc) throws Exception {
        this.plugin = plugin;
        this.jc = jc;
        Class<?> main = jc.getClass();
        combatMgr = main.getMethod("getCombatManager").invoke(jc);
        newbieMgr = main.getMethod("getNewbieProtectionManager").invoke(jc);
        Class<?> cm = combatMgr.getClass();
        isInCombat = cm.getMethod("isInCombat", UUID.class);
        getCombatTag = cm.getMethod("getCombatTag", UUID.class);
        untagPlayer = cm.getMethod("untagPlayer", UUID.class);
        untagSilently = cm.getMethod("untagPlayerSilently", UUID.class);
        getOpponent = cm.getMethod("getCombatOpponent", UUID.class);
        Class<?> nm = newbieMgr.getClass();
        newbieProtected = nm.getMethod("isProtected", UUID.class);
        newbieDisable = nm.getMethod("disableProtection", UUID.class);

        // Respawn protection has no public getter in JustCombat 1.3.1: read its private UUID -> expiry map (best effort).
        Object rl = null; Field rf = null;
        try {
            Field lf = main.getDeclaredField("respawnProtectionListener");
            lf.setAccessible(true);
            rl = lf.get(jc);
            if (rl != null) { rf = rl.getClass().getDeclaredField("respawnProtection"); rf.setAccessible(true); }
        } catch (Throwable t) {
            plugin.getLogger().warning("JustCombat respawn-protection state is not readable (" + t + "); capture zones will only exclude newbie-protected players.");
            rl = null; rf = null;
        }
        respawnListener = rl;
        respawnMapField = rf;
    }

    /** Returns null when JustCombat is missing, disabled or its internals do not match what this bridge expects. */
    static JustCombatHook create(AetherFFA plugin) {
        Plugin jc = Bukkit.getPluginManager().getPlugin("JustCombat");
        if (jc == null || !jc.isEnabled()) {
            plugin.getLogger().severe("JustCombat is not enabled: combat tag, spawn protection and combat-log handling are INACTIVE.");
            return null;
        }
        try { return new JustCombatHook(plugin, jc); }
        catch (Throwable t) {
            plugin.getLogger().severe("JustCombat " + jc.getPluginMeta().getVersion() + " does not expose the methods AetherFFA needs (" + t
                    + "). Tested against 1.3.1. Combat features are INACTIVE.");
            return null;
        }
    }

    private void fail(Throwable t) {
        if (!warned) { warned = true; plugin.getLogger().warning("JustCombat call failed (further failures are silent): " + t); }
    }

    boolean inCombat(UUID id) {
        try { return (boolean) isInCombat.invoke(combatMgr, id); } catch (Throwable t) { fail(t); return false; }
    }

    long secondsLeft(UUID id) {
        try {
            Object tag = getCombatTag.invoke(combatMgr, id);
            if (tag == null) return 0;
            return Math.max(0, (long) tag.getClass().getMethod("getRemainingSeconds").invoke(tag));
        } catch (Throwable t) { fail(t); return 0; }
    }

    void untag(UUID id, boolean notify) {
        try { (notify ? untagPlayer : untagSilently).invoke(combatMgr, id); } catch (Throwable t) { fail(t); }
    }

    UUID opponent(UUID id) {
        try { return (UUID) getOpponent.invoke(combatMgr, id); } catch (Throwable t) { fail(t); return null; }
    }

    /** JustCombat's configured logout action: KILL, NOTHING or PUNISH. */
    String logoutAction() {
        try { return jc.getConfig().getString("combat.logout_action", "KILL").toUpperCase(java.util.Locale.ROOT); }
        catch (Throwable t) { fail(t); return "KILL"; }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> respawnMap() throws IllegalAccessException {
        return respawnMapField == null ? null : (Map<UUID, Object>) respawnMapField.get(respawnListener);
    }

    boolean isProtected(UUID id) {
        try {
            if ((boolean) newbieProtected.invoke(newbieMgr, id)) return true;
            Map<UUID, Object> m = respawnMap();
            Object v = m == null ? null : m.get(id);
            return v instanceof Long until && until > System.currentTimeMillis();
        } catch (Throwable t) { fail(t); return false; }
    }

    /** Ends newbie and respawn protection. Returns true if the player had any. */
    boolean endProtection(UUID id) {
        boolean had = false;
        try {
            if ((boolean) newbieProtected.invoke(newbieMgr, id)) { newbieDisable.invoke(newbieMgr, id); had = true; }
            Map<UUID, Object> m = respawnMap();
            if (m != null && m.remove(id) != null) had = true;
        } catch (Throwable t) { fail(t); }
        return had;
    }
}
