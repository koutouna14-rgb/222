package net.aethermc.ffa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * MiniMessage-based message service. Placeholders are inserted as unparsed text, so player-controlled
 * values (names, team names) can never inject formatting tags.
 */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final AetherFFA plugin;

    public Msg(AetherFFA plugin) { this.plugin = plugin; }

    public static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    private TagResolver resolvers(Object... kv) {
        List<TagResolver> list = new ArrayList<>();
        list.add(Placeholder.parsed("prefix", plugin.cfg().messages().getString("prefix", "<#4DA6FF>Aether</#4DA6FF> <dark_gray>»</dark_gray> ")));
        list.add(Placeholder.parsed("accent", "<#4DA6FF>"));
        list.add(Placeholder.parsed("currency", plugin.cfg().eco().getString("currency.name", "Aether")));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String key = String.valueOf(kv[i]);
            if (key.startsWith("p_")) list.add(Placeholder.parsed(key, String.valueOf(v).replaceAll("[^A-Za-z0-9_]", "")));   // sanitised: safe inside tag arguments
            else if (v instanceof Component comp) list.add(Placeholder.component(key, comp));
            else list.add(Placeholder.unparsed(key, String.valueOf(v)));
        }
        return TagResolver.resolver(list);
    }

    public Component mm(String raw, Object... kv) {
        try { return MM.deserialize(raw, resolvers(kv)); }
        catch (RuntimeException e) { return Component.text(raw); }
    }

    /** MiniMessage text for GUI items: italics off so names look clean. */
    public Component item(String raw, Object... kv) {
        return mm(raw, kv).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public String raw(String key) {
        String v = plugin.cfg().messages().getString(key);
        return v == null ? "<red>Missing message: " + key : v;
    }

    public Component get(String key, Object... kv) { return mm(raw(key), kv); }

    public void send(CommandSender to, String key, Object... kv) {
        String raw = raw(key);
        if (raw.isEmpty()) return;
        to.sendMessage(mm(raw, kv));
    }

    public void sendRaw(CommandSender to, String miniMessage, Object... kv) { to.sendMessage(mm(miniMessage, kv)); }

    public void broadcast(String key, Object... kv) {
        String raw = raw(key);
        if (raw.isEmpty()) return;
        Component c = mm(raw, kv);
        for (var p : Bukkit.getOnlinePlayers()) p.sendMessage(c);
        Bukkit.getConsoleSender().sendMessage(c);
    }

    /** Returns null if the MiniMessage string parses, otherwise an error message. */
    public static String validate(String raw) {
        try { MM.deserialize(raw); return null; } catch (RuntimeException e) { return e.getMessage(); }
    }
}
