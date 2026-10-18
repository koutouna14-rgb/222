package net.aethermc.ffa;

/** Per-world toggles. Keys match worlds.yml. */
public enum Feature {
    KILL_REWARDS("kill-rewards"), KILLSTREAKS("killstreaks"), BOUNTIES("bounties"), CAPTURE_ZONES("capture-zones"),
    KITS("kits"), QUESTS("quests"), COSMETICS("cosmetics"), COMBAT_TAG("combat-tag"), TEAMS("teams");

    public final String key;
    Feature(String key) { this.key = key; }
}
