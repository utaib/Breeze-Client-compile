/**
 * Shrink an image client-side so it fits under the server's upload limit.
 *
 * Why this exists
 * ---------------
 * The API sits behind nginx on a Pterodactyl host, where `client_max_body_size`
 * is at its 1 MB default and cannot be changed from the panel: there is a file
 * manager and a process console, but no root shell and no nginx config access.
 * An upload over that limit is rejected by nginx before Express sees it, and
 * because nginx's own 413 page carries no CORS headers, the browser blocks the
 * response and reports a bare "failed to fetch" with no usable detail.
 *
 * Shrinking before upload sidesteps the limit entirely, and it is the better
 * design regardless: the server already downscales every cape to at most
 * 2048x1024 via sharp, so uploading a multi-megabyte source only to have almost
 * all of it discarded on arrival was pure waste.
 *
 * Quality is preserved as far as possible. Rather than resizing to a fixed
 * small size, this tries the largest cape dimension first and steps down only
 * while the encoded result is still over budget.
 */

/** Cape dimensions the API accepts, largest first. Mirrors CAPE_SIZES in server.js. */
const CAPE_SIZES: Array<[number, number]> = [
  [2048, 1024],
  [1024, 512],
  [512, 256],
  [256, 128],
  [128, 64],
  [64, 32],
];

/**
 * Byte budget for the encoded image.
 *
 * nginx rejects at 1 MB (measured: 1000 KB passes, 1100 KB returns 413). The
 * multipart envelope adds the other form fields and boundary overhead, so this
 * leaves comfortable headroom rather than sitting on the edge.
 */
const MAX_UPLOAD_BYTES = 800 * 1024;

/** Formats whose animation would be destroyed by re-encoding through a canvas. */
const ANIMATED_TYPES = new Set(["image/gif", "image/apng", "image/webp"]);

export type ImageFitResult = {
  file: File;
  /** True when the image was re-encoded rather than passed through unchanged. */
  resized: boolean;
  originalBytes: number;
  finalBytes: number;
  width: number;
  height: number;
};

export class ImageTooLargeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ImageTooLargeError";
  }
}

/**
 * Fit a set of animation frames that all travel in ONE multipart request.
 *
 * This is the case most likely to hit the limit. A single still cape is
 * comfortably small, but an animated cape posts every frame together, so twenty
 * frames of 60 KB is 1.2 MB and nginx rejects the whole upload. Budgeting each
 * frame individually against the 800 KB ceiling would let the total sail past
 * it, so the budget is divided across the frames instead.
 *
 * Frames are separate still images rather than an encoded GIF, so re-encoding
 * them here destroys no animation: the sequence is preserved by the array.
 */
export async function fitCapeFramesForUpload(frames: File[]): Promise<File[]> {
  if (!frames.length) return frames;

  const perFrame = Math.floor(MAX_UPLOAD_BYTES / frames.length);
  const total = frames.reduce((n, f) => n + f.size, 0);

  // Already within budget: leave every frame exactly as the user produced it.
  if (total <= MAX_UPLOAD_BYTES) return frames;

  const out: File[] = [];
  for (const frame of frames) {
    out.push(await fitOne(frame, perFrame));
  }

  const after = out.reduce((n, f) => n + f.size, 0);
  if (after > MAX_UPLOAD_BYTES) {
    throw new ImageTooLargeError(
      `Those ${frames.length} frames come to ${(after / 1024).toFixed(0)} KB even after shrinking, and the ` +
      `server accepts ${Math.round(MAX_UPLOAD_BYTES / 1024)} KB in one upload. Use fewer frames or smaller ones.`,
    );
  }
  return out;
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("That file could not be read as an image.")); };
    img.src = url;
  });
}

function encode(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    // PNG only. A cape is pixel art with hard edges and often transparency,
    // both of which JPEG destroys.
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Could not encode the image."))), "image/png");
  });
}

/**
 * Fit an image under the upload budget, preserving as much resolution as
 * possible.
 *
 * Animated files are returned untouched: re-encoding a GIF through a canvas
 * would flatten it to a single frame, silently turning an animated cape into a
 * still one. An oversized animated file throws instead, because quietly
 * breaking the animation is worse than refusing.
 */
/**
 * Shrink one still image to fit `budget` bytes, returning the largest cape size
 * that fits. Shared by the single-cape and per-frame paths so both use the same
 * step-down rule.
 */
async function fitOne(file: File, budget: number): Promise<File> {
  if (file.size <= budget) return file;

  const img = await loadImage(file);
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Could not prepare the image for upload.");

  for (const [w, h] of CAPE_SIZES) {
    if (w > img.naturalWidth && h > img.naturalHeight) continue;
    canvas.width = w;
    canvas.height = h;
    ctx.clearRect(0, 0, w, h);
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img, 0, 0, w, h);
    const blob = await encode(canvas);
    if (blob.size <= budget) {
      return new File([blob], file.name.replace(/\.[^.]+$/, "") + ".png", { type: "image/png" });
    }
  }
  // Nothing fit. Hand back the smallest attempt and let the caller decide
  // whether the total is still over budget.
  const blob = await encode(canvas);
  return new File([blob], file.name.replace(/\.[^.]+$/, "") + ".png", { type: "image/png" });
}

export async function fitCapeForUpload(file: File): Promise<ImageFitResult> {
  const originalBytes = file.size;

  if (ANIMATED_TYPES.has(file.type)) {
    if (originalBytes > MAX_UPLOAD_BYTES) {
      throw new ImageTooLargeError(
        `That animated cape is ${(originalBytes / 1024 / 1024).toFixed(1)} MB, and the server accepts ` +
        `up to ${Math.round(MAX_UPLOAD_BYTES / 1024)} KB. Reducing it here would flatten the animation ` +
        `to a single frame, so it has to be made smaller first: fewer frames, or smaller dimensions.`,
      );
    }
    const img = await loadImage(file).catch(() => null);
    return {
      file, resized: false, originalBytes, finalBytes: originalBytes,
      width: img?.naturalWidth ?? 0, height: img?.naturalHeight ?? 0,
    };
  }

  const img = await loadImage(file);
  const srcW = img.naturalWidth;
  const srcH = img.naturalHeight;

  // Already small enough and no larger than the biggest accepted size: leave it
  // exactly as the user made it.
  if (originalBytes <= MAX_UPLOAD_BYTES && srcW <= CAPE_SIZES[0][0] && srcH <= CAPE_SIZES[0][1]) {
    return { file, resized: false, originalBytes, finalBytes: originalBytes, width: srcW, height: srcH };
  }

  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Could not prepare the image for upload.");

  for (const [w, h] of CAPE_SIZES) {
    // Never upscale: a 64x32 source stays 64x32.
    if (w > srcW && h > srcH) continue;

    canvas.width = w;
    canvas.height = h;
    ctx.clearRect(0, 0, w, h);
    // Capes are pixel art. Smoothing turns crisp edges into mush, and the game
    // renders them at a small size where that reads as blur.
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img, 0, 0, w, h);

    const blob = await encode(canvas);
    if (blob.size <= MAX_UPLOAD_BYTES) {
      return {
        file: new File([blob], file.name.replace(/\.[^.]+$/, "") + ".png", { type: "image/png" }),
        resized: true, originalBytes, finalBytes: blob.size, width: w, height: h,
      };
    }
  }

  throw new ImageTooLargeError(
    "That image could not be reduced below the server's upload limit even at the smallest cape size. " +
    "It is likely a photograph rather than a cape texture.",
  );
}
