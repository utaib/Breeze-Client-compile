package dev.breeze.cosmetics.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Small GLB files built in code, for the model tests. */
final class GlbTestFiles {

    private GlbTestFiles() {}

    /** Assembles a GLB from its JSON and binary chunk. */
    static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jp = (4 - j.length % 4) % 4;
        int bp = (4 - bin.length % 4) % 4;
        int total = 12 + 8 + j.length + jp + (bin.length > 0 ? 8 + bin.length + bp : 0);
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(total);
        b.putInt(j.length + jp).putInt(0x4E4F534A).put(j);
        for (int i = 0; i < jp; i++) b.put((byte) ' ');
        if (bin.length > 0) {
            b.putInt(bin.length + bp).putInt(0x004E4942).put(bin);
            for (int i = 0; i < bp; i++) b.put((byte) 0);
        }
        return b.array();
    }

    /**
     * The same model as a self-contained .gltf, the way Blockbench exports
     * one: the GLB's JSON with its binary chunk as a base64 data: URI.
     */
    static JsonObject gltf(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLen = b.getInt(12);
        JsonObject json = JsonParser.parseString(new String(glb, 20, jsonLen, StandardCharsets.UTF_8)).getAsJsonObject();
        int at = 20 + jsonLen;
        byte[] bin = new byte[0];
        if (at + 8 <= glb.length) {
            bin = java.util.Arrays.copyOfRange(glb, at + 8, at + 8 + b.getInt(at));
        }
        json.getAsJsonArray("buffers").get(0).getAsJsonObject()
                .addProperty("uri", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bin));
        return json;
    }

    static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] floats(float... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    static byte[] shorts(int... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int s : v) b.putShort((short) s);
        return b.array();
    }

    static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    /**
     * One triangle (0,0,0) (1,0,0) (0,1,0) with uvs, on a node at (0,2,0), and
     * a clip "Spin" that turns the node 90 degrees about Y over one second.
     * A second clip "Idle" moves nothing.
     */
    static byte[] triangleWithSpin() {
        byte[] pos = floats(0, 0, 0, 1, 0, 0, 0, 1, 0);           // 36 bytes at 0
        byte[] uv = floats(0, 0, 1, 0, 0, 1);                     // 24 bytes at 36
        byte[] idx = shorts(0, 1, 2, 0);                          // 8 bytes at 60 (padded)
        byte[] times = floats(0, 1);                              // 8 bytes at 68
        float h = (float) Math.sqrt(0.5);
        byte[] rots = floats(0, 0, 0, 1, 0, h, 0, h);             // 32 bytes at 76
        byte[] bin = concat(pos, uv, idx, times, rots);
        String json = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"name":"root","translation":[0,2,0],"mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,0.5,0.25,1]},"alphaMode":"MASK","doubleSided":true}],
                 "buffers":[{"byteLength":%d}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":24},
                                {"buffer":0,"byteOffset":60,"byteLength":6},{"buffer":0,"byteOffset":68,"byteLength":8},
                                {"buffer":0,"byteOffset":76,"byteLength":32}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
                              {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"},
                              {"bufferView":3,"componentType":5126,"count":2,"type":"SCALAR"},
                              {"bufferView":4,"componentType":5126,"count":2,"type":"VEC4"}],
                 "animations":[{"name":"Spin","samplers":[{"input":3,"output":4,"interpolation":"LINEAR"}],
                                "channels":[{"sampler":0,"target":{"node":0,"path":"rotation"}}]},
                               {"name":"Idle","samplers":[{"input":3,"output":4,"interpolation":"STEP"}],
                                "channels":[]}]}
                """.formatted(bin.length);
        return glb(json, bin);
    }

    /**
     * Two joints (a root at the origin, a child at (0,1,0)) and one mesh with
     * two vertices: the first bound fully to the root, the second fully to the
     * child. A clip "Bend" moves the child joint by (+1, 0, 0).
     */
    static byte[] skinnedTwoJoints() {
        byte[] pos = floats(0, 0, 0, 0, 1, 0, 1, 1, 0);           // 3 verts, 36 bytes at 0
        byte[] joints = bytes(0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0); // 12 bytes at 36
        byte[] weights = floats(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0); // 48 bytes at 48
        // Inverse bind: root identity; child at (0,1,0) -> translate (0,-1,0).
        byte[] ibm = floats(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1, 0, 1);  // 128 bytes at 96
        byte[] times = floats(0, 1);                               // 8 bytes at 224
        byte[] moves = floats(0, 1, 0, 1, 1, 0);                   // 24 bytes at 232
        byte[] bin = concat(pos, joints, weights, ibm, times, moves);
        String json = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,2]}],
                 "nodes":[{"name":"root","children":[1]},{"name":"child","translation":[0,1,0]},
                          {"name":"mesh","mesh":0,"skin":0,"translation":[50,50,50]}],
                 "skins":[{"joints":[0,1],"inverseBindMatrices":3}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"JOINTS_0":1,"WEIGHTS_0":2}}]}],
                 "buffers":[{"byteLength":%d}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":12},
                                {"buffer":0,"byteOffset":48,"byteLength":48},{"buffer":0,"byteOffset":96,"byteLength":128},
                                {"buffer":0,"byteOffset":224,"byteLength":8},{"buffer":0,"byteOffset":232,"byteLength":24}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":1,"componentType":5121,"count":3,"type":"VEC4"},
                              {"bufferView":2,"componentType":5126,"count":3,"type":"VEC4"},
                              {"bufferView":3,"componentType":5126,"count":2,"type":"MAT4"},
                              {"bufferView":4,"componentType":5126,"count":2,"type":"SCALAR"},
                              {"bufferView":5,"componentType":5126,"count":2,"type":"VEC3"}],
                 "animations":[{"name":"Bend","samplers":[{"input":4,"output":5}],
                                "channels":[{"sampler":0,"target":{"node":1,"path":"translation"}}]}]}
                """.formatted(bin.length);
        return glb(json, bin);
    }
}
