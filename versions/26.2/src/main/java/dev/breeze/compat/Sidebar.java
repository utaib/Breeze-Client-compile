package dev.breeze.compat;

import dev.breeze.hud.SidebarLayout;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;

/**
 * The sidebar scoreboard as the server sent it (Scoreboard module): the
 * objective Minecraft would show and its rows, in Minecraft's order, with
 * each name's team prefix, suffix and colour. From 1.20.3 a score can have
 * its own display name, and numbers come in the format the server set (red
 * by default, styled, fixed text or blank), for the board or for one row.
 * 26.2: a team's colour is an Optional TeamColor and DisplaySlot lost
 * teamColorToSlot; the team sidebars are still TEAM_<colour> slots.
 */
public final class Sidebar {

    private Sidebar() {}

    /** One row: the name as Minecraft formats it, and its number (null for none). */
    public record Row(Component name, Component number) {}

    public record Board(Component title, List<Row> rows) {}

    /** The board Minecraft would draw now, or null when there is none. */
    public static Board read(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;
        Scoreboard sb = mc.level.getScoreboard();
        // A player in a coloured team sees that colour's sidebar first.
        Objective obj = null;
        PlayerTeam team = sb.getPlayersTeam(mc.player.getScoreboardName());
        if (team != null && team.getColor().isPresent()) {
            DisplaySlot slot = teamSlot(String.valueOf(team.getColor().get()));
            if (slot != null) obj = sb.getDisplayObjective(slot);
        }
        if (obj == null) obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<PlayerScoreEntry> scores = SidebarLayout.pick(new ArrayList<>(sb.listPlayerScores(obj)),
                PlayerScoreEntry::value, PlayerScoreEntry::owner);
        NumberFormat format = obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        List<Row> rows = new ArrayList<>(scores.size());
        for (PlayerScoreEntry s : scores) {
            Component name = PlayerTeam.formatNameForTeam(sb.getPlayersTeam(s.owner()), s.ownerName());
            rows.add(new Row(name, s.formatValue(format)));
        }
        return new Board(obj.getDisplayName(), rows);
    }

    /** The team sidebar slot for a colour by its name ("DARK_BLUE"), or null. */
    private static DisplaySlot teamSlot(String colour) {
        String want = "TEAM_" + colour.toUpperCase(java.util.Locale.ROOT);
        for (DisplaySlot s : DisplaySlot.values()) {
            if (s.name().equals(want)) return s;
        }
        return null;
    }

    /**
     * For the self-test, on the server thread: a sidebar made by the game's
     * own server, as a server plugin would make one, so it reaches the game
     * as packets. Rows: three names (two with the same score), one hidden
     * ('#'), and one written by a team's prefix and suffix around an
     * invisible owner, as most networks build theirs.
     */
    public static void putTestBoard(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        clearTestBoard(server);
        Objective o = sb.addObjective(TEST, ObjectiveCriteria.DUMMY, Component.literal("Breeze Test"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        PlayerTeam t = sb.addPlayerTeam(TEST);
        t.setPlayerPrefix(Component.literal("Coins: ").withStyle(ChatFormatting.GOLD));
        t.setPlayerSuffix(Component.literal("1,250"));
        sb.addPlayerToTeam(TEAM_ROW, t);
        sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly("carl"), o).set(7);
        sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Alice"), o).set(3);
        sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly("bob"), o).set(3);
        sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly("#hidden"), o).set(99);
        sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(TEAM_ROW), o).set(1);
        sb.setDisplayObjective(DisplaySlot.SIDEBAR, o);
    }

    public static void clearTestBoard(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        Objective o = sb.getObjective(TEST);
        if (o != null) sb.removeObjective(o);
        PlayerTeam t = sb.getPlayerTeam(TEST);
        if (t != null) sb.removePlayerTeam(t);
    }

    private static final String TEST = "breeze_test";
    /** An owner that shows nothing itself: a colour code. */
    private static final String TEAM_ROW = "§7";
}
