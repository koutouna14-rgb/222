package net.aethermc.ffa;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /cosmetics */
final class CosmeticsCmd extends Cmd {
    CosmeticsCmd(AetherFFA plugin) { super(plugin); }
    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!perm(s, "aetherffa.cosmetics.use")) return true;
        Player p = player(s);
        if (p != null) new CosmeticMenu(plugin, p).open();
        return true;
    }
    @Override protected List<String> tab(CommandSender s, String label, String[] a) { return List.of(); }
}
