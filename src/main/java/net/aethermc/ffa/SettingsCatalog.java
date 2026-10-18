package net.aethermc.ffa;

import org.bukkit.Material;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static net.aethermc.ffa.Setting.bool;
import static net.aethermc.ffa.Setting.choice;
import static net.aethermc.ffa.Setting.dec;
import static net.aethermc.ffa.Setting.list;
import static net.aethermc.ffa.Setting.num;
import static net.aethermc.ffa.Setting.text;

/** Every setting the admin GUI exposes. Each entry maps to a config key that the code actually reads. */
final class SettingsCatalog {
    private SettingsCatalog() {}

    private static final String[] COLORS = {"AQUA", "BLUE", "DARK_AQUA", "DARK_BLUE", "GREEN", "DARK_GREEN", "YELLOW", "GOLD", "RED", "DARK_RED", "LIGHT_PURPLE", "DARK_PURPLE", "WHITE", "GRAY", "DARK_GRAY", "BLACK"};
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    static boolean material(String s) { Material m = Material.matchMaterial(s); return m != null && m.isItem() && !m.isAir(); }

    static List<Setting> general() {
        return new ArrayList<>(List.of(
                bool(Cfg.WORLDS, "unlisted-worlds-enabled", "Unlisted worlds enabled", "Apply AetherFFA features in worlds not listed in worlds.yml.", false),
                bool(Cfg.ECONOMY, "transfers.enabled", "Player transfers", "Allow /aether pay.", true),
                bool(Cfg.ZONES, "enabled", "Capture zones", "Master switch for passive capture-zone rewards.", true),
                bool(Cfg.BOUNTIES, "enabled", "Bounties", "Master switch for the bounty system.", true),
                bool(Cfg.TEAMS, "enabled", "Teams", "Allow creating and joining teams.", true),
                bool(Cfg.STREAKS, "enabled", "Kill streaks", "Master switch for kill streaks.", true),
                bool(Cfg.QUESTS, "enabled", "Daily quests", "Master switch for daily quests.", true),
                bool(Cfg.COSMETICS, "enabled", "Cosmetics", "Kill effects and custom death messages.", true)));
    }

    static List<Setting> economy() {
        return new ArrayList<>(List.of(
                text(Cfg.ECONOMY, "currency.name", "Currency name", "Shown in messages as <currency>.", "Aether"),
                text(Cfg.ECONOMY, "currency.symbol", "Currency symbol", "Appended to formatted amounts.", "◆"),
                text(Cfg.ECONOMY, "currency.icon", "Currency icon", "Item shown for Aether in menus (e.g. AMETHYST_SHARD).", "AMETHYST_SHARD").check(m -> { var mat = org.bukkit.Material.matchMaterial(m.trim()); return mat != null && mat.isItem() && !mat.isAir(); }, "Use an item material such as AMETHYST_SHARD"),
                num(Cfg.ECONOMY, "starting-balance", "Starting balance", "Given to new players.", 500, 0, 1_000_000_000L, 50),
                num(Cfg.ECONOMY, "min-balance", "Minimum balance", "Lowest allowed balance (only below 0 if negatives are allowed).", 0, -1_000_000L, 1_000_000L, 100),
                bool(Cfg.ECONOMY, "allow-negative", "Allow negative balances", "Lets min-balance go below zero.", false),
                num(Cfg.ECONOMY, "max-balance", "Maximum balance", "Hard cap. Rewards are trimmed to fit. 0 = no cap.", 1_000_000_000L, 0, 4_000_000_000_000L, 1_000_000),
                num(Cfg.ECONOMY, "transfers.min", "Transfer minimum", "Smallest /aether pay amount.", 1, 1, 1_000_000L, 10),
                num(Cfg.ECONOMY, "transfers.max", "Transfer maximum", "Largest /aether pay amount.", 100000, 1, 1_000_000_000L, 1000),
                num(Cfg.ECONOMY, "transfers.cooldown-seconds", "Transfer cooldown", "Seconds between transfers per player.", 3, 0, 3600, 1),
                num(Cfg.ECONOMY, "transfers.tax-percent", "Transfer tax %", "Burned from each transfer.", 0, 0, 100, 1),
                num(Cfg.ECONOMY, "rewards.kill.base", "Kill reward", "Aether per valid kill. 0 disables.", 20, 0, 1_000_000L, 5),
                num(Cfg.ECONOMY, "rewards.kill.cooldown-seconds", "Kill reward cooldown", "Min seconds between Aether kill rewards per killer.", 3, 0, 3600, 1)));
    }

    static List<Setting> bounty() {
        return new ArrayList<>(List.of(
                bool(Cfg.BOUNTIES, "enabled", "Enabled", "Master switch.", true),
                num(Cfg.BOUNTIES, "min-amount", "Minimum bounty", "Smallest single contribution.", 50, 1, 1_000_000L, 10),
                num(Cfg.BOUNTIES, "max-amount", "Maximum bounty", "Largest single contribution.", 100000, 1, 1_000_000_000L, 1000),
                num(Cfg.BOUNTIES, "max-total-per-target", "Max total per target", "Cap on one player's combined bounty. 0 = none.", 1_000_000, 0, 4_000_000_000L, 10000),
                num(Cfg.BOUNTIES, "place-cooldown-seconds", "Place cooldown", "Seconds between placements per player.", 5, 0, 3600, 1),
                num(Cfg.BOUNTIES, "place-fee-percent", "Placement fee %", "Extra charge on top of the bounty (burned).", 0, 0, 100, 1),
                num(Cfg.BOUNTIES, "expiry-hours", "Expiry (hours)", "0 = never expires.", 168, 0, 8760, 12),
                num(Cfg.BOUNTIES, "refund-on-expiry-percent", "Refund on expiry %", "Share returned to placers when a bounty expires.", 100, 0, 100, 5),
                num(Cfg.BOUNTIES, "payout-percent", "Payout %", "Share of the total paid to the killer.", 100, 0, 100, 5),
                bool(Cfg.BOUNTIES, "target-must-be-online", "Target must be online", "Bounties can only be placed on online players.", true),
                bool(Cfg.BOUNTIES, "block-team-bounties", "Block teammate bounties", "Cannot place a bounty on your own teammate.", true),
                bool(Cfg.BOUNTIES, "allow-claim-by-contributor", "Contributors may claim", "Off = contributors and their teammates cannot claim their own bounty.", false),
                bool(Cfg.BOUNTIES, "broadcast-on-place", "Broadcast placements", "Announce large bounties.", true),
                num(Cfg.BOUNTIES, "broadcast-min-amount", "Broadcast threshold", "Minimum contribution to announce.", 1000, 0, 1_000_000_000L, 500)));
    }

    static List<Setting> team() {
        return new ArrayList<>(List.of(
                bool(Cfg.TEAMS, "enabled", "Enabled", "Allow teams.", true),
                num(Cfg.TEAMS, "max-size", "Max team size", "Members per team including the leader.", 4, 2, 50, 1),
                num(Cfg.TEAMS, "invites.expire-seconds", "Invite expiry", "Seconds an invitation stays valid.", 60, 10, 3600, 10),
                bool(Cfg.TEAMS, "friendly-fire", "Friendly fire", "Off = teammates cannot damage each other in enabled worlds.", false),
                bool(Cfg.TEAMS, "glow.enabled", "Teammate glow", "Glow shown ONLY to teammates (needs the PacketEvents plugin).", true),
                choice(Cfg.TEAMS, "glow.color", "Glow color", "Outline colour.", "AQUA", COLORS),
                num(Cfg.TEAMS, "glow.range", "Glow range", "Blocks within which a teammate glows for you. 0 = unlimited.", 30, 0, 256, 5),
                text(Cfg.TEAMS, "chat.format", "Team chat format", "MiniMessage. Placeholders: <team> <player> <message>.", "<dark_gray>[<#4DA6FF><team></#4DA6FF>]</dark_gray> <white><player></white><dark_gray>:</dark_gray> <gray><message>").mm(),
                bool(Cfg.TEAMS, "chat.log-to-console", "Log team chat", "Write team chat to the console.", false),
                num(Cfg.TEAMS, "rules.switch-cooldown-seconds", "Switch cooldown", "Seconds between create/join/leave actions.", 30, 0, 86400, 10),
                num(Cfg.TEAMS, "rules.reward-lockout-after-change-seconds", "Reward lockout after change", "No kill/zone rewards for this long after joining or leaving a team.", 120, 0, 86400, 30),
                choice(Cfg.TEAMS, "rules.leader-leave", "Leader leaves", "What happens when a leader with members leaves.", "BLOCK", "BLOCK", "PROMOTE_OLDEST", "DISBAND"),
                list(Cfg.TEAMS, "blocked-names", "Blocked name fragments", "Team names containing these are rejected.")));
    }

    static List<Setting> streaks() {
        return new ArrayList<>(List.of(
                bool(Cfg.STREAKS, "enabled", "Enabled", "Master switch.", true),
                bool(Cfg.STREAKS, "reset-on.death", "Reset on death", "Streak ends when the player dies.", true),
                bool(Cfg.STREAKS, "reset-on.quit", "Reset on quit", "Streak ends when the player disconnects.", false),
                bool(Cfg.STREAKS, "reset-on.disabled-world", "Reset in disabled worlds", "Streak ends when entering a world with streaks off.", true),
                text(Cfg.STREAKS, "broadcast-format", "Default broadcast", "MiniMessage. <player> <streak> <reward>.", "<prefix><player> is on a <streak> kill streak!").mm(),
                bool(Cfg.STREAKS, "shutdown.enabled", "Shutdown bonus", "Pay a bonus for ending someone's streak.", true),
                num(Cfg.STREAKS, "shutdown.min-streak", "Shutdown min streak", "Smallest streak that pays a bonus.", 5, 2, 1000, 1),
                num(Cfg.STREAKS, "shutdown.bonus-per-streak", "Bonus per streak kill", "Aether per kill in the ended streak.", 2, 0, 10000, 1),
                num(Cfg.STREAKS, "shutdown.max-bonus", "Max bonus", "Cap on the shutdown bonus.", 100, 0, 1_000_000L, 25),
                bool(Cfg.STREAKS, "shutdown.broadcast", "Broadcast shutdowns", "Announce ended streaks.", true)));
    }

    static List<Setting> milestone(String base) {
        return new ArrayList<>(List.of(
                text(Cfg.STREAKS, base + ".name", "Name", "Milestone name, e.g. Rampage. Placeholder <name>.", "Kill Streak"),
                num(Cfg.STREAKS, base + ".reward", "Reward", "Aether paid when this streak is reached.", 0, 0, 1_000_000L, 5),
                bool(Cfg.STREAKS, base + ".broadcast", "Broadcast", "Announce to everyone.", true),
                text(Cfg.STREAKS, base + ".broadcast-message", "Broadcast text", "MiniMessage sent to EVERYONE. <player> <streak> <name>.", "").mm(),
                text(Cfg.STREAKS, base + ".title", "Title", "Big on-screen text for the killer. Empty = none.", "").mm(),
                text(Cfg.STREAKS, base + ".subtitle", "Subtitle", "Small on-screen text for the killer.", "").mm(),
                text(Cfg.STREAKS, base + ".effect", "Kill effect id", "Plays a kill effect from cosmetics.yml for everyone. Empty = none.", ""),
                text(Cfg.STREAKS, base + ".message", "Message to player", "MiniMessage. <player> <streak> <reward>.", "").mm(),
                text(Cfg.STREAKS, base + ".sound", "Sound key", "e.g. entity.player.levelup. Empty = none.", "").check(s -> s.isEmpty() || s.matches("[a-z0-9_.:/\\-]+"), "Use a sound key like entity.player.levelup"),
                text(Cfg.STREAKS, base + ".particle", "Particle", "Plain particle name. Empty = none.", "").check(s -> s.isEmpty() || Cosmetics.validParticle(s), "Unknown or data-requiring particle")));
    }

    static List<Setting> quests() {
        return new ArrayList<>(List.of(
                bool(Cfg.QUESTS, "enabled", "Enabled", "Master switch.", true),
                text(Cfg.QUESTS, "reset.timezone", "Timezone", "Java zone id, e.g. UTC or Asia/Manila.", "UTC").check(s -> { try { ZoneId.of(s); return true; } catch (RuntimeException e) { return false; } }, "Unknown timezone"),
                text(Cfg.QUESTS, "reset.time", "Reset time", "HH:mm in that timezone.", "00:00").check(s -> { try { LocalTime.parse(s); return true; } catch (RuntimeException e) { return false; } }, "Use HH:mm"),
                num(Cfg.QUESTS, "selection.EASY", "Easy quests per day", "How many EASY quests are chosen daily (max 3 quests per day in total).", 1, 0, 3, 1),
                num(Cfg.QUESTS, "selection.MEDIUM", "Medium quests per day", "How many MEDIUM quests are chosen daily (max 3 quests per day in total).", 1, 0, 3, 1),
                num(Cfg.QUESTS, "selection.HARD", "Hard quests per day", "How many HARD quests are chosen daily (max 3 quests per day in total).", 1, 0, 3, 1)));
    }

    static List<Setting> quest(String id) {
        String b = "pool." + id;
        return new ArrayList<>(List.of(
                bool(Cfg.QUESTS, b + ".enabled", "Enabled", "Disabled quests are never chosen.", true),
                text(Cfg.QUESTS, b + ".name", "Name", "Shown to players (MiniMessage).", id).mm(),
                choice(Cfg.QUESTS, b + ".type", "Objective", "What counts as progress.", "KILLS", "KILLS", "STREAK", "CAPTURE_SECONDS", "CAPTURE_PAYOUTS", "BOUNTY_CLAIMS", "KIT_CLAIMS"),
                num(Cfg.QUESTS, b + ".amount", "Target amount", "Progress needed.", 5, 1, 100000, 1),
                num(Cfg.QUESTS, b + ".reward", "Aether reward", "Paid on claim.", 50, 0, 1_000_000L, 10),
                choice(Cfg.QUESTS, b + ".difficulty", "Difficulty", "Which selection bucket it belongs to.", "EASY", "EASY", "MEDIUM", "HARD")));
    }

    static boolean validIconOrEmpty(String m) {
        if (m == null || m.isBlank()) return true;
        var mat = org.bukkit.Material.matchMaterial(m.trim());
        return mat != null && mat.isItem() && !mat.isAir();
    }

    static List<Setting> cosmeticsGlobal() {
        return new ArrayList<>(List.of(
                bool(Cfg.COSMETICS, "enabled", "Enabled", "Master switch for cosmetics.", true),
                num(Cfg.COSMETICS, "settings.max-particles-per-effect", "Max particles per effect", "Hard cap per play, for performance.", 60, 1, 500, 5)));
    }

    static List<Setting> effect(String id) {
        String b = "kill-effects." + id;
        return new ArrayList<>(List.of(
                text(Cfg.COSMETICS, b + ".name", "Name", "Shown in the menu.", id),
                num(Cfg.COSMETICS, b + ".price", "Price", "0 = free (or permission only).", 0, 0, 10_000_000L, 100),
                text(Cfg.COSMETICS, b + ".permission", "Permission", "Grants it for free. Empty = none.", ""),
                text(Cfg.COSMETICS, b + ".icon", "Menu icon", "Item shown in the shop, e.g. BLAZE_POWDER. Empty = automatic.", "").check(SettingsCatalog::validIconOrEmpty, "Use an item material such as BLAZE_POWDER"),
                text(Cfg.COSMETICS, b + ".description", "Description", "Shown under the name in the shop.", ""),
                choice(Cfg.COSMETICS, b + ".shape", "Shape", "burst = instant puff; others are short animations.", "burst", "burst", "ring", "helix", "spiral", "column", "tornado", "sphere", "rain", "ascend"),
                text(Cfg.COSMETICS, b + ".particle", "Particle", "Plain particle name, e.g. FLAME.", "FLAME").check(Cosmetics::validParticle, "Unknown or data-requiring particle"),
                text(Cfg.COSMETICS, b + ".particle2", "Second particle", "Optional accent particle. Empty = none.", "").check(x -> x.isEmpty() || Cosmetics.validParticle(x), "Unknown or data-requiring particle"),
                num(Cfg.COSMETICS, b + ".duration", "Duration (ticks)", "Animation length for non-burst shapes (1-40).", 16, 1, 40, 1),
                bool(Cfg.COSMETICS, b + ".lightning", "Visual lightning", "Harmless lightning bolt (no damage or fire).", false),
                num(Cfg.COSMETICS, b + ".count", "Particle count", "Capped by the global limit.", 20, 1, 500, 5),
                dec(Cfg.COSMETICS, b + ".spread", "Spread", "Particle offset in blocks.", 0.4, 0, 5, 0.1),
                dec(Cfg.COSMETICS, b + ".speed", "Speed", "Particle speed.", 0.05, 0, 2, 0.01),
                text(Cfg.COSMETICS, b + ".sound", "Sound key", "Empty = none.", "").check(s -> s.isEmpty() || s.matches("[a-z0-9_.:/\\-]+"), "Use a sound key like entity.blaze.shoot")));
    }

    static List<Setting> deathMessage(String id) {
        String b = "death-messages." + id;
        return new ArrayList<>(List.of(
                text(Cfg.COSMETICS, b + ".name", "Name", "Shown in the menu.", id),
                num(Cfg.COSMETICS, b + ".price", "Price", "0 = free (or permission only).", 0, 0, 10_000_000L, 100),
                text(Cfg.COSMETICS, b + ".permission", "Permission", "Grants it for free. Empty = none.", ""),
                text(Cfg.COSMETICS, b + ".icon", "Menu icon", "Item shown in the shop. Empty = automatic.", "").check(SettingsCatalog::validIconOrEmpty, "Use an item material such as OAK_SIGN"),
                text(Cfg.COSMETICS, b + ".format", "Format", "MiniMessage. <killer> and <victim>.", "<victim> was slain by <killer>").mm()));
    }

    /** Tag, protection and restriction rules live in JustCombat's config; only the Aether side of a combat logout is edited here. */
    static List<Setting> combat() {
        return new ArrayList<>(List.of(
                num(Cfg.COMBAT, "logout.aether-fine-percent", "Logout fine %", "Percent of balance removed on combat logout (JustCombat decides what counts as one).", 0, 0, 100, 5),
                bool(Cfg.COMBAT, "logout.credit-kill-to-attacker", "Credit attacker", "On a JustCombat KILL logout the last opponent gets the (guard-checked) kill.", true),
                bool(Cfg.COMBAT, "logout.ignore-kicks", "Ignore kicks", "Staff kicks are not combat logs.", true)));
    }

    static List<Setting> antiFarm() {
        return new ArrayList<>(List.of(
                bool(Cfg.MAIN, "anti-farm.block-safe-zone-kills", "Block safe-zone kills", "No rewards if either player is in a safe-zone region.", true),
                bool(Cfg.MAIN, "anti-farm.block-team-kills", "Block team kills", "Teammate kills never pay.", true),
                bool(Cfg.MAIN, "anti-farm.block-same-ip", "Block same-IP kills", "Kills between players on one public IP never pay.", true),
                num(Cfg.MAIN, "anti-farm.min-seconds-after-victim-respawn", "Spawn-kill window", "Kills on a victim this soon after respawn do not pay.", 5, 0, 120, 1),
                num(Cfg.MAIN, "anti-farm.pair-cooldown-seconds", "Pair cooldown", "Seconds before the same killer can be paid for the same victim again.", 30, 0, 3600, 5),
                num(Cfg.MAIN, "anti-farm.repeat-kill-window-seconds", "Repeat-kill window", "Window for the repeat limit.", 600, 10, 86400, 30),
                num(Cfg.MAIN, "anti-farm.repeat-kill-max", "Repeat-kill limit", "Paid kills of one victim per window. 0 = unlimited.", 3, 0, 100, 1),
                num(Cfg.MAIN, "anti-farm.alert-threshold", "Alert threshold", "Rejected kills in the window that alert staff. 0 = off.", 5, 0, 100, 1),
                num(Cfg.MAIN, "anti-farm.alert-window-seconds", "Alert window", "Window for the alert threshold.", 600, 30, 86400, 30),
                bool(Cfg.MAIN, "anti-farm.alert-staff", "Alert staff in chat", "Notify players with aetherffa.alerts.", true)));
    }

    static List<Setting> boards() {
        return new ArrayList<>(List.of(
                num(Cfg.BOARDS, "refresh-seconds", "Refresh interval", "Seconds between cached refreshes.", 60, 10, 3600, 10),
                num(Cfg.BOARDS, "display-limit", "Entries per board", "How many players each board keeps.", 10, 1, 100, 1)));
    }

    static List<Setting> boardCategory(String id) {
        String b = "categories." + id;
        return new ArrayList<>(List.of(
                bool(Cfg.BOARDS, b + ".enabled", "Enabled", "Show this board.", true),
                text(Cfg.BOARDS, b + ".title", "Title", "Shown in the menu.", id),
                text(Cfg.BOARDS, b + ".icon", "Tab icon", "Material for the menu tab. Empty = default for the column.", ""),
                choice(Cfg.BOARDS, b + ".column", "Sorted by", "Statistic used for ranking.", "kills", "kills", "deaths", "best_streak", "streak", "balance", "bounty_earned", "bounties_claimed")));
    }

    static List<Setting> guiCustom() {
        return new ArrayList<>(List.of(
                text(Cfg.GUI, "filler.material", "Filler material", "Background item.", "BLACK_STAINED_GLASS_PANE").check(SettingsCatalog::material, "Unknown item material"),
                text(Cfg.GUI, "accent", "Accent colour", "Hex, e.g. #4DA6FF.", "#4DA6FF").check(s -> HEX.matcher(s).matches(), "Use #RRGGBB"),
                text(Cfg.GUI, "currency-accent", "Currency colour", "Hex, e.g. #B57BFF.", "#B57BFF").check(s -> HEX.matcher(s).matches(), "Use #RRGGBB"),
                text(Cfg.GUI, "titles.kits", "Title: Kits", "MiniMessage.", "<dark_gray>Aether <#4DA6FF>Kits").mm(),
                text(Cfg.GUI, "titles.economy", "Title: Wallet", "MiniMessage.", "<dark_gray>Aether <#B57BFF>Wallet").mm(),
                text(Cfg.GUI, "titles.bounty", "Title: Bounties", "MiniMessage.", "<dark_gray>Aether <#4DA6FF>Bounties").mm(),
                text(Cfg.GUI, "titles.team", "Title: Team", "MiniMessage.", "<dark_gray>Aether <#4DA6FF>Team").mm(),
                text(Cfg.GUI, "titles.quests", "Title: Quests", "MiniMessage.", "<dark_gray>Aether <#4DA6FF>Daily Quests").mm(),
                text(Cfg.GUI, "titles.cosmetics", "Title: Cosmetics", "MiniMessage.", "<dark_gray>Aether <#4DA6FF>Cosmetics").mm(),
                text(Cfg.GUI, "titles.leaderboard", "Title: Leaderboards", "MiniMessage.", "<dark_gray>Aether <#4DA6FF>Leaderboards").mm(),
                text(Cfg.GUI, "titles.admin", "Title: Admin", "MiniMessage.", "<dark_gray>AetherFFA <#4DA6FF>Admin").mm(),
                text(Cfg.GUI, "navigation.back.material", "Back button item", "Material.", "ARROW").check(SettingsCatalog::material, "Unknown item material"),
                text(Cfg.GUI, "navigation.back.name", "Back button name", "MiniMessage.", "<gray>« Back").mm(),
                text(Cfg.GUI, "navigation.home.name", "Home button name", "MiniMessage.", "<#4DA6FF>Home").mm()));
    }

    static List<Setting> kit(String id) {
        String b = "kits." + id;
        return new ArrayList<>(List.of(
                bool(Cfg.KITS, b + ".enabled", "Enabled", "Disabled kits are hidden.", true),
                text(Cfg.KITS, b + ".display-name", "Display name", "MiniMessage.", id).mm(),
                text(Cfg.KITS, b + ".icon", "Icon", "Material for the menu.", "CHEST").check(SettingsCatalog::material, "Unknown item material"),
                num(Cfg.KITS, b + ".price", "Price", "0 = free (or permission-gated).", 0, 0, 100_000_000L, 100),
                text(Cfg.KITS, b + ".permission", "Permission", "Grants the kit without buying. Empty = none.", ""),
                choice(Cfg.KITS, b + ".unlock", "Unlock type", "PERMANENT or TIMED after purchase.", "PERMANENT", "PERMANENT", "TIMED"),
                num(Cfg.KITS, b + ".timed-hours", "Timed unlock hours", "Used when unlock is TIMED.", 24, 1, 8760, 6),
                num(Cfg.KITS, b + ".cooldown-seconds", "Cooldown", "Seconds between claims.", 300, 0, 604800, 60),
                bool(Cfg.KITS, b + ".clear-inventory", "Clear inventory first", "Only works if kits.yml settings.allow-clear-inventory is on.", false),
                list(Cfg.KITS, b + ".worlds", "Allowed worlds", "Empty = every enabled world.")));
    }

    static List<Setting> kitGlobal() {
        return new ArrayList<>(List.of(
                bool(Cfg.KITS, "settings.allow-clear-inventory", "Allow clear-inventory kits", "Lets kits wipe the player's inventory first.", false),
                bool(Cfg.KITS, "settings.block-while-combat-tagged", "Block while combat-tagged", "Cannot claim kits in combat.", true),
                list(Cfg.KITS, "settings.banned-materials", "Banned materials", "Never allowed inside a kit.")));
    }

    /** Zone options. prefix is "defaults." for the global defaults or "zones.<id>." for a per-zone override. */
    static List<Setting> zone(String prefix, boolean perZone) {
        String fb = perZone ? "defaults." : null;
        List<Setting> l = new ArrayList<>();
        if (perZone) {
            l.add(bool(Cfg.ZONES, prefix + "enabled", "Enabled", "Disabled zones pay nothing.", true));
            l.add(text(Cfg.ZONES, prefix + "name", "Display name", "Shown to players (MiniMessage).", "zone").mm());
        }
        l.add(choice(Cfg.ZONES, prefix + "mode", "Reward mode", "INDIVIDUAL, CONTESTED, MAJORITY or TEAM_SHARED.", "INDIVIDUAL", "INDIVIDUAL", "CONTESTED", "MAJORITY", "TEAM_SHARED").fallback(fb == null ? null : fb + "mode"));
        l.add(num(Cfg.ZONES, prefix + "reward", "Reward", "Aether per payout.", 10, 0, 1_000_000L, 5).fallback(fb == null ? null : fb + "reward"));
        l.add(num(Cfg.ZONES, prefix + "interval-seconds", "Interval", "Seconds of eligible presence per payout.", 30, 1, 3600, 5).fallback(fb == null ? null : fb + "interval-seconds"));
        l.add(num(Cfg.ZONES, prefix + "min-players", "Min eligible players", "Below this nobody is paid.", 1, 1, 100, 1).fallback(fb == null ? null : fb + "min-players"));
        l.add(num(Cfg.ZONES, prefix + "max-players", "Max eligible players", "Above this nobody is paid. 0 = unlimited.", 0, 0, 200, 1).fallback(fb == null ? null : fb + "max-players"));
        l.add(dec(Cfg.ZONES, prefix + "entry-delay-seconds", "Entry delay", "Seconds after entering before progress counts.", 3, 0, 120, 1).fallback(fb == null ? null : fb + "entry-delay-seconds"));
        l.add(dec(Cfg.ZONES, prefix + "contest.grace-seconds", "Contest grace", "CONTESTED: uncontested seconds before payouts resume.", 5, 0, 300, 1).fallback(fb == null ? null : fb + "contest.grace-seconds"));
        l.add(bool(Cfg.ZONES, prefix + "contest.count-afk", "AFK players contest", "Whether AFK enemies pause the zone.", false).fallback(fb == null ? null : fb + "contest.count-afk"));
        l.add(dec(Cfg.ZONES, prefix + "majority.percent", "Majority percent", "MAJORITY: share of eligible players the winning side needs.", 51, 1, 100, 1).fallback(fb == null ? null : fb + "majority.percent"));
        l.add(choice(Cfg.ZONES, prefix + "team-rule", "Team rule", "Non-shared modes: SPLIT, ALL_MEMBERS or ONE_PER_TEAM.", "SPLIT", "SPLIT", "ALL_MEMBERS", "ONE_PER_TEAM").fallback(fb == null ? null : fb + "team-rule"));
        l.add(choice(Cfg.ZONES, prefix + "team-shared.distribution", "Shared distribution", "TEAM_SHARED: SPLIT, FULL_EACH or FIRST_ONLY.", "SPLIT", "SPLIT", "FULL_EACH", "FIRST_ONLY").fallback(fb == null ? null : fb + "team-shared.distribution"));
        l.add(dec(Cfg.ZONES, prefix + "team-shared.bonus-per-extra-member", "Shared bonus per extra member", "Added to the shared pot per additional member.", 0, 0, 100000, 1).fallback(fb == null ? null : fb + "team-shared.bonus-per-extra-member"));
        l.add(bool(Cfg.ZONES, prefix + "safe-zone-rewards", "Pay inside safe zones", "Allow payouts in WorldGuard safe-zone regions.", false).fallback(fb == null ? null : fb + "safe-zone-rewards"));
        l.add(bool(Cfg.ZONES, prefix + "exclude-spawn-protected", "Exclude spawn-protected", "Protected players earn nothing.", true).fallback(fb == null ? null : fb + "exclude-spawn-protected"));
        l.add(num(Cfg.ZONES, prefix + "limit.per-hour", "Hourly cap per player", "Max Aether per player per hour from this zone. 0 = none.", 0, 0, 10_000_000L, 100).fallback(fb == null ? null : fb + "limit.per-hour"));
        l.add(bool(Cfg.ZONES, prefix + "display.actionbar", "Action bar", "Show zone status.", true).fallback(fb == null ? null : fb + "display.actionbar"));
        l.add(bool(Cfg.ZONES, prefix + "display.bossbar", "Boss bar", "Show a progress boss bar instead.", false).fallback(fb == null ? null : fb + "display.bossbar"));
        l.add(choice(Cfg.ZONES, prefix + "display.bossbar-color", "Boss bar colour", "Colour of the bar.", "BLUE", "BLUE", "WHITE", "PINK", "RED", "GREEN", "YELLOW", "PURPLE").fallback(fb == null ? null : fb + "display.bossbar-color"));
        l.add(bool(Cfg.ZONES, prefix + "messages.enter", "Entry message", "Tell players when they enter.", true).fallback(fb == null ? null : fb + "messages.enter"));
        l.add(bool(Cfg.ZONES, prefix + "messages.exit", "Exit message", "Tell players when they leave.", true).fallback(fb == null ? null : fb + "messages.exit"));
        l.add(bool(Cfg.ZONES, prefix + "messages.reward", "Reward message", "Tell players about each payout.", true).fallback(fb == null ? null : fb + "messages.reward"));
        return l;
    }

    static List<Setting> zoneGlobal() {
        List<Setting> l = new ArrayList<>(List.of(
                bool(Cfg.ZONES, "enabled", "Capture zones enabled", "Master switch.", true),
                dec(Cfg.ZONES, "afk.seconds", "AFK after", "Seconds without real movement before a player is AFK. 0 = off.", 60, 0, 3600, 10),
                dec(Cfg.ZONES, "afk.min-movement-blocks", "AFK movement threshold", "Movement smaller than this does not count.", 1.5, 0.1, 20, 0.5),
                dec(Cfg.ZONES, "progress-keep-seconds", "Keep progress after leaving", "Seconds partial progress survives leaving the zone.", 120, 0, 3600, 30)));
        l.addAll(zone("defaults.", false));
        return l;
    }

    static List<Setting> world(String name) {
        String b = "worlds." + name;
        List<Setting> l = new ArrayList<>();
        l.add(bool(Cfg.WORLDS, b + ".enabled", "World enabled", "Master switch for AetherFFA in this world.", true));
        for (Feature f : Feature.values()) l.add(bool(Cfg.WORLDS, b + ".features." + f.key, pretty(f.key), "Enable this system in " + name + ".", true));
        l.add(bool(Cfg.WORLDS, b + ".safe-zone-capture-rewards", "Capture rewards in safe zones", "Off (default): zones pay nothing inside safe-zone regions.", false));
        l.add(list(Cfg.WORLDS, b + ".safe-regions", "Safe-zone regions", "WorldGuard region ids that count as safe zones here."));
        return l;
    }

    private static String pretty(String key) {
        String s = key.replace('-', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
