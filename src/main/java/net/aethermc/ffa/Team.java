package net.aethermc.ffa;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A lightweight team: one leader, UUID members, nothing else. */
public final class Team {
    public final UUID id;
    public volatile String name;
    public volatile UUID leader;
    public final Set<UUID> members = Collections.synchronizedSet(new LinkedHashSet<>());
    public final long created;

    public Team(UUID id, String name, UUID leader, long created) {
        this.id = id; this.name = name; this.leader = leader; this.created = created;
        this.members.add(leader);
    }

    public boolean isLeader(UUID u) { return leader.equals(u); }
}
