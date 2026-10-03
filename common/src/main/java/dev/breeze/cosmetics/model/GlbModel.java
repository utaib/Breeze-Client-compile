package dev.breeze.cosmetics.model;

import java.util.List;

/**
 * A cosmetic model read from a GLB (docs/COSMETICS.md): the node tree, the
 * triangles of each mesh, base-colour materials, the embedded images, skins
 * and animation clips. Only what drawing a cosmetic in Minecraft needs; the
 * API has already refused compressed meshes and exotic textures.
 */
public final class GlbModel {

    public final List<Node> nodes;
    public final List<Mesh> meshes;
    public final List<Material> materials;
    public final List<Image> images;
    public final List<Skin> skins;
    public final List<Clip> clips;
    /** Root nodes of the scene that is drawn. */
    public final int[] roots;

    public GlbModel(List<Node> nodes, List<Mesh> meshes, List<Material> materials, List<Image> images,
                    List<Skin> skins, List<Clip> clips, int[] roots) {
        this.nodes = nodes;
        this.meshes = meshes;
        this.materials = materials;
        this.images = images;
        this.skins = skins;
        this.clips = clips;
        this.roots = roots;
    }

    /** Triangles across every mesh instance in the scene. */
    public int triangles() {
        int n = 0;
        for (Node node : nodes) {
            if (node.mesh < 0) continue;
            for (Primitive p : meshes.get(node.mesh).primitives) n += p.indices.length / 3;
        }
        return n;
    }

    public Clip clip(String name) {
        if (name == null) return null;
        for (Clip c : clips) if (c.name.equals(name)) return c;
        return null;
    }

    public static final class Node {
        public final String name;
        /** Rest pose: translation, rotation (x, y, z, w), scale; or a matrix. */
        public final float[] t;
        public final float[] r;
        public final float[] s;
        public final float[] matrix;
        public final int[] children;
        public final int mesh;
        public final int skin;

        public Node(String name, float[] t, float[] r, float[] s, float[] matrix, int[] children, int mesh, int skin) {
            this.name = name;
            this.t = t;
            this.r = r;
            this.s = s;
            this.matrix = matrix;
            this.children = children;
            this.mesh = mesh;
            this.skin = skin;
        }
    }

    public static final class Mesh {
        public final List<Primitive> primitives;

        public Mesh(List<Primitive> primitives) {
            this.primitives = primitives;
        }
    }

    /** One triangle list: xyz per vertex, uv per vertex, three indices per triangle. */
    public static final class Primitive {
        public final float[] positions;
        public final float[] normals;
        public final float[] uvs;
        /** Four joint indices and weights per vertex, or null for a rigid mesh. */
        public final int[] joints;
        public final float[] weights;
        public final int[] indices;
        public final int material;

        public Primitive(float[] positions, float[] normals, float[] uvs, int[] joints, float[] weights,
                         int[] indices, int material) {
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.joints = joints;
            this.weights = weights;
            this.indices = indices;
            this.material = material;
        }

        public int vertices() {
            return positions.length / 3;
        }
    }

    public static final class Material {
        /** Base colour factor, RGBA 0..1. */
        public final float[] color;
        /** Index into images for the base colour, or -1. */
        public final int image;
        /** "OPAQUE", "MASK" or "BLEND". */
        public final String alphaMode;
        public final boolean doubleSided;

        public Material(float[] color, int image, String alphaMode, boolean doubleSided) {
            this.color = color;
            this.image = image;
            this.alphaMode = alphaMode;
            this.doubleSided = doubleSided;
        }
    }

    public static final class Image {
        public final String mime;
        public final byte[] bytes;

        public Image(String mime, byte[] bytes) {
            this.mime = mime;
            this.bytes = bytes;
        }
    }

    public static final class Skin {
        public final int[] joints;
        /** One column-major 4x4 per joint. */
        public final float[] inverseBind;

        public Skin(int[] joints, float[] inverseBind) {
            this.joints = joints;
            this.inverseBind = inverseBind;
        }
    }

    public enum Path { TRANSLATION, ROTATION, SCALE }

    public static final class Channel {
        public final int node;
        public final Path path;
        public final float[] times;
        /** 3 (translation, scale) or 4 (rotation) floats per key; 3x that for CUBICSPLINE. */
        public final float[] values;
        /** "LINEAR", "STEP" or "CUBICSPLINE". */
        public final String interpolation;

        public Channel(int node, Path path, float[] times, float[] values, String interpolation) {
            this.node = node;
            this.path = path;
            this.times = times;
            this.values = values;
            this.interpolation = interpolation;
        }
    }

    public static final class Clip {
        public final String name;
        public final float duration;
        public final List<Channel> channels;

        public Clip(String name, float duration, List<Channel> channels) {
            this.name = name;
            this.duration = duration;
            this.channels = channels;
        }
    }
}
