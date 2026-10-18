package net.aethermc.ffa;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * %aetherffa_balance% %aetherffa_kills% %aetherffa_deaths% %aetherffa_kdr% %aetherffa_streak% %aetherffa_best_streak%
 * %aetherffa_world% %aetherffa_team% %aetherffa_team_members% %aetherffa_bounty% %aetherffa_quests_done% %aetherffa_quests_total%
 * %aetherffa_quest_progress% %aetherffa_zone% %aetherffa_zone_status% %aetherffa_rank_<category>%
 * %aetherffa_top_<category>_<n>_name% %aetherffa_top_<category>_<n>_value%
 * Reads cached data only: never touches the database on the calling thread.
 */
public final class PapiExpansion extends PlaceholderExpansion {
    private final AetherFFA plugin;

    public PapiExpansion(AetherFFA plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "aetherffa"; }
    @Override public String getAuthor() { return "AetherMC"; }
    @Override public String getVersion() { return plugin.version(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer off, String params) {
        try { return resolve(off, params.toLowerCase(Locale.ROOT)); }
        catch (RuntimeException e) { return ""; }
    }

    private String resolve(OfflinePlayer off, String p) {
        if (p.startsWith("top_")) {
            String[] parts = p.substring(4).split("_");
            if (parts.length < 3) return "";
            String field = parts[parts.length - 1];
            int n;
            try { n = Integer.parseInt(parts[parts.length - 2]); } catch (NumberFormatException e) { return ""; }
            String cat = String.join("_", java.util.Arrays.copyOf(parts, parts.length - 2));
            List<Leaderboards.Entry> l = plugin.boards().top(cat);
            if (n < 1 || n > l.size()) return field.equals("name") ? "-" : "0";
            Leaderboards.Entry e = l.get(n - 1);
            return field.equals("name") ? e.name() : Long.toString(e.value());
        }
        if (off == null) return "";
        PlayerData d = plugin.store().get(off.getUniqueId());
        if (p.startsWith("rank_")) { int r = plugin.boards().rankOf(p.substring(5), off.getUniqueId()); return r < 0 ? "-" : Integer.toString(r); }
        if (d == null) return "";
        Player pl = off.getPlayer();
        Team t = plugin.teams().teamOf(off.getUniqueId());
        switch (p) {
            case "balance": return Long.toString(d.balance);
            case "balance_formatted": return Amounts.format(d.balance);
            case "kills": return Integer.toString(d.kills);
            case "deaths": return Integer.toString(d.deaths);
            case "kdr": return String.format(Locale.US, "%.2f", d.deaths == 0 ? (double) d.kills : (double) d.kills / d.deaths);
            case "streak": return Integer.toString(d.streak);
            case "best_streak": return Integer.toString(d.bestStreak);
            case "world": return pl == null ? "" : pl.getWorld().getName();
            case "team": return t == null ? "" : t.name;
            case "team_members": return t == null ? "0" : Integer.toString(t.members.size());
            case "bounty": return Long.toString(plugin.bounties().total(off.getUniqueId()));
            case "quests_total": return Integer.toString(plugin.quests().today().size());
            case "quests_done": {
                if (pl == null) return "0";
                int n = 0; for (QuestManager.Quest q : plugin.quests().today()) if (plugin.quests().progress(pl, q) >= q.amount()) n++;
                return Integer.toString(n);
            }
            case "quest_progress": {
                if (pl == null) return "";
                int n = 0, total = 0; for (QuestManager.Quest q : plugin.quests().today()) { total++; if (plugin.quests().progress(pl, q) >= q.amount()) n++; }
                return n + "/" + total;
            }
            case "zone": { if (pl == null) return ""; ZoneManager.Zone z = plugin.zones().zoneAt(pl); return z == null ? "" : plugin.zones().displayName(z.id); }
            case "zone_status": { if (pl == null) return ""; ZoneManager.Zone z = plugin.zones().zoneAt(pl); return z == null ? "" : plugin.zones().statusOf(z.id).name(); }
            default: return "";
        }
    }
}
