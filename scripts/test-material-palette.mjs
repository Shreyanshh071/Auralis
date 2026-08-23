// Behaviour tests for the Material 3 palette generator.
//
// Run with:  node scripts/test-material-palette.mjs
//
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  FALLBACK_SEED,
  MATERIAL_CSS_VARIABLES,
  MODE_CONTRAST_SURFACE,
  applyMaterialPalette,
  buildMaterialPalette,
  contrastRatio,
  materialPaletteToCssVariables,
  oklchToHex,
  parseCssColor,
  rgbToOklch,
} from '../src/lib/materialPalette.ts';

const MODES = ['light', 'dark'];

// A spread of seeds: the app's own fallback, a saturated violet (the colour the
// old extractor used to fail to, i.e. the tone this whole change removes), a
// near-black cover, a near-white cover, and a fully grey one.
const SEEDS = [
  FALLBACK_SEED,
  '#7c3aed',
  '#e11d48',
  '#0ea5e9',
  '#111111',
  '#f5f5f5',
  '#808080',
];

test('parseCssColor accepts every format the extractor can emit', () => {
  assert.deepEqual(parseCssColor('#4d7c0f'), { r: 77, g: 124, b: 15 });
  assert.deepEqual(parseCssColor('#FFF'), { r: 255, g: 255, b: 255 });
  assert.deepEqual(parseCssColor('rgb(12, 34, 56)'), { r: 12, g: 34, b: 56 });
  assert.deepEqual(parseCssColor('rgba(12,34,56,0.5)'), { r: 12, g: 34, b: 56 });
  assert.deepEqual(parseCssColor('  #4D7C0F  '), { r: 77, g: 124, b: 15 });
});

test('parseCssColor rejects junk instead of producing a bogus colour', () => {
  for (const bad of [null, undefined, '', 'transparent', 'not-a-colour', '#12', '#12345', 'rgb()']) {
    assert.equal(parseCssColor(bad), null, `${String(bad)} must not parse`);
  }
});

test('OKLCh round-trips a colour back to roughly the same sRGB', () => {
  for (const seed of SEEDS) {
    const rgb = parseCssColor(seed);
    const back = parseCssColor(oklchToHex(rgbToOklch(rgb)));
    for (const channel of ['r', 'g', 'b']) {
      assert.ok(
        Math.abs(back[channel] - rgb[channel]) <= 2,
        `${seed}: ${channel} drifted from ${rgb[channel]} to ${back[channel]}`,
      );
    }
  }
});

test('oklchToHex gamut-maps out-of-range chroma instead of clipping to garbage', () => {
  // C = 0.5 at this lightness is far outside sRGB. The result must still be a
  // valid hex triple and must keep the hue rather than collapsing to a channel
  // wall.
  const hex = oklchToHex({ L: 0.6, C: 0.5, h: 140 });
  assert.match(hex, /^#[0-9a-f]{6}$/);
  const mapped = rgbToOklch(parseCssColor(hex));
  assert.ok(mapped.C < 0.5, 'chroma must have been reduced');
  assert.ok(Math.abs(mapped.h - 140) < 8, `hue drifted to ${mapped.h}`);
});

test('contrastRatio matches known WCAG values', () => {
  assert.ok(Math.abs(contrastRatio('#000000', '#ffffff') - 21) < 0.01);
  assert.ok(Math.abs(contrastRatio('#ffffff', '#ffffff') - 1) < 0.01);
  // Symmetric.
  assert.ok(
    Math.abs(contrastRatio('#4d7c0f', '#ffffff') - contrastRatio('#ffffff', '#4d7c0f')) < 1e-9,
  );
});

test('every palette role is a resolved colour, never a var() chain', () => {
  // The palette is written as inline styles on <html>, where a var() would be
  // substituted against the wrong element. Resolved values only.
  for (const mode of MODES) {
    for (const seed of SEEDS) {
      const palette = buildMaterialPalette(seed, mode);
      for (const [role, value] of Object.entries(palette)) {
        assert.equal(typeof value, 'string', `${role} must be a string`);
        assert.ok(!value.includes('var('), `${mode}/${seed}: ${role} must not contain var()`);
        assert.match(
          value,
          /^(#[0-9a-f]{6}|rgba\(\d+, \d+, \d+, [\d.]+\))$/,
          `${mode}/${seed}: ${role} = ${value} is not a resolved colour`,
        );
      }
    }
  }
});

test('foreground roles clear WCAG AA against their own container', () => {
  for (const mode of MODES) {
    for (const seed of SEEDS) {
      const p = buildMaterialPalette(seed, mode);
      const pairs = [
        ['onPrimary/primary', p.onPrimary, p.primary],
        ['onPrimaryContainer/primaryContainer', p.onPrimaryContainer, p.primaryContainer],
        ['onSecondaryContainer/secondaryContainer', p.onSecondaryContainer, p.secondaryContainer],
        ['primary/surface', p.primary, MODE_CONTRAST_SURFACE[mode]],
      ];
      for (const [label, fg, bg] of pairs) {
        const ratio = contrastRatio(fg, bg);
        assert.ok(
          ratio >= 4.5,
          `${mode}/${seed}: ${label} contrast ${ratio.toFixed(2)} is below AA (4.5)`,
        );
      }
    }
  }
});

test('outlines clear the 3:1 threshold for non-text UI against the surface', () => {
  for (const mode of MODES) {
    for (const seed of SEEDS) {
      const p = buildMaterialPalette(seed, mode);
      const ratio = contrastRatio(p.outline, MODE_CONTRAST_SURFACE[mode]);
      assert.ok(
        ratio >= 3,
        `${mode}/${seed}: outline contrast ${ratio.toFixed(2)} is below 3:1`,
      );
    }
  }
});

test('the palette stays restrained — no neon accent, even from a neon cover', () => {
  for (const mode of MODES) {
    for (const seed of ['#ff00ff', '#00ff00', '#ff0000', '#7c3aed']) {
      const { C: c } = rgbToOklch(parseCssColor(buildMaterialPalette(seed, mode).primary));
      assert.ok(c <= 0.16, `${mode}/${seed}: primary chroma ${c.toFixed(3)} is too saturated`);
    }
  }
});

test('a greyscale cover still yields a visible, non-flat accent', () => {
  for (const mode of MODES) {
    for (const seed of ['#808080', '#111111', '#f5f5f5']) {
      const { C: c } = rgbToOklch(parseCssColor(buildMaterialPalette(seed, mode).primary));
      assert.ok(c >= 0.02, `${mode}/${seed}: primary chroma ${c.toFixed(3)} is too flat`);
    }
  }
});

test('the ambient tints are transparent so the surface underneath stays neutral', () => {
  for (const mode of MODES) {
    const p = buildMaterialPalette(FALLBACK_SEED, mode);
    for (const role of ['surfaceTint', 'playerTint', 'primary08', 'primary12', 'primary16', 'primary24', 'primary40']) {
      const alpha = Number(/rgba\(\d+, \d+, \d+, ([\d.]+)\)/.exec(p[role])[1]);
      assert.ok(alpha > 0 && alpha < 1, `${mode}: ${role} alpha ${alpha} must be translucent`);
    }
    // The whole-app wash must be barely there; a heavy one is the "purple
    // wash" this replaced.
    const surfaceAlpha = Number(/, ([\d.]+)\)$/.exec(p.surfaceTint)[1]);
    assert.ok(surfaceAlpha <= 0.1, `${mode}: surfaceTint alpha ${surfaceAlpha} is too strong`);
  }
});

test('the state-layer alphas increase monotonically', () => {
  for (const mode of MODES) {
    const p = buildMaterialPalette(FALLBACK_SEED, mode);
    const alphas = ['primary08', 'primary12', 'primary16', 'primary24', 'primary40'].map(
      (role) => Number(/, ([\d.]+)\)$/.exec(p[role])[1]),
    );
    for (let i = 1; i < alphas.length; i += 1) {
      assert.ok(alphas[i] > alphas[i - 1], `state layer ${i} must be stronger than ${i - 1}`);
    }
  }
});

test('an unparseable seed falls back rather than throwing or emitting empty values', () => {
  for (const mode of MODES) {
    const expected = buildMaterialPalette(FALLBACK_SEED, mode);
    for (const bad of [null, undefined, '', 'rgb(nope)', 'chartreuse-ish']) {
      assert.deepEqual(
        buildMaterialPalette(bad, mode),
        expected,
        `${String(bad)} must fall back to the neutral palette`,
      );
    }
  }
});

test('light and dark are tonally distinct, not inverted', () => {
  for (const seed of SEEDS) {
    const light = buildMaterialPalette(seed, 'light');
    const dark = buildMaterialPalette(seed, 'dark');
    assert.notEqual(light.primary, dark.primary);

    const lightL = rgbToOklch(parseCssColor(light.primary)).L;
    const darkL = rgbToOklch(parseCssColor(dark.primary)).L;
    // M3: the accent is a mid tone on light surfaces and a high tone on dark.
    assert.ok(darkL > lightL, `${seed}: dark accent (${darkL}) must be lighter than light (${lightL})`);

    // Inversion would put the container on the opposite side of its on-colour;
    // in M3 both modes keep container darker-than-on in dark and vice versa.
    assert.ok(
      rgbToOklch(parseCssColor(light.primaryContainer)).L >
        rgbToOklch(parseCssColor(light.onPrimaryContainer)).L,
      `${seed}: light container must be lighter than its on-colour`,
    );
    assert.ok(
      rgbToOklch(parseCssColor(dark.primaryContainer)).L <
        rgbToOklch(parseCssColor(dark.onPrimaryContainer)).L,
      `${seed}: dark container must be darker than its on-colour`,
    );
  }
});

test('the same seed always produces the same palette', () => {
  for (const mode of MODES) {
    assert.deepEqual(
      buildMaterialPalette('#e11d48', mode),
      buildMaterialPalette('#e11d48', mode),
    );
  }
});

test('every role maps to a --m3-* custom property', () => {
  const palette = buildMaterialPalette(FALLBACK_SEED, 'dark');
  const vars = materialPaletteToCssVariables(palette);

  assert.deepEqual(
    Object.keys(MATERIAL_CSS_VARIABLES).sort(),
    Object.keys(palette).sort(),
    'the variable map must cover exactly the palette roles',
  );
  for (const [role, name] of Object.entries(MATERIAL_CSS_VARIABLES)) {
    assert.match(name, /^--m3-[a-z0-9-]+$/, `${role} -> ${name} is not a --m3-* name`);
    assert.equal(vars[name], palette[role]);
  }
  assert.equal(new Set(Object.values(MATERIAL_CSS_VARIABLES)).size, Object.keys(palette).length);
});

test('applyMaterialPalette writes each variable exactly once onto the target', () => {
  const written = new Map();
  const target = {
    style: {
      setProperty(name, value) {
        written.set(name, value);
      },
    },
  };

  applyMaterialPalette(target, FALLBACK_SEED, 'light');

  const expected = materialPaletteToCssVariables(buildMaterialPalette(FALLBACK_SEED, 'light'));
  assert.equal(written.size, Object.keys(expected).length);
  for (const [name, value] of Object.entries(expected)) {
    assert.equal(written.get(name), value, `${name} was not written`);
  }
});

test('applyMaterialPalette is a no-op on a target with no style object', () => {
  assert.doesNotThrow(() => applyMaterialPalette(null, FALLBACK_SEED, 'dark'));
  assert.doesNotThrow(() => applyMaterialPalette({}, FALLBACK_SEED, 'dark'));
});
