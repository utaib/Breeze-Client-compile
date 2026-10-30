package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.Frame;
import dev.breeze.render.HitboxGeometry;
import dev.breeze.render.HitboxGeometry.Kind;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/**
 * Hitboxes drawn by Breeze, by kind of entity and in its colours, instead of
 * Minecraft's own F3+B view, which this no longer switches on.
 *
 * Two ways were weighed (1.1.0 directive, fifth item): restyling the game's
 * own hitbox drawing, or drawing Breeze's own boxes. The game's drawing moved
 * three times across the supported versions (render states in 1.21.2, a
 * debug renderer in 1.21.9, again in 26.x), so a hook would need a variant
 * per family and still could not leave out kinds of entity without
 * redrawing them. Breeze's own boxes go through WorldRender, which Block
 * Overlay and Ping Ghost already draw with on every version, and leave the
 * game's renderer untouched. The boxes are the game's own bounding boxes,
 * placed between ticks as the game places the entity.
 *
 * Like F3+B: nothing for invisible entities, and nothing at all where the
 * server asks for reduced debug information.
 *
 * Where the Fabric API cannot draw in the world (1.21.9), the module keeps
 * what it did before: it switches the game's own F3+B boxes, and its own
 * settings are not listed.
 */
public class Hitboxes extends Module {

    private static Hitboxes instance;

    /** False on 1.21.9: no world drawing, so the game's own boxes instead. */
    private static final boolean OWN = dev.breeze.compat.Hooks.worldDrawing();

    private final Setting.Bool players = own(new Setting.Bool("players", "Players", "Show", true));
    private final Setting.Bool hostile = own(new Setting.Bool("hostile", "Hostile mobs", "Show", true));
    private final Setting.Bool passive = own(new Setting.Bool("passive", "Animals and other mobs", "Show", true));
    private final Setting.Bool items = own(new Setting.Bool("items", "Items and experience orbs", "Show", false));
    private final Setting.Bool projectiles = own(new Setting.Bool("projectiles", "Arrows and other projectiles", "Show", true));
    private final Setting.Bool other = own(new Setting.Bool("other", "Boats, minecarts, armour stands and the rest", "Show", false));
    private final Setting.Bool self = own(new Setting.Bool("self", "Yourself in third person", "Show", false));
    private final Setting.Int range = own(new Setting.Int("range", "Within", "Show", 32, 8, 64, " blocks"));

    private final Setting.Color playerColour = own(new Setting.Color("player_colour", "Players' colour", "Look", 0xFFFFFFFF));
    private final Setting.Color hostileColour = own(new Setting.Color("hostile_colour", "Hostile mobs' colour", "Look", 0xFFFF5555));
    private final Setting.Color passiveColour = own(new Setting.Color("passive_colour", "Other mobs' colour", "Look", 0xFF55FF55));
    private final Setting.Color itemColour = own(new Setting.Color("item_colour", "Items' colour", "Look", 0xFFFFDD55));
    private final Setting.Color projectileColour = own(new Setting.Color("projectile_colour", "Projectiles' colour", "Look", 0xFF55DDFF));
    private final Setting.Color otherColour = own(new Setting.Color("other_colour", "The rest's colour", "Look", 0xFFAAAAAA));
    private final Setting.Int width = WorldRender.canStyle()
            ? own(new Setting.Int("width", "Line width", "Look", 2, 1, 5, " px")) : null;
    private final Setting.Bool eyes = own(new Setting.Bool("eyes", "Eye height", "Look", true));
    private final Setting.Color eyeColour = own(new Setting.Color("eye_colour", "Eye height colour", "Look", 0xFFFF3333));
    private final Setting.Bool look = own(new Setting.Bool("look", "Where it looks", "Look", true));
    private final Setting.Color lookColour = own(new Setting.Color("look_colour", "Look line colour", "Look", 0xFF3355FF));

    private static volatile int lastDrawn;
    private static final Map<Kind, Integer> lastKinds = new EnumMap<>(Kind.class);

    public Hitboxes() {
        super("Hitboxes", Category.VISUAL, OWN
                ? "Shows the hitboxes of the kinds of entity you choose, in your colours."
                : "Shows Minecraft's own hitboxes, as F3+B does.", KEY_NONE);
        instance = this;
    }

    /** Listed and saved only where Breeze draws its own boxes. */
    private <T extends Setting> T own(T setting) {
        return OWN ? add(setting) : setting;
    }

    @Override
    public boolean offeredWithoutWorldDrawing() { return true; }

    public static Hitboxes get() {
        return instance;
    }

    @Override
    protected void onEnable() {
        // Earlier versions of this module switched Minecraft's own F3+B view
        // on (and 1.21.9 and later keep that switch): off, so the boxes are
        // not drawn twice. F3+B still shows the game's own when pressed.
        // Without world drawing it is the game's view this module switches.
        try {
            dev.breeze.compat.Debug.setHitboxes(Minecraft.getInstance(), !OWN);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        if (OWN) return;
        try {
            dev.breeze.compat.Debug.setHitboxes(Minecraft.getInstance(), false);
        } catch (Throwable ignored) {}
    }

    /** For the self-test: whether Breeze draws its own boxes on this version. */
    public static boolean drawsOwn() { return OWN; }

    static Kind kind(Entity e) {
        if (e instanceof Player) return Kind.PLAYER;
        if (e instanceof ItemEntity || e instanceof ExperienceOrb) return Kind.ITEM;
        if (e instanceof Projectile) return Kind.PROJECTILE;
        if (e instanceof Mob) return e.getType().getCategory() == MobCategory.MONSTER ? Kind.HOSTILE : Kind.PASSIVE;
        return Kind.OTHER;
    }

    private boolean shows(Kind k) {
        return switch (k) {
            case PLAYER -> players.value;
            case HOSTILE -> hostile.value;
            case PASSIVE -> passive.value;
            case ITEM -> items.value;
            case PROJECTILE -> projectiles.value;
            case OTHER -> other.value;
        };
    }

    private int colour(Kind k) {
        return switch (k) {
            case PLAYER -> playerColour.argb;
            case HOSTILE -> hostileColour.argb;
            case PASSIVE -> passiveColour.argb;
            case ITEM -> itemColour.argb;
            case PROJECTILE -> projectileColour.argb;
            case OTHER -> otherColour.argb;
        };
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ctx.ready()) return;
        if (mc.player.isReducedDebugInfo()) {
            lastDrawn = 0;
            return;
        }
        float pt = Frame.partialTick();
        Vec3 cam = ctx.cam();
        double maxSq = (double) range.value * range.value;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        float px = width == null ? 1f : width.value;
        int drawn = 0;
        Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) {
                if (!self.value || firstPerson) continue;
            } else if (e.isInvisible() || e.distanceToSqr(mc.player) > maxSq) {
                continue;
            }
            Kind k = kind(e);
            if (e == mc.player ? !self.value : !shows(k)) continue;
            // Where the game draws the entity this frame: between its last
            // two tick positions.
            double dx = (e.xo + (e.getX() - e.xo) * pt) - e.getX();
            double dy = (e.yo + (e.getY() - e.yo) * pt) - e.getY();
            double dz = (e.zo + (e.getZ() - e.zo) * pt) - e.getZ();
            AABB b = e.getBoundingBox();
            double ox = dx - cam.x, oy = dy - cam.y, oz = dz - cam.z;
            boolean living = e instanceof LivingEntity;
            Vec3 view = living && look.value ? e.getViewVector(pt) : Vec3.ZERO;
            HitboxGeometry.Lines l = HitboxGeometry.of(b.minX + ox, b.minY + oy, b.minZ + oz, b.maxX + ox, b.maxY + oy, b.maxZ + oz,
                    e.getEyeY() + oy, view.x, view.y, view.z, living && eyes.value, living && look.value);
            WorldRender.lines(ctx, l.box(), l.box().length / 6, colour(k), px);
            if (l.eye() != null) WorldRender.lines(ctx, l.eye(), l.eye().length / 6, eyeColour.argb, px);
            if (l.look() != null) WorldRender.lines(ctx, l.look(), 1, lookColour.argb, px);
            drawn++;
            kinds.merge(k, 1, Integer::sum);
        }
        lastDrawn = drawn;
        synchronized (lastKinds) {
            lastKinds.clear();
            lastKinds.putAll(kinds);
        }
    }

    /** For the self-test: boxes drawn in the last frame, and of which kinds. */
    public static int lastDrawn() { return lastDrawn; }

    public static String lastKinds() {
        synchronized (lastKinds) {
            return lastKinds.toString();
        }
    }

    /** For the self-test: show yourself, as the setting does. */
    public void showSelf(boolean on) { self.value = on; }
}
