package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.OutlineGeometry;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * The outline around the block you look at, in place of Minecraft's: the
 * block's real shape (a slab, stairs, a fence) or always a full square, in
 * your colours and width, optionally filled.
 *
 * Minecraft's own outline is left out while this is on (compat/Hooks
 * blockOutline). Line width and fill need see-through quads, which Minecraft
 * has from 1.19.4 (WorldRender.canStyle); before that the outline keeps
 * Minecraft's width and those settings are not offered.
 */
public class BlockOverlay extends Module {

    private static final String SHAPE = "Outline";
    private static final String FILL = "Fill";

    private static BlockOverlay instance;

    private final Setting.Mode shape = add(new Setting.Mode("shape", "Shape", SHAPE,
            new String[] {"Hitbox", "Full block"}, 0));
    private final Setting.Bool outline = add(new Setting.Bool("outline", "Outline", SHAPE, true));
    private final Setting.Color outlineColour = add(new Setting.Color("outlineColour", "Outline colour", SHAPE, 0xFF33FFCC));
    private final Setting.Int width = WorldRender.canStyle()
            ? add(new Setting.Int("width", "Line width", SHAPE, 3, 1, 10, " px")) : null;
    private final Setting.Bool fill = WorldRender.canStyle()
            ? add(new Setting.Bool("fill", "Fill", FILL, false)) : null;
    private final Setting.Color fillColour = WorldRender.canStyle()
            ? add(new Setting.Color("fillColour", "Fill colour", FILL, 0x3333FFCC)) : null;
    private final Setting.Bool rainbow = add(new Setting.Bool("rainbow", "Rainbow", SHAPE, false));

    /** What was drawn last frame, for the self-test: "<shape> <edges> edges <boxes> boxes". */
    private static volatile String lastDrawn = "";

    public BlockOverlay() {
        super("Block Overlay", Category.VISUAL,
                "Outlines the block you look at, as its real shape or a full square, in your colours.", KEY_NONE);
        instance = this;
    }

    /** True while this draws the outline, so Minecraft's own is left out. */
    public static boolean replacesVanilla() {
        return instance != null && instance.isEnabled();
    }

    public static String lastDrawn() {
        return lastDrawn;
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hr = mc.hitResult;
        if (mc.level == null || hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult bhr)) return;
        if (!ctx.ready()) return;
        BlockPos pos = bhr.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        List<AABB> boxes = new ArrayList<>();
        float[] edges;
        if (shape.is("Full block")) {
            boxes.add(new AABB(pos));
            edges = OutlineGeometry.boxEdges(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        } else {
            Entity viewer = mc.getCameraEntity();
            VoxelShape vs = state.getShape(mc.level, pos, viewer == null ? CollisionContext.empty() : CollisionContext.of(viewer));
            if (vs.isEmpty()) return;
            for (AABB b : vs.toAabbs()) boxes.add(b.move(pos));
            List<double[]> found = new ArrayList<>();
            vs.forAllEdges((x0, y0, z0, x1, y1, z1) -> found.add(new double[] {
                    x0 + pos.getX(), y0 + pos.getY(), z0 + pos.getZ(), x1 + pos.getX(), y1 + pos.getY(), z1 + pos.getZ()}));
            edges = new float[found.size() * 6];
            for (int i = 0; i < found.size(); i++) {
                for (int k = 0; k < 6; k++) edges[i * 6 + k] = (float) found.get(i)[k];
            }
        }

        Vec3 cam = ctx.cam();
        long now = System.currentTimeMillis();
        int edgeCount = edges.length / 6;
        if (fill != null && fill.value) {
            int argb = rainbow.value ? OutlineGeometry.rainbow(now, fillColour.alpha()) : fillColour.argb;
            float[] faces = new float[boxes.size() * 72];
            int o = 0;
            for (AABB b : boxes) {
                // A hair larger than the block, so the faces do not flicker against its own.
                AABB g = b.inflate(0.002);
                float[] f = OutlineGeometry.boxFaces(g.minX - cam.x, g.minY - cam.y, g.minZ - cam.z,
                        g.maxX - cam.x, g.maxY - cam.y, g.maxZ - cam.z);
                System.arraycopy(f, 0, faces, o, 72);
                o += 72;
            }
            WorldRender.quads(ctx, faces, boxes.size() * 6, argb);
        }
        if (outline.value) {
            for (int i = 0; i < edges.length; i += 3) {
                edges[i] -= (float) cam.x;
                edges[i + 1] -= (float) cam.y;
                edges[i + 2] -= (float) cam.z;
            }
            int argb = rainbow.value ? OutlineGeometry.rainbow(now, outlineColour.alpha()) : outlineColour.argb;
            WorldRender.lines(ctx, edges, edgeCount, argb, width == null ? 2 : width.value);
        }
        lastDrawn = shape.value() + " " + edgeCount + " edges " + boxes.size() + " boxes";
    }
}
