package dev.breeze.names;

import dev.breeze.compat.Hovers;
import dev.breeze.modules.DisplayName;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place Breeze shows the player's own name as their local display
 * name (Display Name module). Only what is drawn on this computer changes:
 * nothing here is sent, and other players' text is never touched.
 *
 * Which text is the player's own is decided by identity, never by spelling:
 * in chat, the pieces whose hover shows the player's own entity (Minecraft
 * styles every player name it writes that way: chat, joins, deaths,
 * advancements); in the tab list, the entry whose profile is the player's.
 * Inside those pieces, {@link LocalName#swap} replaces the real name as a
 * whole word and keeps the server's colours, prefixes and click actions. A
 * name a server wrote as plain text, without that hover, is left as the
 * server wrote it: it cannot be told apart from someone typing the name.
 */
public final class OwnName {

    private OwnName() {}

    private static volatile String lastChat;
    private static volatile int chatSwaps;

    /** A chat line with the player's own name, where the line says it is theirs, as the alias. */
    public static Component inChat(Component line) {
        if (line == null || !DisplayName.inChat()) return line;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return line;
        Component out = rebuild(line, mc.player.getUUID(), mc.player.getName().getString(), DisplayName.alias(), false);
        if (out != line) {
            chatSwaps++;
            lastChat = out.getString();
        }
        return out;
    }

    /** The player's own tab list entry (the caller has matched its uuid), with the alias. */
    public static Component inEntry(Component name) {
        if (name == null || !DisplayName.inTab()) return name;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return name;
        return rebuild(name, mc.player.getUUID(), mc.player.getName().getString(), DisplayName.alias(), true);
    }

    /**
     * {@code text} as flat styled pieces with the name swapped in the pieces
     * that are the player's (all of them when {@code mine}), or {@code text}
     * itself when nothing was swapped, so an untouched line keeps its own
     * structure (translations, signatures' display) exactly.
     */
    static Component rebuild(Component text, UUID me, String real, String alias, boolean mine) {
        List<Style> styles = new ArrayList<>();
        List<String> pieces = new ArrayList<>();
        boolean[] changed = {false};
        text.<Object>visit((style, piece) -> {
            String shown = piece;
            if (mine || me.equals(Hovers.entityId(style))) {
                String swapped = LocalName.swap(piece, real, alias);
                if (swapped != piece) {
                    changed[0] = true;
                    shown = swapped;
                }
            }
            styles.add(style);
            pieces.add(shown);
            return Optional.empty();
        }, Style.EMPTY);
        if (!changed[0]) return text;
        MutableComponent out = Component.empty();
        for (int i = 0; i < pieces.size(); i++) out.append(Component.literal(pieces.get(i)).setStyle(styles.get(i)));
        return out;
    }

    /** For the self-test: how many chat lines had the name swapped, and the last one as drawn. */
    public static int chatSwaps() { return chatSwaps; }

    public static String lastChat() { return lastChat; }
}
