package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Simple optional teams. All state is UUID-based and persisted; invitations are in-memory and expire. */
public final class TeamManager {
    private record Invite(UUID teamId, UUID inviter, long expires) { }

    private final AetherFFA plugin;
    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, List<Invite>> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> changed = new ConcurrentHashMap<>();
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();

    public TeamManager(AetherFFA plugin) { this.plugin = plugin; }

    private YamlConfiguration c() { return plugin.cfg().teams(); }
    public boolean enabled() { return c().getBoolean("enabled", true); }

    public void load() throws SQLException {
        plugin.db().query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,name,leader,created FROM teams"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Team t = new Team(UUID.fromString(rs.getString(1)), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getLong(4));
                    teams.put(t.id, t);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT uuid,team_id FROM team_members"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Team t = teams.get(UUID.fromString(rs.getString(2)));
                    if (t == null) continue;
                    UUID u = UUID.fromString(rs.getString(1));
                    t.members.add(u); byPlayer.put(u, t.id);
                }
            }
            return null;
        });
        // Repair: a team whose leader is not a member, or an empty team, is removed.
        for (Team t : new ArrayList<>(teams.values())) {
            if (!t.members.contains(t.leader)) {
                if (t.members.isEmpty()) { teams.remove(t.id); delete(t.id); }
                else { t.leader = t.members.iterator().next(); persist(t); }
            }
            byPlayer.put(t.leader, t.id);
        }
    }

    // ---- queries ----

    public Team teamOf(UUID id) { UUID tid = byPlayer.get(id); return tid == null ? null : teams.get(tid); }
    public Team byName(String name) { for (Team t : teams.values()) if (t.name.equalsIgnoreCase(name)) return t; return null; }
    public List<Team> all() { return new ArrayList<>(teams.values()); }
    public boolean sameTeam(UUID a, UUID b) { Team t = teamOf(a); return t != null && t.members.contains(b); }
    public boolean recentlyChanged(UUID id, long ms) { Long t = changed.get(id); return t != null && System.currentTimeMillis() - t < ms; }
    public boolean chatMode(UUID id) { return chatMode.contains(id); }

    public List<Player> onlineMembers(Team t) {
        List<Player> out = new ArrayList<>();
        synchronized (t.members) { for (UUID u : t.members) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); } }
        return out;
    }

    public String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    private UUID memberByName(Team t, String name) {
        synchronized (t.members) { for (UUID u : t.members) if (nameOf(u).equalsIgnoreCase(name)) return u; }
        return null;
    }

    // ---- actions (each sends its own feedback to the actor) ----

    public boolean create(Player p, String name) {
        if (!enabled()) { plugin.msg().send(p, "teams.disabled"); return false; }
        if (teamOf(p.getUniqueId()) != null) { plugin.msg().send(p, "teams.already-in-team"); return false; }
        if (!name.matches(c().getString("name-regex", "[A-Za-z0-9_]{3,16}"))) { plugin.msg().send(p, "teams.invalid-name"); return false; }
        for (String b : c().getStringList("blocked-names")) if (name.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) { plugin.msg().send(p, "teams.invalid-name"); return false; }
        if (byName(name) != null) { plugin.msg().send(p, "teams.name-taken"); return false; }
        if (onCooldown(p)) return false;
        Team t = new Team(UUID.randomUUID(), name, p.getUniqueId(), System.currentTimeMillis());
        teams.put(t.id, t); byPlayer.put(p.getUniqueId(), t.id);
        changed.put(p.getUniqueId(), System.currentTimeMillis());
        persist(t);
        plugin.msg().send(p, "teams.created", "team", t.name);
        plugin.glow().refreshTeam(t);
        return true;
    }

    private boolean onCooldown(Player p) {
        long cd = c().getLong("rules.switch-cooldown-seconds", 30) * 1000L;
        Long last = changed.get(p.getUniqueId());
        if (cd > 0 && last != null && System.currentTimeMillis() - last < cd) {
            plugin.msg().send(p, "teams.switch-cooldown", "time", Amounts.duration((cd - (System.currentTimeMillis() - last)) / 1000 + 1));
            return true;
        }
        return false;
    }

    public boolean invite(Player leader, Player target) {
        Team t = teamOf(leader.getUniqueId());
        if (t == null) { plugin.msg().send(leader, "teams.not-in-team"); return false; }
        if (!t.isLeader(leader.getUniqueId())) { plugin.msg().send(leader, "teams.leader-only"); return false; }
        if (leader.equals(target)) { plugin.msg().send(leader, "teams.cannot-self"); return false; }
        if (teamOf(target.getUniqueId()) != null) { plugin.msg().send(leader, "teams.target-in-team", "player", target.getName()); return false; }
        if (t.members.size() >= c().getInt("max-size", 4)) { plugin.msg().send(leader, "teams.full"); return false; }
        List<Invite> list = invites.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        long now = System.currentTimeMillis();
        synchronized (list) {
            list.removeIf(i -> i.expires() < now || i.teamId().equals(t.id));
            if (list.size() >= 5) { plugin.msg().send(leader, "teams.too-many-invites"); return false; }
            list.add(new Invite(t.id, leader.getUniqueId(), now + c().getLong("invites.expire-seconds", 60) * 1000L));
        }
        plugin.msg().send(leader, "teams.invite-sent", "player", target.getName());
        plugin.msg().send(target, "teams.invite-received", "team", t.name, "p_team", t.name, "player", leader.getName(), "seconds", c().getLong("invites.expire-seconds", 60));
        return true;
    }

    public List<Team> pendingInvites(UUID id) {
        List<Invite> list = invites.get(id);
        List<Team> out = new ArrayList<>();
        if (list == null) return out;
        long now = System.currentTimeMillis();
        synchronized (list) { for (Invite i : list) if (i.expires() >= now) { Team t = teams.get(i.teamId()); if (t != null) out.add(t); } }
        return out;
    }

    /** Accepts an invitation identified by the inviter's name or the team name. */
    public boolean accept(Player p, String key) {
        if (teamOf(p.getUniqueId()) != null) { plugin.msg().send(p, "teams.already-in-team"); return false; }
        List<Invite> list = invites.get(p.getUniqueId());
        Invite found = null;
        long now = System.currentTimeMillis();
        if (list != null) synchronized (list) {
            list.removeIf(i -> i.expires() < now || !teams.containsKey(i.teamId()));
            for (Invite i : list) {
                Team t = teams.get(i.teamId());
                if (t.name.equalsIgnoreCase(key) || nameOf(i.inviter()).equalsIgnoreCase(key)) { found = i; break; }
            }
        }
        if (found == null) { plugin.msg().send(p, "teams.no-invite"); return false; }
        Team t = teams.get(found.teamId());
        if (t.members.size() >= c().getInt("max-size", 4)) { plugin.msg().send(p, "teams.full"); return false; }
        if (onCooldown(p)) return false;
        invites.remove(p.getUniqueId());
        t.members.add(p.getUniqueId()); byPlayer.put(p.getUniqueId(), t.id);
        changed.put(p.getUniqueId(), System.currentTimeMillis());
        persist(t);
        plugin.msg().send(p, "teams.joined", "team", t.name);
        for (Player m : onlineMembers(t)) if (!m.equals(p)) plugin.msg().send(m, "teams.member-joined", "player", p.getName());
        plugin.glow().refreshTeam(t);
        return true;
    }

    public boolean deny(Player p, String key) {
        List<Invite> list = invites.get(p.getUniqueId());
        if (list == null) { plugin.msg().send(p, "teams.no-invite"); return false; }
        boolean removed;
        synchronized (list) {
            removed = list.removeIf(i -> { Team t = teams.get(i.teamId()); return t != null && (t.name.equalsIgnoreCase(key) || nameOf(i.inviter()).equalsIgnoreCase(key)); });
        }
        if (!removed) { plugin.msg().send(p, "teams.no-invite"); return false; }
        plugin.msg().send(p, "teams.invite-denied");
        return true;
    }

    public boolean kick(Player leader, String targetName) {
        Team t = teamOf(leader.getUniqueId());
        if (t == null) { plugin.msg().send(leader, "teams.not-in-team"); return false; }
        if (!t.isLeader(leader.getUniqueId())) { plugin.msg().send(leader, "teams.leader-only"); return false; }
        UUID target = memberByName(t, targetName);
        if (target == null) { plugin.msg().send(leader, "teams.not-member", "player", targetName); return false; }
        if (target.equals(leader.getUniqueId())) { plugin.msg().send(leader, "teams.cannot-self"); return false; }
        removeMember(t, target);
        plugin.msg().send(leader, "teams.kicked", "player", nameOf(target));
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) plugin.msg().send(tp, "teams.you-were-kicked", "team", t.name);
        return true;
    }

    public boolean promote(Player leader, String targetName) {
        Team t = teamOf(leader.getUniqueId());
        if (t == null) { plugin.msg().send(leader, "teams.not-in-team"); return false; }
        if (!t.isLeader(leader.getUniqueId())) { plugin.msg().send(leader, "teams.leader-only"); return false; }
        UUID target = memberByName(t, targetName);
        if (target == null) { plugin.msg().send(leader, "teams.not-member", "player", targetName); return false; }
        if (target.equals(leader.getUniqueId())) { plugin.msg().send(leader, "teams.cannot-self"); return false; }
        t.leader = target;
        persist(t);
        for (Player m : onlineMembers(t)) plugin.msg().send(m, "teams.promoted", "player", nameOf(target));
        return true;
    }

    public boolean leave(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { plugin.msg().send(p, "teams.not-in-team"); return false; }
        if (onCooldown(p)) return false;
        if (t.isLeader(p.getUniqueId()) && t.members.size() > 1) {
            String mode = c().getString("rules.leader-leave", "BLOCK").toUpperCase(Locale.ROOT);
            if (mode.equals("BLOCK")) { plugin.msg().send(p, "teams.leader-cannot-leave"); return false; }
            if (mode.equals("DISBAND")) return disband(p);
            synchronized (t.members) { for (UUID u : t.members) if (!u.equals(p.getUniqueId())) { t.leader = u; break; } }
        }
        if (t.members.size() <= 1) return disband(p);
        removeMember(t, p.getUniqueId());
        plugin.msg().send(p, "teams.left", "team", t.name);
        for (Player m : onlineMembers(t)) plugin.msg().send(m, "teams.member-left", "player", p.getName());
        return true;
    }

    public boolean disband(Player leader) {
        Team t = teamOf(leader.getUniqueId());
        if (t == null) { plugin.msg().send(leader, "teams.not-in-team"); return false; }
        if (!t.isLeader(leader.getUniqueId())) { plugin.msg().send(leader, "teams.leader-only"); return false; }
        disbandTeam(t, "teams.disbanded");
        return true;
    }

    /** Staff/forced disband. Cleans glow, chat mode and persistence for every member. */
    public void disbandTeam(Team t, String messageKey) {
        List<Player> online = onlineMembers(t);
        List<UUID> ids;
        synchronized (t.members) { ids = new ArrayList<>(t.members); }
        teams.remove(t.id);
        long now = System.currentTimeMillis();
        for (UUID u : ids) { byPlayer.remove(u); chatMode.remove(u); changed.put(u, now); }
        delete(t.id);
        for (Player p : online) { plugin.glow().clear(p); plugin.msg().send(p, messageKey, "team", t.name); }
        plugin.glow().dropScoreboardTeam(t);
    }

    /** Staff: remove a member by UUID without leader checks. */
    public void forceRemove(Team t, UUID member) {
        if (t.members.size() <= 1) { disbandTeam(t, "teams.disbanded"); return; }
        if (t.isLeader(member)) synchronized (t.members) { for (UUID u : t.members) if (!u.equals(member)) { t.leader = u; break; } }
        removeMember(t, member);
    }

    private void removeMember(Team t, UUID u) {
        t.members.remove(u); byPlayer.remove(u); chatMode.remove(u);
        changed.put(u, System.currentTimeMillis());
        persist(t);
        Player p = Bukkit.getPlayer(u);
        if (p != null) plugin.glow().clear(p);
        plugin.glow().refreshTeam(t);
    }

    // ---- chat ----

    public boolean toggleChat(Player p) {
        if (teamOf(p.getUniqueId()) == null) { plugin.msg().send(p, "teams.not-in-team"); return false; }
        if (chatMode.remove(p.getUniqueId())) plugin.msg().send(p, "teams.chat-off");
        else { chatMode.add(p.getUniqueId()); plugin.msg().send(p, "teams.chat-on"); }
        return true;
    }

    /** Sends only to online teammates. Never touches public chat. */
    public boolean chat(Player p, String text) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { plugin.msg().send(p, "teams.not-in-team"); return false; }
        Component msg = plugin.msg().mm(c().getString("chat.format", "<dark_gray>[<#4DA6FF><team></#4DA6FF>]</dark_gray> <white><player></white><dark_gray>:</dark_gray> <gray><message>"),
                "team", t.name, "player", p.getName(), "message", text);
        for (Player m : onlineMembers(t)) m.sendMessage(msg);
        if (c().getBoolean("chat.log-to-console", false)) plugin.getLogger().info("[TeamChat:" + t.name + "] " + p.getName() + ": " + text);
        return true;
    }

    // ---- housekeeping ----

    public void tick() {
        long now = System.currentTimeMillis();
        for (List<Invite> l : invites.values()) synchronized (l) { l.removeIf(i -> i.expires() < now); }
        invites.entrySet().removeIf(e -> e.getValue().isEmpty());
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        changed.entrySet().removeIf(e -> !online.contains(e.getKey()) && now - e.getValue() > 3_600_000L);
    }

    public void onQuit(UUID id) { chatMode.remove(id); invites.remove(id); }

    // ---- persistence ----

    private void persist(Team t) {
        List<UUID> ids;
        synchronized (t.members) { ids = new ArrayList<>(t.members); }
        String name = t.name; String tid = t.id.toString(); String leader = t.leader.toString(); long created = t.created;
        plugin.db().write("team-save", conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO teams(id,name,leader,created) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, leader=excluded.leader")) {
                ps.setString(1, tid); ps.setString(2, name); ps.setString(3, leader); ps.setLong(4, created); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM team_members WHERE team_id=?")) { ps.setString(1, tid); ps.executeUpdate(); }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO team_members(uuid,team_id,joined) VALUES(?,?,?)")) {
                for (UUID u : ids) { ps.setString(1, u.toString()); ps.setString(2, tid); ps.setLong(3, System.currentTimeMillis()); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    private void delete(UUID id) {
        plugin.db().write("team-delete", conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM team_members WHERE team_id=?")) { ps.setString(1, id.toString()); ps.executeUpdate(); }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM teams WHERE id=?")) { ps.setString(1, id.toString()); ps.executeUpdate(); }
            return null;
        });
    }
}
