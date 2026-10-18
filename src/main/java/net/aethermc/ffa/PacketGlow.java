package net.aethermc.ffa;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Per-viewer glow through PacketEvents (same technique justTeams 2.8 uses). Nothing is ever changed on the server:
 * no Entity#setGlowing, no Bukkit scoreboard team. For each (receiver, target) pair the RECEIVER's client alone is told
 * "this player glows, in this colour". Anyone who is not that receiver's teammate gets no packet and sees nothing.
 * This class touches PacketEvents types, so it is only ever loaded by TeamGlow when the PacketEvents plugin is present.
 */
final class PacketGlow implements PacketListener {
    private static final String TEAM = "aff_glow";
    private static final byte GLOW_BIT = 0x40;

    private final AetherFFA plugin;
    /** receiver -> targets whose glow that receiver's client currently shows. Read from the netty thread. */
    private final Map<UUID, Set<UUID>> shown = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> byEntityId = new ConcurrentHashMap<>();
    private final Set<UUID> hasTeam = ConcurrentHashMap.newKeySet();
    private volatile NamedTextColor sentColor;
    private PacketListenerCommon handle;

    PacketGlow(AetherFFA plugin) { this.plugin = plugin; }

    void register() {
        handle = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
    }

    void shutdown() {
        for (Player r : Bukkit.getOnlinePlayers()) {
            Set<UUID> set = shown.get(r.getUniqueId());
            if (set == null) continue;
            for (UUID t : new ArrayList<>(set)) { Player tp = Bukkit.getPlayer(t); if (tp != null) unset(tp, r); }
        }
        shown.clear(); hasTeam.clear(); byEntityId.clear();
        if (handle != null) { PacketEvents.getAPI().getEventManager().unregisterListener(handle); handle = null; }
    }

    // ---- bookkeeping (main thread) ----

    void track(Player p) { byEntityId.put(p.getEntityId(), p.getUniqueId()); }

    void forget(Player p) {
        UUID id = p.getUniqueId();
        byEntityId.values().remove(id);
        shown.remove(id);
        hasTeam.remove(id);
        for (Set<UUID> s : shown.values()) s.remove(id);
    }

    boolean isShown(Player receiver, Player target) {
        Set<UUID> s = shown.get(receiver.getUniqueId());
        return s != null && s.contains(target.getUniqueId());
    }

    // ---- sending (main thread) ----

    private static void send(Player receiver, PacketWrapper<?> w) {
        if (receiver.isOnline()) PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, w);
    }

    private WrapperPlayServerTeams.ScoreBoardTeamInfo info(NamedTextColor color) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.text(TEAM), Component.empty(), Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS,
                color, WrapperPlayServerTeams.OptionData.NONE);
    }

    /** Creates the client-side colour team once per receiver (never as a server scoreboard team). */
    private void ensureTeam(Player receiver, NamedTextColor color) {
        if (!hasTeam.add(receiver.getUniqueId())) return;
        send(receiver, new WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.CREATE, info(color), new ArrayList<>()));
    }

    /** Pushes a changed glow colour to every receiver that already has the client-side team. */
    void updateColor(NamedTextColor color) {
        if (color.equals(sentColor)) return;
        sentColor = color;
        for (Player r : Bukkit.getOnlinePlayers()) {
            if (hasTeam.contains(r.getUniqueId()))
                send(r, new WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.UPDATE, info(color), new ArrayList<>()));
        }
    }

    void set(Player target, Player receiver, NamedTextColor color) {
        if (sentColor == null) sentColor = color;
        ensureTeam(receiver, sentColor);
        shown.computeIfAbsent(receiver.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(target.getUniqueId());
        send(receiver, new WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, Collections.singletonList(target.getName())));
        sendFlags(target, receiver, true);
    }

    void unset(Player target, Player receiver) {
        Set<UUID> s = shown.get(receiver.getUniqueId());
        if (s == null || !s.remove(target.getUniqueId())) return;       // removed first, so the packet hook below does not re-add the bit
        send(receiver, new WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, Collections.singletonList(target.getName())));
        sendFlags(target, receiver, false);
    }

    private void sendFlags(Player target, Player receiver, boolean glowing) {
        byte status = 0;
        if (target.getFireTicks() > 0) status |= 0x01;
        if (target.isSneaking()) status |= 0x02;
        if (target.isSprinting()) status |= 0x08;
        if (target.isSwimming()) status |= 0x10;
        if (target.isInvisible()) status |= 0x20;
        if (glowing) status |= GLOW_BIT;
        if (target.isGliding()) status |= (byte) 0x80;
        EntityData<Byte> data = new EntityData<>(0, EntityDataTypes.BYTE, status);
        send(receiver, new WrapperPlayServerEntityMetadata(target.getEntityId(), Collections.singletonList(data)));
    }

    // ---- packet hook (netty thread; only reads the concurrent maps) ----

    /**
     * The server re-sends a player's flags whenever they sprint, sneak, burn, or come into view, which would wipe the glow
     * bit. For receivers that should see the glow we put the bit back into those packets; everyone else's are untouched.
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) return;
        Object user = event.getPlayer();
        if (!(user instanceof Player receiver)) return;
        Set<UUID> set = shown.get(receiver.getUniqueId());
        if (set == null || set.isEmpty()) return;
        WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata(event);
        UUID target = byEntityId.get(w.getEntityId());
        if (target == null || !set.contains(target)) return;
        for (EntityData<?> d : w.getEntityMetadata()) {
            if (d.getIndex() == 0 && d.getType() == EntityDataTypes.BYTE) {
                @SuppressWarnings("unchecked") EntityData<Byte> b = (EntityData<Byte>) d;
                byte s = b.getValue();
                if ((s & GLOW_BIT) == 0) { b.setValue((byte) (s | GLOW_BIT)); event.markForReEncode(true); }
                break;
            }
        }
    }
}
