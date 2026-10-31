package dev.breeze.compat;

import dev.breeze.hud.SidebarLayout;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;

/**
 * The sidebar scoreboard as the server sent it (Scoreboard module): the
 * objective Minecraft would show and its rows, in Minecraft's order, with
 * each name's team prefix, suffix and colour. 1.20.2: display slots are an
 * enum; scores still have a plain owner and numbers are drawn red.
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
        if (team != null) {
            DisplaySlot slot = DisplaySlot.teamColorToSlot(team.getColor());
            if (slot != null) obj = sb.getDisplayObjective(slot);
        }
        if (obj == null) obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<Score> scores = SidebarLayout.pick(new ArrayList<>(sb.getPlayerScores(obj)), Score::getScore, Score::getOwner);
        List<Row> rows = new ArrayList<>(scores.size());
        for (Score s : scores) {
            Component name = PlayerTeam.formatNameForTeam(sb.getPlayersTeam(s.getOwner()), Component.literal(s.getOwner()));
            rows.add(new Row(name, Component.literal(String.valueOf(s.getScore())).withStyle(ChatFormatting.RED)));
        }
        return new Board(obj.getDisplayName(), rows);
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
                ObjectiveCriteria.RenderType.INTEGER);
        PlayerTeam t = sb.addPlayerTeam(TEST);
        t.setPlayerPrefix(Component.literal("Coins: ").withStyle(ChatFormatting.GOLD));
        t.setPlayerSuffix(Component.literal("1,250"));
        sb.addPlayerToTeam(TEAM_ROW, t);
        sb.getOrCreatePlayerScore("carl", o).setScore(7);
        sb.getOrCreatePlayerScore("Alice", o).setScore(3);
        sb.getOrCreatePlayerScore("bob", o).setScore(3);
        sb.getOrCreatePlayerScore("#hidden", o).setScore(99);
        sb.getOrCreatePlayerScore(TEAM_ROW, o).setScore(1);
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
