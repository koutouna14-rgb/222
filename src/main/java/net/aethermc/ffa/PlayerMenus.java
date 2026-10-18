package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Player-facing menus. All share Menu's click protection; each action re-validates server-side before doing anything.

final class EconomyMenu extends Menu {
    EconomyMenu(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("economy", "<dark_gray>Aether <#B57BFF>Wallet"); }
    @Override protected int rows() { return 3; }
    @Override protected void build() {
        long bal = plugin.economy().balance(viewer.getUniqueId());
        display(11, Items.of(Items.currency(), "<#B57BFF>Balance", "<white>" + plugin.economy().fmt(bal), "<gray>Earn Aether from kills, zones and quests."));
        set(13, Items.of(Material.BOOK, "<#4DA6FF>Recent transactions", "<gray>Click to list your last entries in chat."), t ->
                plugin.economy().history(viewer.getUniqueId(), 8).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
                    viewer.closeInventory();
                    if (list.isEmpty()) plugin.msg().send(viewer, "economy.no-history");
                    for (Economy.Tx tx : list) plugin.msg().send(viewer, "economy.history-line", "delta", (tx.delta() >= 0 ? "+" : "") + Amounts.format(tx.delta()),
                            "balance", Amounts.format(tx.balanceAfter()), "reason", String.valueOf(tx.reason()));
                })));
        set(15, Items.of(Material.NETHER_STAR, "<#4DA6FF>Richest players", "<gray>Click to view the leaderboard."), t -> new LeaderboardMenu(plugin, viewer, "balance").open());
        display(22, Items.of(Items.currency(), "<gray>Send Aether", "<white>/aether pay <player> <amount>"));
        navBack(new AetherHubMenu(plugin, viewer));
    }
}

final class KitMenu extends Menu {
    KitMenu(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("kits", "<dark_gray>Aether <#4DA6FF>Kits"); }
    @Override protected int rows() { return 6; }

    @Override protected void build() {
        KitManager km = plugin.kits();
        List<String> ids = new ArrayList<>();
        for (String id : km.ids()) if (km.enabled(id)) ids.add(id);
        paged(ids, id -> icon(id), (id, type) -> click(id, type));
        navBack(new AetherHubMenu(plugin, viewer));
        display(navRow() + 4, Items.of(Items.currency(), "<#B57BFF>" + plugin.economy().fmt(plugin.economy().balance(viewer.getUniqueId())), "<gray>Your balance"));
        navRefresh();
    }

    private ItemStack icon(String id) {
        KitManager km = plugin.kits();
        KitManager.State st = km.state(viewer, id);
        List<String> lore = new ArrayList<>();
        for (String d : km.description(id)) lore.add("<gray>" + d);
        if (!km.description(id).isEmpty()) lore.add(" ");
        int lvl = km.level(viewer, id);
        if (km.maxLevel(id) > 1) lore.add("<gray>Level: <white>" + lvl + "/" + km.maxLevel(id));
        switch (st) {
            case AVAILABLE -> { lore.add("<green>Unlocked"); lore.add("<gray>Left-click: <white>claim"); }
            case COOLDOWN -> { lore.add("<yellow>Cooldown: <white>" + Amounts.duration(km.cooldownLeftSeconds(viewer, id))); }
            case LOCKED_PRICE -> { lore.add("<red>Locked"); lore.add("<gray>Price: <#B57BFF>" + plugin.economy().fmt(km.price(id)) + (km.timed(id) ? " <gray>(" + km.timedHours(id) + "h)" : "")); lore.add("<gray>Left-click: <white>purchase"); }
            case LOCKED_PERMISSION -> lore.add("<red>Locked <gray>- requires a rank");
            case UNAVAILABLE_WORLD -> lore.add("<dark_gray>Not available in this world");
        }
        long exp = km.unlockExpiry(viewer, id);
        if (exp > 0) lore.add("<gray>Unlock expires in <white>" + Amounts.duration((exp - System.currentTimeMillis()) / 1000));
        if (km.owns(viewer, id) && lvl < km.maxLevel(id)) lore.add("<gray>Shift-click: <white>upgrade <#B57BFF>(" + plugin.economy().fmt(km.upgradePrice(id, lvl + 1)) + ")");
        lore.add("<gray>Right-click: <white>preview");
        return Items.of(km.icon(id), km.display(id), lore);
    }

    private void click(String id, ClickType type) {
        KitManager km = plugin.kits();
        if (!km.exists(id)) return;
        if (type == ClickType.RIGHT) { new KitPreviewMenu(plugin, viewer, id, this).open(); return; }
        if (type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT) {
            if (!km.owns(viewer, id)) return;
            new ConfirmMenu(plugin, viewer, "<#4DA6FF>Upgrade " + id, List.of("<gray>Cost: <#B57BFF>" + plugin.economy().fmt(km.upgradePrice(id, km.level(viewer, id) + 1))), null, this,
                    () -> { km.upgrade(viewer, id); new KitMenu(plugin, viewer).open(); }).open();
            return;
        }
        KitManager.State st = km.state(viewer, id);
        if (st == KitManager.State.AVAILABLE) { km.claim(viewer, id); render(); }
        else if (st == KitManager.State.LOCKED_PRICE) {
            new ConfirmMenu(plugin, viewer, "<#4DA6FF>Buy " + id, List.of("<gray>Price: <#B57BFF>" + plugin.economy().fmt(km.price(id))), null, this,
                    () -> { km.purchase(viewer, id); new KitMenu(plugin, viewer).open(); }).open();
        } else if (st == KitManager.State.COOLDOWN) plugin.msg().send(viewer, "kits.cooldown", "time", Amounts.duration(km.cooldownLeftSeconds(viewer, id)));
        else if (st == KitManager.State.UNAVAILABLE_WORLD) plugin.msg().send(viewer, "kits.unavailable-world");
        else plugin.msg().send(viewer, "kits.locked");
    }
}

final class KitPreviewMenu extends Menu {
    private final String id; private final Menu back;
    KitPreviewMenu(AetherFFA plugin, Player viewer, String id, Menu back) { super(plugin, viewer); this.id = id; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Preview <#4DA6FF>" + id); }
    @Override protected int rows() { return 6; }
    @Override protected void build() {
        List<ItemStack> stacks = plugin.kits().previewStacks(id, plugin.kits().level(viewer, id));
        for (int i = 0; i < stacks.size() && i < 45; i++) display(i, stacks.get(i).clone());
        navBack(back);
    }
}

final class BountyMenu extends Menu {
    BountyMenu(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("bounty", "<dark_gray>Aether <#4DA6FF>Bounties"); }
    @Override protected int rows() { return 6; }
    @Override protected void build() {
        List<Map.Entry<UUID, Long>> top = plugin.bounties().top(100);
        paged(top, e -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            return Items.head(op, "<white>" + (op.getName() == null ? "?" : op.getName()), List.of("<gray>Bounty: <#B57BFF>" + plugin.economy().fmt(e.getValue()), "<gray>Contributors: <white>" + plugin.bounties().contributions(e.getKey()).size(), " ", "<gray>Click to add to this bounty"));
        }, (e, t) -> {
            Player target = Bukkit.getPlayer(e.getKey());
            if (target == null) { plugin.msg().send(viewer, "bounty.target-offline"); return; }
            new BountyAmountMenu(plugin, viewer, target, this).open();
        });
        navBack(new AetherHubMenu(plugin, viewer));
        set(navRow() + 4, Items.of(Items.currency(), "<#B57BFF>Place a bounty", "<gray>Pick an online player."), t ->
                new PlayerPickMenu(plugin, viewer, "<dark_gray>Choose a target", p -> !p.equals(viewer), this, target -> new BountyAmountMenu(plugin, viewer, target, this).open()).open());
        long mine = plugin.bounties().total(viewer.getUniqueId());
        PlayerData d = plugin.store().get(viewer);
        display(navRow() + 1, Items.of(Material.TARGET, "<#4DA6FF>Your status", "<gray>Bounty on you: <#B57BFF>" + plugin.economy().fmt(mine),
                "<gray>Claimed: <white>" + (d == null ? 0 : d.bountiesClaimed), "<gray>Earned: <#B57BFF>" + plugin.economy().fmt(d == null ? 0 : d.bountyEarned)));
        navRefresh();
    }
}

final class BountyAmountMenu extends Menu {
    private final Player target; private final Menu back;
    BountyAmountMenu(AetherFFA plugin, Player viewer, Player target, Menu back) { super(plugin, viewer); this.target = target; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Bounty on <#4DA6FF>" + target.getName()); }
    @Override protected int rows() { return 3; }
    @Override protected void build() {
        long[] presets = {100, 500, 1000, 5000};
        int[] slots = {10, 11, 12, 13};
        for (int i = 0; i < presets.length; i++) {
            long amt = presets[i];
            set(slots[i], Items.of(Items.currency(), "<#B57BFF>" + plugin.economy().fmt(amt), "<gray>Click to place"), t -> {
                viewer.closeInventory();
                plugin.bounties().place(viewer, target.getUniqueId(), target.getName(), amt);
            });
        }
        set(15, Items.of(Material.WRITABLE_BOOK, "<#4DA6FF>Custom amount", "<gray>Type an amount in chat."), t ->
                plugin.prompts().ask(viewer, "<prefix><gray>Type the bounty amount (e.g. 2500 or 2.5k):", text -> {
                    var amt = Amounts.parse(text);
                    if (amt.isEmpty()) { plugin.msg().send(viewer, "general.invalid-amount"); return; }
                    plugin.bounties().place(viewer, target.getUniqueId(), target.getName(), amt.getAsLong());
                }, null));
        navBack(back);
    }
}

final class QuestMenu extends Menu {
    QuestMenu(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("quests", "<dark_gray>Aether <#4DA6FF>Daily Quests"); }
    @Override protected int rows() { return 3; }
    @Override protected void build() {
        List<QuestManager.Quest> quests = plugin.quests().today();
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < quests.size() && i < slots.length; i++) {
            QuestManager.Quest q = quests.get(i);
            long prog = plugin.quests().progress(viewer, q);
            boolean done = prog >= q.amount(), claimed = plugin.quests().claimed(viewer, q);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + plugin.quests().describeTypes().getOrDefault(q.type(), q.type()));
            lore.add("<gray>Progress: <white>" + prog + "/" + q.amount() + " " + bar(prog, q.amount()));
            lore.add("<gray>Reward: <#B57BFF>" + plugin.economy().fmt(q.reward()));
            lore.add(claimed ? "<dark_gray>Claimed" : done ? "<green>Click to claim" : "<gray>In progress");
            Material m = claimed ? Material.GRAY_DYE : done ? Material.LIME_DYE : Items.questIcon(q.type());
            set(slots[i], Items.of(m, "<#4DA6FF>" + q.name() + " <dark_gray>[" + q.difficulty() + "]", lore), t -> { plugin.quests().claim(viewer, q); render(); });
        }
        if (quests.isEmpty()) display(13, Items.of(Material.BARRIER, "<gray>No quests today"));
        display(22, Items.of(Material.CLOCK, "<gray>Resets in <white>" + Amounts.duration(plugin.quests().secondsUntilReset())));
        navBack(new AetherHubMenu(plugin, viewer));
    }
    private static String bar(long v, long max) {
        int filled = (int) Math.min(10, v * 10 / Math.max(1, max));
        return "<#4DA6FF>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(10 - filled);
    }
}

final class CosmeticMenu extends Menu {
    private boolean effects;
    CosmeticMenu(AetherFFA plugin, Player viewer) { this(plugin, viewer, true); }
    CosmeticMenu(AetherFFA plugin, Player viewer, boolean effects) { super(plugin, viewer); this.effects = effects; }
    @Override protected Component title() { return titleOf("cosmetics", "<dark_gray>Aether <#4DA6FF>Cosmetics") ; }
    @Override protected int rows() { return 6; }

    private ItemStack glow(ItemStack it) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); return it; }

    @Override protected void build() {
        Cosmetics cs = plugin.cosmetics();
        PlayerData d = plugin.store().get(viewer);
        if (d == null) return;
        int nav = navRow();
        List<String> ids = effects ? cs.effectIds() : cs.messageIds();
        String selected = effects ? d.effect : d.deathMsg;

        paged(ids, id -> {
            boolean owned = cs.owns(viewer, effects, id);
            boolean isSel = id.equals(selected);
            List<String> lore = new ArrayList<>();
            String desc = cs.descriptionOf(effects, id);
            if (!desc.isEmpty()) lore.add("<gray>" + desc);
            if (effects) lore.add("<dark_gray>Style: <white>" + cs.shapeOf(id) + " <dark_gray>- " + cs.particleOf(id).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
            else lore.add("<gray>\"" + plugin.cfg().cosmetics().getString("death-messages." + id + ".format", "").replace("<killer>", "You").replace("<victim>", "Them") + "<gray>\"");
            lore.add(" ");
            lore.add(isSel ? "<green>Equipped" : owned ? "<green>Unlocked" : cs.priceOf(effects, id) > 0 ? "<red>Locked <gray>- <#B57BFF>" + plugin.economy().fmt(cs.priceOf(effects, id)) : "<red>Locked <gray>- requires a rank");
            lore.add(owned ? "<gray>Left-click: <white>" + (isSel ? "unequip" : "equip") : "<gray>Left-click: <white>buy");
            lore.add(effects ? "<gray>Right-click: <white>preview" : "<gray>Right-click: <white>preview in chat");
            ItemStack it = Items.of(cs.iconOf(effects, id), "<white>" + cs.richName(effects, id), lore);
            return isSel ? glow(it) : it;
        }, (id, type) -> {
            if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
                if (effects) cs.preview(viewer, id);
                else viewer.sendMessage(cs.previewDeathMessage(id, viewer));
                return;
            }
            if (cs.owns(viewer, effects, id)) { cs.select(viewer, effects, id.equals(effects ? d.effect : d.deathMsg) ? "" : id); render(); }
            else new ConfirmMenu(plugin, viewer, "<#4DA6FF>Buy " + cs.richName(effects, id), List.of("<gray>Price: <#B57BFF>" + plugin.economy().fmt(cs.priceOf(effects, id))), null, this,
                    () -> { cs.buy(viewer, effects, id); this.open(); }).open();
        });

        // Category tabs. They sit on slots the paging buttons never use (prev = +2, page = +3, next = +6).
        ItemStack fxTab = Items.of(Material.FIREWORK_ROCKET, (effects ? "<#4DA6FF>» " : "<gray>") + "Kill Effects", "<gray>" + cs.effectIds().size() + " particle effects", effects ? "<dark_gray>Showing" : "<dark_gray>Click to view");
        ItemStack dmTab = Items.of(Material.OAK_SIGN, (!effects ? "<#4DA6FF>» " : "<gray>") + "Kill Messages", "<gray>" + cs.messageIds().size() + " death messages", !effects ? "<dark_gray>Showing" : "<dark_gray>Click to view");
        set(nav + 1, effects ? glow(fxTab) : fxTab, t -> { if (!effects) { effects = true; page = 0; render(); } });
        set(nav + 4, !effects ? glow(dmTab) : dmTab, t -> { if (effects) { effects = false; page = 0; render(); } });

        navBack(new AetherHubMenu(plugin, viewer));
        display(nav + 5, Items.of(Items.currency(), "<#B57BFF>" + plugin.economy().fmt(plugin.economy().balance(viewer.getUniqueId())), "<gray>Your balance"));
        String curName = selected.isEmpty() ? "<dark_gray>none" : "<white>" + cs.richName(effects, selected);
        set(nav + 7, Items.of(selected.isEmpty() ? Material.GRAY_DYE : Material.BARRIER, "<#4DA6FF>Equipped: " + curName, selected.isEmpty() ? "<gray>Nothing equipped" : "<gray>Click to unequip"), t -> { if (!selected.isEmpty()) { cs.select(viewer, effects, ""); render(); } });
        set(nav + 8, Items.of(d.effectsOn ? Material.LIME_DYE : Material.GRAY_DYE, "<#4DA6FF>Effects: " + Items.onOff(d.effectsOn), "<gray>Click to toggle your kill effect"), t -> { cs.toggleEffects(viewer); render(); });
    }
}

final class LeaderboardMenu extends Menu {
    private String cat;
    LeaderboardMenu(AetherFFA plugin, Player viewer, String cat) { super(plugin, viewer); this.cat = cat; }
    @Override protected Component title() { return titleOf("leaderboard", "<dark_gray>Aether <#4DA6FF>Leaderboards"); }
    @Override protected int rows() { return 5; }
    @Override protected void build() {
        List<String> cats = plugin.boards().categories();
        if (cat == null || !cats.contains(cat)) cat = cats.isEmpty() ? null : cats.get(0);
        for (int i = 0; i < cats.size() && i < 9; i++) {
            String c = cats.get(i);
            boolean sel = c.equals(cat);
            ItemStack tab = Items.of(plugin.boards().icon(c), (sel ? "<#4DA6FF>» " : "<gray>") + plugin.boards().title(c), sel ? "<dark_gray>Showing" : "<dark_gray>Click to view");
            if (sel) { ItemMeta im = tab.getItemMeta(); im.setEnchantmentGlintOverride(true); tab.setItemMeta(im); }   // selected tab glows
            set(i, tab, t -> { cat = c; render(); });
        }
        if (cat != null) {
            List<Leaderboards.Entry> list = plugin.boards().top(cat);
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
            for (int i = 0; i < list.size() && i < slots.length; i++) {
                Leaderboards.Entry e = list.get(i);
                display(slots[i], Items.head(Bukkit.getOfflinePlayer(e.uuid()), "<#4DA6FF>#" + (i + 1) + " <white>" + e.name(), List.of("<gray>" + plugin.boards().title(cat) + ": <white>" + Amounts.format(e.value()))));
            }
            if (list.isEmpty()) display(22, Items.of(Material.BARRIER, "<gray>No data yet"));
        }
        navBack(new AetherHubMenu(plugin, viewer));
        display(navRow() + 4, Items.of(Material.CLOCK, "<gray>Updated " + (plugin.boards().refreshedAt() == 0 ? "soon" : Amounts.duration((System.currentTimeMillis() - plugin.boards().refreshedAt()) / 1000) + " ago")));
    }
}

final class TeamMenu extends Menu {
    TeamMenu(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("team", "<dark_gray>Aether <#4DA6FF>Team"); }
    @Override protected int rows() { return 4; }

    @Override protected void build() {
        Team t = plugin.teams().teamOf(viewer.getUniqueId());
        if (t == null) { buildNoTeam(); return; }
        boolean leader = t.isLeader(viewer.getUniqueId());
        display(4, Items.of(Material.WHITE_BANNER, "<#4DA6FF>" + t.name, "<gray>Members: <white>" + t.members.size() + "/" + plugin.cfg().teams().getInt("max-size", 4), "<gray>Leader: <white>" + plugin.teams().nameOf(t.leader)));
        int slot = 9;
        List<UUID> ids;
        synchronized (t.members) { ids = new ArrayList<>(t.members); }
        for (UUID u : ids) {
            if (slot > 17) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            boolean online = op.isOnline();
            List<String> lore = new ArrayList<>();
            lore.add(t.isLeader(u) ? "<#FFC83D>Leader" : "<gray>Member");
            lore.add(online ? "<green>Online" : "<dark_gray>Offline");
            if (leader && !u.equals(viewer.getUniqueId())) lore.add("<gray>Click to manage");
            ItemStack head = Items.head(op, "<white>" + plugin.teams().nameOf(u), lore);
            if (leader && !u.equals(viewer.getUniqueId())) set(slot, head, tp -> new TeamMemberMenu(plugin, viewer, u, this).open());
            else display(slot, head);
            slot++;
        }
        if (leader) set(29, Items.of(Material.WRITABLE_BOOK, "<#4DA6FF>Invite player", "<gray>Pick an online player."), tp ->
                new PlayerPickMenu(plugin, viewer, "<dark_gray>Invite a player", p -> !p.equals(viewer) && plugin.teams().teamOf(p.getUniqueId()) == null, this, p -> { plugin.teams().invite(viewer, p); new TeamMenu(plugin, viewer).open(); }).open());
        set(31, Items.of(Material.OAK_SIGN, "<#4DA6FF>Team chat: " + Items.onOff(plugin.teams().chatMode(viewer.getUniqueId())), "<gray>Click to toggle. Also: /tc <message>"), tp -> { plugin.teams().toggleChat(viewer); render(); });
        set(33, Items.of(Material.OAK_DOOR, "<red>Leave team"), tp ->
                new ConfirmMenu(plugin, viewer, "<red>Leave " + t.name + "?", List.of("<gray>You will lose your team membership."), null, this, () -> plugin.teams().leave(viewer)).open());
        if (leader) set(35, Items.of(Material.TNT, "<red>Disband team", "<gray>Removes the team for everyone."), tp ->
                new ConfirmMenu(plugin, viewer, "<red>Disband " + t.name + "?", List.of("<gray>All members are removed."), null, this, () -> plugin.teams().disband(viewer)).open());
        navBack(new AetherHubMenu(plugin, viewer));
    }

    private void buildNoTeam() {
        set(11, Items.of(Material.WRITABLE_BOOK, "<#4DA6FF>Create a team", "<gray>Type a name in chat."), t ->
                plugin.prompts().ask(viewer, "<prefix><gray>Type your team name (3-16 letters/numbers):", text -> { plugin.teams().create(viewer, text); new TeamMenu(plugin, viewer).open(); }, () -> new TeamMenu(plugin, viewer).open()));
        List<Team> inv = plugin.teams().pendingInvites(viewer.getUniqueId());
        display(15, Items.of(Material.BELL, "<#4DA6FF>Invitations: <white>" + inv.size(), "<gray>Listed below."));
        int slot = 27 - 9 + 0;
        slot = 18;
        for (Team t : inv) {
            if (slot > 26) break;
            set(slot++, Items.of(Material.WHITE_BANNER, "<white>" + t.name, "<gray>Left-click: <green>accept", "<gray>Right-click: <red>deny"), type -> {
                if (type == ClickType.RIGHT) plugin.teams().deny(viewer, t.name); else plugin.teams().accept(viewer, t.name);
                new TeamMenu(plugin, viewer).open();
            });
        }
        navBack(new AetherHubMenu(plugin, viewer));
    }
}

final class TeamMemberMenu extends Menu {
    private final UUID member; private final Menu back;
    TeamMemberMenu(AetherFFA plugin, Player viewer, UUID member, Menu back) { super(plugin, viewer); this.member = member; this.back = back; }
    @Override protected Component title() { return plugin.msg().item("<dark_gray>Manage <#4DA6FF>" + plugin.teams().nameOf(member)); }
    @Override protected int rows() { return 3; }
    @Override protected void build() {
        String name = plugin.teams().nameOf(member);
        set(11, Items.of(Material.GOLDEN_HELMET, "<#FFC83D>Promote to leader", "<gray>You become a regular member."), t ->
                new ConfirmMenu(plugin, viewer, "<#FFC83D>Promote " + name + "?", List.of("<gray>They become the team leader."), null, back, () -> { plugin.teams().promote(viewer, name); new TeamMenu(plugin, viewer).open(); }).open());
        set(15, Items.of(Material.IRON_BOOTS, "<red>Kick from team"), t ->
                new ConfirmMenu(plugin, viewer, "<red>Kick " + name + "?", List.of("<gray>They are removed immediately."), null, back, () -> { plugin.teams().kick(viewer, name); new TeamMenu(plugin, viewer).open(); }).open());
        navBack(back);
    }
}

/** /aether: one hub for everything a player can do (wallet, bounties, quests, kits, cosmetics, team, leaderboards). */
final class AetherHubMenu extends Menu {
    AetherHubMenu(AetherFFA plugin, Player viewer) { super(plugin, viewer); }
    @Override protected Component title() { return titleOf("hub", "<dark_gray>Aether <#4DA6FF>Hub"); }
    @Override protected int rows() { return 5; }

    @Override protected void build() {
        PlayerData d = plugin.store().get(viewer);
        long bal = plugin.economy().balance(viewer.getUniqueId());
        long bounty = plugin.bounties().total(viewer.getUniqueId());
        int rank = plugin.boards().rankOf("kills", viewer.getUniqueId());

        // Profile card in the middle of the top row.
        List<String> stats = new ArrayList<>();
        stats.add("<gray>Balance: <#B57BFF>" + plugin.economy().fmt(bal));
        if (d != null) {
            stats.add("<gray>Kills: <white>" + d.kills + " <gray>Deaths: <white>" + d.deaths);
            stats.add("<gray>Streak: <white>" + d.streak + " <gray>Best: <white>" + d.bestStreak);
            String nm = plugin.streaks().titleFor(d.streak);
            if (!nm.isEmpty()) stats.add("<gray>Rank: <#FFC83D>" + nm);
        }
        stats.add("<gray>Bounty on you: <#B57BFF>" + plugin.economy().fmt(bounty));
        if (rank > 0) stats.add("<gray>Kills rank: <white>#" + rank);
        display(4, Items.head(viewer, "<#4DA6FF>" + viewer.getName(), stats));

        set(10, Items.of(Items.currency(), "<#B57BFF>Wallet", "<gray>Balance, transactions and sending.", "<white>" + plugin.economy().fmt(bal), " ", "<dark_gray>Click to open"),
                t -> new EconomyMenu(plugin, viewer).open());
        set(12, Items.of(Material.TARGET, "<#4DA6FF>Bounties", "<gray>Place and claim bounties.", "<gray>On you: <#B57BFF>" + plugin.economy().fmt(bounty), " ", "<dark_gray>Click to open"),
                "aetherffa.bounty.use", t -> new BountyMenu(plugin, viewer).open());
        set(14, Items.of(Material.WRITABLE_BOOK, "<#4DA6FF>Daily Quests", "<gray>Complete quests for Aether.", "<gray>Resets in <white>" + Amounts.duration(plugin.quests().secondsUntilReset()), " ", "<dark_gray>Click to open"),
                "aetherffa.quests.use", t -> new QuestMenu(plugin, viewer).open());
        set(16, Items.of(Material.NETHER_STAR, "<#4DA6FF>Leaderboards", "<gray>Kills, streaks, richest, hunters.", " ", "<dark_gray>Click to open"),
                "aetherffa.leaderboard.use", t -> new LeaderboardMenu(plugin, viewer, null).open());
        set(20, Items.of(Material.CHEST, "<#4DA6FF>Kits", "<gray>Claim, buy and upgrade kits.", " ", "<dark_gray>Click to open"),
                "aetherffa.kit.use", t -> new KitMenu(plugin, viewer).open());
        set(22, Items.of(Material.FIREWORK_ROCKET, "<#4DA6FF>Cosmetics Shop", "<gray>Kill effects and kill messages.", "<gray>" + plugin.cosmetics().effectIds().size() + " effects, " + plugin.cosmetics().messageIds().size() + " messages", " ", "<dark_gray>Click to open"),
                "aetherffa.cosmetics.use", t -> new CosmeticMenu(plugin, viewer, true).open());
        set(24, Items.of(Material.WHITE_BANNER, "<#4DA6FF>Team", "<gray>Create or manage your team.", " ", "<dark_gray>Click to open"),
                "aetherffa.team.use", t -> new TeamMenu(plugin, viewer).open());
        set(30, Items.of(Material.PAPER, "<#4DA6FF>Send Aether", "<gray>Pay another online player.", " ", "<dark_gray>Click, then pick a player"),
                "aetherffa.aether.pay", t -> new PlayerPickMenu(plugin, viewer, "<dark_gray>Send Aether to...", p -> !p.equals(viewer), this, target ->
                        plugin.prompts().ask(viewer, "<prefix><gray>Type the amount to send to <white>" + target.getName() + "<gray> (e.g. 250 or 1.5k):", text -> {
                            var amt = Amounts.parse(text);
                            if (amt.isEmpty() || amt.getAsLong() <= 0) { plugin.msg().send(viewer, "general.invalid-amount"); return; }
                            viewer.performCommand("aether pay " + target.getName() + " " + amt.getAsLong());
                        }, () -> new AetherHubMenu(plugin, viewer).open())).open());
        set(32, Items.of(Material.EXPERIENCE_BOTTLE, "<#4DA6FF>Recent Activity", "<gray>Your last transactions in chat.", " ", "<dark_gray>Click to list"), t ->
                plugin.economy().history(viewer.getUniqueId(), 8).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
                    viewer.closeInventory();
                    if (list.isEmpty()) plugin.msg().send(viewer, "economy.no-history");
                    for (Economy.Tx tx : list) plugin.msg().send(viewer, "economy.history-line", "delta", (tx.delta() >= 0 ? "+" : "") + Amounts.format(tx.delta()),
                            "balance", Amounts.format(tx.balanceAfter()), "reason", String.valueOf(tx.reason()));
                })));
        if (viewer.hasPermission("aetherffa.admin"))
            set(40, Items.of(Material.COMMAND_BLOCK, "<red>Admin Panel", "<gray>Staff only.", " ", "<dark_gray>Click to open"), "aetherffa.admin", t -> new AdminDashboard(plugin, viewer).open());
        set(36, Items.of(Material.BARRIER, "<gray>Close"), t -> viewer.closeInventory());
        navRefresh();
    }
}
