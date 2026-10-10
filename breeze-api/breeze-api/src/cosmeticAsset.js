'use strict';
/**
 * Cosmetic model ingestion (v1.0.22). The format is described in
 * docs/COSMETICS.md; this file is what enforces it.
 *
 * Whatever shape an upload arrives in (a .glb, a .gltf with its .bin and
 * textures beside it, or a .zip of those), it leaves here as one
 * self-contained GLB with every buffer and texture inside it. That is the
 * fix for "the model shows up but its texture does not": a .gltf used to be
 * stored on its own, so the files it pointed at were never on the server, and
 * nothing checked that a texture was there at all.
 *
 * Inspection happens here too, so the launcher preview, the store and the mod
 * all read one description of the asset instead of each guessing: the
 * animations it contains and which one plays when, its bounds (for scaling to
 * the attachment point), and what the creator chose for placement.
 */

const path = require('path');
const { NodeIO, ImageUtils } = require('@gltf-transform/core');
const { ALL_EXTENSIONS } = require('@gltf-transform/extensions');
const { unzipSync } = require('fflate');

const SPEC_VERSION = 1;

const LIMITS = Object.freeze({
    /**
     * A whole upload request. nginx in front of the API refuses bodies over
     * 8 MB by default (scripts/ensure-nginx-upload-limit.sh); the API refuses
     * anything larger itself, before reading it, for when nginx is not there.
     */
    requestBytes: 8 * 1024 * 1024,
    /**
     * Per uploaded file, and for the finished GLB. Under requestBytes so that
     * a file this size still fits in a request with the form around it, and
     * the creator sees this limit's message rather than the proxy's.
     */
    fileBytes: 7 * 1024 * 1024,
    /** Loose files beside a .gltf, when it is not sent as a .zip. */
    resourceFiles: 32,
    packageFiles: 64,
    /** Everything in a .zip once unpacked, so a small archive cannot expand without bound. */
    unpackedBytes: 32 * 1024 * 1024,
    triangles: 50000,
    textures: 16,
    textureEdge: 2048,
    animations: 64,
});

/**
 * How much structure a model may declare, checked on the raw JSON before any
 * parser allocates for it. A few hundred bytes of JSON can claim a billion-
 * element accessor; parsing that first is what would take the API down.
 */
const STRUCTURE = Object.freeze({
    accessors: 4096,
    bufferViews: 4096,
    buffers: 64,
    nodes: 4096,
    meshes: 1024,
    materials: 512,
    images: 64,
    skins: 64,
    scenes: 16,
    channels: 8192,
    /** Accessor components across the file: 8 million floats is 32 MB. */
    elements: 8 * 1024 * 1024,
});
const COMPONENT_BYTES = { 5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4 };
const TYPE_COMPONENTS = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT2: 4, MAT3: 9, MAT4: 16 };

/**
 * Where a cosmetic sits on the player. A slot says what a cosmetic is and which
 * other cosmetics it replaces when equipped; an attachment says where it goes.
 */
const ATTACHMENTS = Object.freeze(['HEAD', 'SHOULDER', 'BACK', 'HAND', 'FEET', 'SIDE', 'FLYING_PET', 'TRAIL']);

/** The attachments each slot allows. The first is the default. */
const SLOT_ATTACHMENTS = Object.freeze({
    hat: ['HEAD'],
    wings: ['BACK'],
    cape: ['BACK'],
    back: ['BACK'],
    shield: ['HAND', 'BACK'],
    pet: ['SHOULDER', 'FLYING_PET', 'SIDE', 'FEET'],
    aura: ['FEET', 'TRAIL'],
    trail: ['TRAIL'],
});

/**
 * glTF extensions the launcher's three.js loader renders with no extra decoder.
 * Anything optional outside this list is stripped; anything required is refused
 * with the reason, because a model that needs it would load without its
 * geometry or textures.
 */
const RENDERABLE_EXTENSIONS = new Set([
    'KHR_materials_clearcoat', 'KHR_materials_emissive_strength', 'KHR_materials_ior',
    'KHR_materials_iridescence', 'KHR_materials_sheen', 'KHR_materials_specular',
    'KHR_materials_transmission', 'KHR_materials_unlit', 'KHR_materials_volume',
    'KHR_materials_anisotropy', 'KHR_lights_punctual', 'KHR_mesh_quantization',
    'KHR_texture_transform', 'EXT_texture_webp',
]);
const UNSUPPORTED = Object.freeze({
    KHR_draco_mesh_compression: 'Draco-compressed meshes',
    EXT_meshopt_compression: 'Meshopt-compressed meshes',
    KHR_texture_basisu: 'KTX2 (Basis) textures',
    KHR_materials_pbrSpecularGlossiness: 'specular-glossiness materials',
    EXT_texture_avif: 'AVIF textures',
});
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

/**
 * Animation roles, found from clip names so a creator never types one.
 * Order matters: a clip called "Fly_Loop" is a flying clip, not an idle one,
 * so the specific roles are matched before idle's catch-all words.
 */
const ROLE_RULES = Object.freeze([
    ['fly', /fly|flying|flap|hover|glide|soar|wing/i],
    ['walk', /walk|run|move|follow|trot|hop|step/i],
    ['sit', /sit|perch|sleep|shoulder/i],
    ['idle', /idle|breath|stand|rest/i],
    // Weak words last: "Fly_Loop" says loop, but a clip actually called Idle
    // should win. Calm loops (a dance, a bob) make a better idle than nothing.
    ['idle', /default|loop|anim|dance|dancing|bob|float|wiggle|happy|spin/i],
]);

/**
 * Clips that are a reaction, not a resting state. Never the fallback idle: a
 * pet standing angry forever reads as a bug. They still play now and then.
 */
const REACTION = /angry|attack|hurt|hit|damage|death|die|scared|panic/i;

/**
 * The words in a clip name that say what it is, most meaningful first.
 *
 * Exporters decorate names: Blockbench writes "animation.glare.fly", Blender
 * "Armature|Idle" and appends ".001" to duplicates, and creators write
 * "Fly.Loop" or "flyLoop". Matching the whole name would let "animation"
 * satisfy the weak idle words for every clip in a Blockbench file; matching
 * only the last part would read "Walk.001" as "001" and "Fly.Loop" as "Loop",
 * which is how a walk clip ended up as the idle one.
 *
 * So: drop the exporter's decoration, then return the remaining words with the
 * last one first, because that is the one that names the motion.
 */
function clipWords(name) {
    let s = String(name);
    const bar = s.lastIndexOf('|');           // Blender rig prefix
    if (bar >= 0) s = s.slice(bar + 1);
    s = s.replace(/^animation\./i, '');       // Blockbench namespace
    s = s.replace(/\.\d+$/, '');              // duplicate suffix, ".001"
    const words = s
        .split(/[.:/\\\s_-]+/)
        .filter(Boolean)
        .flatMap((part) => part.replace(/([a-z0-9])([A-Z])/g, '$1 $2').split(' '))
        .filter(Boolean);
    if (!words.length) return [String(name)];
    // "Fly.Loop" and "Idle Cycle" name a motion and then say it repeats. The
    // motion is the word before the decoration, so decoration never counts as
    // the naming word unless it is all there is.
    let last = words.length - 1;
    while (last > 0 && DECORATION.test(words[last])) last -= 1;
    return [words[last], ...words.filter((_, i) => i !== last)];
}

/** Words that decorate a clip name without saying what the clip does. */
const DECORATION = /^(loop|loops|looped|cycle|anim|animation|action|clip|take|new|\d+)$/i;

/** True when any word of the clip name matches. */
const clipMatches = (re, name) => clipWords(name).some((w) => re.test(w));
const ROLES = Object.freeze(['idle', 'fly', 'walk', 'sit']);

class CosmeticAssetError extends Error {
    constructor(message) {
        super(message);
        this.name = 'CosmeticAssetError';
        this.status = 400;
    }
}
const reject = (message) => { throw new CosmeticAssetError(message); };

/**
 * Which clip plays in which state. A creator's explicit choice wins when it
 * names a real clip, and an explicit empty choice turns the role off; otherwise
 * the names decide; otherwise the first clip is the idle one, so an animated
 * model never sits frozen for want of a name.
 */
function discoverAnimationRoles(names, overrides = {}) {
    const roles = {};
    const taken = new Set();
    const off = new Set();
    for (const role of ROLES) {
        if (!overrides || !Object.prototype.hasOwnProperty.call(overrides, role)) continue;
        const wanted = overrides[role];
        if (!wanted) off.add(role);
        else if (names.includes(wanted)) { roles[role] = wanted; taken.add(wanted); }
    }
    // Two passes. The first matches only the word that names the motion, so a
    // model called "Walker" cannot claim the walk role with "animation.walker.idle".
    // The second lets the earlier words answer for anything still unfilled,
    // which is what reads "Fly_Loop" as flying and "Idle Loop" as idle.
    const isReaction = (n) => clipMatches(REACTION, n);
    for (const words of [(n) => clipWords(n).slice(0, 1), clipWords]) {
        for (const [role, rule] of ROLE_RULES) {
            if (roles[role] || off.has(role)) continue;
            const hit = names.find((n) => !taken.has(n) && words(n).some((w) => rule.test(w)) && !isReaction(n));
            if (hit) { roles[role] = hit; taken.add(hit); }
        }
    }
    if (!roles.idle && !off.has('idle')) {
        // Prefer a clip that is not a reaction. A reaction becomes the idle only
        // when the model has nothing else at all: a pet that found a fly clip is
        // better left resting than standing angry forever, and the reaction still
        // plays now and then as an extra.
        const free = names.filter((n) => !taken.has(n));
        const first = free.find((n) => !isReaction(n)) || (Object.keys(roles).length ? null : free[0]);
        if (first) { roles.idle = first; taken.add(first); }
    }
    // Clips no state claims play now and then as variety, in the mod and the preview.
    const extras = names.filter((n) => !taken.has(n));
    return { roles, extras };
}

function clampNumber(value, min, max, fallback) {
    const n = Number(value);
    if (!Number.isFinite(n)) return fallback;
    return Math.min(max, Math.max(min, n));
}

/**
 * Placement relative to the attachment point: offset in Minecraft pixels,
 * rotation in degrees, scale as a multiplier on the fitted size. Clamped
 * rather than refused, because these come from sliders.
 */
function sanitizeTransform(raw) {
    const t = raw && typeof raw === 'object' ? raw : {};
    const vec = (v, limit) => [0, 1, 2].map((i) => clampNumber(Array.isArray(v) ? v[i] : 0, -limit, limit, 0));
    return {
        offset: vec(t.offset, 32),
        rotation: vec(t.rotation, 360),
        scale: clampNumber(t.scale, 0.1, 4, 1),
    };
}

function resolveAttachment(slot, requested) {
    const allowed = SLOT_ATTACHMENTS[slot];
    if (!allowed) reject(`Unknown slot "${slot}"`);
    if (!requested) return allowed[0];
    const want = String(requested).toUpperCase();
    if (!allowed.includes(want)) reject(`A ${slot} can attach to ${allowed.join(', ')}, not ${want}`);
    return want;
}

function parseJsonField(raw, label) {
    if (raw === undefined || raw === null || raw === '') return undefined;
    if (typeof raw === 'object') return raw;
    try { return JSON.parse(raw); } catch { return reject(`${label} must be valid JSON`); }
}

/** A path inside the upload, with / separators and nothing that climbs out. */
function cleanPath(name) {
    const posix = String(name || '').replace(/\\/g, '/');
    const normalized = path.posix.normalize(posix).replace(/^\.\//, '');
    if (!normalized || normalized.startsWith('../') || normalized === '..' || path.posix.isAbsolute(normalized) || /^[a-z]:/i.test(normalized)) {
        return null;
    }
    return normalized;
}

const isModel = (name) => /\.(glb|gltf)$/i.test(name);
const isNoise = (name) => name.split('/').some((part) => part.startsWith('.') || part === '__MACOSX');

/**
 * Gather the upload into one flat set of files: a .zip is unpacked, loose
 * files are taken as they are. Sizes are checked from the archive headers
 * before anything is inflated.
 */
function collectFiles(files) {
    const out = new Map();
    let unpacked = 0;
    const add = (name, bytes) => {
        const clean = cleanPath(name);
        if (!clean || isNoise(clean)) return;
        if (out.size >= LIMITS.packageFiles) reject(`Too many files; a cosmetic can have at most ${LIMITS.packageFiles}`);
        out.set(clean, bytes);
    };
    for (const file of files) {
        const name = String(file.originalname || '');
        const bytes = new Uint8Array(file.buffer);
        if (bytes.length > LIMITS.fileBytes) reject(`${name} is larger than ${LIMITS.fileBytes / 1024 / 1024} MB`);
        if (/\.zip$/i.test(name)) {
            let entries;
            try {
                entries = unzipSync(bytes, {
                    filter: (entry) => {
                        if (entry.name.endsWith('/')) return false;
                        unpacked += entry.originalSize;
                        if (entry.originalSize > LIMITS.fileBytes || unpacked > LIMITS.unpackedBytes) {
                            reject('The package unpacks to more than the size limit');
                        }
                        return true;
                    },
                });
            } catch (e) {
                if (e instanceof CosmeticAssetError) throw e;
                return reject(`${name} is not a readable .zip`);
            }
            for (const [entryName, data] of Object.entries(entries)) add(entryName, data);
        } else {
            add(path.posix.basename(name.replace(/\\/g, '/')), bytes);
        }
    }
    return out;
}

/** The JSON half of a GLB, read without parsing anything else. */
function glbJson(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (bytes.length < 20 || view.getUint32(0, true) !== 0x46546c67) reject('That .glb file is not a valid GLB');
    const length = view.getUint32(12, true);
    if (view.getUint32(16, true) !== 0x4e4f534a || 20 + length > bytes.length) reject('That .glb file is not a valid GLB');
    let json;
    try {
        json = JSON.parse(Buffer.from(bytes.subarray(20, 20 + length)).toString('utf8'));
    } catch {
        return reject('That .glb file has an unreadable header');
    }
    // The binary chunk, if any, follows the JSON chunk; its length is the
    // real size of the GLB's own buffer.
    const binAt = 20 + length;
    const binLength = binAt + 8 <= bytes.length && view.getUint32(binAt + 4, true) === 0x004e4942
        ? Math.min(view.getUint32(binAt, true), bytes.length - binAt - 8)
        : 0;
    return { json, binLength };
}

/**
 * The fields the pipeline reads before parsing, checked for type, so malformed
 * JSON is refused with a reason instead of failing somewhere inside.
 */
function checkShape(json) {
    if (!json || typeof json !== 'object' || Array.isArray(json)) reject('The model file is not a glTF document');
    for (const key of ['buffers', 'images', 'bufferViews', 'accessors', 'nodes', 'meshes', 'animations', 'extensionsUsed', 'extensionsRequired']) {
        if (json[key] !== undefined && !Array.isArray(json[key])) reject(`The model's ${key} are malformed`);
    }
    for (const list of [json.buffers || [], json.images || []]) {
        for (const item of list) {
            if (!item || typeof item !== 'object') reject('The model has a malformed buffer or image');
            if (item.uri !== undefined && typeof item.uri !== 'string') reject('The model has a malformed file reference');
        }
    }
    for (const name of [...(json.extensionsUsed || []), ...(json.extensionsRequired || [])]) {
        if (typeof name !== 'string') reject('The model lists a malformed extension');
    }
}

/**
 * Refuse a model whose declared structure is too large or does not fit the
 * bytes it came with. bufferSizes[i] is the real length of buffer i.
 */
function checkStructure(json, bufferSizes) {
    if (!json || typeof json !== 'object' || Array.isArray(json)) reject('The model file is not a glTF document');
    for (const key of ['accessors', 'bufferViews', 'buffers', 'nodes', 'meshes', 'materials', 'images', 'skins', 'scenes']) {
        const list = json[key];
        if (list === undefined) continue;
        if (!Array.isArray(list)) reject(`The model's ${key} are malformed`);
        if (list.length > STRUCTURE[key]) reject(`The model has ${list.length} ${key}; the limit is ${STRUCTURE[key]}`);
    }
    const whole = (n) => Number.isInteger(n) && n >= 0;
    const buffers = json.buffers || [];
    buffers.forEach((buffer, i) => {
        if (!buffer || !whole(buffer.byteLength)) reject('The model declares a buffer without a valid length');
        if (buffer.byteLength > (bufferSizes[i] ?? 0)) reject('The model declares more buffer data than it contains');
    });
    const views = json.bufferViews || [];
    views.forEach((view) => {
        const buffer = view && buffers[view.buffer];
        if (!buffer || !whole(view.byteLength) || !whole(view.byteOffset ?? 0)) reject('The model has a malformed buffer view');
        if ((view.byteOffset || 0) + view.byteLength > buffer.byteLength) reject('The model has a buffer view past the end of its buffer');
        if (view.byteStride !== undefined && !(whole(view.byteStride) && view.byteStride >= 4 && view.byteStride <= 252)) reject('The model has a malformed buffer view stride');
    });
    let elements = 0;
    for (const accessor of json.accessors || []) {
        const components = accessor && TYPE_COMPONENTS[accessor.type];
        const bytes = accessor && COMPONENT_BYTES[accessor.componentType];
        if (!components || !bytes || !whole(accessor.count)) reject('The model has a malformed accessor');
        elements += accessor.count * components;
        if (accessor.bufferView !== undefined) {
            const view = views[accessor.bufferView];
            if (!view) reject('The model has an accessor pointing at a missing buffer view');
            const element = components * bytes;
            const stride = view.byteStride || element;
            const needed = accessor.count === 0 ? 0 : (accessor.byteOffset || 0) + (accessor.count - 1) * stride + element;
            if (needed > view.byteLength) reject('The model has an accessor larger than its data');
        }
        if (accessor.sparse) {
            const sparse = accessor.sparse;
            if (!whole(sparse.count) || sparse.count > accessor.count) reject('The model has a malformed sparse accessor');
            elements += sparse.count * (components + 1);
        }
        if (elements > STRUCTURE.elements) reject('The model declares more geometry and animation data than a cosmetic may have');
    }
    let channels = 0;
    for (const animation of json.animations || []) channels += Array.isArray(animation?.channels) ? animation.channels.length : 0;
    if (channels > STRUCTURE.channels) reject(`The model has ${channels} animation channels; the limit is ${STRUCTURE.channels}`);

    // A node that is its own ancestor would send every tree walk round forever.
    const nodes = json.nodes || [];
    const state = new Uint8Array(nodes.length);
    const visit = (start) => {
        const stack = [[start, 0]];
        while (stack.length) {
            const top = stack[stack.length - 1];
            const [index, next] = top;
            if (next === 0) {
                if (state[index] === 1) reject('The model has a node that contains itself');
                if (state[index] === 2) { stack.pop(); continue; }
                state[index] = 1;
            }
            const children = Array.isArray(nodes[index]?.children) ? nodes[index].children : [];
            if (next < children.length) {
                top[1] = next + 1;
                const child = children[next];
                if (!whole(child) || child >= nodes.length) reject('The model has a node with a missing child');
                if (state[child] === 1) reject('The model has a node that contains itself');
                if (state[child] === 0) stack.push([child, 0]);
            } else {
                state[index] = 2;
                stack.pop();
            }
        }
    };
    for (let i = 0; i < nodes.length; i++) if (state[i] === 0) visit(i);
}

/** Decoded length of a base64 data: URI, without decoding it. */
function dataUriBytes(uri) {
    const comma = uri.indexOf(',');
    if (comma < 0 || !uri.slice(0, comma).endsWith(';base64')) return uri.length;
    const body = uri.length - comma - 1;
    const padding = uri.endsWith('==') ? 2 : uri.endsWith('=') ? 1 : 0;
    return Math.floor((body * 3) / 4) - padding;
}

function checkExtensions(json) {
    const used = [...new Set([...(json.extensionsUsed || []), ...(json.extensionsRequired || [])])];
    const blocked = used.filter((e) => UNSUPPORTED[e]).map((e) => UNSUPPORTED[e]);
    if (blocked.length) {
        reject(`This model uses ${blocked.join(' and ')}, which Breeze cannot display. Export it again without compression, with PNG, JPEG or WebP textures and metallic-roughness materials.`);
    }
    const unknownRequired = (json.extensionsRequired || []).filter((e) => !RENDERABLE_EXTENSIONS.has(e));
    if (unknownRequired.length) reject(`This model requires ${unknownRequired.join(', ')}, which Breeze cannot display.`);
}

/**
 * Every file a .gltf points at, found in the upload. Names are matched exactly
 * first and then case-insensitively, since a model exported on Windows often
 * refers to "Texture.PNG" when the file is "texture.png".
 */
function resolveResources(json, modelPath, files) {
    const dir = path.posix.dirname(modelPath);
    const lower = new Map([...files.keys()].map((k) => [k.toLowerCase(), k]));
    // Loose files lose their folders on upload, so a unique file name is
    // enough to find "textures/skin.png" when only "skin.png" arrived.
    const byName = new Map();
    for (const k of files.keys()) {
        const base = path.posix.basename(k).toLowerCase();
        byName.set(base, byName.has(base) ? null : k);
    }
    const resources = {};
    const missing = [];
    const refs = [...(json.buffers || []), ...(json.images || [])].map((r) => r && r.uri).filter(Boolean);
    for (const uri of refs) {
        if (uri.startsWith('data:')) continue;
        if (/^[a-z][a-z0-9+.-]*:/i.test(uri)) reject(`The model points at ${uri}; files must be included in the upload, not linked`);
        let decoded;
        try { decoded = decodeURIComponent(uri); } catch { decoded = uri; }
        const target = cleanPath(path.posix.join(dir === '.' ? '' : dir, decoded));
        if (!target) reject(`The model points outside its folder (${uri})`);
        const key = files.has(target) ? target
            : lower.get(target.toLowerCase()) || byName.get(path.posix.basename(target).toLowerCase());
        if (!key) { missing.push(decoded); continue; }
        resources[uri] = files.get(key);
    }
    if (missing.length) {
        const list = missing.slice(0, 5).join(', ') + (missing.length > 5 ? ` and ${missing.length - 5} more` : '');
        reject(`The model needs ${list}, which ${missing.length === 1 ? 'is' : 'are'} not in the upload. Upload a .glb, or a .zip with the .gltf and every file beside it.`);
    }
    return resources;
}

function primitiveTriangles(prim) {
    const indices = prim.getIndices();
    const position = prim.getAttribute('POSITION');
    const count = indices ? indices.getCount() : position ? position.getCount() : 0;
    const mode = prim.getMode();
    if (mode === 4) return Math.floor(count / 3);
    if (mode === 5 || mode === 6) return Math.max(0, count - 2);
    return 0;
}

/**
 * Triangles and bounds of what the scene actually draws.
 *
 * A mesh drawn by several nodes counts once per node, so reusing one mesh
 * cannot slip past the triangle limit. Each mesh's own box is measured once
 * and then moved by each node's transform, so reuse cannot make this slow
 * either: the cost is one pass over the geometry plus a few multiplications
 * per node. Stops as soon as the limit is passed.
 */
function measureScene(doc) {
    const root = doc.getRoot();
    const scene = root.getDefaultScene() || root.listScenes()[0];
    const perMesh = new Map();
    const measureMesh = (mesh) => {
        if (perMesh.has(mesh)) return perMesh.get(mesh);
        const m = { triangles: 0, min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
        for (const prim of mesh.listPrimitives()) {
            m.triangles += primitiveTriangles(prim);
            const position = prim.getAttribute('POSITION');
            if (!position || !position.getCount()) continue;
            const lo = position.getNormalized() ? position.getMinNormalized([]) : position.getMin([]);
            const hi = position.getNormalized() ? position.getMaxNormalized([]) : position.getMax([]);
            for (let i = 0; i < 3; i++) {
                if (lo[i] < m.min[i]) m.min[i] = lo[i];
                if (hi[i] > m.max[i]) m.max[i] = hi[i];
            }
        }
        perMesh.set(mesh, m);
        return m;
    };
    let triangles = 0;
    const min = [Infinity, Infinity, Infinity];
    const max = [-Infinity, -Infinity, -Infinity];
    const seen = new Set();
    const stack = scene ? [...scene.listChildren()] : [];
    while (stack.length) {
        const node = stack.pop();
        if (seen.has(node)) continue;
        seen.add(node);
        stack.push(...node.listChildren());
        const mesh = node.getMesh();
        if (!mesh) continue;
        const m = measureMesh(mesh);
        triangles += m.triangles;
        if (triangles > LIMITS.triangles) reject(`The model draws more than ${LIMITS.triangles} triangles`);
        if (!(m.min[0] <= m.max[0])) continue;
        const w = node.getWorldMatrix();
        for (const x of [m.min[0], m.max[0]]) {
            for (const y of [m.min[1], m.max[1]]) {
                for (const z of [m.min[2], m.max[2]]) {
                    const p = [
                        w[0] * x + w[4] * y + w[8] * z + w[12],
                        w[1] * x + w[5] * y + w[9] * z + w[13],
                        w[2] * x + w[6] * y + w[10] * z + w[14],
                    ];
                    for (let i = 0; i < 3; i++) {
                        if (p[i] < min[i]) min[i] = p[i];
                        if (p[i] > max[i]) max[i] = p[i];
                    }
                }
            }
        }
    }
    const finite = min.every(Number.isFinite) && max.every(Number.isFinite);
    return { triangles, box: finite ? { min, max } : null };
}

function animationSummary(doc) {
    return doc.getRoot().listAnimations().map((anim, i) => {
        let duration = 0;
        for (const sampler of anim.listSamplers()) {
            const input = sampler.getInput();
            if (input) duration = Math.max(duration, input.getMax([0])[0] || 0);
        }
        return { name: anim.getName() || `Animation ${i + 1}`, duration: Math.round(duration * 1000) / 1000 };
    });
}

/**
 * Make the document one GLB's worth: a single buffer, no external image URIs,
 * and no optional extensions the launcher would not render.
 */
function flatten(doc, animations) {
    const root = doc.getRoot();
    for (const ext of root.listExtensionsUsed()) {
        if (!RENDERABLE_EXTENSIONS.has(ext.extensionName)) ext.dispose();
    }
    const [primary, ...rest] = root.listBuffers();
    const buffer = primary || doc.createBuffer();
    for (const accessor of root.listAccessors()) accessor.setBuffer(buffer);
    for (const extra of rest) extra.dispose();
    buffer.setURI('');
    for (const texture of root.listTextures()) texture.setURI('');
    // Unnamed clips get the name the metadata calls them by, so the two agree.
    root.listAnimations().forEach((anim, i) => { if (!anim.getName()) anim.setName(animations[i].name); });
}

/**
 * Turn an upload into a stored model and its metadata.
 *
 * files: multer-style [{ originalname, buffer }], the model plus any resources.
 * options: { slot, attachment, transform, animationRoles, manifest } from the form.
 * Returns { glb: Buffer, metadata }. Throws CosmeticAssetError (status 400)
 * with a message meant for the creator.
 */
async function prepareCosmeticModel(files, options = {}) {
    if (!files || !files.length) reject('A model file is required (.glb, .gltf or .zip)');
    const collected = collectFiles(files);
    const models = [...collected.keys()].filter(isModel);
    if (!models.length) reject('No .glb or .gltf model was found in the upload');
    if (models.length > 1) reject(`The upload has more than one model (${models.slice(0, 3).join(', ')}); include exactly one`);
    const modelPath = models[0];
    const modelBytes = collected.get(modelPath);
    const isGlb = /\.glb$/i.test(modelPath);

    // A cosmetic.json beside the model can carry the same choices as the form.
    const manifestKey = [...collected.keys()].find((k) => path.posix.basename(k).toLowerCase() === 'cosmetic.json');
    let manifest = {};
    if (manifestKey) {
        try { manifest = JSON.parse(Buffer.from(collected.get(manifestKey)).toString('utf8')) || {}; }
        catch { reject('cosmetic.json is not valid JSON'); }
    }

    const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
    let doc;
    if (isGlb) {
        const { json, binLength } = glbJson(modelBytes);
        checkShape(json);
        checkExtensions(json);
        const external = [...(json.buffers || []), ...(json.images || [])].filter((r) => r && r.uri && !r.uri.startsWith('data:'));
        if (external.length) reject('This .glb points at separate files. Export it with textures embedded, or upload the .gltf and its files as a .zip.');
        checkStructure(json, (json.buffers || []).map((b, i) => (b && typeof b.uri === 'string' ? dataUriBytes(b.uri) : i === 0 ? binLength : 0)));
        try { doc = await io.readBinary(modelBytes); } catch (e) { reject(`The .glb could not be read: ${e.message}`); }
    } else {
        let json;
        try { json = JSON.parse(Buffer.from(modelBytes).toString('utf8')); } catch { reject('The .gltf file is not valid JSON'); }
        checkShape(json);
        checkExtensions(json);
        const resources = resolveResources(json, modelPath, collected);
        checkStructure(json, (json.buffers || []).map((b) => {
            if (!b || typeof b.uri !== 'string') return 0;
            return b.uri.startsWith('data:') ? dataUriBytes(b.uri) : resources[b.uri]?.byteLength ?? 0;
        }));
        try { doc = await io.readJSON({ json, resources }); } catch (e) { reject(`The .gltf could not be read: ${e.message}`); }
    }

    try {
        return await finishModel(doc, io, { files, options, manifest, isGlb });
    } catch (e) {
        // Anything the checks above did not anticipate (a corrupt image header,
        // an accessor the writer rejects) is still the upload's fault, not the
        // server's: a clear 400, never a 500.
        if (e instanceof CosmeticAssetError) throw e;
        return reject(`The model could not be processed: ${e.message}`);
    }
}

/** Measure, check and write a parsed model. Split out so its errors are caught. */
async function finishModel(doc, io, { files, options, manifest, isGlb }) {
    const root = doc.getRoot();
    if (!root.listMeshes().length) reject('The model has no meshes');
    const { triangles, box } = measureScene(doc);

    const textures = root.listTextures();
    if (textures.length > LIMITS.textures) reject(`The model has ${textures.length} textures; the limit is ${LIMITS.textures}`);
    const textureInfo = textures.map((texture, i) => {
        const image = texture.getImage();
        const label = texture.getName() || texture.getURI() || `texture ${i + 1}`;
        if (!image || !image.byteLength) reject(`Texture "${label}" has no image data`);
        const mime = ImageUtils.getMimeType(image);
        if (!IMAGE_TYPES.has(mime)) reject(`Texture "${label}" is not a PNG, JPEG or WebP image`);
        texture.setMimeType(mime);
        let size = null;
        // A truncated header makes the size reader throw rather than return null.
        try { size = ImageUtils.getSize(image, mime); } catch { size = null; }
        if (!size) reject(`Texture "${label}" could not be decoded`);
        if (Math.max(size[0], size[1]) > LIMITS.textureEdge) reject(`Texture "${label}" is ${size[0]}x${size[1]}; the largest allowed side is ${LIMITS.textureEdge}`);
        return { mime, width: size[0], height: size[1] };
    });

    const clips = animationSummary(doc);
    if (clips.length > LIMITS.animations) reject(`The model has ${clips.length} animations; the limit is ${LIMITS.animations}`);
    const overrides = parseJsonField(options.animationRoles, 'animationRoles') || manifest.animations || {};
    for (const [role, name] of Object.entries(overrides)) {
        if (!ROLES.includes(role)) reject(`"${role}" is not an animation role (${ROLES.join(', ')})`);
        if (name && !clips.some((c) => c.name === name)) reject(`The model has no animation called "${name}"`);
    }
    const { roles, extras } = discoverAnimationRoles(clips.map((c) => c.name), overrides);

    const bounds = box
        ? { min: box.min.map((v) => Math.round(v * 1e4) / 1e4), max: box.max.map((v) => Math.round(v * 1e4) / 1e4) }
        : null;
    if (!bounds || bounds.max.every((v, i) => v === bounds.min[i])) reject('The model has no size; check that it has visible geometry');

    flatten(doc, clips);
    const glb = Buffer.from(await io.writeBinary(doc));
    if (glb.length > LIMITS.fileBytes) reject(`The finished model is larger than ${LIMITS.fileBytes / 1024 / 1024} MB`);

    const slot = String(options.slot || manifest.slot || '').toLowerCase();
    const attachment = resolveAttachment(slot, options.attachment || manifest.attachment);
    const transform = sanitizeTransform(parseJsonField(options.transform, 'transform') || manifest.transform);

    return {
        glb,
        metadata: {
            spec: SPEC_VERSION,
            attachment,
            transform,
            animations: { clips, roles, extras },
            bounds,
            stats: {
                triangles,
                textures: textureInfo,
                materials: root.listMaterials().length,
                skinned: root.listSkins().length > 0,
                bytes: glb.length,
                source: isGlb ? 'glb' : files.some((f) => /\.zip$/i.test(f.originalname || '')) ? 'zip' : 'gltf',
            },
        },
    };
}

/**
 * Apply an edit to stored metadata. Only the creator's choices change; what
 * the server measured (clips, bounds, stats) is kept, so an edit cannot make
 * the metadata disagree with the file.
 */
function editCosmeticMetadata(current, slot, edit) {
    const base = current && typeof current === 'object' ? current : {};
    const next = { ...base };
    if (edit.attachment !== undefined) next.attachment = resolveAttachment(slot, edit.attachment);
    if (edit.transform !== undefined) next.transform = sanitizeTransform(parseJsonField(edit.transform, 'transform'));
    if (edit.animationRoles !== undefined) {
        const clips = (base.animations && Array.isArray(base.animations.clips)) ? base.animations.clips : null;
        if (!clips) reject('This cosmetic was uploaded before Breeze read animations from the file. Upload it again to choose animation roles.');
        if (!clips.length) reject('This model has no animations.');
        const edits = parseJsonField(edit.animationRoles, 'animationRoles') || {};
        for (const [role, name] of Object.entries(edits)) {
            if (!ROLES.includes(role)) reject(`"${role}" is not an animation role (${ROLES.join(', ')})`);
            if (name && !clips.some((c) => c.name === name)) reject(`The model has no animation called "${name}"`);
        }
        // Start from the stored choices, so a role the edit does not mention
        // keeps its clip, and a role that was off stays off.
        const current = (base.animations && base.animations.roles) || {};
        const overrides = {};
        for (const role of ROLES) overrides[role] = current[role] || '';
        Object.assign(overrides, edits);
        const found = discoverAnimationRoles(clips.map((c) => c.name), overrides);
        next.animations = { clips, roles: found.roles, extras: found.extras };
    }
    return next;
}

module.exports = {
    SPEC_VERSION,
    LIMITS,
    ATTACHMENTS,
    SLOT_ATTACHMENTS,
    ROLES,
    CosmeticAssetError,
    discoverAnimationRoles,
    sanitizeTransform,
    prepareCosmeticModel,
    editCosmeticMetadata,
};
