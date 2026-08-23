import { FALLBACK_SEED } from '../lib/materialPalette';

// In-memory cache for extracted dominant artwork colors, keyed by image URL.
// Extraction is the expensive part (decode + canvas read), so a repeat of the
// same cover — replaying a track, reopening Now Playing — is free.
const colorCache = new Map<string, string>();

/**
 * Extract the dominant colour of an image using an offscreen canvas.
 *
 * Every failure path resolves to Auralis' own olive seed rather than a hard
 * failure. That fallback matters more than it looks: YouTube thumbnails are
 * cross-origin, so a CORS-blocked cover is a routine outcome, and this used to
 * resolve to `#7c3aed` — which is how a violet wash ended up behind the player
 * for any track whose artwork could not be sampled.
 *
 * The returned colour is a *source* colour, not a UI colour. It is fed to
 * buildMaterialPalette() in src/lib/materialPalette.ts, which is what keeps a
 * saturated cover from painting the interface neon.
 */
export async function getDominantColor(imageUrl: string): Promise<string> {
  if (!imageUrl || typeof imageUrl !== 'string') {
    return FALLBACK_SEED;
  }

  const cached = colorCache.get(imageUrl);
  if (cached) {
    return cached;
  }

  return new Promise((resolve) => {
    const finish = (color: string) => {
      colorCache.set(imageUrl, color);
      resolve(color);
    };

    const img = new Image();
    img.crossOrigin = 'Anonymous';
    img.src = imageUrl;

    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          finish(FALLBACK_SEED);
          return;
        }

        const size = 40;
        canvas.width = size;
        canvas.height = size;
        ctx.drawImage(img, 0, 0, size, size);
        const imageData = ctx.getImageData(0, 0, size, size).data;

        finish(dominantColorFromPixels(imageData) ?? FALLBACK_SEED);
      } catch {
        // Tainted canvas (the usual cross-origin case) throws on getImageData.
        finish(FALLBACK_SEED);
      }
    };

    img.onerror = () => {
      finish(FALLBACK_SEED);
    };
  });
}

/**
 * Pick a dominant colour from raw RGBA pixels.
 *
 * Averaging every pixel — the previous approach — pulls any cover with more
 * than one hue towards grey-brown, so the accent ended up muddy and the
 * artwork's actual colour never came through. Instead pixels are bucketed into
 * a coarse 4-bits-per-channel histogram, the most populous bucket wins, and
 * only that bucket is averaged. Buckets are weighted by saturation so a cover
 * that is 70% dark background does not hand back near-black.
 *
 * Exported for the palette tests; also usable anywhere pixel data is already
 * to hand.
 */
export function dominantColorFromPixels(pixels: Uint8ClampedArray | number[]): string | null {
  const buckets = new Map<number, { r: number; g: number; b: number; weight: number }>();

  for (let i = 0; i + 3 < pixels.length; i += 4) {
    const r = pixels[i];
    const g = pixels[i + 1];
    const b = pixels[i + 2];
    const alpha = pixels[i + 3];
    if (alpha < 128) continue;

    // Skip near-black and near-white: both are structural (letterboxing, text)
    // rather than the cover's identity, and neither makes a usable accent.
    const brightness = (r * 299 + g * 587 + b * 114) / 1000;
    if (brightness <= 26 || brightness >= 232) continue;

    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const saturation = max === 0 ? 0 : (max - min) / max;

    const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const bucket = buckets.get(key);
    // A colourful pixel counts for up to three flat ones, so a small vivid
    // element wins over a large desaturated field of near-grey.
    const weight = 1 + saturation * 2;
    if (bucket) {
      bucket.r += r * weight;
      bucket.g += g * weight;
      bucket.b += b * weight;
      bucket.weight += weight;
    } else {
      buckets.set(key, { r: r * weight, g: g * weight, b: b * weight, weight });
    }
  }

  let best: { r: number; g: number; b: number; weight: number } | null = null;
  for (const bucket of buckets.values()) {
    if (!best || bucket.weight > best.weight) {
      best = bucket;
    }
  }

  if (!best || best.weight === 0) return null;

  const r = Math.round(best.r / best.weight);
  const g = Math.round(best.g / best.weight);
  const b = Math.round(best.b / best.weight);
  return `rgb(${r}, ${g}, ${b})`;
}
