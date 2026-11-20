package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.hud.Crosshair;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * A crosshair made from settings (style, length, thickness, gap, colour,
 * centre dot, outline) in place of Minecraft's, and shared as a code. The
 * shape and the code are dev.breeze.hud.Crosshair, so the menu's preview and
 * the game draw the same thing. Minecraft's own crosshair is cancelled while
 * this is on (GuiMixin) and untouched while it is off.
 */
public class CustomCrosshair extends Module {

    private static final String GROUP = "Crosshair";
    private static final String OUTLINE = "Outline";
    private static CustomCrosshair instance;

    private final Setting.Mode style = add(new Setting.Mode("style", "Style", GROUP, Crosshair.STYLES, 0));
    private final Setting.Int length = add(new Setting.Int("length", "Length", GROUP, 4, Crosshair.LENGTH_MIN, Crosshair.LENGTH_MAX, " px"));
    private final Setting.Int thickness = add(new Setting.Int("thickness", "Thickness", GROUP, 1, Crosshair.THICKNESS_MIN, Crosshair.THICKNESS_MAX, " px"));
    private final Setting.Int gap = add(new Setting.Int("gap", "Gap", GROUP, 2, Crosshair.GAP_MIN, Crosshair.GAP_MAX, " px"));
    private final Setting.Color color = add(new Setting.Color("color", "Colour", GROUP, 0xFFFFFFFF));
    private final Setting.Bool dot = add(new Setting.Bool("dot", "Centre dot", GROUP, false));
    private final Setting.Bool thirdPerson = add(new Setting.Bool("third_person", "Show in third person", GROUP, false));
    private final Setting.Bool outline = add(new Setting.Bool("outline", "Outline", OUTLINE, true));
    private final Setting.Int outlineThickness = add(new Setting.Int("outline_thickness", "Outline thickness", OUTLINE, 1, Crosshair.OUTLINE_MIN, Crosshair.OUTLINE_MAX, " px"));
    private final Setting.Color outlineColor = add(new Setting.Color("outline_color", "Outline colour", OUTLINE, 0xFF000000));

    // The shape for the settings last drawn, so a frame allocates nothing.
    private Crosshair.Config drawnFor;
    private List<Crosshair.Rect> drawn = List.of();
    private int frames;

    public CustomCrosshair() {
        super("Custom Crosshair", Category.VISUAL, "Your own crosshair: style, size, gap, colours, outline, and a code to share it.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static CustomCrosshair get() {
        return instance;
    }

    public Crosshair.Config config() {
        return new Crosshair.Config(style.value(), length.value, thickness.value, gap.value, color.argb,
                dot.value, outline.value, outlineThickness.value, outlineColor.argb);
    }

    /** Takes the settings of a share code; nothing else changes. */
    public void apply(Crosshair.Config c) {
        Crosshair.Config in = c.clamped();
        style.index = Math.max(0, Crosshair.styleIndex(in.style()));
        length.value = in.length();
        thickness.value = in.thickness();
        gap.value = in.gap();
        color.argb = in.color();
        dot.value = in.dot();
        outline.value = in.outline();
        outlineThickness.value = in.outlineThickness();
        outlineColor.argb = in.outlineColor();
    }

    /** Whether the crosshair is shown now: in a world, and in first person unless set to show in third. */
    public static boolean showsNow(Minecraft mc) {
        return active() && mc.player != null && (mc.options.getCameraType().isFirstPerson() || instance.thirdPerson.value);
    }

    /** Minecraft's own crosshair is cancelled (GuiMixin); this draws this one in its place. */
    @Override
    protected void onHudRender(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!showsNow(mc)) return;
        Crosshair.Config now = config();
        if (!now.equals(drawnFor)) {
            drawn = Crosshair.rects(now);
            drawnFor = now;
        }
        // Where Minecraft centres its own 15 pixel crosshair.
        int cx = (mc.getWindow().getGuiScaledWidth() - 15) / 2 + 7;
        int cy = (mc.getWindow().getGuiScaledHeight() - 15) / 2 + 7;
        for (Crosshair.Rect r : drawn) g.fill(cx + r.x0(), cy + r.y0(), cx + r.x1(), cy + r.y1(), r.color());
        frames++;
    }

    // ── For the self-test ───────────────────────────────────────────────

    /** Frames the crosshair was drawn in, and how many rectangles the last one had. */
    public static int framesDrawn() {
        return instance == null ? 0 : instance.frames;
    }

    public static int lastDrawnRects() {
        return instance == null ? 0 : instance.drawn.size();
    }
}
