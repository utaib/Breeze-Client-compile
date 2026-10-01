package dev.breeze.cosmetics.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.Json;
import dev.breeze.cosmetics.model.GlbModel.Channel;
import dev.breeze.cosmetics.model.GlbModel.Clip;
import dev.breeze.cosmetics.model.GlbModel.Image;
import dev.breeze.cosmetics.model.GlbModel.Material;
import dev.breeze.cosmetics.model.GlbModel.Mesh;
import dev.breeze.cosmetics.model.GlbModel.Node;
import dev.breeze.cosmetics.model.GlbModel.Path;
import dev.breeze.cosmetics.model.GlbModel.Primitive;
import dev.breeze.cosmetics.model.GlbModel.Skin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a GLB (binary glTF 2.0) into a {@link GlbModel}, or a self-contained
 * .gltf: the JSON form with its buffers and images inside it as data: URIs,
 * which is how Blockbench exports and how some cosmetics in production are
 * stored (docs/COSMETICS.md). A .gltf that points at files outside itself is
 * refused, since those files were never uploaded. Every read is checked
 * against the bytes that are there: a file that claims more than it holds is
 * refused with the reason instead of read past its end. The limits follow the
 * API's, with room for the API's own rounding.
 */
public final class GlbReader {

    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TRIANGLES = 50_000;
    public static final int MAX_IMAGES = 16;
    private static final int MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;

    private final JsonObject gltf;
    /** Buffer 0 is a GLB's binary chunk; the rest (and all of a .gltf's) come from data: URIs. */
    private final List<ByteBuffer> buffers;

    private GlbReader(JsonObject gltf, ByteBuffer bin) {
        this.gltf = gltf;
        this.buffers = new ArrayList<>();
        JsonArray declared = arr("buffers");
        for (int i = 0; i < Math.max(1, declared.size()); i++) {
            JsonObject o = i < declared.size() && declared.get(i).isJsonObject() ? declared.get(i).getAsJsonObject() : new JsonObject();
            String uri = str(o, "uri", null);
            if (uri == null) {
                if (i == 0) buffers.add(bin);
                else throw new IllegalArgumentException("buffer " + i + " has no data");
            } else {
                buffers.add(ByteBuffer.wrap(dataUri(uri, "buffer " + i)).order(ByteOrder.LITTLE_ENDIAN));
            }
        }
    }

    public static GlbModel read(byte[] glb) {
        if (glb == null || glb.length < 20) throw new IllegalArgumentException("not a GLB: too short");
        if (glb.length > MAX_BYTES) throw new IllegalArgumentException("model over " + MAX_BYTES + " bytes");
        if (startsWithJson(glb)) {
            JsonElement root = Json.parse(new String(glb, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) throw new IllegalArgumentException("not a glTF: no JSON object");
            JsonObject json = root.getAsJsonObject();
            JsonObject asset = json.has("asset") && json.get("asset").isJsonObject() ? json.getAsJsonObject("asset") : null;
            String version = asset == null ? null : str(asset, "version", null);
            if (version == null || !version.startsWith("2")) throw new IllegalArgumentException("glTF version " + version + ", not 2");
            return new GlbReader(json, ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)).model();
        }
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != MAGIC) throw new IllegalArgumentException("not a GLB: wrong magic");
        if (b.getInt(4) != 2) throw new IllegalArgumentException("glTF version " + b.getInt(4) + ", not 2");
        int total = Math.min(b.getInt(8), glb.length);
        JsonObject json = null;
        ByteBuffer bin = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        int at = 12;
        while (at + 8 <= total) {
            int len = b.getInt(at);
            int type = b.getInt(at + 4);
            if (len < 0 || at + 8 + len > total) throw new IllegalArgumentException("chunk runs past the file");
            if (type == JSON_CHUNK && json == null) {
                String text = new String(glb, at + 8, len, StandardCharsets.UTF_8);
                json = Json.parse(text).getAsJsonObject();
            } else if (type == BIN_CHUNK && bin.capacity() == 0) {
                bin = ByteBuffer.wrap(glb, at + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            at += 8 + len;
        }
        if (json == null) throw new IllegalArgumentException("GLB without a JSON chunk");
        return new GlbReader(json, bin).model();
    }

    /** A .gltf is JSON: its first character that is not white space is '{'. */
    private static boolean startsWithJson(byte[] bytes) {
        for (int i = 0; i < bytes.length && i < 64; i++) {
            byte c = bytes[i];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            // A UTF-8 byte order mark.
            if (i == 0 && bytes.length > 3 && (c & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                i = 2;
                continue;
            }
            return c == '{';
        }
        return false;
    }

    /** The bytes of a base64 data: URI; anything else is a file that is not inside the model. */
    private static byte[] dataUri(String uri, String what) {
        if (!uri.startsWith("data:")) {
            throw new IllegalArgumentException(what + " points at " + uri + ", which is not inside the model");
        }
        int comma = uri.indexOf(',');
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
            throw new IllegalArgumentException(what + " is a data: URI that is not base64");
        }
        try {
            return java.util.Base64.getDecoder().decode(uri.substring(comma + 1).trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " has broken base64");
        }
    }

    private GlbModel model() {
        List<Image> images = new ArrayList<>();
        for (JsonElement e : arr("images")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("bufferView")) {
                String uri = str(o, "uri", null);
                if (uri != null && uri.startsWith("data:")) {
                    int semi = uri.indexOf(';');
                    String mime = semi > 5 ? uri.substring(5, semi) : str(o, "mimeType", "");
                    images.add(new Image(mime, dataUri(uri, "an image")));
                } else {
                    // An image file that was never uploaded: drawn white.
                    images.add(new Image("", new byte[0]));
                }
                continue;
            }
            ByteBuffer v = view(o.get("bufferView").getAsInt());
            byte[] bytes = new byte[v.remaining()];
            v.get(bytes);
            images.add(new Image(str(o, "mimeType", ""), bytes));
        }
        if (images.size() > MAX_IMAGES) throw new IllegalArgumentException(images.size() + " images, over " + MAX_IMAGES);

        JsonArray textures = arr("textures");
        List<Material> materials = new ArrayList<>();
        for (JsonElement e : arr("materials")) {
            JsonObject o = e.getAsJsonObject();
            float[] color = {1, 1, 1, 1};
            int image = -1;
            if (o.has("pbrMetallicRoughness")) {
                JsonObject pbr = o.getAsJsonObject("pbrMetallicRoughness");
                if (pbr.has("baseColorFactor")) color = floats(pbr.getAsJsonArray("baseColorFactor"), 4);
                if (pbr.has("baseColorTexture")) {
                    int t = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                    if (t >= 0 && t < textures.size() && textures.get(t).getAsJsonObject().has("source")) {
                        image = textures.get(t).getAsJsonObject().get("source").getAsInt();
                        if (image >= images.size()) image = -1;
                    }
                }
            }
            materials.add(new Material(color, image, str(o, "alphaMode", "OPAQUE"),
                    o.has("doubleSided") && o.get("doubleSided").getAsBoolean()));
        }

        List<Mesh> meshes = new ArrayList<>();
        for (JsonElement e : arr("meshes")) {
            List<Primitive> prims = new ArrayList<>();
            for (JsonElement pe : e.getAsJsonObject().getAsJsonArray("primitives")) {
                Primitive p = primitive(pe.getAsJsonObject(), materials.size());
                if (p != null) prims.add(p);
            }
            meshes.add(new Mesh(prims));
        }

        List<Node> nodes = new ArrayList<>();
        JsonArray nodeArr = arr("nodes");
        for (JsonElement e : nodeArr) {
            JsonObject o = e.getAsJsonObject();
            int mesh = o.has("mesh") ? o.get("mesh").getAsInt() : -1;
            if (mesh >= meshes.size()) mesh = -1;
            nodes.add(new Node(str(o, "name", ""),
                    o.has("translation") ? floats(o.getAsJsonArray("translation"), 3) : new float[]{0, 0, 0},
                    o.has("rotation") ? floats(o.getAsJsonArray("rotation"), 4) : new float[]{0, 0, 0, 1},
                    o.has("scale") ? floats(o.getAsJsonArray("scale"), 3) : new float[]{1, 1, 1},
                    o.has("matrix") ? floats(o.getAsJsonArray("matrix"), 16) : null,
                    o.has("children") ? ints(o.getAsJsonArray("children"), nodeArr.size()) : new int[0],
                    mesh, o.has("skin") ? o.get("skin").getAsInt() : -1));
        }

        List<Skin> skins = new ArrayList<>();
        for (JsonElement e : arr("skins")) {
            JsonObject o = e.getAsJsonObject();
            int[] joints = ints(o.getAsJsonArray("joints"), nodes.size());
            float[] ibm;
            if (o.has("inverseBindMatrices")) {
                ibm = floatAccessor(o.get("inverseBindMatrices").getAsInt());
            } else {
                ibm = new float[joints.length * 16];
                for (int j = 0; j < joints.length; j++) System.arraycopy(Mat4.identity(), 0, ibm, j * 16, 16);
            }
            if (ibm.length < joints.length * 16) throw new IllegalArgumentException("skin with too few bind matrices");
            skins.add(new Skin(joints, ibm));
        }

        List<Clip> clips = new ArrayList<>();
        int unnamed = 0;
        for (JsonElement e : arr("animations")) {
            JsonObject o = e.getAsJsonObject();
            JsonArray samplers = o.getAsJsonArray("samplers");
            List<Channel> channels = new ArrayList<>();
            float duration = 0;
            for (JsonElement ce : o.getAsJsonArray("channels")) {
                JsonObject c = ce.getAsJsonObject();
                JsonObject target = c.getAsJsonObject("target");
                if (!target.has("node")) continue;
                Path path = switch (target.get("path").getAsString()) {
                    case "translation" -> Path.TRANSLATION;
                    case "rotation" -> Path.ROTATION;
                    case "scale" -> Path.SCALE;
                    default -> null;
                };
                int node = target.get("node").getAsInt();
                if (path == null || node < 0 || node >= nodes.size()) continue;
                JsonObject s = samplers.get(c.get("sampler").getAsInt()).getAsJsonObject();
                float[] times = floatAccessor(s.get("input").getAsInt());
                float[] values = floatAccessor(s.get("output").getAsInt());
                String interp = str(s, "interpolation", "LINEAR");
                int width = (path == Path.ROTATION ? 4 : 3) * (interp.equals("CUBICSPLINE") ? 3 : 1);
                if (times.length == 0 || values.length < times.length * width) continue;
                for (float t : times) duration = Math.max(duration, t);
                channels.add(new Channel(node, path, times, values, interp));
            }
            String name = str(o, "name", "");
            if (name.isEmpty()) name = "animation" + unnamed++;
            clips.add(new Clip(name, duration, channels));
        }

        int[] roots;
        JsonArray scenes = arr("scenes");
        if (scenes.size() > 0) {
            int scene = gltf.has("scene") ? gltf.get("scene").getAsInt() : 0;
            if (scene < 0 || scene >= scenes.size()) scene = 0;
            JsonObject sc = scenes.get(scene).getAsJsonObject();
            roots = sc.has("nodes") ? ints(sc.getAsJsonArray("nodes"), nodes.size()) : new int[0];
        } else {
            boolean[] child = new boolean[nodes.size()];
            for (Node n : nodes) for (int c : n.children) child[c] = true;
            List<Integer> r = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) if (!child[i]) r.add(i);
            roots = r.stream().mapToInt(Integer::intValue).toArray();
        }

        GlbModel model = new GlbModel(nodes, meshes, materials, images, skins, clips, roots);
        if (model.triangles() > MAX_TRIANGLES) {
            throw new IllegalArgumentException(model.triangles() + " triangles, over " + MAX_TRIANGLES);
        }
        checkTree(model);
        return model;
    }

    /** Refuses a node tree that loops, which would never finish drawing. */
    private static void checkTree(GlbModel m) {
        int[] state = new int[m.nodes.size()];
        for (int r : m.roots) visit(m, r, state, 0);
    }

    private static void visit(GlbModel m, int n, int[] state, int depth) {
        if (depth > 256) throw new IllegalArgumentException("node tree too deep");
        if (state[n] == 1) throw new IllegalArgumentException("node tree loops");
        if (state[n] == 2) return;
        state[n] = 1;
        for (int c : m.nodes.get(n).children) visit(m, c, state, depth + 1);
        state[n] = 2;
    }

    private Primitive primitive(JsonObject p, int materialCount) {
        int mode = p.has("mode") ? p.get("mode").getAsInt() : 4;
        JsonObject attr = p.getAsJsonObject("attributes");
        if (attr == null || !attr.has("POSITION")) return null;
        float[] pos = floatAccessor(attr.get("POSITION").getAsInt());
        int n = pos.length / 3;
        float[] nrm = attr.has("NORMAL") ? floatAccessor(attr.get("NORMAL").getAsInt()) : null;
        float[] uv = attr.has("TEXCOORD_0") ? floatAccessor(attr.get("TEXCOORD_0").getAsInt()) : null;
        int[] joints = attr.has("JOINTS_0") ? intAccessor(attr.get("JOINTS_0").getAsInt()) : null;
        float[] weights = attr.has("WEIGHTS_0") ? floatAccessor(attr.get("WEIGHTS_0").getAsInt()) : null;
        if (nrm != null && nrm.length < n * 3) nrm = null;
        if (uv != null && uv.length < n * 2) uv = null;
        if (joints == null || weights == null || joints.length < n * 4 || weights.length < n * 4) {
            joints = null;
            weights = null;
        }
        int[] idx;
        if (p.has("indices")) {
            idx = intAccessor(p.get("indices").getAsInt());
        } else {
            idx = new int[n];
            for (int i = 0; i < n; i++) idx[i] = i;
        }
        for (int i : idx) if (i < 0 || i >= n) throw new IllegalArgumentException("index out of range");
        int[] tris = switch (mode) {
            case 4 -> idx.length % 3 == 0 ? idx : java.util.Arrays.copyOf(idx, idx.length - idx.length % 3);
            case 5 -> strip(idx);
            case 6 -> fan(idx);
            default -> null;
        };
        if (tris == null) return null;
        int material = p.has("material") ? p.get("material").getAsInt() : -1;
        if (material >= materialCount) material = -1;
        return new Primitive(pos, nrm, uv, joints, weights, tris, material);
    }

    private static int[] strip(int[] s) {
        if (s.length < 3) return new int[0];
        int[] out = new int[(s.length - 2) * 3];
        for (int i = 0; i + 2 < s.length; i++) {
            boolean even = i % 2 == 0;
            out[i * 3] = s[i];
            out[i * 3 + 1] = even ? s[i + 1] : s[i + 2];
            out[i * 3 + 2] = even ? s[i + 2] : s[i + 1];
        }
        return out;
    }

    private static int[] fan(int[] f) {
        if (f.length < 3) return new int[0];
        int[] out = new int[(f.length - 2) * 3];
        for (int i = 1; i + 1 < f.length; i++) {
            out[(i - 1) * 3] = f[0];
            out[(i - 1) * 3 + 1] = f[i];
            out[(i - 1) * 3 + 2] = f[i + 1];
        }
        return out;
    }

    // ── Accessors ────────────────────────────────────────────────────────

    private static int components(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException("accessor type " + type);
        };
    }

    private static int bytes(int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IllegalArgumentException("component type " + componentType);
        };
    }

    private float[] floatAccessor(int index) {
        JsonObject a = accessor(index);
        int ct = a.get("componentType").getAsInt();
        boolean norm = a.has("normalized") && a.get("normalized").getAsBoolean();
        double[] raw = read(a);
        float[] out = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double v = raw[i];
            // Integers marked normalized stand for 0..1 (or -1..1); unmarked
            // ones (quantized positions) are taken as they are.
            if (norm) {
                v = switch (ct) {
                    case 5121 -> v / 255.0;
                    case 5123 -> v / 65535.0;
                    case 5120 -> Math.max(v / 127.0, -1.0);
                    case 5122 -> Math.max(v / 32767.0, -1.0);
                    default -> v;
                };
            }
            out[i] = (float) v;
        }
        return out;
    }

    private int[] intAccessor(int index) {
        double[] raw = read(accessor(index));
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = (int) raw[i];
        return out;
    }

    private JsonObject accessor(int index) {
        JsonArray accessors = arr("accessors");
        if (index < 0 || index >= accessors.size()) throw new IllegalArgumentException("no accessor " + index);
        return accessors.get(index).getAsJsonObject();
    }

    private double[] read(JsonObject a) {
        int count = a.get("count").getAsInt();
        int comps = components(a.get("type").getAsString());
        int ct = a.get("componentType").getAsInt();
        int size = bytes(ct);
        if (count < 0 || (long) count * comps > 8L * 1024 * 1024) throw new IllegalArgumentException("accessor too large");
        double[] out = new double[count * comps];
        if (!a.has("bufferView")) return out;
        JsonObject bv = arr("bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
        int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : 0;
        int elem = comps * size;
        if (stride == 0) stride = elem;
        ByteBuffer v = view(a.get("bufferView").getAsInt());
        int base = a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0;
        if (count > 0 && (base < 0 || (long) base + (long) (count - 1) * stride + elem > v.limit())) {
            throw new IllegalArgumentException("accessor runs past its buffer view");
        }
        for (int i = 0; i < count; i++) {
            int at = base + i * stride;
            for (int c = 0; c < comps; c++) {
                int p = at + c * size;
                out[i * comps + c] = switch (ct) {
                    case 5120 -> v.get(p);
                    case 5121 -> v.get(p) & 0xFF;
                    case 5122 -> v.getShort(p);
                    case 5123 -> v.getShort(p) & 0xFFFF;
                    case 5125 -> v.getInt(p) & 0xFFFFFFFFL;
                    default -> v.getFloat(p);
                };
            }
        }
        return out;
    }

    private ByteBuffer view(int index) {
        JsonArray views = arr("bufferViews");
        if (index < 0 || index >= views.size()) throw new IllegalArgumentException("no buffer view " + index);
        JsonObject bv = views.get(index).getAsJsonObject();
        int buffer = bv.has("buffer") ? bv.get("buffer").getAsInt() : 0;
        if (buffer < 0 || buffer >= buffers.size()) throw new IllegalArgumentException("no buffer " + buffer);
        ByteBuffer bin = buffers.get(buffer);
        int off = bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0;
        int len = bv.get("byteLength").getAsInt();
        if (off < 0 || len < 0 || (long) off + len > bin.capacity()) {
            throw new IllegalArgumentException("buffer view runs past its buffer");
        }
        ByteBuffer d = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        d.position(off);
        d.limit(off + len);
        return d.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    private JsonArray arr(String key) {
        return gltf.has(key) && gltf.get(key).isJsonArray() ? gltf.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key, String dflt) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : dflt;
    }

    private static float[] floats(JsonArray a, int n) {
        if (a.size() < n) throw new IllegalArgumentException("expected " + n + " numbers");
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static int[] ints(JsonArray a, int bound) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
            if (out[i] < 0 || out[i] >= bound) throw new IllegalArgumentException("reference " + out[i] + " out of range");
        }
        return out;
    }
}
