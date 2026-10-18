package net.aethermc.ffa;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Consecutive valid-kill tracking with configurable milestones. */
public final class StreakManager {
    private final AetherFFA plugin;

    public StreakManager(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().streaks(); }
    public boolean enabled() { return c().getBoolean("enabled", true); }

    /** Called only for a kill that passed the guard. */
    public void onValidKill(Player killer, PlayerData d) {
        if (!enabled() || !plugin.worlds().enabled(killer.getWorld(), Feature.KILLSTREAKS)) return;
        int streak;
        synchronized (d) { d.streak++; if (d.streak > d.bestStreak) d.bestStreak = d.streak; d.dirty = true; streak = d.streak; }
        plugin.quests().progressMax(killer, "STREAK", streak);
        ConfigurationSection ms = c().getConfigurationSection("milestones." + streak);
        if (ms == null) return;
        long reward = Math.max(0, ms.getLong("reward", 0));
        if (reward > 0) plugin.economy().deposit(killer.getUniqueId(), reward, "streak:" + streak, "streak:" + killer.getUniqueId() + ":" + d.kills);
        String name = ms.getString("name", "Kill Streak");
        String rewardText = plugin.economy().fmt(reward);
        Object[] ph = {"player", killer.getName(), "streak", streak, "reward", rewardText, "name", name};

        String msg = ms.getString("message", "");
        if (!msg.isEmpty()) plugin.msg().sendRaw(killer, msg, ph);

        String title = ms.getString("title", ""), sub = ms.getString("subtitle", "");
        if (!title.isEmpty() || !sub.isEmpty()) {
            killer.showTitle(net.kyori.adventure.title.Title.title(plugin.msg().mm(title, ph), plugin.msg().mm(sub, ph),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(400))));
        }

        // Broadcast: EVERY online player gets the line (plus the console), not just the killer.
        if (ms.getBoolean("broadcast", false)) {
            String fmt = ms.getString("broadcast-message", "");
            if (fmt.isBlank()) fmt = c().getString("broadcast-format", "<prefix><player> is on a <streak> kill streak!");
            net.kyori.adventure.text.Component comp = plugin.msg().mm(fmt, ph);
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(comp);
            Bukkit.getConsoleSender().sendMessage(comp);
            String bs = ms.getString("broadcast-sound", "");
            if (!bs.isEmpty()) for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), bs, 0.6f, 1f);
        }

        String sound = ms.getString("sound", "");
        if (!sound.isEmpty()) killer.playSound(killer.getLocation(), sound, 1f, 1f);
        String effect = ms.getString("effect", "");
        if (!effect.isEmpty()) plugin.cosmetics().playById(effect, killer.getLocation());
        String fx = ms.getString("particle", "");
        if (!fx.isEmpty()) Cosmetics.spawn(killer.getLocation().add(0, 1, 0), fx, ms.getInt("particle-count", 20), 0.4, 0.02, killer.getWorld().getPlayers());
    }

    /** Name of the highest milestone the player has reached, for menus/placeholders. */
    public String titleFor(int streak) {
        ConfigurationSection all = c().getConfigurationSection("milestones");
        if (all == null) return "";
        int best = -1;
        for (String k : all.getKeys(false)) {
            try { int n = Integer.parseInt(k); if (n <= streak && n > best) best = n; } catch (NumberFormatException ignored) { }
        }
        return best < 0 ? "" : all.getString(best + ".name", "");
    }

    /** Victim died: reset the streak if configured and pay the shutdown bonus to a valid killer. Returns the bonus paid. */
    public long onDeath(Player victim, PlayerData d, Player validKiller) {
        int ended;
        synchronized (d) { ended = d.streak; if (c().getBoolean("reset-on.death", true)) { d.streak = 0; d.dirty = true; } }
        if (!enabled() || ended <= 0) return 0;
        if (validKiller == null) return 0;
        ConfigurationSection sd = c().getConfigurationSection("shutdown");
        if (sd == null || !sd.getBoolean("enabled", true) || ended < sd.getInt("min-streak", 5)) return 0;
        long bonus = Math.min(sd.getLong("max-bonus", 100), Math.max(0, sd.getLong("bonus-per-streak", 2)) * ended);
        if (bonus > 0) plugin.economy().deposit(validKiller.getUniqueId(), bonus, "shutdown:" + victim.getName(), "shutdown:" + validKiller.getUniqueId() + ":" + victim.getUniqueId() + ":" + System.currentTimeMillis() / 1000);
        if (sd.getBoolean("broadcast", true)) {
            net.kyori.adventure.text.Component comp = plugin.msg().mm(sd.getString("broadcast-message", "<prefix><player> ended <victim>'s <streak> kill streak!"),
                    "player", validKiller.getName(), "victim", victim.getName(), "streak", ended, "bonus", plugin.economy().fmt(bonus));
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(comp);
            Bukkit.getConsoleSender().sendMessage(comp);
        }
        return bonus;
    }

    public void reset(PlayerData d) { synchronized (d) { d.streak = 0; d.dirty = true; } }
}
