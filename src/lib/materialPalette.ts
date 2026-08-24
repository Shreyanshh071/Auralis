/**
 * Material 3 style tonal palette generation from a single source colour.
 *
 * Auralis used to hardcode one accent per theme (`purple-600` in light,
 * `#dbe7b5` in dark) in ~250 places, which is where the purple tone came from:
 * every chip, slider, spinner and selected state was literally purple, and the
 * album-artwork colour only reached two decorative background blobs.
 *
 * This module inverts that. A single source colour — the dominant colour of the
 * current album artwork, or the Auralis olive when no artwork colour is
 * available — is turned into a small set of Material 3 roles (primary, its
 * containers, outlines, state layers). Those roles are published as CSS custom
 * properties, so the whole UI follows the artwork without any component
 * knowing which colour is playing.
 *
 * Roles are generated in OKLCh: hue comes from the source, lightness is pinned
 * per role so contrast is predictable, and chroma is capped per role so a
 * neon-pink cover produces a *restrained* pink rather than a neon UI. Every
 * role is gamut-mapped back into sRGB and the text-bearing ones are nudged
 * until they clear WCAG AA against the surface they sit on.
 *
 * The maths here is deliberately dependency-free and pure so
 * `scripts/test-material-palette.mjs` can exercise it directly.
 */

export type MaterialMode = 'light' | 'dark';

export interface Rgb {
  r: number;
  g: number;
  b: number;
}

export interface Oklch {
  /** Perceptual lightness, 0-1. */
  L: number;
  /** Chroma, 0-~0.4 in sRGB. */
  C: number;
  /** Hue angle in degrees, 0-360. */
  h: number;
}

export interface MaterialPalette {
  primary: string;
  primaryHover: string;
  onPrimary: string;
  primaryContainer: string;
  primaryContainerHover: string;
  onPrimaryContainer: string;
  secondaryContainer: string;
  onSecondaryContainer: string;
  outline: string;
  outlineVariant: string;
  primary08: string;
  primary12: string;
  primary16: string;
  primary24: string;
  primary40: string;
  surfaceTint: string;
  playerTint: string;
  /**
   * Now Playing atmospheric background, top gradient stop. A dark, heavily
   * tinted wash from the artwork's hue, much stronger than `surfaceTint` so
   * the background clearly relates to the album cover.
   */
  nowPlayingTintA: string;
  /**
   * Now Playing atmospheric background, bottom gradient stop. Same hue
   * shifted slightly warmer and darker to create depth.
   */
  nowPlayingTintB: string;
}

/**
 * Fallback source colour: Auralis' own olive/lime. Used when there is no
 * artwork, when extraction fails, or when the extracted value is unparseable.
 * Generating from this seed reproduces the accents Auralis already shipped
 * (~#4d7c0f in light, ~#dbe7b5 in dark), so the app keeps its identity instead
 * of falling back to a generic grey.
 */
export const FALLBACK_SEED = '#4d7c0f';

/**
 * The surface each mode's accent text is measured against for the AA nudge:
 * the card colour, which is the busiest place an accent label appears.
 * Keep in sync with `--bg-card` in src/index.css.
 */
export const MODE_CONTRAST_SURFACE: Record<MaterialMode, string> = {
  light: '#ffffff',
  dark: '#1b1c19',
};

/** Body-text contrast floor (WCAG AA). */
const AA_TEXT = 4.5;
/** Large-text / UI-component contrast floor (WCAG AA). */
const AA_LARGE = 3;

interface RoleTarget {
  L: number;
  maxC: number;
}

const DARK_ROLES: Record<string, RoleTarget> = {
  primary: { L: 0.855, maxC: 0.075 },
  primaryHover: { L: 0.915, maxC: 0.06 },
  onPrimary: { L: 0.255, maxC: 0.045 },
  primaryContainer: { L: 0.325, maxC: 0.05 },
  primaryContainerHover: { L: 0.385, maxC: 0.05 },
  onPrimaryContainer: { L: 0.9, maxC: 0.07 },
  secondaryContainer: { L: 0.355, maxC: 0.035 },
  onSecondaryContainer: { L: 0.92, maxC: 0.055 },
  outline: { L: 0.5, maxC: 0.018 },
  outlineVariant: { L: 0.33, maxC: 0.012 },
};

const LIGHT_ROLES: Record<string, RoleTarget> = {
  primary: { L: 0.505, maxC: 0.115 },
  primaryHover: { L: 0.445, maxC: 0.115 },
  onPrimary: { L: 0.995, maxC: 0.006 },
  primaryContainer: { L: 0.915, maxC: 0.05 },
  primaryContainerHover: { L: 0.875, maxC: 0.05 },
  onPrimaryContainer: { L: 0.32, maxC: 0.09 },
  secondaryContainer: { L: 0.9, maxC: 0.035 },
  onSecondaryContainer: { L: 0.3, maxC: 0.06 },
  outline: { L: 0.6, maxC: 0.02 },
  outlineVariant: { L: 0.85, maxC: 0.012 },
};

/** Chroma floor, so a black-and-white cover still yields a usable accent. */
const MIN_CHROMA = 0.028;

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

/** Parse `#rgb`, `#rrggbb`, `rgb(...)` and `rgba(...)`. Returns null otherwise. */
export function parseCssColor(input: string | null | undefined): Rgb | null {
  if (typeof input !== 'string') return null;
  const value = input.trim().toLowerCase();
  if (value.length === 0) return null;

  if (value.charAt(0) === '#') {
    const hex = value.slice(1);
    if (/^[0-9a-f]{3}$/.test(hex)) {
      return {
        r: parseInt(hex.charAt(0) + hex.charAt(0), 16),
        g: parseInt(hex.charAt(1) + hex.charAt(1), 16),
        b: parseInt(hex.charAt(2) + hex.charAt(2), 16),
      };
    }
    if (/^[0-9a-f]{6}$/.test(hex)) {
      return {
        r: parseInt(hex.slice(0, 2), 16),
        g: parseInt(hex.slice(2, 4), 16),
        b: parseInt(hex.slice(4, 6), 16),
      };
    }
    return null;
  }

  const match = /^rgba?\(([^)]+)\)$/.exec(value);
  if (!match) return null;
  const parts = match[1]
    .replace(/\//g, ' ')
    .split(/[\s,]+/)
    .filter((part) => part.length > 0)
    .map(Number);
  if (parts.length < 3 || parts.some((part) => !Number.isFinite(part))) return null;
  return {
    r: clamp(Math.round(parts[0]), 0, 255),
    g: clamp(Math.round(parts[1]), 0, 255),
    b: clamp(Math.round(parts[2]), 0, 255),
  };
}

function srgbChannelToLinear(channel: number): number {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function linearToSrgbChannel(linear: number): number {
  const v = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
  return v;
}

/** sRGB (0-255) to OKLCh. */
export function rgbToOklch(rgb: Rgb): Oklch {
  const r = srgbChannelToLinear(rgb.r);
  const g = srgbChannelToLinear(rgb.g);
  const b = srgbChannelToLinear(rgb.b);

  const l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
  const m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
  const s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

  const l_ = Math.cbrt(l);
  const m_ = Math.cbrt(m);
  const s_ = Math.cbrt(s);

  const L = 0.2104542553 * l_ + 0.793617785 * m_ - 0.0040720468 * s_;
  const a = 1.9779984951 * l_ - 2.428592205 * m_ + 0.4505937099 * s_;
  const bb = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.808675766 * s_;

  const C = Math.sqrt(a * a + bb * bb);
  let h = (Math.atan2(bb, a) * 180) / Math.PI;
  if (h < 0) h += 360;
  return { L, C, h };
}

interface LinearRgb {
  r: number;
  g: number;
  b: number;
}

function oklchToLinearRgb(color: Oklch): LinearRgb {
  const hRad = (color.h * Math.PI) / 180;
  const a = Math.cos(hRad) * color.C;
  const b = Math.sin(hRad) * color.C;

  const l_ = color.L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = color.L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = color.L - 0.0894841775 * a - 1.291485548 * b;

  const l = l_ * l_ * l_;
  const m = m_ * m_ * m_;
  const s = s_ * s_ * s_;

  return {
    r: 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    g: -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    b: -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  };
}

function isInGamut(linear: LinearRgb): boolean {
  const limit = 1.0001;
  const floor = -0.0001;
  return (
    linear.r >= floor &&
    linear.r <= limit &&
    linear.g >= floor &&
    linear.g <= limit &&
    linear.b >= floor &&
    linear.b <= limit
  );
}

/**
 * OKLCh to an sRGB hex string, reducing chroma until the colour fits in the
 * sRGB gamut so hue and lightness survive instead of being clipped per channel.
 */
export function oklchToHex(color: Oklch): string {
  let candidate: Oklch = { L: clamp(color.L, 0, 1), C: Math.max(0, color.C), h: color.h };
  let linear = oklchToLinearRgb(candidate);

  for (let i = 0; i < 48 && !isInGamut(linear); i += 1) {
    candidate = { L: candidate.L, C: candidate.C * 0.94, h: candidate.h };
    linear = oklchToLinearRgb(candidate);
  }

  const toByte = (value: number): number =>
    clamp(Math.round(linearToSrgbChannel(clamp(value, 0, 1)) * 255), 0, 255);

  const r = toByte(linear.r);
  const g = toByte(linear.g);
  const b = toByte(linear.b);
  const hex = (n: number): string => n.toString(16).padStart(2, '0');
  return `#${hex(r)}${hex(g)}${hex(b)}`;
}

/** WCAG relative luminance of an sRGB colour. */
export function relativeLuminance(rgb: Rgb): number {
  const r = srgbChannelToLinear(rgb.r);
  const g = srgbChannelToLinear(rgb.g);
  const b = srgbChannelToLinear(rgb.b);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio between two CSS colours. Returns 1 if either is unparseable. */
export function contrastRatio(a: string, b: string): number {
  const rgbA = parseCssColor(a);
  const rgbB = parseCssColor(b);
  if (!rgbA || !rgbB) return 1;
  const lumA = relativeLuminance(rgbA);
  const lumB = relativeLuminance(rgbB);
  const lighter = Math.max(lumA, lumB);
  const darker = Math.min(lumA, lumB);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Walk a role's lightness away from `against` until it clears `minRatio`.
 * Roles are already positioned for contrast; this only catches the awkward
 * hues (yellows and cyans read much lighter than their OKLCh L suggests).
 */
function ensureContrast(role: Oklch, against: string, minRatio: number, mode: MaterialMode): string {
  const step = mode === 'dark' ? 0.012 : -0.012;
  let candidate: Oklch = { L: role.L, C: role.C, h: role.h };

  for (let i = 0; i < 56; i += 1) {
    const hex = oklchToHex(candidate);
    if (contrastRatio(hex, against) >= minRatio) return hex;
    const nextL = candidate.L + step;
    if (nextL <= 0.02 || nextL >= 0.995) return hex;
    candidate = { L: nextL, C: candidate.C, h: candidate.h };
  }
  return oklchToHex(candidate);
}

function rgba(hex: string, alpha: number): string {
  const rgb = parseCssColor(hex);
  if (!rgb) return `rgba(0, 0, 0, ${alpha})`;
  return `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${alpha})`;
}

/**
 * Build the Material 3 role set for a source colour.
 *
 * `seed` accepts whatever the artwork extractor produced (`rgb(...)` or hex);
 * anything unparseable falls back to the Auralis olive rather than throwing, so
 * a bad thumbnail can never leave the UI without an accent.
 */
export function buildMaterialPalette(
  seed: string | null | undefined,
  mode: MaterialMode,
): MaterialPalette {
  const seedRgb = parseCssColor(seed) ?? parseCssColor(FALLBACK_SEED)!;
  const source = rgbToOklch(seedRgb);
  const roles = mode === 'dark' ? DARK_ROLES : LIGHT_ROLES;
  const surface = MODE_CONTRAST_SURFACE[mode];

  const role = (name: string): Oklch => {
    const target = roles[name];
    return {
      L: target.L,
      C: clamp(source.C, MIN_CHROMA, target.maxC),
      h: source.h,
    };
  };

  // Accent text and icons sit on the card surface, so they get the full AA
  // text treatment. Containers only ever carry text of their own `on-` role,
  // which is generated at the opposite end of the tonal range.
  const primary = ensureContrast(role('primary'), surface, AA_TEXT, mode);
  const primaryHover = ensureContrast(role('primaryHover'), surface, AA_TEXT, mode);
  const primaryContainer = oklchToHex(role('primaryContainer'));
  const primaryContainerHover = oklchToHex(role('primaryContainerHover'));
  const secondaryContainer = oklchToHex(role('secondaryContainer'));

  const onPrimary = ensureContrast(
    role('onPrimary'),
    primary,
    AA_TEXT,
    mode === 'dark' ? 'light' : 'dark',
  );
  const onPrimaryContainer = ensureContrast(
    role('onPrimaryContainer'),
    primaryContainer,
    AA_TEXT,
    mode === 'dark' ? 'dark' : 'light',
  );
  const onSecondaryContainer = ensureContrast(
    role('onSecondaryContainer'),
    secondaryContainer,
    AA_TEXT,
    mode === 'dark' ? 'dark' : 'light',
  );
  const outline = ensureContrast(role('outline'), surface, AA_LARGE, mode);
  const outlineVariant = oklchToHex(role('outlineVariant'));

  return {
    primary,
    primaryHover,
    onPrimary,
    primaryContainer,
    primaryContainerHover,
    onPrimaryContainer,
    secondaryContainer,
    onSecondaryContainer,
    outline,
    outlineVariant,
    primary08: rgba(primary, 0.08),
    primary12: rgba(primary, 0.12),
    primary16: rgba(primary, 0.16),
    primary24: rgba(primary, 0.24),
    primary40: rgba(primary, 0.4),
    // Ambient wash behind the app shell: deliberately faint. The brief for this
    // system is that the background stays neutral and the accent supplies the
    // personality, not the other way round.
    surfaceTint: rgba(primary, mode === 'dark' ? 0.07 : 0.05),
    // Tonal glaze painted over the blurred artwork inside the MiniPlayer.
    // Kept translucent so the backdrop blur and vibrant cover art shine through cleanly.
    playerTint: rgba(primaryContainer, mode === 'dark' ? 0.22 : 0.16),
    // Now Playing atmospheric background — vibrant tonal washes that enrich
    // the dynamic blurred album art glow in sync with the cover's color.
    nowPlayingTintA: rgba(primary, mode === 'dark' ? 0.50 : 0.35),
    nowPlayingTintB: rgba(primaryContainer, mode === 'dark' ? 0.40 : 0.25),
  };
}

/** The CSS custom property name for each role. */
export const MATERIAL_CSS_VARIABLES: Record<keyof MaterialPalette, string> = {
  primary: '--m3-primary',
  primaryHover: '--m3-primary-hover',
  onPrimary: '--m3-on-primary',
  primaryContainer: '--m3-primary-container',
  primaryContainerHover: '--m3-primary-container-hover',
  onPrimaryContainer: '--m3-on-primary-container',
  secondaryContainer: '--m3-secondary-container',
  onSecondaryContainer: '--m3-on-secondary-container',
  outline: '--m3-outline',
  outlineVariant: '--m3-outline-variant',
  primary08: '--m3-primary-08',
  primary12: '--m3-primary-12',
  primary16: '--m3-primary-16',
  primary24: '--m3-primary-24',
  primary40: '--m3-primary-40',
  surfaceTint: '--m3-surface-tint',
  playerTint: '--m3-player-tint',
  nowPlayingTintA: '--m3-np-tint-a',
  nowPlayingTintB: '--m3-np-tint-b',
};

/** Flatten a palette into `{ '--m3-primary': '#...' }` form. */
export function materialPaletteToCssVariables(palette: MaterialPalette): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of Object.keys(MATERIAL_CSS_VARIABLES) as Array<keyof MaterialPalette>) {
    out[MATERIAL_CSS_VARIABLES[key]] = palette[key];
  }
  return out;
}

/**
 * Minimal shape of the element the palette is written to, so this stays
 * callable (and testable) without a DOM.
 */
export interface StyleTarget {
  style: { setProperty(name: string, value: string): void };
}

/**
 * Publish a palette as inline custom properties on `target` (in the app, the
 * <html> element). Inline properties beat the stylesheet defaults, and because
 * every value written here is already a resolved colour there is no chained
 * `var()` substitution to go wrong.
 */
export function applyMaterialPalette(
  target: StyleTarget | null | undefined,
  seed: string | null | undefined,
  mode: MaterialMode,
): MaterialPalette {
  const palette = buildMaterialPalette(seed, mode);
  if (target && target.style) {
    const variables = materialPaletteToCssVariables(palette);
    for (const name of Object.keys(variables)) {
      target.style.setProperty(name, variables[name]);
    }
  }
  return palette;
}
