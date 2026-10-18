# AetherFFA

Gameplay systems for AetherMC's **existing** battleground worlds (Paper 1.21, Java 21, SQLite). Not an arena plugin:
no arenas, queues, match instances, auto-teleports, inventory restores, NPC management or WorldGuard region management.

> **Build status: NOT compiled, NOT run on a server.** The authoring sandbox had no Maven, no network and no Paper API jar.
> See [Verification status](#verification-status) for exactly what was and was not checked. Build it yourself with the commands below.

## Build

```bash
mvn -q clean package          # needs JDK 21 and internet access for the Paper/WorldGuard/PlaceholderAPI repos
# result: target/AetherFFA-1.0.0.jar
```
Dependencies (all `provided`, nothing is shaded; PacketEvents `com.github.retrooper:packetevents-spigot:2.11.0` from the CodeMC repo, same version justTeams 2.8.0 builds against): `io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT` (same line as the supplied
justTeams/capturezones projects), `com.sk89q.worldguard:worldguard-bukkit:7.0.9`, `me.clip:placeholderapi:2.11.6`; test: JUnit 5.
SQLite comes from the JDBC driver Paper bundles. If your build uses a newer Paper API than 1.21, `Particle`/`Sound` handling is the
first place to check (the plugin uses string sound keys and `Particle.valueOf` with validation).

## Install
1. Drop the jar into `plugins/` **next to JustCombat 1.3.1 (required)**, start once, stop. Optional: PacketEvents (needed for team glow), WorldGuard, WorldEdit/FAWE (draws capture zones from your selection), PlaceholderAPI (FancyNpcs is only *detected* for the diagnostics screen).
2. Edit `plugins/AetherFFA/worlds.yml`: list each battleground world, its WorldGuard **safe-zone region ids**, and per-world feature toggles.
3. Start the server, run `/aetherffa admin`. Everything routine (settings, kits, zones, quests, cosmetics, messages, players) is editable in-game.
4. Three premade kits are installed on first start (PARROTX2, ClownPierce, Wemmbu (Invis Knight): names and prices from kits.unstable.sh, item lists are our interpretation); `/kit premade` adds any that are missing. Create your own with `/kit create <name>` (captures your inventory) and zones by selecting an area with WorldEdit (`//wand`, or `//sel cyl` for a circle) and running `/capturezone create <id>` (or `/capturezone createcircle <id> <radius>`; without WorldEdit use `pos1`/`pos2`).

## What is implemented
| Area | Notes |
|---|---|
| Aether economy | Internal, UUID accounts, limits, tax, transfers (online players), ledger, offline admin adjustments, crash-safe payouts |
| Kits | Free / permission / purchase, permanent or timed unlocks, cooldowns, upgrades (level 2+ replace contents), per-world rules, preview, space-checked delivery, GUI + admin editing |
| Capture zones | Passive rewards; cuboid + cylinder; `INDIVIDUAL` / `CONTESTED` (5 s grace default) / `MAJORITY` / `TEAM_SHARED`; AFK, entry delay, progress kept across re-entry, team rules, hourly cap, boss bar/action bar |
| Bounties | Escrow, multiple contributors, expiry + refund, payout %, fee, anti-self/teammate/contributor claims, GUI, top list, staff inspect/remove |
| Teams | Create/invite/accept/deny/kick/promote/leave/disband/info, private chat (`/tc`), friendly-fire, teammate-only glow, GUI with confirmations, persistence, switch cooldown + reward lockout |
| Kill streaks | Milestones, broadcasts, sound/particle, shutdown bonus, reset rules, persistence |
| Daily quests | Seeded daily selection per difficulty from a pool of 25 quests, **hard cap of 3 quests per day**, timezone + reset time, 6 objective types, claim GUI, duplicate-claim protection |
| Cosmetics | Kill effects + death messages, purchasable or permission-based, preview, per-player toggle, particle cap |
| Combat (via **JustCombat**) | JustCombat owns combat tag, spawn/respawn protection, command/item/elytra/teleport restrictions and the logout punishment. AetherFFA reads its state (kits and capture zones respect it) and adds team friendly-fire, an optional Aether fine on combat logout, and kill credit for the attacker on a KILL logout |
| Anti-farm | One shared guard verdict for every kill reward; staff alerts (never auto-punish) |
| Leaderboards | Async cached top lists (kills, best streak, balance, bounty earnings) + PlaceholderAPI |
| Admin GUI | 18 categories, staged Save/Discard, validation, confirmations, back/home/refresh, permission re-check on every click |

## Combat = JustCombat
* JustCombat is a hard dependency. AetherFFA talks to it by reflection (no extra jar needed to build), written against the method
  signatures in **JustCombat 1.3.1**. If another version changes them, AetherFFA logs a clear error and combat queries fall back to
  "not tagged / not protected" instead of crashing.
* Set tag duration, blocked commands/items, logout action and respawn protection in `plugins/JustCombat/config.yml`.
  AetherFFA's own `combat.yml` now only holds the Aether-side logout settings (fine %, credit the attacker, ignore kicks).
* Removed from AetherFFA: its own tagging, spawn-protection timer, command/item/elytra/teleport blocking, and the
  `spawn-protection` world feature / admin category.
* Friendly-fire runs at `LOW` priority so a blocked teammate hit is cancelled before JustCombat's later handlers can tag anyone.
* Capture zones' `exclude-spawn-protected` now means JustCombat newbie protection + respawn protection (respawn protection is read
  from a private JustCombat field because 1.3.1 has no getter; if that fails, only newbie protection counts).
* Kill credit for combat logouts only works for JustCombat's `KILL` action and not when its combat-log NPC takes the logout.

## Verification status
Verified here:
* All Java sources **parse** with `javac` (JDK 21 compiler module); no syntax errors and no unresolved references to AetherFFA's own classes or methods.
* `Amounts`, `RepeatTracker`, `DayKey` (Bukkit-free) compiled and **9 JUnit tests passed** through a JUnit shim (`src/test/.../PureLogicTest.java`).
* All YAML parses; every message key used in code exists in `messages.yml`; every static admin-GUI setting path exists in the bundled YAML.

**Not** verified (needs your build + a test server):
* Compilation against the real Paper / WorldGuard / WorldEdit / PlaceholderAPI APIs. API calls were written from knowledge of the 1.21 API, not checked against the jar.
* Everything that needs a running server: GUIs, events, SQLite behaviour (schema, `VACUUM INTO` backups, `ON CONFLICT` upserts), WorldGuard region queries, PlaceholderAPI, glow, boss bars.
* Concurrency under real load, multi-world behaviour, restarts, and the combat-log/kick paths.
Please run through the scenarios in your test plan (multiple worlds, zones in all four modes, team glow cleanup, bounty expiry, combat logout, restart persistence) before production.

## Known limitations / deviations (be aware)
* **Team glow is per-viewer and needs the PacketEvents plugin.** Glow packets are sent only to teammates' clients (same technique as justTeams 2.8), so players outside the team never see it. Nothing is set server-side (no `setGlowing`, no Bukkit scoreboard team). Without PacketEvents glow is disabled rather than falling back to a global glow. If justTeams runs on the same server, set `features.team_glow: false` in its config, otherwise its refresh loop also pushes glow flags for every player that is not in a justTeams team.
* `/aether pay` requires the recipient to be online. Admin give/take/set work on known offline players (tiny race if that player logs in during the same instant).
* Online balance changes are written to SQLite asynchronously (ledger row + player snapshot in one transaction). A hard crash can lose only writes still queued.
* Kill-reward cooldown and zone hourly caps live in memory (reset on restart). Team switch cooldown/lockout also reset on restart.
* Kit contents are edited by capturing an admin's inventory (no in-GUI item editing, deliberately, to avoid dupe vectors). List settings support add/edit/remove; enable/disable toggles exist on kits, zones, quests and boards.
* GUI customisation covers titles, colours, filler, navigation and dashboard icons/names, not slot layouts.
* Messages are edited and saved immediately (not staged). Other settings are staged until **Save changes**.
* Paper only (uses Bukkit scheduler); Folia is not supported. `PlaceholderAPI` leaderboard rank placeholders only know players inside the cached top-N.
* Not implemented as separate features: per-viewer glow, MySQL, a plugin-side NPC integration (intentionally none).

## Commands
| Command | Permission | Notes |
|---|---|---|
| `/aether`, `balance [player]`, `pay <player> <amount>`, `top` | `aetherffa.aether.use`, `.pay`, `.balance.others` | `/aether` opens the wallet GUI |
| `/aether admin give\|take\|set <player> <amount>` | `aetherffa.admin.economy` | online or known offline players |
| `/kits`, `/kit <name>`, `/kit preview <name>` | `aetherffa.kit.use` | |
| `/kit create\|edit\|delete <name>`, `/kit give <player> <name>`, `/kit reload` | `aetherffa.admin.kits` / `.reload` | create captures your inventory |
| `/bounty`, `set <player> <amount>`, `top`, `info <player>` | `aetherffa.bounty.use` | |
| `/bounty remove <player>` | `aetherffa.admin.bounty` | refunds contributors |
| `/team` + `create/invite/accept/deny/kick/promote/leave/disband/chat/info`, `/tc <msg>` | `aetherffa.team.use`; leader actions `aetherffa.team.manage` | |
| `/team admin list\|info <team>\|disband <team>` | `aetherffa.admin.teams` | |
| `/capturezone status` | none | zone you stand in |
| `/capturezone create\|redefine\|createcircle\|pos1\|pos2\|delete\|enable\|disable\|info\|list\|set\|gui` | `aetherffa.admin.zones` | `create`/`redefine` use your WorldEdit selection (cuboid or circular cylinder); `pos1`/`pos2` only apply when WorldEdit is absent. `set <id> <key> <value>` is validated like the GUI |
| `/quests`, `/quests reset <player>` | `aetherffa.quests.use` / `aetherffa.admin.quests` | |
| `/cosmetics`, `/leaderboard [category]` | `aetherffa.cosmetics.use`, `aetherffa.leaderboard.use` | |
| `/aetherffa admin\|settings\|reload\|alerts` | `aetherffa.admin`, `.admin.settings`, `.admin.reload`, `aetherffa.alerts` | |

Other permissions: `aetherffa.capture.earn` (default true), `aetherffa.bypass.kitcooldown` (default false) (combat bypass is JustCombat's `justcombat.bypass.*`),
`aetherffa.admin.players|diagnostics|messages`. All `aetherffa.admin.*` and `aetherffa.alerts` default to **op only**; `aetherffa.admin` is a parent granting all of them.

## Configuration files
`config.yml` (anti-farm) · `messages.yml` · `worlds.yml` · `economy.yml` · `kits.yml` · `capture-zones.yml` · `teams.yml` ·
`bounties.yml` · `killstreaks.yml` · `quests.yml` · `cosmetics.yml` · `combat.yml` (Aether-side logout settings only) · `leaderboards.yml` · `gui.yml` · `database.yml`.
Reloading (`/aetherffa reload` or the GUI) re-reads YAML only; balances, stats, teams, bounties and quest progress live in the database and are untouched.
A YAML syntax error keeps the last good config in memory, copies the bad file to `<name>.broken`, and reports the problem. GUI saves are atomic (temp file + move) and keep a `<name>.bak`.
Admin-managed collections (kits, zones, quest pool, worlds) are never restored from bundled defaults after you delete them.

## Database and backups
SQLite file `plugins/AetherFFA/aetherffa.db` (WAL mode). Tables: `players`, `player_kv` (kit unlocks/cooldowns, cosmetics, quest progress), `transactions` (ledger),
`pending_payouts` (exactly-once payout recovery), `teams`, `team_members`, `bounties`, `alerts`. No credentials are stored.
* In-game: **Admin → Database & Diagnostics → Back up database now** writes a consistent copy to `plugins/AetherFFA/backups/` (`VACUUM INTO`).
* Offline: stop the server and copy `aetherffa.db` (and any `-wal`/`-shm` files) together. Do not copy a live database file by hand.
* Recommended: schedule off-server copies of the `backups/` folder daily.
* If the database cannot be opened at startup the plugin disables itself rather than running with lost data. If a single player's load fails, that player plays without saving (so defaults never overwrite real data).

## Reward-integrity design (short)
* One guard verdict per kill (`Guard.judgeKill`): self, safe-zone (WorldGuard region ids from `worlds.yml`), teammate, recent team change, same public IP, spawn-kill window, pair cooldown, repeat limit. Streaks, bounties, quests and Aether all use that same verdict.
* Economy mutations are per-account locked; ledger rows carry optional unique refs; kit/cosmetic purchases persist the unlock in the same SQL transaction as the charge; bounty escrow rows are written in the charge's transaction.
* Bounty claims remove contributions in memory atomically, then one SQL transaction deletes them and records a pending payout; payout is applied exactly once (and recovered on startup after a crash).
* Menus cancel all clicks/drags/shift-clicks/number-keys; buttons re-check permissions on every click; input from chat prompts is validated again on save.

## v3 changes (cosmetics, streaks, /aether hub)
- `/aether` now opens one hub: wallet, bounties, quests, leaderboards, kits, cosmetics shop, team, pay, history (+ admin panel for staff). Subcommands: `/aether bounty|lb|quests|kits|shop|messages|team|wallet`.
- Cosmetics shop has working **Kill Effects** / **Kill Messages** tabs (the toggle used to be overwritten by the page indicator).
- Every effect / message has its own icon (`icon:` in cosmetics.yml, otherwise derived from the particle).
- 14 new animated kill effects (`shape:` burst, ring, helix, spiral, column, tornado, sphere, rain, ascend), 14 new kill messages.
- Kill streaks: 12 named milestones (3 to 100) with title, broadcast to EVERYONE, broadcast sound and optional kill effect. Existing servers get the new milestones once and old milestones are switched to broadcast.
- Custom kill messages are sent to every online player and the console.
