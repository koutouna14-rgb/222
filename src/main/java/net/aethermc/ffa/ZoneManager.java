package net.aethermc.ffa;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive capture zones: eligible players inside a zone earn Aether periodically. There is no capture progress, ownership
 * or arena logic. WorldGuard (via {@link Worlds#inSafeZone}) is only consulted to decide eligibility.
 */
public final class ZoneManager {
    public enum Mode { INDIVIDUAL, CONTESTED, MAJORITY, TEAM_SHARED }
    public enum Shape { CUBOID, CYLINDER }
    public enum Status { DISABLED, WORLD_DISABLED, EMPTY, ACTIVE, CONTESTED, GRACE, WAITING_MIN, TOO_MANY, NO_MAJORITY }

    public static final class Zone {
        public final String id, world;
        public final Shape shape;
        public final boolean enabled;
        final double minX, minY, minZ, maxX, maxY, maxZ, cx, cz, radius;
        Zone(String id, ConfigurationSection s) {
            this.id = id;
            this.world = s.getString("world", "world");
            this.shape = parse(s.getString("shape", "CUBOID"));
            this.enabled = s.getBoolean("enabled", true);
            this.minX = Math.min(s.getInt("min-x"), s.getInt("max-x")); this.maxX = Math.max(s.getInt("min-x"), s.getInt("max-x"));
            this.minY = Math.min(s.getInt("min-y"), s.getInt("max-y")); this.maxY = Math.max(s.getInt("min-y"), s.getInt("max-y"));
            this.minZ = Math.min(s.getInt("min-z"), s.getInt("max-z")); this.maxZ = Math.max(s.getInt("min-z"), s.getInt("max-z"));
            this.cx = s.getDouble("center-x"); this.cz = s.getDouble("center-z"); this.radius = Math.max(0.5, s.getDouble("radius", 5));
        }
        private static Shape parse(String v) { try { return Shape.valueOf(v.toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return Shape.CUBOID; } }

        public boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            double x = l.getX(), y = l.getY(), z = l.getZ();
            if (y < minY || y >= maxY + 1) return false;
            if (shape == Shape.CUBOID) return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
            double dx = x - cx, dz = z - cz;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    public static final class State {
        public volatile Status status = Status.EMPTY;
        public volatile int occupants, eligible;
        public volatile long graceLeftMs;
        public volatile long paidTotal, payouts;
    }

    private record Anchor(double x, double y, double z, String world, long since) { }

    private final AetherFFA plugin;
    private volatile List<Zone> zones = new ArrayList<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> previous = new HashMap<>();
    private final Map<String, Map<UUID, Double>> accum = new HashMap<>();
    private final Map<String, Map<String, Double>> sideAccum = new HashMap<>();
    private final Map<String, Map<UUID, Long>> seen = new HashMap<>();
    private final Map<String, Map<UUID, Long>> enteredAt = new HashMap<>();
    private final Map<String, Long> lastContested = new HashMap<>();
    private final Map<String, Long> teamPaid = new HashMap<>();
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private final Map<UUID, long[]> hourly = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Location[]> selections = new ConcurrentHashMap<>();
    private long lastTick = System.currentTimeMillis();

    public ZoneManager(AetherFFA plugin) { this.plugin = plugin; reload(); }

    private YamlConfiguration c() { return plugin.cfg().zones(); }

    public void reload() {
        List<Zone> list = new ArrayList<>();
        ConfigurationSection s = c().getConfigurationSection("zones");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection z = s.getConfigurationSection(id);
            if (z != null) list.add(new Zone(id, z));
        }
        zones = list;
        states.keySet().removeIf(k -> list.stream().noneMatch(z -> z.id.equals(k)));
    }

    public List<Zone> zones() { return zones; }
    public Zone get(String id) { for (Zone z : zones) if (z.id.equalsIgnoreCase(id)) return z; return null; }
    public State state(String id) { return states.computeIfAbsent(id, k -> new State()); }

    /** Zone setting with fallback to defaults.*. */
    public ConfigurationSection settings(String id) { return c().getConfigurationSection("zones." + id); }
    private Object val(String id, String key, Object def) {
        String zp = "zones." + id + "." + key;
        if (c().contains(zp, true)) return c().get(zp);
        String dp = "defaults." + key;
        return c().contains(dp, true) ? c().get(dp) : def;
    }
    private double num(String id, String key, double def) { Object o = val(id, key, def); return o instanceof Number n ? n.doubleValue() : def; }
    private boolean bool(String id, String key, boolean def) { Object o = val(id, key, def); return o instanceof Boolean b ? b : def; }
    private String str(String id, String key, String def) { Object o = val(id, key, def); return o == null ? def : o.toString(); }
    public String displayName(String id) { return c().getString("zones." + id + ".name", id); }

    public Mode mode(String id) { try { return Mode.valueOf(str(id, "mode", "INDIVIDUAL").toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return Mode.INDIVIDUAL; } }

    // ---- selections / admin ops ----

    public void select(Player p, int which) {
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        sel[which] = p.getLocation().getBlock().getLocation();
    }
    public Location[] selection(Player p) { return selections.get(p.getUniqueId()); }

    /** True when zones are drawn with the WorldEdit selection instead of the built-in pos1/pos2 corners. */
    public boolean usesWorldEdit() { return plugin.we() != null; }

    /** The admin's selection: their WorldEdit selection when WorldEdit is installed, else the built-in pos1/pos2 corners. */
    ZoneSel resolve(Player p) {
        if (plugin.we() != null) {
            try { return plugin.we().selection(p); }
            catch (Throwable t) { plugin.getLogger().warning("WorldEdit selection could not be read: " + t); return ZoneSel.error("zones.we-failed"); }
        }
        Location[] sel = selections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) return ZoneSel.error("zones.need-selection");
        if (!sel[0].getWorld().equals(sel[1].getWorld())) return ZoneSel.error("zones.selection-world");
        return ZoneSel.box(sel[0].getWorld(), sel[0].getBlockX(), sel[0].getBlockY(), sel[0].getBlockZ(), sel[1].getBlockX(), sel[1].getBlockY(), sel[1].getBlockZ());
    }

    /** Creates a zone from the admin's current selection (WorldEdit cuboid/cylinder, or built-in corners without WorldEdit). */
    public String createFromSelection(Player p, String id) {
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{2,24}")) return "zones.invalid-id";
        if (get(id) != null) return "zones.exists";
        ZoneSel sel = resolve(p);
        if (sel.err() != null) return sel.err();
        String b = "zones." + id;
        c().set(b + ".name", id); c().set(b + ".enabled", true);
        writeShape(b, sel);
        return finish();
    }

    /** Writes a resolved shape into a zone's config section, clearing the keys of the other shape. */
    private void writeShape(String b, ZoneSel s) {
        c().set(b + ".world", s.world().getName());
        c().set(b + ".min-y", s.minY()); c().set(b + ".max-y", s.maxY());
        if (s.circle()) {
            c().set(b + ".shape", "CYLINDER");
            c().set(b + ".center-x", s.cx()); c().set(b + ".center-z", s.cz()); c().set(b + ".radius", s.radius());
            for (String k : new String[]{"min-x", "max-x", "min-z", "max-z"}) c().set(b + "." + k, null);
        } else {
            c().set(b + ".shape", "CUBOID");
            c().set(b + ".min-x", s.minX()); c().set(b + ".max-x", s.maxX());
            c().set(b + ".min-z", s.minZ()); c().set(b + ".max-z", s.maxZ());
            for (String k : new String[]{"center-x", "center-z", "radius"}) c().set(b + "." + k, null);
        }
    }

    public String createCylinder(Player p, String id, double radius, int below, int above) {
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{2,24}")) return "zones.invalid-id";
        if (get(id) != null) return "zones.exists";
        if (radius < 1 || radius > 500) return "zones.bad-radius";
        Location l = p.getLocation();
        String b = "zones." + id;
        c().set(b + ".name", id); c().set(b + ".enabled", true); c().set(b + ".world", l.getWorld().getName()); c().set(b + ".shape", "CYLINDER");
        c().set(b + ".center-x", l.getBlockX() + 0.5); c().set(b + ".center-z", l.getBlockZ() + 0.5); c().set(b + ".radius", radius);
        c().set(b + ".min-y", l.getBlockY() - Math.max(0, below)); c().set(b + ".max-y", l.getBlockY() + Math.max(1, above));
        return finish();
    }

    public String remove(String id) {
        if (get(id) == null) return "zones.unknown";
        c().set("zones." + id.toLowerCase(Locale.ROOT), null);
        clearRuntime(id);
        return finish();
    }

    public String setEnabled(String id, boolean on) {
        if (get(id) == null) return "zones.unknown";
        c().set("zones." + id + ".enabled", on);
        return finish();
    }

    /** Replaces an existing zone's shape with the admin's current selection (WorldEdit, or built-in corners without it). */
    public String applySelection(Player p, String id) {
        Zone z = get(id);
        if (z == null) return "zones.unknown";
        ZoneSel sel = resolve(p);
        if (sel.err() != null) return sel.err();
        writeShape("zones." + z.id, sel);
        return finish();
    }

    /** Moves a cylinder zone's centre (and vertical span) to the admin's current position. */
    public String recenter(Player p, String id) {
        Zone z = get(id);
        if (z == null) return "zones.unknown";
        Location l = p.getLocation();
        String b = "zones." + z.id;
        double r = c().getDouble(b + ".radius", 8);
        int below = Math.max(0, (int) (z.maxY - z.minY) / 3), above = Math.max(1, (int) (z.maxY - z.minY) - below);
        c().set(b + ".shape", "CYLINDER"); c().set(b + ".world", l.getWorld().getName());
        c().set(b + ".center-x", l.getBlockX() + 0.5); c().set(b + ".center-z", l.getBlockZ() + 0.5); c().set(b + ".radius", r);
        c().set(b + ".min-y", l.getBlockY() - below); c().set(b + ".max-y", l.getBlockY() + above);
        return finish();
    }

    public Location center(Zone z) {
        World w = Bukkit.getWorld(z.world);
        if (w == null) return null;
        if (z.shape == Shape.CYLINDER) return new Location(w, z.cx, z.maxY, z.cz);
        return new Location(w, (z.minX + z.maxX + 1) / 2, z.maxY + 1, (z.minZ + z.maxZ + 1) / 2);
    }

    private String finish() {
        String err = plugin.cfg().save(Cfg.ZONES);
        reload();
        return err == null ? null : "general.save-failed";
    }

    private void clearRuntime(String id) {
        previous.remove(id); accum.remove(id); sideAccum.remove(id); seen.remove(id); enteredAt.remove(id); lastContested.remove(id); states.remove(id);
    }

    // ---- eligibility ----

    private boolean afk(Player p, long now) {
        long afkMs = (long) (plugin.cfg().zones().getDouble("afk.seconds", 60) * 1000);
        if (afkMs <= 0) return false;
        Anchor a = anchors.get(p.getUniqueId());
        Location l = p.getLocation();
        double minMove = plugin.cfg().zones().getDouble("afk.min-movement-blocks", 1.5);
        if (a == null || !a.world().equals(l.getWorld().getName())
                || Math.abs(a.x() - l.getX()) + Math.abs(a.y() - l.getY()) + Math.abs(a.z() - l.getZ()) > minMove) {
            anchors.put(p.getUniqueId(), new Anchor(l.getX(), l.getY(), l.getZ(), l.getWorld().getName(), now));
            return false;
        }
        return now - a.since() > afkMs;
    }

    /** Marks recent real activity (combat) so players fighting on the spot are not treated as AFK. */
    public void touch(Player p) {
        Location l = p.getLocation();
        anchors.put(p.getUniqueId(), new Anchor(l.getX(), l.getY(), l.getZ(), l.getWorld().getName(), System.currentTimeMillis()));
    }

    private boolean eligible(Zone z, Player p, long now, boolean isAfk) {
        if (p.isDead() || isAfk) return false;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        if (plugin.store().get(p) == null || plugin.isDegraded(p.getUniqueId())) return false;
        if (!p.hasPermission("aetherffa.capture.earn")) return false;
        boolean safe = plugin.worlds().inSafeZone(p);
        if (safe && !(bool(z.id, "safe-zone-rewards", false) || plugin.worlds().safeZoneCaptureRewards(z.world))) return false;
        if (bool(z.id, "exclude-spawn-protected", true) && plugin.combat().isProtected(p.getUniqueId())) return false;
        long lockout = plugin.cfg().teams().getLong("rules.reward-lockout-after-change-seconds", 120) * 1000L;
        if (lockout > 0 && plugin.teams().recentlyChanged(p.getUniqueId(), lockout)) return false;
        Long entered = enteredAt.getOrDefault(z.id, Map.of()).get(p.getUniqueId());
        long delay = (long) (num(z.id, "entry-delay-seconds", 3) * 1000);
        return entered != null && now - entered >= delay;
    }

    private String sideOf(Player p) {
        Team t = plugin.teams().teamOf(p.getUniqueId());
        return t == null || !plugin.worlds().enabled(p.getWorld(), Feature.TEAMS) ? "p:" + p.getUniqueId() : "t:" + t.id;
    }

    // ---- main tick (every second) ----

    public void tick() {
        long now = System.currentTimeMillis();
        double dt = Math.max(0, Math.min(3, (now - lastTick) / 1000.0));
        lastTick = now;
        if (!c().getBoolean("enabled", true)) { hideAllBars(); return; }
        Map<String, List<Player>> in = new HashMap<>();
        Map<UUID, Boolean> afkCache = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            for (Zone z : zones) {
                if (!z.enabled || !z.contains(p.getLocation())) continue;
                if (!plugin.worlds().enabled(p.getWorld(), Feature.CAPTURE_ZONES)) continue;
                in.computeIfAbsent(z.id, k -> new ArrayList<>()).add(p);
                afkCache.computeIfAbsent(p.getUniqueId(), k -> afk(p, now));
            }
        }
        Set<UUID> shownBars = new HashSet<>();
        for (Zone z : zones) {
            State st = state(z.id);
            if (!z.enabled) { st.status = Status.DISABLED; st.occupants = 0; st.eligible = 0; handleEnterExit(z, List.of()); continue; }
            if (Bukkit.getWorld(z.world) == null || !plugin.worlds().enabled(z.world, Feature.CAPTURE_ZONES)) { st.status = Status.WORLD_DISABLED; st.occupants = 0; st.eligible = 0; handleEnterExit(z, List.of()); continue; }
            List<Player> occ = in.getOrDefault(z.id, List.of());
            process(z, occ, afkCache, now, dt, st, shownBars);
        }
        for (UUID id : new ArrayList<>(bars.keySet())) if (!shownBars.contains(id)) hideBar(id);
        if (now % 7 < 2) purge(now);
    }

    private void handleEnterExit(Zone z, List<Player> occ) {
        Set<UUID> cur = new HashSet<>();
        for (Player p : occ) cur.add(p.getUniqueId());
        Set<UUID> prev = previous.getOrDefault(z.id, Set.of());
        Map<UUID, Long> ent = enteredAt.computeIfAbsent(z.id, k -> new HashMap<>());
        long now = System.currentTimeMillis();
        for (Player p : occ) if (!prev.contains(p.getUniqueId())) {
            ent.put(p.getUniqueId(), now);
            if (bool(z.id, "messages.enter", true)) plugin.msg().send(p, "zones.entered", "zone", displayName(z.id));
        }
        for (UUID u : prev) if (!cur.contains(u)) {
            ent.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null && bool(z.id, "messages.exit", true)) plugin.msg().send(p, "zones.left", "zone", displayName(z.id));
        }
        previous.put(z.id, cur);
    }

    private void process(Zone z, List<Player> occ, Map<UUID, Boolean> afkCache, long now, double dt, State st, Set<UUID> shownBars) {
        handleEnterExit(z, occ);
        Map<UUID, Long> seenMap = seen.computeIfAbsent(z.id, k -> new HashMap<>());
        List<Player> eligible = new ArrayList<>();
        for (Player p : occ) { seenMap.put(p.getUniqueId(), now); if (eligible(z, p, now, afkCache.getOrDefault(p.getUniqueId(), false))) eligible.add(p); }
        st.occupants = occ.size(); st.eligible = eligible.size();
        Mode mode = mode(z.id);
        int min = (int) num(z.id, "min-players", 1), max = (int) num(z.id, "max-players", 0);
        double interval = Math.max(1, num(z.id, "interval-seconds", 30));
        List<Player> payees = new ArrayList<>(eligible);
        st.graceLeftMs = 0;
        if (occ.isEmpty()) st.status = Status.EMPTY;
        else if (eligible.size() < min) { st.status = Status.WAITING_MIN; payees.clear(); }
        else if (max > 0 && eligible.size() > max) { st.status = Status.TOO_MANY; payees.clear(); }
        else st.status = Status.ACTIVE;

        if (st.status == Status.ACTIVE && mode == Mode.CONTESTED) {
            Set<String> sides = new HashSet<>();
            boolean countAfk = bool(z.id, "contest.count-afk", false);
            for (Player p : occ) if (countAfk || !afkCache.getOrDefault(p.getUniqueId(), false)) sides.add(sideOf(p));
            long graceMs = (long) (num(z.id, "contest.grace-seconds", 5) * 1000);
            if (sides.size() >= 2) { lastContested.put(z.id, now); st.status = Status.CONTESTED; payees.clear(); }
            else {
                long since = now - lastContested.getOrDefault(z.id, 0L);
                if (since < graceMs) { st.status = Status.GRACE; st.graceLeftMs = graceMs - since; payees.clear(); }
            }
        } else if (st.status == Status.ACTIVE && mode == Mode.MAJORITY) {
            Map<String, List<Player>> bySide = new LinkedHashMap<>();
            for (Player p : eligible) bySide.computeIfAbsent(sideOf(p), k -> new ArrayList<>()).add(p);
            double need = num(z.id, "majority.percent", 51);
            List<Player> win = null; int top = 0, second = 0;
            for (List<Player> l : bySide.values()) { if (l.size() > top) { second = top; top = l.size(); win = l; } else if (l.size() > second) second = l.size(); }
            if (win != null && top > second && top * 100.0 / eligible.size() >= need) { payees = new ArrayList<>(win); }
            else { st.status = Status.NO_MAJORITY; payees.clear(); }
        }

        Map<UUID, Double> acc = accum.computeIfAbsent(z.id, k -> new HashMap<>());
        double reward = Math.max(0, num(z.id, "reward", 10));
        if (!payees.isEmpty() && reward > 0) {
            if (mode == Mode.TEAM_SHARED) paySides(z, payees, eligible, dt, interval, reward);
            else payIndividuals(z, payees, eligible, acc, dt, interval, reward, now);
        }
        for (Player p : payees) plugin.quests().progress(p, "CAPTURE_SECONDS", 1);
        display(z, occ, eligible, payees, st, acc, interval, shownBars);
    }

    private void payIndividuals(Zone z, List<Player> payees, List<Player> eligible, Map<UUID, Double> acc, double dt, double interval, double reward, long now) {
        String rule = str(z.id, "team-rule", "SPLIT").toUpperCase(Locale.ROOT);
        for (Player p : payees) {
            double v = acc.merge(p.getUniqueId(), dt, Double::sum);
            if (v < interval) continue;
            acc.put(p.getUniqueId(), 0.0);
            long amount = (long) reward;
            Team t = plugin.teams().teamOf(p.getUniqueId());
            if (t != null && plugin.worlds().enabled(p.getWorld(), Feature.TEAMS)) {
                int mates = 0;
                for (Player e : eligible) if (t.members.contains(e.getUniqueId())) mates++;
                if (rule.equals("SPLIT") && mates > 1) amount = Math.max(1, (long) (reward / mates));
                else if (rule.equals("ONE_PER_TEAM")) {
                    String key = z.id + ":" + t.id;
                    Long last = teamPaid.get(key);
                    if (last != null && now - last < interval * 900) continue;   // a teammate already got this interval's payout
                    teamPaid.put(key, now);
                }
            }
            pay(z, p, amount);
        }
    }

    private void paySides(Zone z, List<Player> payees, List<Player> eligible, double dt, double interval, double reward) {
        Map<String, Double> acc = sideAccum.computeIfAbsent(z.id, k -> new HashMap<>());
        Map<String, List<Player>> bySide = new LinkedHashMap<>();
        for (Player p : payees) bySide.computeIfAbsent(sideOf(p), k -> new ArrayList<>()).add(p);
        String dist = str(z.id, "team-shared.distribution", "SPLIT").toUpperCase(Locale.ROOT);
        double bonus = num(z.id, "team-shared.bonus-per-extra-member", 0);
        for (var e : bySide.entrySet()) {
            double v = acc.merge(e.getKey(), dt, Double::sum);
            if (v < interval) continue;
            acc.put(e.getKey(), 0.0);
            List<Player> members = e.getValue();
            long total = (long) (reward + bonus * (members.size() - 1));
            switch (dist) {
                case "FULL_EACH" -> { for (Player p : members) pay(z, p, (long) reward); }
                case "FIRST_ONLY" -> pay(z, members.get(0), total);
                default -> { long each = Math.max(1, total / members.size()); for (Player p : members) pay(z, p, each); }
            }
        }
    }

    private void pay(Zone z, Player p, long amount) {
        if (amount <= 0) return;
        long cap = (long) num(z.id, "limit.per-hour", 0);
        if (cap > 0) {
            long hour = System.currentTimeMillis() / 3_600_000L;
            long[] h = hourly.computeIfAbsent(p.getUniqueId(), k -> new long[]{hour, 0});
            if (h[0] != hour) { h[0] = hour; h[1] = 0; }
            long room = cap - h[1];
            if (room <= 0) { plugin.msg().send(p, "zones.limit-reached"); return; }
            amount = Math.min(amount, room);
            h[1] += amount;
        }
        Economy.Result r = plugin.economy().deposit(p.getUniqueId(), amount, "capture:" + z.id, null);
        if (r != Economy.Result.OK) return;
        State st = state(z.id);
        st.paidTotal += amount; st.payouts++;
        plugin.quests().progress(p, "CAPTURE_PAYOUTS", 1);
        if (bool(z.id, "messages.reward", true)) plugin.msg().send(p, "zones.reward", "amount", plugin.economy().fmt(amount), "zone", displayName(z.id));
        String sound = str(z.id, "reward-sound", "entity.experience_orb.pickup");
        if (!sound.isEmpty()) p.playSound(p.getLocation(), sound, 0.6f, 1.2f);
    }

    // ---- display ----

    private void display(Zone z, List<Player> occ, List<Player> eligible, List<Player> payees, State st, Map<UUID, Double> acc, double interval, Set<UUID> shown) {
        boolean bossbar = bool(z.id, "display.bossbar", false), action = bool(z.id, "display.actionbar", true);
        if (!bossbar && !action) return;
        for (Player p : occ) {
            double progress = Math.min(1.0, acc.getOrDefault(p.getUniqueId(), 0.0) / interval);
            String statusKey = !eligible.contains(p) && st.status == Status.ACTIVE ? "ineligible" : st.status.name().toLowerCase(Locale.ROOT);
            var comp = plugin.msg().mm(plugin.msg().raw("zones.actionbar"), "zone", displayName(z.id),
                    "status", Msg.plain(plugin.msg().get("zones.status." + statusKey, "seconds", st.graceLeftMs / 1000 + 1)),
                    "seconds", (long) Math.ceil(Math.max(0, interval - acc.getOrDefault(p.getUniqueId(), 0.0))));
            if (bossbar) {
                BossBar bar = bars.get(p.getUniqueId());
                if (bar == null) {
                    BossBar.Color color;
                    try { color = BossBar.Color.valueOf(str(z.id, "display.bossbar-color", "BLUE").toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { color = BossBar.Color.BLUE; }
                    bar = BossBar.bossBar(comp, (float) progress, color, BossBar.Overlay.PROGRESS);
                    bars.put(p.getUniqueId(), bar);
                    p.showBossBar(bar);
                } else { bar.name(comp); bar.progress((float) progress); }
                shown.add(p.getUniqueId());
            } else p.sendActionBar(comp);
        }
    }

    private void hideBar(UUID id) {
        BossBar bar = bars.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (bar != null && p != null) p.hideBossBar(bar);
    }
    private void hideAllBars() { for (UUID id : new ArrayList<>(bars.keySet())) hideBar(id); }

    public void onQuit(UUID id) {
        hideBar(id); anchors.remove(id); hourly.remove(id); selections.remove(id);
        for (Set<UUID> s : previous.values()) s.remove(id);
        for (Map<UUID, Long> m : enteredAt.values()) m.remove(id);
    }

    private void purge(long now) {
        long keep = (long) (plugin.cfg().zones().getDouble("progress-keep-seconds", 120) * 1000);
        for (var e : seen.entrySet()) {
            Map<UUID, Double> acc = accum.get(e.getKey());
            e.getValue().entrySet().removeIf(en -> {
                if (now - en.getValue() > keep) { if (acc != null) acc.remove(en.getKey()); return true; }
                return false;
            });
        }
        teamPaid.values().removeIf(t -> now - t > 3_600_000L);
        hourly.values().removeIf(h -> h[0] != now / 3_600_000L);
        anchors.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        for (var e : sideAccum.entrySet()) if (previous.getOrDefault(e.getKey(), Set.of()).isEmpty()) e.getValue().clear();
    }

    public void shutdown() { hideAllBars(); }

    /** Human-readable lines for /capturezone info. */
    public List<String> info(String id) {
        Zone z = get(id);
        List<String> out = new ArrayList<>();
        if (z == null) return out;
        State st = state(id);
        out.add("<gray>Zone: <white>" + id + " <gray>(" + displayName(id) + ")");
        out.add("<gray>World: <white>" + z.world + " <gray>Shape: <white>" + z.shape + " <gray>Enabled: " + Items.onOff(z.enabled));
        out.add("<gray>Mode: <white>" + mode(id) + " <gray>Reward: <white>" + (long) num(id, "reward", 10) + " <gray>every <white>" + (long) num(id, "interval-seconds", 30) + "s");
        out.add("<gray>Status: <white>" + st.status + " <gray>Occupants: <white>" + st.occupants + " <gray>Eligible: <white>" + st.eligible);
        out.add("<gray>Paid since start: <white>" + Amounts.format(st.paidTotal) + " <gray>in <white>" + st.payouts + " <gray>payouts");
        return out;
    }

    public Status statusOf(String id) { return state(id).status; }

    /** Zone the player currently stands in, or null. */
    public Zone zoneAt(Player p) {
        for (Zone z : zones) if (z.enabled && z.contains(p.getLocation())) return z;
        return null;
    }
}
