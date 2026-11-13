package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.music.Lrc;
import dev.breeze.music.Lyrics;
import dev.breeze.music.LyricsPlace;
import dev.breeze.net.SpotifyPlayer;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The lyrics of the song that is playing, line by line in time with it.
 *
 * Three places (Show): on screen, as a HUD element like any other; in the
 * world, standing at a spot the player chose (the Music screen's "Place
 * here"), facing whoever looks at it; or following the player, floating a
 * few blocks ahead and catching up when they turn. Only this player sees
 * them.
 *
 * The song comes from the Music player's source (the Spotify account or any
 * player on this computer, {@link SpotifyPlayer}); the words from LRCLIB
 * ({@link Lyrics}), which gets the song's title, artist and length. A player
 * that does not tell its position is timed from when the song appeared, and
 * Offset corrects what is left.
 */
public class LyricsHud extends AbstractHudModule {

    private static final String SHOW = "Lyrics";
    private static final String WORLD = "In the world";
    public static final String ON_SCREEN = "On screen", IN_WORLD = "In the world", FOLLOW = "Follow me";

    private static LyricsHud instance;

    private final Setting.Mode where;
    private final Setting.Int lines = new Setting.Int("lines", "Lines shown", SHOW, 3, 1, 5, "");
    private final Setting.Int offset = new Setting.Int("offset", "Offset (later +, sooner -)", SHOW, 0, -5000, 5000, " ms");
    private final Setting.Color current = new Setting.Color("current", "Line being sung", SHOW, 0xFFFFFFFF);
    private final Setting.Color others = new Setting.Color("others", "Other lines", SHOW, 0xFF9AA7BD);
    private final Setting.Int width = new Setting.Int("width", "Width on screen", SHOW, 220, 120, 360, "px");
    private final Setting.Int size = new Setting.Int("size", "Size", WORLD, 100, 50, 300, "%");
    private final Setting.Bool plate = new Setting.Bool("plate", "Dark plate behind the words", WORLD, true);
    private final Setting.Bool through = new Setting.Bool("through", "Show through blocks", WORLD, false);
    private final Setting.Int distance = new Setting.Int("distance", "Follow me: blocks ahead", WORLD, 4, 2, 8, "");

    /** For the self-test: what the last frame showed, on screen and in the world. */
    private static volatile String lastHud = "";
    private static volatile String lastWorld = "";
    private static volatile int testColour;

    // Follow me: where the words float now, easing toward a point ahead of the player.
    private double fx, fy, fz;
    private double aheadX, aheadZ = 1;
    private boolean following;
    private long followAt;

    public LyricsHud() {
        super("Lyrics", Category.HUD, "Shows the lyrics of the song playing, on screen or placed in the world. Lyrics come from LRCLIB.", KEY_NONE, 4, 120);
        instance = this;
        boolean world = dev.breeze.compat.Hooks.worldDrawing();
        where = new Setting.Mode("where", "Show", SHOW, world ? new String[]{ON_SCREEN, IN_WORLD, FOLLOW} : new String[]{ON_SCREEN}, 0);
        add(where);
        add(lines);
        add(offset);
        add(current);
        add(others);
        add(width);
        if (world) {
            add(size);
            add(plate);
            add(through);
            add(distance);
        }
    }

    public static LyricsHud get() {
        return instance;
    }

    /** On screen only where this Minecraft cannot draw in the world (1.21.9). */
    @Override
    public boolean offeredWithoutWorldDrawing() {
        return true;
    }

    public String where() {
        return where.value();
    }

    public boolean worldModes() {
        return where.options.length > 1;
    }

    public void cycleWhere() {
        where.cycle(1);
    }

    public int offsetMs() {
        return offset.value;
    }

    public void nudgeOffset(int byMs) {
        offset.set(offset.value + byMs);
    }

    /** The lines to show now: {text, colour} pairs around the one being sung. Empty when there is nothing to show. */
    private List<Object[]> showing(Font font, int wrapWidth) {
        List<Object[]> out = new ArrayList<>();
        SpotifyPlayer.State s = SpotifyPlayer.state();
        Lyrics.Now now = Lyrics.now(s, System.currentTimeMillis(), offset.value);
        if (now.result.status != Lyrics.Status.SYNCED) return out;
        List<Lrc.Line> all = now.result.lines;
        int n = lines.value;
        int before = (n - 1) / 2;
        // Before the first line: the lines to come, none of them lit.
        int centre = Math.max(now.index, 0);
        int from = Math.max(0, centre - before);
        int to = Math.min(all.size() - 1, from + n - 1);
        int lit = testColour != 0 ? testColour : current.argb;
        for (int i = from; i <= to; i++) {
            String text = all.get(i).text;
            boolean sung = i == now.index;
            if (text.isEmpty()) {
                // A gap in the singing: a note where the line being sung would be, nothing elsewhere.
                if (sung) out.add(new Object[]{"♪", lit});
                continue;
            }
            for (String part : Lrc.wrap(text, wrapWidth, 2, font::width)) {
                out.add(new Object[]{part, sung ? lit : others.argb});
            }
        }
        return out;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        boolean editing = dev.breeze.compat.ActiveScreen.get(mc) instanceof dev.breeze.menu.HudEditorScreen;
        if (!where.is(ON_SCREEN)) {
            // The words are in the world; the HUD editor still shows where the element would be.
            if (editing) line(g, font, "Lyrics (shown in the world)", others.argb);
            lastHud = "";
            return;
        }
        List<Object[]> rows = showing(font, width.value);
        if (rows.isEmpty()) {
            if (editing) line(g, font, "Lyrics appear here", others.argb);
            lastHud = "";
            return;
        }
        StringBuilder shown = new StringBuilder();
        for (Object[] row : rows) {
            line(g, font, (String) row[0], (Integer) row[1]);
            if ((Integer) row[1] != others.argb) shown.append(shown.length() > 0 ? " " : "").append((String) row[0]);
        }
        lastHud = shown.toString();
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (where.is(ON_SCREEN) || mc.level == null || mc.player == null || !ctx.ready()) {
            lastWorld = "";
            following = false;
            return;
        }
        Vec3 cam = ctx.cam();
        Vec3 spot = where.is(IN_WORLD) ? LyricsPlace.get(mc) : null;
        if (spot == null) spot = follow(mc, cam);
        else following = false;
        double dx = spot.x - cam.x, dy = spot.y - cam.y, dz = spot.z - cam.z;
        if (dx * dx + dy * dy + dz * dz > 96 * 96) {
            lastWorld = "";
            return;
        }
        Font font = mc.font;
        List<Object[]> rows = showing(font, 200);
        if (rows.isEmpty()) {
            lastWorld = "";
            return;
        }
        float scale = size.value / 100f;
        // One line of text is 0.025 blocks a pixel: nine pixels and a gap.
        double step = 0.025 * scale * (font.lineHeight + 2);
        int back = plate.value && testColour == 0 ? 0x70000000 : 0;
        StringBuilder shown = new StringBuilder();
        // Centred on the spot: the first row above it, the last below.
        double top = spot.y + step * (rows.size() - 1) / 2.0;
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            WorldRender.text(ctx, spot.x, top - i * step, spot.z, (String) row[0], (Integer) row[1], scale, through.value, back);
            if ((Integer) row[1] != others.argb) shown.append(shown.length() > 0 ? " " : "").append((String) row[0]);
        }
        lastWorld = shown.toString();
    }

    /** Follow me: a point ahead of the player and a little above their line of sight, approached smoothly. */
    private Vec3 follow(Minecraft mc, Vec3 cam) {
        Vec3 look = mc.player.getViewVector(dev.breeze.render.Frame.partialTick());
        double flat = Math.hypot(look.x, look.z);
        // Looking straight up or down there is no "ahead": keep the last direction.
        if (flat > 0.02) {
            aheadX = look.x / flat;
            aheadZ = look.z / flat;
        }
        // Fourteen degrees above the crosshair, so the words never cover what the player aims at.
        double up = Math.max(-1.39, Math.min(1.39, Math.atan2(look.y, flat) + 0.245));
        double d = distance.value;
        double tx = cam.x + aheadX * Math.cos(up) * d;
        double ty = cam.y + Math.sin(up) * d;
        double tz = cam.z + aheadZ * Math.cos(up) * d;
        long now = System.nanoTime();
        double far = (tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz);
        if (!following || far > 24 * 24) {
            fx = tx;
            fy = ty;
            fz = tz;
            following = true;
        } else {
            double dt = Math.min(0.25, Math.max(0, (now - followAt) / 1e9));
            double k = 1 - Math.exp(-dt * 7);
            fx += (tx - fx) * k;
            fy += (ty - fy) * k;
            fz += (tz - fz) * k;
        }
        followAt = now;
        return new Vec3(fx, fy, fz);
    }

    /** For the self-test: the line shown as being sung in the last frame. */
    public static String lastHud() { return lastHud; }

    public static String lastWorld() { return lastWorld; }

    /** For the self-test: the line being sung in {@code argb}, without a plate (0 gives the player's colours back). */
    public static void testColour(int argb) { testColour = argb; }
}
