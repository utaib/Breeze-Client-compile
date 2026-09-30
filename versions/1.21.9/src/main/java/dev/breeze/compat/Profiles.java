package dev.breeze.compat;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/**
 * A player's account name and id from their GameProfile. The profile became a
 * record in Minecraft 1.21.9 (name() and id() instead of getName() and
 * getId()), so versions/ has a copy of this file per form; this is the 1.21.9
 * form.
 */
public final class Profiles {

    private Profiles() {}

    public static String name(GameProfile profile) {
        return profile.name();
    }

    public static UUID id(GameProfile profile) {
        return profile.id();
    }
}
