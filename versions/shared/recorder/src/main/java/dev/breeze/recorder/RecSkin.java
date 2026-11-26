package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * The player's own skin in a watched recording (1.2.3, round 3; Minecraft
 * 1.20.2 and later). A recording starts in the middle of a game, so it
 * holds no player-list entry for the player and the game drew Steve or Alex.
 * The playback adds one from the profile this game signed in with (which
 * has the skin from 1.20.2 on), under the watched player's id. Only the
 * playback's own connection is changed; nothing is sent anywhere.
 */
final class RecSkin {

    private RecSkin() {}

    /** True once the player has an entry (added now or already there). */
    static boolean ensure(Minecraft mc) {
        ClientPacketListener c = mc.getConnection();
        if (c == null || mc.player == null) return false;
        java.util.UUID id = mc.player.getUUID();
        if (c.getPlayerInfo(id) != null) return true;
        ((dev.breeze.recmixin.PlayerInfoMapAccess) c).breeze$playerInfoMap().put(id, new PlayerInfo(mc.getGameProfile(), false));
        return true;
    }
}
