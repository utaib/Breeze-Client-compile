package dev.breeze.net;

import net.minecraft.client.Minecraft;

import java.util.UUID;

public final class Self {

    private Self() {}

    private static volatile UUID launchUuid;
    private static volatile boolean launchUuidRead;

    /**
     * The account this game session belongs to, for anything the API scopes to
     * "me": DMs, friends, hosting, the selected cape.
     *
     * Not the same as {@link #uuid(Minecraft)}. On a cracked server
     * mc.player.getUUID() is an offline id derived from the name, which never
     * matches the account the game token speaks for, so every self-scoped call
     * would be refused. The launcher passes the real account uuid as
     * -Dbreeze.player.uuid; the session profile is the fallback when the game
     * was not started by the launcher.
     */
    public static UUID selfUuid() {
        if (!launchUuidRead) {
            String prop = System.getProperty("breeze.player.uuid");
            launchUuid = parse(prop);
            launchUuidRead = true;
        }
        if (launchUuid != null) return launchUuid;
        try {
            return Minecraft.getInstance().getUser().getProfileId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Accepts both dashed and undashed uuids, since the launcher stores either. */
    private static UUID parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 32 && s.indexOf('-') < 0) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The uuid the world knows this player by. Use for in-world rendering lookups. */
    public static UUID uuid(Minecraft mc) {
        try {
            if (mc.player != null) return mc.player.getUUID();
            return mc.getUser().getProfileId();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String name(Minecraft mc) {
        try {
            if (mc.player != null) return mc.player.getScoreboardName();
            return mc.getUser().getName();
        } catch (Throwable t) {
            return "Player";
        }
    }
}
