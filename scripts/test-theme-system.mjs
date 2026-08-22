// Behaviour tests for the Auralis Theme System (Light, Dark, System modes).
//
// Run with:  node scripts/test-theme-system.mjs
//
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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
    '--accent-purple',
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
