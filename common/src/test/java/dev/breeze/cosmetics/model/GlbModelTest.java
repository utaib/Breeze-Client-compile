package dev.breeze.cosmetics.model;

import dev.breeze.cosmetics.model.CosmeticRig.Attachment;
import dev.breeze.cosmetics.model.CosmeticRig.Transform;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlbModelTest {

    private static final float E = 1e-4f;

    @Test
    void readsNodesMeshesMaterialsAndClips() {
        GlbModel m = GlbReader.read(GlbTestFiles.triangleWithSpin());
        assertEquals(1, m.nodes.size());
        assertEquals(1, m.triangles());
        assertArrayEquals(new int[]{0}, m.roots);
        GlbModel.Primitive p = m.meshes.get(0).primitives.get(0);
        assertArrayEquals(new int[]{0, 1, 2}, p.indices);
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1}, p.uvs, E);
        GlbModel.Material mat = m.materials.get(0);
        assertArrayEquals(new float[]{1, 0.5f, 0.25f, 1}, mat.color, E);
        assertEquals("MASK", mat.alphaMode);
        assertTrue(mat.doubleSided);
        assertEquals(2, m.clips.size());
        assertEquals(1f, m.clip("Spin").duration, E);
    }

    @Test
    void restPoseAppliesTheNodeTransform() {
        GlbModel m = GlbReader.read(GlbTestFiles.triangleWithSpin());
        Pose.Part part = Pose.rest(m).parts.get(0);
        assertEquals(3, part.corners());
        assertArrayEquals(new float[]{0, 2, 0, 1, 2, 0, 0, 3, 0}, part.positions, E);
    }

    @Test
    void animationTurnsTheNodeAndLoops() {
        GlbModel m = GlbReader.read(GlbTestFiles.triangleWithSpin());
        GlbModel.Clip spin = m.clip("Spin");
        // At the end of the clip: 90 degrees about Y, so +X goes to -Z.
        float[] end = Pose.at(m, spin, 0.999f).parts.get(0).positions;
        assertEquals(0f, end[3], 0.01f);
        assertEquals(-1f, end[5], 0.01f);
        // Halfway: 45 degrees (slerp).
        float[] half = Pose.at(m, spin, 0.5f).parts.get(0).positions;
        assertEquals((float) Math.sqrt(0.5), half[3], 0.01f);
        assertEquals(-(float) Math.sqrt(0.5), half[5], 0.01f);
        // Looping: 1.5 s is the same as 0.5 s.
        assertArrayEquals(half, Pose.at(m, spin, 1.5f).parts.get(0).positions, 0.001f);
    }

    @Test
    void skinnedVerticesFollowTheirJointsAndIgnoreTheMeshNode() {
        GlbModel m = GlbReader.read(GlbTestFiles.skinnedTwoJoints());
        float[] rest = Pose.rest(m).parts.get(0).positions;
        // Rest: bind pose, and the mesh node's own (50,50,50) does not apply.
        assertArrayEquals(new float[]{0, 0, 0, 0, 1, 0, 1, 1, 0}, sortedCorners(rest, 3), E);
        float[] bent = Pose.at(m, m.clip("Bend"), 0.999f).parts.get(0).positions;
        // The child joint moved +1 in x: its vertex (0,1,0) goes to about (1,1,0);
        // the root's vertex stays.
        assertEquals(0f, bent[0], 0.01f);
        assertEquals(0.999f, cornerForVertex(m, bent, 1)[0], 0.01f);
    }

    @Test
    void refusesBrokenFiles() {
        assertThrows(IllegalArgumentException.class, () -> GlbReader.read(new byte[4]));
        byte[] good = GlbTestFiles.triangleWithSpin();
        byte[] bad = good.clone();
        bad[0] = 'x';
        assertThrows(IllegalArgumentException.class, () -> GlbReader.read(bad));
        // An accessor that claims more than its buffer view holds.
        String json = "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"buffers\":[{\"byteLength\":12}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":12}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":1000,\"type\":\"VEC3\"}]}";
        assertThrows(IllegalArgumentException.class,
                () -> GlbReader.read(GlbTestFiles.glb(json, GlbTestFiles.floats(0, 0, 0))));
        // A node tree that loops.
        String loop = "{\"asset\":{\"version\":\"2.0\"},\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"children\":[1]},{\"children\":[0]}]}";
        assertThrows(IllegalArgumentException.class, () -> GlbReader.read(GlbTestFiles.glb(loop, new byte[0])));
    }

    // ── Self-contained .gltf (Blockbench's export, some production cosmetics) ──

    @Test
    void readsASelfContainedGltfLikeTheSameGlb() {
        for (byte[] glb : new byte[][]{GlbTestFiles.triangleWithSpin(), GlbTestFiles.skinnedTwoJoints()}) {
            GlbModel a = GlbReader.read(glb);
            GlbModel b = GlbReader.read(GlbTestFiles.bytes(GlbTestFiles.gltf(glb)));
            assertEquals(a.triangles(), b.triangles());
            assertArrayEquals(a.roots, b.roots);
            assertEquals(a.clips.size(), b.clips.size());
            assertArrayEquals(Pose.rest(a).parts.get(0).positions, Pose.rest(b).parts.get(0).positions, E);
            GlbModel.Clip clip = a.clips.get(0);
            assertArrayEquals(Pose.at(a, clip, 0.5f).parts.get(0).positions,
                    Pose.at(b, b.clip(clip.name), 0.5f).parts.get(0).positions, E);
        }
        // White space and a byte order mark before the JSON are allowed.
        byte[] text = GlbTestFiles.bytes(GlbTestFiles.gltf(GlbTestFiles.triangleWithSpin()));
        byte[] bom = GlbTestFiles.concat(GlbTestFiles.bytes(0xEF, 0xBB, 0xBF, '\n', ' '), text);
        assertEquals(1, GlbReader.read(bom).triangles());
    }

    @Test
    void gltfImagesComeFromTheirDataUris() {
        com.google.gson.JsonObject json = GlbTestFiles.gltf(GlbTestFiles.triangleWithSpin());
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        String uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        json.add("images", com.google.gson.JsonParser.parseString("[{\"uri\":\"" + uri + "\"},{\"uri\":\"skin.png\"}]"));
        json.add("textures", com.google.gson.JsonParser.parseString("[{\"source\":0}]"));
        json.getAsJsonArray("materials").get(0).getAsJsonObject().getAsJsonObject("pbrMetallicRoughness")
                .add("baseColorTexture", com.google.gson.JsonParser.parseString("{\"index\":0}"));
        GlbModel m = GlbReader.read(GlbTestFiles.bytes(json));
        assertEquals(0, m.materials.get(0).image);
        assertEquals("image/png", m.images.get(0).mime);
        assertArrayEquals(png, m.images.get(0).bytes);
        // An image file that was never uploaded is drawn white, not refused.
        assertEquals(0, m.images.get(1).bytes.length);
    }

    @Test
    void refusesAGltfThatPointsOutsideItself() {
        com.google.gson.JsonObject json = GlbTestFiles.gltf(GlbTestFiles.triangleWithSpin());
        json.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri", "model.bin");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GlbReader.read(GlbTestFiles.bytes(json)));
        assertTrue(e.getMessage().contains("not inside the model"), e.getMessage());
        // Not glTF 2.
        com.google.gson.JsonObject old = GlbTestFiles.gltf(GlbTestFiles.triangleWithSpin());
        old.getAsJsonObject("asset").addProperty("version", "1.0");
        assertThrows(IllegalArgumentException.class, () -> GlbReader.read(GlbTestFiles.bytes(old)));
        // A buffer whose data: URI is cut short.
        com.google.gson.JsonObject cut = GlbTestFiles.gltf(GlbTestFiles.triangleWithSpin());
        cut.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri", "data:application/octet-stream;base64,AAAA");
        assertThrows(IllegalArgumentException.class, () -> GlbReader.read(GlbTestFiles.bytes(cut)));
    }

    // ── Placement (matches the launcher's rig) ─────────────────────────────

    /** A unit cube's bounds centred on the origin. */
    private static final float[] CUBE = {-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};

    @Test
    void hatRestsOnTopOfTheHead() {
        float[] m = CosmeticRig.toPart(Attachment.HEAD, Transform.NONE, CUBE);
        float[] o = new float[3];
        Mat4.point(m, 0, -0.5f, 0, o, 0);   // the cube's bottom centre
        assertArrayEquals(new float[]{0, -8, 0}, o, E, "on top of the head (Minecraft: y down, head top at -8)");
        Mat4.point(m, 0, 0.5f, 0, o, 0);    // its top: 10 pixels higher
        assertArrayEquals(new float[]{0, -18, 0}, o, E);
        Mat4.point(m, 0, 0, 0.5f, o, 0);    // the model's front (+Z) faces the way the player does (-Z)
        assertEquals(-5f, o[2], E);
    }

    @Test
    void backPiecePressesItsFrontAgainstTheBack() {
        float[] m = CosmeticRig.toPart(Attachment.BACK, Transform.NONE, CUBE);
        float[] o = new float[3];
        Mat4.point(m, 0, 0, 0.5f, o, 0);    // the cube's front face centre
        // skinview3d body space (0, 1, -2): Minecraft body space, neck origin, y down, back at +z.
        assertArrayEquals(new float[]{0, 5, 2}, o, E);
    }

    @Test
    void offsetRotationAndScaleApplyAsInTheLauncher() {
        Transform tr = new Transform(new float[]{2, 0, 0}, new float[]{0, 90, 0}, 2f);
        float[] m = CosmeticRig.toLauncherPart(Attachment.HEAD, tr, CUBE);
        float[] o = new float[3];
        Mat4.point(m, 0, -0.5f, 0, o, 0);
        assertArrayEquals(new float[]{2, 8, 0}, o, E, "anchor plus offset");
        Mat4.point(m, 0.5f, -0.5f, 0, o, 0);
        // 90 degrees about Y takes +X to -Z; scale 2 doubles the fitted 10.
        assertArrayEquals(new float[]{2, 8, -10}, o, E);
    }

    @Test
    void transformIsClampedLikeTheApi() {
        Transform t = new Transform(new float[]{99, -99, Float.NaN}, new float[]{720, 0, 0}, 9f);
        assertArrayEquals(new float[]{32, -32, 0}, t.offset, E);
        assertEquals(360f, t.rotation[0], E);
        assertEquals(4f, t.scale, E);
    }

    @Test
    void attachmentFallsBackToTheSlotDefault() {
        assertEquals(Attachment.FLYING_PET, Attachment.of("FLYING_PET", "pet"));
        assertEquals(Attachment.SHOULDER, Attachment.of(null, "pet"));
        assertEquals(Attachment.BACK, Attachment.of("NOPE", "wings"));
        assertEquals(Attachment.HEAD, Attachment.of(null, null));
    }

    @Test
    void clipForARolePrefersItsOwnThenIdleThenTheFirst() {
        GlbModel m = GlbReader.read(GlbTestFiles.triangleWithSpin());
        assertEquals("Spin", CosmeticRig.clipFor(m, Map.of("fly", "Spin", "idle", "Idle"), "fly").name);
        assertEquals("Idle", CosmeticRig.clipFor(m, Map.of("idle", "Idle"), "walk").name);
        assertEquals("Spin", CosmeticRig.clipFor(m, Map.of(), "walk").name);
    }

    private static float[] sortedCorners(float[] pos, int n) {
        float[][] c = new float[n][];
        for (int i = 0; i < n; i++) c[i] = new float[]{pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]};
        java.util.Arrays.sort(c, (a, b) -> a[1] != b[1] ? Float.compare(a[1], b[1]) : Float.compare(a[0], b[0]));
        float[] o = new float[n * 3];
        for (int i = 0; i < n; i++) System.arraycopy(c[i], 0, o, i * 3, 3);
        return o;
    }

    /** The posed corner for vertex v of a non-indexed mesh (corner order is vertex order). */
    private static float[] cornerForVertex(GlbModel m, float[] pos, int v) {
        return new float[]{pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]};
    }
}
