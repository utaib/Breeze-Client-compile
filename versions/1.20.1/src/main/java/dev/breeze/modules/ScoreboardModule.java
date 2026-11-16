package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.compat.Sidebar;
import dev.breeze.hud.HudPlacement;
import dev.breeze.hud.SidebarLayout;
import dev.breeze.settings.Setting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The sidebar scoreboard, drawn by Breeze where the player puts it (HUD
 * editor) in Breeze's panel, or hidden. While on, Minecraft's own sidebar is
 * not drawn (GuiMixin); off, Minecraft draws it as always.
 *
 * Everything shown is what the server sent: the objective the game would
 * show (the player's team colour slot first, then the sidebar), its rows in
 * Minecraft's own choice and order (SidebarLayout.pick), each name with its
 * team prefix, suffix and colours, and each number in the format the server
 * set (from 1.20.3: styled, fixed text or blank). Breeze changes only the
 * layout: where the title sits, the space between rows, whether numbers are
 * shown, and the panel. Server boards made of team prefixes (most networks)
 * read the same way, since their text is the names' formatting.
 */
public class ScoreboardModule extends AbstractHudModule {

    private static ScoreboardModule instance;

    private final Setting.Bool hide = add(new Setting.Bool("hide", "Hide the sidebar entirely", "Sidebar", false));
    private final Setting.Mode numbers = add(new Setting.Mode("numbers", "Numbers", "Sidebar",
            new String[] {"As the server sends them", "Hidden"}, 0));
    private final Setting.Mode title = add(new Setting.Mode("title", "Title", "Sidebar", new String[] {"Centre", "Left", "Right"}, 0));
    private final Setting.Int rowGap = add(new Setting.Int("row_gap", "Space between rows", "Sidebar", 0, 0, 6, " px"));

    private Sidebar.Board board;
    private static volatile int lastRows = -1;
    private static volatile String lastTitle = "";
    private static volatile List<String> lastNames = List.of();
    private static volatile String lastNumbers = "";
    private static volatile long vanillaDrawn;

    public ScoreboardModule() {
        super("Scoreboard", Category.HUD, "The sidebar scoreboard in Breeze's style, where you put it, or hidden.", KEY_NONE, 4, 4);
        instance = this;
    }

    /** While on, Minecraft's own sidebar is not drawn. */
    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static ScoreboardModule get() {
        return instance;
    }

    /** Where Minecraft puts its own: the right edge, half way down. */
    @Override
    protected HudPlacement defaultPlacement() {
        return new HudPlacement(HudPlacement.H.RIGHT, HudPlacement.V.MIDDLE, 1, 0);
    }

    /**
     * Before 2.21.0 this module only hid the sidebar and had no settings: a
     * save with it on and no settings meant "hidden", and stays so.
     */
    @Override
    public void settingsLoaded(boolean found) {
        if (!found && isEnabled()) hide.value = true;
    }

    @Override
    protected boolean drawsShapes() { return true; }

    private static boolean editing(Minecraft mc) {
        return dev.breeze.compat.ActiveScreen.get(mc) instanceof dev.breeze.menu.HudEditorScreen;
    }

    @Override
    protected boolean hasContent() {
        Minecraft mc = Minecraft.getInstance();
        if (hide.value) {
            lastRows = -1;
            return false;
        }
        Sidebar.Board b = null;
        try {
            b = Sidebar.read(mc);
        } catch (Throwable ignored) {}
        if (b == null && editing(mc)) b = sample();
        board = b;
        if (b == null) lastRows = 0;
        return b != null;
    }

    /** What the HUD editor shows when there is no sidebar to place. */
    private static Sidebar.Board sample() {
        return new Sidebar.Board(Component.literal("Scoreboard"), List.of(
                new Sidebar.Row(Component.literal("Players: 12"), Component.literal("3").withStyle(ChatFormatting.RED)),
                new Sidebar.Row(Component.literal("Kills: 4"), Component.literal("2").withStyle(ChatFormatting.RED)),
                new Sidebar.Row(Component.literal("play.example.net"), Component.literal("1").withStyle(ChatFormatting.RED))));
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        Sidebar.Board b = board;
        if (b == null) return;
        boolean showNumbers = numbers.index == 0;
        int n = b.rows().size();
        int[] nameW = new int[n];
        int[] numberW = new int[n];
        for (int i = 0; i < n; i++) {
            Sidebar.Row r = b.rows().get(i);
            nameW[i] = font.width(r.name());
            numberW[i] = r.number() == null ? 0 : font.width(r.number());
        }
        SidebarLayout.Align align = title.index == 1 ? SidebarLayout.Align.LEFT
                : title.index == 2 ? SidebarLayout.Align.RIGHT : SidebarLayout.Align.CENTER;
        SidebarLayout.Layout l = SidebarLayout.layout(font.width(b.title()), nameW, numberW, font.lineHeight,
                rowGap.value, font.width(": "), align, showNumbers);
        bounds(l.width(), l.height());
        boolean shadow = style().textShadow.value;
        g.drawString(font, b.title(), x + l.titleX(), y, 0xFFFFFFFF, shadow);
        for (int i = 0; i < n; i++) {
            Sidebar.Row r = b.rows().get(i);
            int ry = y + l.rowY()[i];
            g.drawString(font, r.name(), x, ry, 0xFFFFFFFF, shadow);
            if (showNumbers && numberW[i] > 0) g.drawString(font, r.number(), x + l.numberRight() - numberW[i], ry, 0xFFFFFFFF, shadow);
        }
        List<String> names = new ArrayList<>(n);
        StringBuilder nums = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Sidebar.Row r = b.rows().get(i);
            names.add(r.name().getString());
            if (i > 0) nums.append(',');
            if (showNumbers && r.number() != null) nums.append(r.number().getString());
        }
        lastNames = names;
        lastNumbers = nums.toString();
        lastRows = n;
        lastTitle = b.title().getString();
    }

    /** Called where Minecraft draws its own sidebar (GuiMixin), while this is off. */
    public static void vanillaDrawn() { vanillaDrawn++; }

    /** For the self-test: how often Minecraft drew its own sidebar. */
    public static long vanillaDraws() { return vanillaDrawn; }

    /** For the self-test: the rows' text and numbers as drawn in the last frame. */
    public static List<String> lastNames() { return lastNames; }

    public static String lastNumbers() { return lastNumbers; }

    /** For the self-test: rows drawn in the last frame (-1 hidden), and the title. */
    public static int lastRows() { return lastRows; }

    public static String lastTitle() { return lastTitle; }

    /** For the self-test. */
    public void setHidden(boolean on) { hide.value = on; }

    /** For the self-test: numbers shown (as the server sends them) or hidden. */
    public void setNumbersShown(boolean on) { numbers.index = on ? 0 : 1; }
}
