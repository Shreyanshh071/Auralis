// Behaviour tests for the Auralis Theme System (Light, Dark, System modes).
//
// Run with:  node scripts/test-theme-system.mjs
//
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { contrastRatio } from '../src/lib/materialPalette.ts';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// Helper function to resolve theme mode given a setting and system preference
function resolveEffectiveTheme(mode, prefersDark) {
  if (mode === 'dark') return 'dark';
  if (mode === 'light') return 'light';
  if (mode === 'system') return prefersDark ? 'dark' : 'light';
  return 'dark'; // safe default
}

test('Theme resolution logic: explicit dark mode resolves to dark', () => {
  assert.equal(resolveEffectiveTheme('dark', true), 'dark');
  assert.equal(resolveEffectiveTheme('dark', false), 'dark');
});

test('Theme resolution logic: explicit light mode resolves to light', () => {
  assert.equal(resolveEffectiveTheme('light', true), 'light');
  assert.equal(resolveEffectiveTheme('light', false), 'light');
});

test('Theme resolution logic: system mode reflects OS prefers-color-scheme', () => {
  assert.equal(resolveEffectiveTheme('system', true), 'dark');
  assert.equal(resolveEffectiveTheme('system', false), 'light');
});

test('Theme resolution logic: invalid/fallback mode defaults safely to dark', () => {
  assert.equal(resolveEffectiveTheme('unknown', true), 'dark');
  assert.equal(resolveEffectiveTheme(undefined, false), 'dark');
});

test('index.html contains zero-flash theme bootstrap script', () => {
  const htmlPath = path.join(repoRoot, 'index.html');
  const content = fs.readFileSync(htmlPath, 'utf8');

  assert.ok(
    content.includes('localStorage.getItem(\'auralis_theme\')') ||
    content.includes('localStorage.getItem("auralis_theme")'),
    'index.html must check stored theme preference',
  );
  assert.ok(
    content.includes('prefers-color-scheme: dark'),
    'index.html must check OS color scheme preference',
  );
  assert.ok(
    content.includes('classList.add(\'dark\')') ||
    content.includes('classList.add("dark")'),
    'index.html must apply dark class',
  );
  assert.ok(
    content.includes('setAttribute(\'data-theme\''),
    'index.html must set data-theme attribute',
  );
});

test('src/index.css defines comprehensive semantic theme tokens for both light and dark modes', () => {
  const cssPath = path.join(repoRoot, 'src', 'index.css');
  const content = fs.readFileSync(cssPath, 'utf8');

  // Verify :root tokens (Light mode defaults)
  const requiredTokens = [
    '--bg-base',
    '--bg-surface',
    '--bg-surface-elevated',
    '--bg-surface-hover',
    '--bg-card',
    '--bg-card-hover',
    '--bg-input',
    '--bg-input-focus',
    '--bg-header',
    '--bg-sidebar',
    '--bg-nav',
    '--bg-player-bar',
    '--bg-modal',
    '--bg-popover',
    '--bg-glass',
    '--text-primary',
    '--text-secondary',
    '--text-muted',
    '--text-subtle',
    '--text-inverse',
    '--border-subtle',
    '--border-medium',
    '--border-strong',
    '--accent-lime',
  ];

  for (const token of requiredTokens) {
    assert.ok(
      content.includes(token),
      `CSS must define semantic token ${token}`,
    );
  }

  // Verify dark mode class selector exists and redefines tokens
  assert.ok(
    content.includes('.dark') || content.includes('[data-theme="dark"]'),
    'CSS must include dark mode token overrides',
  );
});

test('src/index.css no longer hardcodes a purple accent', () => {
  const content = fs.readFileSync(path.join(repoRoot, 'src', 'index.css'), 'utf8');
  const declarations = content.replace(/\/\*[\s\S]*?\*\//g, '');

  assert.ok(
    !declarations.includes('--accent-purple'),
    'the fixed purple accent token must be gone; the accent now comes from the Material 3 palette',
  );
  assert.ok(
    !/#7c3aed|#8b5cf6|#a855f7/i.test(declarations),
    'no violet literals may remain in the stylesheet',
  );
});

test('src/index.css publishes a fallback value for every Material 3 role in both modes', () => {
  const content = fs.readFileSync(path.join(repoRoot, 'src', 'index.css'), 'utf8');

  // Same list the palette generator writes at runtime. Static defaults must
  // exist so the app is fully styled before any artwork has been sampled.
  const roles = [
    '--m3-primary',
    '--m3-primary-hover',
    '--m3-on-primary',
    '--m3-primary-container',
    '--m3-primary-container-hover',
    '--m3-on-primary-container',
    '--m3-secondary-container',
    '--m3-on-secondary-container',
    '--m3-outline',
    '--m3-outline-variant',
    '--m3-primary-08',
    '--m3-primary-12',
    '--m3-primary-16',
    '--m3-primary-24',
    '--m3-primary-40',
    '--m3-surface-tint',
    '--m3-player-tint',
  ];

  const darkIndex = content.search(/\.dark,\s*\[data-theme="dark"\]/);
  assert.ok(darkIndex > 0, 'CSS must contain a dark mode block');
  const lightBlock = content.slice(0, darkIndex);
  const darkBlock = content.slice(darkIndex);

  for (const role of roles) {
    // `\b` will not do here: --m3-primary is a prefix of --m3-primary-hover.
    const declared = new RegExp(`${role}:\\s*[^;]+;`);
    assert.ok(declared.test(lightBlock), `light mode must define ${role}`);
    assert.ok(declared.test(darkBlock), `dark mode must define ${role}`);
  }
});

test('src/index.css drives range inputs from the Material 3 accent', () => {
  const content = fs.readFileSync(path.join(repoRoot, 'src', 'index.css'), 'utf8');

  assert.ok(
    /accent-color:\s*var\(--m3-primary\)/.test(content),
    'sliders must take their accent from --m3-primary, not a fixed colour',
  );
});

test('src/index.css contains no continuously animated blur or filter', () => {
  const content = fs.readFileSync(path.join(repoRoot, 'src', 'index.css'), 'utf8');

  assert.ok(
    !content.includes('ambientPulse'),
    'the ambient blob keyframes must stay deleted (per-frame blur is too expensive on mobile)',
  );

  // Any `animation:` shorthand that targets a blurred element is a per-frame
  // filter rasterisation. Guard the whole file rather than one keyframe name.
  const keyframeNames = [...content.matchAll(/@keyframes\s+([\w-]+)/g)].map((m) => m[1]);
  for (const name of keyframeNames) {
    const body = content.slice(content.indexOf(`@keyframes ${name}`));
    const block = body.slice(0, body.indexOf('\n}') + 2);
    assert.ok(
      !/filter:\s*blur|backdrop-filter/.test(block),
      `@keyframes ${name} must not animate a blur`,
    );
  }
});

test('the Material 3 gloss overlay is a static sheen, not an animation', () => {
  const content = fs.readFileSync(path.join(repoRoot, 'src', 'index.css'), 'utf8');

  assert.ok(content.includes('.m3-gloss'), 'CSS must define the .m3-gloss overlay');
  const start = content.indexOf('.m3-gloss');
  const glossSection = content.slice(start, start + 2000);
  assert.ok(
    !/animation:/.test(glossSection),
    '.m3-gloss must not animate — it is a still specular highlight',
  );
});

test('no component hardcodes the old purple or olive accent literals', () => {
  const srcRoot = path.join(repoRoot, 'src');
  const files = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir)) {
      const full = path.join(dir, entry);
      if (fs.statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.tsx')) files.push(full);
    }
  };
  walk(srcRoot);
  assert.ok(files.length > 10, 'expected to find the component tree');

  // The two halves of the old hardcoded accent pair.
  const banned = /(?:bg|text|border|border-t|stroke|fill|ring|from|to|via)-purple-\d|\[#dbe7b5\]|\[#c9d79e\]|\[#14190c\]/;

  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const offenders = source
      .split('\n')
      .map((line, i) => [i + 1, line])
      .filter(([, line]) => banned.test(line));
    assert.equal(
      offenders.length,
      0,
      `${path.relative(repoRoot, file)} still hardcodes the old accent on line(s) ${offenders
        .map(([n]) => n)
        .join(', ')}`,
    );
  }
});

test('src/index.css keeps body text at WCAG AA against the base surface in both modes', () => {
  const content = fs.readFileSync(path.join(repoRoot, 'src', 'index.css'), 'utf8');

  const darkIndex = content.search(/\.dark,\s*\[data-theme="dark"\]/);
  const blocks = { light: content.slice(0, darkIndex), dark: content.slice(darkIndex) };

  const read = (block, token) => {
    const m = new RegExp(`${token}:\\s*([^;]+);`).exec(block);
    assert.ok(m, `${token} must be declared`);
    return m[1].trim();
  };

  for (const [mode, block] of Object.entries(blocks)) {
    const base = read(block, '--bg-base');
    // 4.5:1 is AA for normal text; --text-muted is the smallest text that uses
    // a reduced-emphasis colour (the unselected bottom-nav labels).
    for (const [token, min] of [['--text-primary', 7], ['--text-secondary', 4.5], ['--text-muted', 4.5]]) {
      const ratio = contrastRatio(read(block, token), base);
      assert.ok(
        ratio >= min,
        `${mode}: ${token} on --bg-base is ${ratio.toFixed(2)}:1, below the ${min}:1 floor`,
      );
    }
  }
});

test('src/types/music.ts declares ThemeMode union type and updates PlayerSettings', () => {
  const typesPath = path.join(repoRoot, 'src', 'types', 'music.ts');
  const content = fs.readFileSync(typesPath, 'utf8');

  assert.ok(
    content.includes('type ThemeMode'),
    'music.ts must export ThemeMode type',
  );
  assert.ok(
    content.includes("'dark'") && content.includes("'light'") && content.includes("'system'"),
    "ThemeMode must support 'dark' | 'light' | 'system'",
  );
  assert.ok(
    content.includes('theme: ThemeMode'),
    'PlayerSettings must include theme: ThemeMode',
  );
});

test('src/context/PlayerContext.tsx exposes theme, effectiveTheme, setTheme with matchMedia listener', () => {
  const contextPath = path.join(repoRoot, 'src', 'context', 'PlayerContext.tsx');
  const content = fs.readFileSync(contextPath, 'utf8');

  assert.ok(content.includes('theme: ThemeMode'), 'PlayerContext must expose theme state');
  assert.ok(content.includes('effectiveTheme: \'dark\' | \'light\''), 'PlayerContext must expose effectiveTheme');
  assert.ok(content.includes('setTheme: (theme: ThemeMode) => void'), 'PlayerContext must expose setTheme function');
  assert.ok(content.includes('matchMedia'), 'PlayerContext must listen for OS theme changes');
  assert.ok(content.includes('auralis_theme'), 'PlayerContext must persist theme to auralis_theme in localStorage');
});

test('Header component includes accessible Theme Selector menu', () => {
  const headerPath = path.join(repoRoot, 'src', 'components', 'common', 'Header.tsx');
  const content = fs.readFileSync(headerPath, 'utf8');

  assert.ok(content.includes('theme'), 'Header must consume theme from PlayerContext');
  assert.ok(content.includes('setTheme'), 'Header must consume setTheme from PlayerContext');
  assert.ok(content.includes('isThemeMenuOpen'), 'Header must contain theme menu toggle state');
  assert.ok(content.includes('Sun') && content.includes('Moon') && content.includes('Monitor'), 'Header must display Moon, Sun, and Monitor icons');
});
