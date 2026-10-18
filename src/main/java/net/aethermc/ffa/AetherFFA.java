package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AetherFFA: gameplay systems for existing battleground worlds. No arenas, no NPC management, no region management. */
public final class AetherFFA extends JavaPlugin {
    private static AetherFFA instance;
    private Cfg cfg; private Db db; private Msg msg; private PlayerStore store; private Economy economy; private Worlds worlds;
    private Guard guard; private TeamManager teams; private TeamGlow glow; private KitManager kits; private ZoneManager zones;
    private BountyManager bounties; private StreakManager streaks; private QuestManager quests; private Cosmetics cosmetics;
    private CombatManager combat; private Leaderboards boards; private Prompts prompts; private WgHook wg; private WeHook we;
    private final Set<UUID> degraded = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;
    private BukkitTask boardTask;
    private boolean papi, fancyNpcs;

    public static AetherFFA get() { return instance; }
    public Cfg cfg() { return cfg; }
    public Db db() { return db; }
    public Msg msg() { return msg; }
    public PlayerStore store() { return store; }
    public Economy economy() { return economy; }
    public Worlds worlds() { return worlds; }
    public Guard guard() { return guard; }
    public TeamManager teams() { return teams; }
    public TeamGlow glow() { return glow; }
    public KitManager kits() { return kits; }
    public ZoneManager zones() { return zones; }
    public BountyManager bounties() { return bounties; }
    public StreakManager streaks() { return streaks; }
    public QuestManager quests() { return quests; }
    public Cosmetics cosmetics() { return cosmetics; }
    public CombatManager combat() { return combat; }
    public Leaderboards boards() { return boards; }
    public Prompts prompts() { return prompts; }
    /** Null when WorldGuard is absent: safe zones are then simply never detected. */
    public WgHook wg() { return wg; }
    /** Null when WorldEdit/FAWE is absent: capture zones then fall back to the built-in pos1/pos2 corners. */
    public WeHook we() { return we; }
    @SuppressWarnings("deprecation")
    public String version() { return getDescription().getVersion(); }
    public boolean hasPapi() { return papi; }
    public boolean hasFancyNpcs() { return fancyNpcs; }
    public boolean isShuttingDown() { return shuttingDown; }
    public boolean isDegraded(UUID id) { return degraded.contains(id); }
    public void markDegraded(UUID id) { degraded.add(id); }
    public void clearDegraded(UUID id) { degraded.remove(id); }

    @Override public void onEnable() {
        instance = this;
        cfg = new Cfg(this);
        List<String> problems = cfg.loadAll();
        for (String p : problems) getLogger().severe("Config problem: " + p);
        msg = new Msg(this);
        db = new Db(this, new File(getDataFolder(), cfg.dbc().getString("file", "aetherffa.db")));
        try { db.open(); }
        catch (Exception e) {
            getLogger().severe("Could not open the database: " + e.getMessage() + " - disabling AetherFFA.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store = new PlayerStore(this);
        economy = new Economy(this);
        worlds = new Worlds(this);
        guard = new Guard(this);
        teams = new TeamManager(this);
        glow = new TeamGlow(this);
        kits = new KitManager(this);
        PremadeKits.installIfFresh(this);
        ContentUpdate.run(this);
        bounties = new BountyManager(this);
        streaks = new StreakManager(this);
        quests = new QuestManager(this);
        cosmetics = new Cosmetics(this);
        combat = new CombatManager(this);
        boards = new Leaderboards(this);
        prompts = new Prompts(this);
        zones = new ZoneManager(this);
        try { teams.load(); bounties.load(); }
        catch (Exception e) { getLogger().severe("Failed to load teams/bounties: " + e.getMessage()); }
        economy.recoverPending();

        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try { wg = new WgHook(); getLogger().info("WorldGuard hook active (read-only safe-zone detection)."); }
            catch (Throwable t) { getLogger().warning("WorldGuard present but its API could not be used: " + t); wg = null; }
        } else getLogger().info("WorldGuard not found: safe-zone rules are inactive.");
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit") || Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            try { we = new WeHook(); getLogger().info("WorldEdit hook active (capture zones are drawn with your WorldEdit selection)."); }
            catch (Throwable t) { getLogger().warning("WorldEdit present but its API could not be used: " + t); we = null; }
        } else getLogger().info("WorldEdit not found: capture zones use /capturezone pos1 and pos2.");
        fancyNpcs = Bukkit.getPluginManager().isPluginEnabled("FancyNpcs");   // detection only; AetherFFA never touches NPCs
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { new PapiExpansion(this).register(); papi = true; }
            catch (Throwable t) { getLogger().warning("PlaceholderAPI hook failed: " + t); }
        }

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(prompts, this);
        pm.registerEvents(new FfaListener(this), this);
        pm.registerEvents(combat, this);
        if (combat.available()) getLogger().info("JustCombat hook active: JustCombat handles combat tag, spawn protection and combat logging.");
        pm.registerEvents(glow, this);
        glow.start();
        registerCommands();

        Bukkit.getScheduler().runTaskTimer(this, () -> zones.tick(), 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> store.flushDirty(), 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { bounties.tick(); quests.onlineTick(); }, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { teams.tick(); store.purgePending(); }, 200L, 200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { guard.cleanup(); economy.cleanupRefs(); combat.cleanup(); }, 6000L, 6000L);
        scheduleBoards();
        getLogger().info("AetherFFA enabled.");
    }

    private void scheduleBoards() {
        if (boardTask != null) boardTask.cancel();
        long ticks = Math.max(10, cfg.boards().getLong("refresh-seconds", 60)) * 20L;
        boardTask = Bukkit.getScheduler().runTaskTimer(this, () -> boards.refresh(), 100L, ticks);
    }

    private void registerCommands() {
        reg("aether", new AetherCmd(this));
        reg("kits", new KitCmd(this)); reg("kit", new KitCmd(this));
        reg("bounty", new BountyCmd(this));
        TeamCmd tc = new TeamCmd(this);
        reg("team", tc); reg("tc", tc);
        reg("aetherffa", new AdminCmd(this));
        reg("capturezone", new ZoneCmd(this));
        reg("quests", new QuestsCmd(this));
        reg("cosmetics", new CosmeticsCmd(this));
        reg("leaderboard", new BoardCmd(this));
    }

    private void reg(String name, Object handler) {
        PluginCommand c = getCommand(name);
        if (c == null) { getLogger().severe("Command missing from plugin.yml: " + name); return; }
        c.setExecutor((org.bukkit.command.CommandExecutor) handler);
        if (handler instanceof org.bukkit.command.TabCompleter t) c.setTabCompleter(t);
    }

    /** Re-reads every YAML file. Player data, balances, teams and bounties live in the database and are untouched. */
    public List<String> reloadAll() {
        List<String> problems = cfg.loadAll();
        applyConfig();
        return problems;
    }

    /** Pushes freshly loaded/changed configuration into live components. */
    public void applyConfig() {
        zones.reload();
        glow.refreshAll();
        scheduleBoards();
    }

    @Override public void onDisable() {
        shuttingDown = true;
        try {
            if (glow != null) glow.clearAll();
            if (zones != null) zones.shutdown();
            if (store != null) store.flushAll();
        } finally {
            if (db != null) db.close();
        }
    }
}
