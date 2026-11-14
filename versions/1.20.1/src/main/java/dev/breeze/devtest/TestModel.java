package dev.breeze.devtest;

import dev.breeze.cosmetics.Png;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A 3D cosmetic built in code for the self-test: a cube with a 16x16
 * checkered texture embedded, as one GLB like the ones the API serves. Worn as
 * a hat, it shows the whole path (read, textures, placement, drawing) working
 * without a Breeze account.
 */
final class TestModel {

    private TestModel() {}

    static byte[] cubeGlb() {
        // 6 faces x 2 triangles x 3 corners, each corner with its own uv.
        float[][] faces = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
        };
        ByteBuffer pos = ByteBuffer.allocate(36 * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer nrm = ByteBuffer.allocate(36 * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uv = ByteBuffer.allocate(36 * 8).order(ByteOrder.LITTLE_ENDIAN);
        float[][] quadUv = {{0, 0}, {1, 0}, {1, 1}, {0, 0}, {1, 1}, {0, 1}};
        for (float[] f : faces) {
            // Two axes across the face, so its corners are +-0.5 along them.
            float[] a = Math.abs(f[1]) > 0 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] b = cross(f, a);
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 6; i++) {
                float s = corners[i][0] * 0.5f, t = corners[i][1] * 0.5f;
                for (int k = 0; k < 3; k++) pos.putFloat(f[k] * 0.5f + a[k] * s + b[k] * t);
                for (int k = 0; k < 3; k++) nrm.putFloat(f[k]);
                uv.putFloat(quadUv[i][0]).putFloat(quadUv[i][1]);
            }
        }
        byte[] png = Png.encode(16, 16, (x, y) -> ((x / 4 + y / 4) % 2 == 0) ? 0xFF2F6BD8 : 0xFFF2C94C);
        int posAt = 0, nrmAt = 432, uvAt = 864, imgAt = 1152;
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        bin.writeBytes(pos.array());
        bin.writeBytes(nrm.array());
        bin.writeBytes(uv.array());
        bin.writeBytes(png);
        while (bin.size() % 4 != 0) bin.write(0);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"name\":\"cube\",\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"material\":0}]}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0}}}],"
                + "\"textures\":[{\"source\":0}],\"images\":[{\"bufferView\":3,\"mimeType\":\"image/png\"}],"
                + "\"buffers\":[{\"byteLength\":" + bin.size() + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":" + posAt + ",\"byteLength\":432},"
                + "{\"buffer\":0,\"byteOffset\":" + nrmAt + ",\"byteLength\":432},"
                + "{\"buffer\":0,\"byteOffset\":" + uvAt + ",\"byteLength\":288},"
                + "{\"buffer\":0,\"byteOffset\":" + imgAt + ",\"byteLength\":" + png.length + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":36,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":36,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":36,\"type\":\"VEC2\"}]}";
        return glb(json, bin.toByteArray());
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jp = (4 - j.length % 4) % 4;
        int total = 12 + 8 + j.length + jp + 8 + bin.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(total);
        b.putInt(j.length + jp).putInt(0x4E4F534A).put(j);
        for (int i = 0; i < jp; i++) b.put((byte) ' ');
        b.putInt(bin.length).putInt(0x004E4942).put(bin);
        return b.array();
    }
}
