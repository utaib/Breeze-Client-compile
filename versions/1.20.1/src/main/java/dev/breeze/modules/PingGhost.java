package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.compat.ServerPos;
import dev.breeze.net.PositionPredictor;
import dev.breeze.render.GhostModel;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A see-through copy of each other player where the server has them now,
 * worked out from your ping (PositionPredictor), so the delay before you see
 * a move is shown. The game draws players where the server said they were,
 * half a ping ago, then smooths them over a few ticks.
 *
 * Off unless switched on. Invisible players and spectators get no ghost.
 */
public class PingGhost extends Module {

    private static final String GROUP = "Ghost";

    private final Setting.Mode lead = add(new Setting.Mode("lead", "Show", GROUP,
            new String[] {"Server now", "When your hit lands"}, 0));
    private final Setting.Color colour = add(new Setting.Color("colour", "Colour", GROUP, 0x5533CCFF));
    private final Setting.Bool outline = WorldRender.canStyle()
            ? add(new Setting.Bool("outline", "Outline", GROUP, true)) : null;
    private final Setting.Bool onlyApart = add(new Setting.Bool("onlyApart", "Only when apart", GROUP, true));
    private final Setting.Int range = add(new Setting.Int("range", "Range", GROUP, 32, 8, 96, " blocks"));

    private final Map<UUID, PositionPredictor> predictors = new HashMap<>();

    /** Ghosts drawn last frame, for the self-test. */
    private static volatile int lastDrawn;
    /** Self-test only: a ghost drawn at this spot, facing this way, as well as any players'. */
    private static volatile Vec3 testAt;
    private static volatile float testYaw;

    public PingGhost() {
        super("Ping Ghost", Category.PVP,
                "Shows a see-through copy of other players where the server has them now, from your ping.", KEY_NONE);
    }

    public static int lastDrawn() {
        return lastDrawn;
    }

    /** Self-test only; null stops it. */
    public static void testFigure(Vec3 feet, float yaw) {
        testAt = feet;
        testYaw = yaw;
    }

    @Override
    protected void onDisable() {
        predictors.clear();
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ctx.ready()) return;
        long now = System.currentTimeMillis();
        int ping = ping(mc);
        long leadMs = lead.is("Server now") ? ping / 2 : ping;
        Vec3 cam = ctx.cam();
        double maxSq = (double) range.value * range.value;
        int drawn = 0;
        Set<UUID> seen = new HashSet<>();

        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator() || p.isInvisible() || !p.isAlive()) continue;
            if (p.distanceToSqr(mc.player) > maxSq) continue;
            UUID id = p.getUUID();
            seen.add(id);
            PositionPredictor pr = predictors.computeIfAbsent(id, k -> new PositionPredictor());
            Vec3 server = ServerPos.of(p);
            pr.observe(now, server.x, server.y, server.z);
            double[] at = pr.predict(now, leadMs);
            if (onlyApart.value) {
                double dx = at[0] - p.getX(), dy = at[1] - p.getY(), dz = at[2] - p.getZ();
                if (dx * dx + dy * dy + dz * dz < 0.15 * 0.15) continue;
            }
            draw(ctx, at[0] - cam.x, at[1] - cam.y, at[2] - cam.z, p.yBodyRot, p.getYHeadRot(), p.getXRot(), p.getBbHeight());
            drawn++;
        }
        predictors.keySet().retainAll(seen);

        Vec3 probe = testAt;
        if (probe != null) {
            draw(ctx, probe.x - cam.x, probe.y - cam.y, probe.z - cam.z, testYaw, testYaw, 0, 1.8);
            drawn++;
        }
        lastDrawn = drawn;
    }

    private void draw(WorldCtx ctx, double x, double y, double z, float bodyYaw, float headYaw, float pitch, double height) {
        GhostModel.Figure f = GhostModel.build(x, y, z, bodyYaw, headYaw, pitch, height);
        int fill = colour.argb;
        if (WorldRender.canStyle()) WorldRender.quads(ctx, f.faces, f.quadCount(), fill);
        if (outline == null || outline.value) {
            // The edges a little stronger than the fill, so the shape reads.
            int a = Math.min(255, Math.max(colour.alpha() * 2, 0x99));
            WorldRender.lines(ctx, f.edges, f.edgeCount(), (a << 24) | colour.rgb(), 1.5f);
        }
    }

    /** Your own ping as the server reports it, in milliseconds; 0 when unknown. */
    private static int ping(Minecraft mc) {
        try {
            if (mc.getConnection() == null) return 0;
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            return info == null ? 0 : Math.max(0, info.getLatency());
        } catch (Throwable t) {
            return 0;
        }
    }
}
