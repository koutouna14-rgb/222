package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Kill effects and custom death messages. Purely cosmetic: no damage, knockback or stats are touched. */
public final class Cosmetics {
    private final AetherFFA plugin;

    public Cosmetics(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().cosmetics(); }
    public boolean enabled() { return c().getBoolean("enabled", true); }

    public List<String> effectIds() { ConfigurationSection s = c().getConfigurationSection("kill-effects"); return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false)); }
    public List<String> messageIds() { ConfigurationSection s = c().getConfigurationSection("death-messages"); return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false)); }

    private String path(boolean fx, String id) { return (fx ? "kill-effects." : "death-messages.") + id; }
    /** Plain-text name (safe for chat placeholders and titles). */
    public String nameOf(boolean fx, String id) { return Msg.plain(plugin.msg().mm(richName(fx, id))); }
    /** Name with MiniMessage colours/gradients, for menu item titles. */
    public String richName(boolean fx, String id) { return c().getString(path(fx, id) + ".name", id); }
    public long priceOf(boolean fx, String id) { return Math.max(0, c().getLong(path(fx, id) + ".price", 0)); }
    public String permOf(boolean fx, String id) { return c().getString(path(fx, id) + ".permission", ""); }
    public boolean exists(boolean fx, String id) { return c().isConfigurationSection(path(fx, id)); }

    /** Menu icon: the entry's own "icon" if set, else derived from the particle (effects) or rotated (messages). */
    public Material iconOf(boolean fx, String id) {
        String custom = c().getString(path(fx, id) + ".icon", "");
        if (!custom.isEmpty()) return Items.mat(custom, fx ? Material.FIREWORK_STAR : Material.OAK_SIGN);
        return fx ? Items.particleIcon(c().getString(path(true, id) + ".particle", "")) : Items.rotatingIcon(id);
    }

    public String shapeOf(String id) { return c().getString(path(true, id) + ".shape", "burst").toLowerCase(Locale.ROOT); }
    public String particleOf(String id) { return c().getString(path(true, id) + ".particle", "?"); }
    public String descriptionOf(boolean fx, String id) { return c().getString(path(fx, id) + ".description", ""); }

    public boolean owns(Player p, boolean fx, String id) {
        if (!exists(fx, id)) return false;
        PlayerData d = plugin.store().get(p);
        if (d == null) return false;
        if (priceOf(fx, id) <= 0 && permOf(fx, id).isEmpty()) return true;
        if (!permOf(fx, id).isEmpty() && p.hasPermission(permOf(fx, id))) return true;
        return d.has("cos." + (fx ? "fx." : "dm.") + id);
    }

    public boolean buy(Player p, boolean fx, String id) {
        if (!exists(fx, id)) return false;
        if (owns(p, fx, id)) { plugin.msg().send(p, "cosmetics.already-owned"); return false; }
        if (!permOf(fx, id).isEmpty() && priceOf(fx, id) <= 0) { plugin.msg().send(p, "cosmetics.permission-only"); return false; }
        PlayerData d = plugin.store().get(p);
        if (d == null) return false;
        String key = "cos." + (fx ? "fx." : "dm.") + id;
        Economy.Result r = plugin.economy().withdraw(p.getUniqueId(), priceOf(fx, id), "cosmetic:" + id, null, () -> d.kv.put(key, "1"));
        if (r == Economy.Result.INSUFFICIENT) { plugin.msg().send(p, "economy.insufficient"); return false; }
        if (r != Economy.Result.OK) { plugin.msg().send(p, "general.error"); return false; }
        plugin.msg().send(p, "cosmetics.purchased", "name", nameOf(fx, id), "price", plugin.economy().fmt(priceOf(fx, id)));
        return true;
    }

    public void select(Player p, boolean fx, String id) {
        PlayerData d = plugin.store().get(p);
        if (d == null) return;
        if (!id.isEmpty() && !owns(p, fx, id)) { plugin.msg().send(p, "cosmetics.locked"); return; }
        if (fx) d.effect = id; else d.deathMsg = id;
        d.dirty = true;
        plugin.msg().send(p, id.isEmpty() ? "cosmetics.deselected" : "cosmetics.selected", "name", id.isEmpty() ? "-" : nameOf(fx, id));
    }

    public void toggleEffects(Player p) {
        PlayerData d = plugin.store().get(p);
        if (d == null) return;
        d.effectsOn = !d.effectsOn; d.dirty = true;
        plugin.msg().send(p, d.effectsOn ? "cosmetics.effects-on" : "cosmetics.effects-off");
    }

    /** Plays the killer's selected effect at the victim's location. Particle count is capped for performance. */
    public void playKillEffect(Player killer, Location at) {
        if (!enabled() || !plugin.worlds().enabled(killer.getWorld(), Feature.COSMETICS)) return;
        PlayerData d = plugin.store().get(killer);
        if (d == null || !d.effectsOn || d.effect.isEmpty() || !owns(killer, true, d.effect)) return;
        playEffect(d.effect, at, null);
    }

    /** Preview is visible only to the previewing player. */
    public void preview(Player p, String id) {
        if (!exists(true, id)) return;
        playEffect(id, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2)), p);
    }

    /** Plays any kill effect by id for everyone in the world (used by kill-streak milestones). */
    public void playById(String id, Location at) {
        if (!enabled() || id == null || id.isEmpty() || !exists(true, id) || at.getWorld() == null) return;
        playEffect(id, at, null);
    }

    private void playEffect(String id, Location at, Player onlyFor) {
        ConfigurationSection s = c().getConfigurationSection(path(true, id));
        if (s == null || at.getWorld() == null) return;
        int cap = Math.max(1, c().getInt("settings.max-particles-per-effect", 60));
        int count = Math.min(cap, Math.max(1, s.getInt("count", 20)));
        Collection<Player> viewers = onlyFor != null ? List.of(onlyFor) : new ArrayList<>(at.getWorld().getPlayers());
        Location base = at.clone().add(0, 1, 0);
        String shape = s.getString("shape", "burst").toLowerCase(Locale.ROOT);
        String p1 = s.getString("particle", "FLAME"), p2 = s.getString("particle2", "");
        double spread = s.getDouble("spread", 0.4), speed = s.getDouble("speed", 0.05);
        int duration = Math.max(1, Math.min(40, s.getInt("duration", 16)));

        if (shape.equals("burst")) {
            spawn(base, p1, count, spread, speed, viewers);
            if (!p2.isEmpty()) spawn(base, p2, Math.max(1, count / 2), spread, speed, viewers);
        } else {
            animate(shape, base, p1, p2, count, spread, speed, duration, viewers);
        }
        if (s.getBoolean("lightning", false) && onlyFor == null) at.getWorld().strikeLightningEffect(at);   // visual only: no damage, no fire
        String sound = s.getString("sound", "");
        float vol = (float) s.getDouble("sound-volume", 0.8), pitch = (float) s.getDouble("sound-pitch", 1.0);
        playSound(sound, at, onlyFor, vol, pitch);
        String sound2 = s.getString("sound2", "");
        if (!sound2.isEmpty()) {
            long delay = Math.max(1, s.getInt("sound2-delay", 6));
            Bukkit.getScheduler().runTaskLater(plugin, () -> playSound(sound2, at, onlyFor, vol, pitch), delay);
        }
    }

    private static void playSound(String sound, Location at, Player onlyFor, float vol, float pitch) {
        if (sound == null || sound.isEmpty() || at.getWorld() == null) return;
        if (onlyFor != null) { if (onlyFor.isOnline()) onlyFor.playSound(at, sound, vol, pitch); }
        else at.getWorld().playSound(at, sound, vol, pitch);
    }

    /**
     * Short tick-based particle animations. Everything is plain particles for a few ticks (max 40): no entities, no
     * blocks, no damage. Particles per tick stay small so a full effect never exceeds a few hundred points.
     */
    private void animate(String shape, Location base, String p1, String p2, int count, double spread, double speed, int duration, Collection<Player> viewers) {
        final int per = Math.max(2, Math.min(24, count / 3));
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= duration || base.getWorld() == null) { cancel(); return; }
                double prog = (t + 1.0) / duration;
                java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
                switch (shape) {
                    case "ring" -> {            // expanding shockwave on the ground
                        double r = 0.3 + 2.6 * prog;
                        for (int i = 0; i < per * 2; i++) {
                            double a = Math.PI * 2 * i / (per * 2);
                            point(base.clone().add(Math.cos(a) * r, -0.85, Math.sin(a) * r), p1, speed);
                            if (!p2.isEmpty() && i % 2 == 0) point(base.clone().add(Math.cos(a) * r * 0.6, -0.85, Math.sin(a) * r * 0.6), p2, speed);
                        }
                    }
                    case "helix" -> {           // double helix climbing up the body
                        double y = -0.9 + prog * 2.9;
                        for (int strand = 0; strand < 2; strand++) {
                            for (int j = 0; j < 3; j++) {
                                double a = (prog + j * 0.012) * Math.PI * 6 + strand * Math.PI;
                                point(base.clone().add(Math.cos(a) * 0.95, y - j * 0.05, Math.sin(a) * 0.95), strand == 0 || p2.isEmpty() ? p1 : p2, speed);
                            }
                        }
                    }
                    case "spiral" -> {          // collapses inwards, like a black hole
                        double r = 2.4 * (1 - prog) + 0.15;
                        for (int i = 0; i < per; i++) {
                            double a = prog * Math.PI * 8 + Math.PI * 2 * i / per;
                            point(base.clone().add(Math.cos(a) * r, -0.6 + prog * 1.6, Math.sin(a) * r), i % 2 == 0 || p2.isEmpty() ? p1 : p2, speed);
                        }
                    }
                    case "column" -> {          // pillar of light / souls rising from the spot
                        for (int i = 0; i < per; i++) {
                            double a = rnd.nextDouble(Math.PI * 2), r = rnd.nextDouble(0.55);
                            point(base.clone().add(Math.cos(a) * r, -0.9 + rnd.nextDouble(3.2) * Math.min(1, prog * 1.6), Math.sin(a) * r), i % 3 == 0 && !p2.isEmpty() ? p2 : p1, speed);
                        }
                    }
                    case "tornado" -> {         // cone that widens upwards
                        for (int i = 0; i < per; i++) {
                            double h = 3.2 * i / per;
                            double a = t * 0.9 + i * 0.8;
                            double r = 0.2 + h * 0.38;
                            point(base.clone().add(Math.cos(a) * r, -0.9 + h, Math.sin(a) * r), i % 2 == 0 || p2.isEmpty() ? p1 : p2, speed);
                        }
                    }
                    case "sphere" -> {          // expanding bubble / supernova shell
                        double r = 0.2 + 2.3 * prog;
                        for (int i = 0; i < per * 2; i++) {
                            double x = rnd.nextGaussian(), y = rnd.nextGaussian(), z = rnd.nextGaussian();
                            double len = Math.sqrt(x * x + y * y + z * z);
                            if (len < 1e-6) continue;
                            point(base.clone().add(x / len * r, y / len * r * 0.8, z / len * r), i % 3 == 0 && !p2.isEmpty() ? p2 : p1, speed);
                        }
                    }
                    case "rain" -> {            // meteor shower dropping onto the spot
                        for (int i = 0; i < per; i++) {
                            double a = rnd.nextDouble(Math.PI * 2), r = rnd.nextDouble(1.6);
                            double y = 3.4 - prog * 4.2 + rnd.nextDouble(0.6);
                            point(base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), i % 3 == 0 && !p2.isEmpty() ? p2 : p1, speed);
                        }
                        if (t == duration - 1) spawn(base, p2.isEmpty() ? p1 : p2, Math.max(4, count / 2), Math.max(0.3, spread), 0.15, viewers);
                    }
                    case "ascend" -> {          // halo that lifts off the victim
                        double y = -0.8 + prog * 3.0;
                        for (int i = 0; i < per * 2; i++) {
                            double a = Math.PI * 2 * i / (per * 2) + t * 0.3;
                            point(base.clone().add(Math.cos(a) * 0.8, y, Math.sin(a) * 0.8), p1, speed);
                        }
                        if (!p2.isEmpty()) point(base.clone().add(0, y, 0), p2, speed);
                    }
                    default -> { spawn(base, p1, count, spread, speed, viewers); cancel(); return; }
                }
                t++;
            }
            private void point(Location l, String particle, double sp) { spawn(l, particle, 1, 0.0, sp, viewers); }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Spawns a plain (data-less) particle for the given viewers. Invalid names are ignored. */
    public static void spawn(Location loc, String name, int count, double spread, double speed, Collection<Player> viewers) {
        Particle particle = parse(name);
        if (particle == null || loc.getWorld() == null) return;
        try {
            for (Player v : viewers) {
                if (!v.isOnline() || !v.getWorld().equals(loc.getWorld()) || v.getLocation().distanceSquared(loc) > 48 * 48) continue;
                v.spawnParticle(particle, loc, count, spread, spread, spread, speed);
            }
        } catch (IllegalArgumentException ignored) { /* particle requires extra data: skip */ }
    }

    /**
     * Vanilla particle names that were renamed between Minecraft versions. Each config name is tried first, then these
     * alternatives, so the same cosmetics.yml works on 1.20.x and 1.21.x servers without editing.
     */
    private static final java.util.Map<String, String[]> ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("HAPPY_VILLAGER", new String[]{"VILLAGER_HAPPY"}),
            java.util.Map.entry("ANGRY_VILLAGER", new String[]{"VILLAGER_ANGRY"}),
            java.util.Map.entry("LARGE_SMOKE", new String[]{"SMOKE_LARGE", "SMOKE"}),
            java.util.Map.entry("ENCHANTED_HIT", new String[]{"CRIT_MAGIC", "CRIT"}),
            java.util.Map.entry("WITCH", new String[]{"SPELL_WITCH", "PORTAL"}),
            java.util.Map.entry("FIREWORK", new String[]{"FIREWORKS_SPARK", "END_ROD"}),
            java.util.Map.entry("EXPLOSION", new String[]{"EXPLOSION_LARGE", "EXPLOSION_NORMAL"}),
            java.util.Map.entry("ENCHANT", new String[]{"ENCHANTMENT_TABLE", "END_ROD"}),
            java.util.Map.entry("TOTEM_OF_UNDYING", new String[]{"TOTEM", "END_ROD"}),
            java.util.Map.entry("DAMAGE_INDICATOR", new String[]{"CRIT"}),
            java.util.Map.entry("SOUL_FIRE_FLAME", new String[]{"FLAME"}),
            java.util.Map.entry("SOUL", new String[]{"SMOKE_NORMAL", "CLOUD"}),
            java.util.Map.entry("SNOWFLAKE", new String[]{"SNOW_SHOVEL", "CLOUD"}),
            java.util.Map.entry("CHERRY_LEAVES", new String[]{"HAPPY_VILLAGER", "VILLAGER_HAPPY"}),
            java.util.Map.entry("GLOW", new String[]{"END_ROD"}),
            java.util.Map.entry("ELECTRIC_SPARK", new String[]{"CRIT", "END_ROD"}),
            java.util.Map.entry("WAX_ON", new String[]{"END_ROD"}),
            java.util.Map.entry("WHITE_ASH", new String[]{"CLOUD"}),
            java.util.Map.entry("CRIMSON_SPORE", new String[]{"SMOKE_NORMAL", "CLOUD"}),
            java.util.Map.entry("REVERSE_PORTAL", new String[]{"PORTAL"}),
            java.util.Map.entry("SONIC_BOOM", new String[]{"EXPLOSION", "CLOUD"}));

    private static Particle exact(String n) {
        try { return Particle.valueOf(n); } catch (IllegalArgumentException e) { return null; }
    }

    /** Resolves a vanilla particle by name, falling back to its renamed/older equivalent. Null only if nothing matches. */
    @SuppressWarnings("deprecation")
    public static Particle parse(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim().toUpperCase(Locale.ROOT);
        Particle p = exact(n);
        if (p != null) return p;
        String[] alt = ALIASES.get(n);
        if (alt != null) for (String a : alt) { p = exact(a); if (p != null) return p; }
        return null;
    }

    /** Startup check: logs every particle in cosmetics.yml / killstreaks.yml that cannot be resolved on this server. */
    static void audit(AetherFFA plugin) {
        List<String> bad = new ArrayList<>();
        ConfigurationSection fx = plugin.cfg().cosmetics().getConfigurationSection("kill-effects");
        if (fx != null) for (String id : fx.getKeys(false))
            for (String key : new String[]{"particle", "particle2"}) {
                String v = fx.getString(id + "." + key, "");
                if (!v.isEmpty() && !validParticle(v)) bad.add(id + "." + key + "=" + v);
            }
        ConfigurationSection ms = plugin.cfg().streaks().getConfigurationSection("milestones");
        if (ms != null) for (String id : ms.getKeys(false)) {
            String v = ms.getString(id + ".particle", "");
            if (!v.isEmpty() && !validParticle(v)) bad.add("streak " + id + ".particle=" + v);
        }
        if (!bad.isEmpty()) plugin.getLogger().warning("Particles not usable on this server version (they will be skipped): " + String.join(", ", bad));
    }

    public static boolean validParticle(String name) {
        Particle p = parse(name);
        return p != null && p.getDataType() == Void.class;
    }

    /**
     * Replaces the vanilla death message when the killer has a selected, owned custom message. The custom line is sent
     * to EVERY online player (and the console) and the vanilla one is suppressed, so it can never be limited to one
     * player or hidden by the showDeathMessages gamerule.
     */
    public void applyDeathMessage(PlayerDeathEvent e, Player killer, Player victim) {
        if (!enabled() || killer == null || !plugin.worlds().enabled(victim.getWorld(), Feature.COSMETICS)) return;
        PlayerData d = plugin.store().get(killer);
        if (d == null || d.deathMsg.isEmpty() || !owns(killer, false, d.deathMsg)) return;
        String fmt = c().getString(path(false, d.deathMsg) + ".format", "");
        if (fmt.isEmpty()) return;
        Component comp = plugin.msg().mm(fmt, "killer", killer.getName(), "victim", victim.getName());
        e.deathMessage(null);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(comp);
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    /** What a death message looks like, for the shop preview (sent to the previewing player only). */
    public Component previewDeathMessage(String id, Player viewer) {
        String fmt = c().getString(path(false, id) + ".format", "");
        return plugin.msg().mm(fmt, "killer", viewer.getName(), "victim", "Someone");
    }
}
