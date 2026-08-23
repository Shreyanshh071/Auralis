/**
 * One-shot codemod: replace the purple/olive hardcoded Tailwind palette with the
 * Material 3 CSS custom properties published by src/lib/materialPalette.ts.
 *
 * Not part of `npm test`. Kept in the repo only so the mapping is auditable.
 */
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** Dark-mode olive literals that only existed to pair with a light purple. */
const OLIVE =
  '(?:dbe7b5|c9d79e|14190c|161c0d|191f0f|171b11|1b200f|171e0d|27311d|252d19|3a4727|59693d|6c7f4a|232a19|f0f4dc|f3f7d8|3c472a|25301a)';

/** Ordered: composite (light + dark) patterns first, then the single tokens. */
const RULES = [
  // --- composites that need their dark half read before it is stripped ------
  [
    'bg-purple-600 hover:bg-purple-700 text-white dark:bg-[#59693d] dark:hover:bg-[#6c7f4a] dark:text-[#14190c]',
    'bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-[var(--m3-on-primary)]',
  ],
  ['text-white dark:text-[#f3f7d8]', 'text-[var(--m3-on-primary)]'],
  [
    'bg-purple-700 text-white dark:bg-[#c9d79e] dark:text-[#161c0d] border-purple-600 dark:border-[#dbe7b5]',
    'bg-[var(--m3-primary)] text-[var(--m3-on-primary)] border-[var(--m3-primary)]',
  ],
  ['bg-white dark:bg-[#161c0d] translate-x-5', 'bg-[var(--m3-on-primary)] translate-x-5'],
  [
    'bg-gradient-to-tr from-purple-600 to-emerald-500 flex items-center justify-center text-xs font-bold text-white',
    'bg-[var(--m3-primary)] flex items-center justify-center text-xs font-bold text-[var(--m3-on-primary)]',
  ],

  // --- tonal containers: a tinted surface plus its own on-colour ------------
  [
    'bg-purple-500/10 text-purple-500 dark:text-purple-400 border border-purple-500/20',
    'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)] border border-[var(--m3-outline-variant)]',
  ],
  [
    'bg-purple-500/10 border border-purple-500/30 dark:bg-[#252d19] dark:border-[#3a4727]',
    'bg-[var(--m3-secondary-container)] border border-[var(--m3-outline-variant)]',
  ],
  [
    'bg-purple-500/10 dark:bg-[#dbe7b5]/10 text-purple-600 dark:text-[#dbe7b5]',
    'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]',
  ],
  ['bg-purple-500/15 dark:bg-[#27311d]', 'bg-[var(--m3-secondary-container)]'],
  [
    'bg-purple-500/25 text-[10px] font-bold text-purple-300',
    'bg-[var(--m3-secondary-container)] text-[10px] font-bold text-[var(--m3-on-secondary-container)]',
  ],
  [
    'bg-purple-500/20 text-purple-300',
    'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]',
  ],
  [
    'bg-purple-500/20 text-purple-400 border border-purple-500/30',
    'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)] border border-[var(--m3-outline-variant)]',
  ],

  // --- strip the dark olive overrides; the M3 tokens are theme-aware -------
  [new RegExp(`\\s+dark:(?:group-hover:|hover:|focus:|active:)?(?:bg|text|border|border-t|stroke|fill|ring|from|to|via)-\\[#${OLIVE}\\](?:\\/\\d+)?`, 'g'), ''],
  [/\s+dark:text-purple-400/g, ''],

  // --- state layers (opacity ramp -> the published alpha tokens) -----------
  ['bg-purple-600/30', 'bg-[var(--m3-primary-24)]'],
  ['bg-purple-600/25', 'bg-[var(--m3-primary-24)]'],
  ['bg-purple-600/20', 'bg-[var(--m3-primary-16)]'],
  ['hover:bg-purple-500/25', 'hover:bg-[var(--m3-primary-24)]'],
  ['bg-purple-500/25', 'bg-[var(--m3-primary-24)]'],
  ['bg-purple-500/20', 'bg-[var(--m3-primary-16)]'],
  ['bg-purple-500/15', 'bg-[var(--m3-primary-12)]'],
  ['bg-purple-500/10', 'bg-[var(--m3-primary-08)]'],

  // --- fills ---------------------------------------------------------------
  ['hover:bg-purple-700', 'hover:bg-[var(--m3-primary-hover)]'],
  ['hover:bg-purple-500', 'hover:bg-[var(--m3-primary-hover)]'],
  ['bg-purple-700', 'bg-[var(--m3-primary)]'],
  ['bg-purple-600', 'bg-[var(--m3-primary)]'],
  ['bg-purple-500', 'bg-[var(--m3-primary)]'],

  // --- foregrounds ---------------------------------------------------------
  ['group-hover:text-purple-500', 'group-hover:text-[var(--m3-primary)]'],
  ['group-hover:text-purple-400', 'group-hover:text-[var(--m3-primary)]'],
  ['hover:text-purple-300', 'hover:text-[var(--m3-primary-hover)]'],
  ['text-purple-500/70', 'text-[var(--m3-primary)]'],
  ['text-purple-500/60', 'text-[var(--m3-primary)]'],
  ['text-purple-600', 'text-[var(--m3-primary)]'],
  ['text-purple-500', 'text-[var(--m3-primary)]'],
  ['text-purple-400', 'text-[var(--m3-primary)]'],
  ['text-purple-300', 'text-[var(--m3-on-secondary-container)]'],

  // --- outlines ------------------------------------------------------------
  ['group-hover:border-purple-500/50', 'group-hover:border-[var(--m3-primary-40)]'],
  ['border-purple-500/50', 'border-[var(--m3-primary-40)]'],
  ['border-purple-500/40', 'border-[var(--m3-primary-40)]'],
  ['border-purple-500/30', 'border-[var(--m3-primary-24)]'],
  ['border-purple-500/20', 'border-[var(--m3-outline-variant)]'],
  ['focus:ring-purple-500/20', 'focus:ring-[var(--m3-primary-16)]'],
  ['hover:ring-purple-500/40', 'hover:ring-[var(--m3-primary-40)]'],
  ['focus:border-purple-500', 'focus:border-[var(--m3-primary)]'],
  ['border-t-purple-500', 'border-t-[var(--m3-primary)]'],
  ['border-purple-600', 'border-[var(--m3-primary)]'],
  ['border-purple-500', 'border-[var(--m3-primary)]'],
];

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (entry.endsWith('.tsx')) out.push(full);
  }
  return out;
}

let touched = 0;
for (const file of walk('src')) {
  const before = readFileSync(file, 'utf8');
  let after = before;
  for (const [from, to] of RULES) {
    after = typeof from === 'string' ? after.replaceAll(from, to) : after.replace(from, to);
  }
  // `text-white` is only wrong where it now sits on an M3 primary fill; on
  // artwork overlays it is still correct, so this is a per-line decision.
  after = after
    .split('\n')
    .map((line) =>
      line.includes('bg-[var(--m3-primary)]')
        ? line.replaceAll('text-white', 'text-[var(--m3-on-primary)]')
        : line,
    )
    .join('\n');
  if (after !== before) {
    writeFileSync(file, after);
    touched += 1;
    console.log('updated', file);
  }
}
console.log(`\n${touched} files updated`);
