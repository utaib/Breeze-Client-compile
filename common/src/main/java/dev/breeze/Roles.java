package dev.breeze;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The role table, supplied by the backend rather than compiled into the mod.
 *
 * This is the point of the whole class: adding Developer, or a seasonal role, or
 * anything else, is one row in the `tags` table. The mod holds no list of role
 * names, no switch over slugs and no per-role constants, so a new role appears
 * in game on the next /tag poll without a mod release, a client update or a
 * version bump.
 *
 * The only thing hardcoded here is the fallback used when the backend has not
 * answered yet, which is the plain Breeze look every account has.
 */
public final class Roles {

    /** One role, exactly as the backend describes it. */
    public static final class Role {
        public final String slug;
        public final String name;
        public final int color;
        public final int priority;
        /** Optional icon asset URL. Null means draw the built-in wind charge. */
        public final String icon;

        public Role(String slug, String name, int color, int priority, String icon) {
            this.slug = slug;
            this.name = name;
            this.color = color;
            this.priority = priority;
            this.icon = icon;
        }
    }

    /**
     * What every Breeze account is before the backend says otherwise, and what
     * an unknown slug resolves to.
     *
     * Never null, so no caller has to null-check a role. Returning null here
     * would push a branch into the nameplate and tab list render paths, which
     * run per player per frame.
     */
    public static final Role DEFAULT = new Role("breeze", "Breeze", 0xFF55FFFF, 0, null);

    private static final Map<String, Role> ROLES = new ConcurrentHashMap<>();

    private Roles() {}

    /** Replace the whole table. Called from the /tag poll. */
    public static void set(Map<String, Role> roles) {
        if (roles == null) return;
        ROLES.clear();
        ROLES.putAll(roles);
    }

    /** Look up a role by slug. Unknown slugs fall back rather than failing. */
    public static Role get(String slug) {
        if (slug == null || slug.isEmpty()) return DEFAULT;
        Role r = ROLES.get(slug);
        return r != null ? r : DEFAULT;
    }

    public static boolean known(String slug) {
        return slug != null && ROLES.containsKey(slug);
    }

    public static boolean isEmpty() {
        return ROLES.isEmpty();
    }

    /** Every role, highest priority first. For the Wardrobe's tag list. */
    public static Map<String, Role> all() {
        Map<String, Role> sorted = new LinkedHashMap<>();
        ROLES.values().stream()
                .sorted((a, b) -> Integer.compare(b.priority, a.priority))
                .forEach(r -> sorted.put(r.slug, r));
        return Collections.unmodifiableMap(sorted);
    }
}
