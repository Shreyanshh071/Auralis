import type { LyricLine, LyricsData } from '../types/music';

export interface LanguageOption {
  code: string;
  name: string;
  nativeName: string;
}

export const SUPPORTED_LANGUAGES: LanguageOption[] = [
  { code: 'en', name: 'English', nativeName: 'English' },
  { code: 'es', name: 'Spanish', nativeName: 'Español' },
  { code: 'fr', name: 'French', nativeName: 'Français' },
  { code: 'de', name: 'German', nativeName: 'Deutsch' },
  { code: 'ja', name: 'Japanese', nativeName: '日本語' },
  { code: 'ko', name: 'Korean', nativeName: '한국어' },
  { code: 'hi', name: 'Hindi', nativeName: 'हिन्दी' },
  { code: 'pt', name: 'Portuguese', nativeName: 'Português' },
  { code: 'it', name: 'Italian', nativeName: 'Italiano' },
  { code: 'ru', name: 'Russian', nativeName: 'Русский' },
  { code: 'zh', name: 'Chinese', nativeName: '中文' },
  { code: 'ar', name: 'Arabic', nativeName: 'العربية' },
];

// Memory cache for line translations: key is `${targetLang}:${normalizedText}`
const lineCache = new Map<string, string>();

/** Decode HTML entities that some translation APIs return (e.g. &#39;, &quot;, &amp;) */
export function decodeHtmlEntities(str: string): string {
  if (!str) return '';
  return str
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#x2F;/g, '/')
    .replace(/&apos;/g, "'");
}

/** Check if text is non-translatable symbol or whitespace */
export function isNonTranslatable(text: string): boolean {
  if (!text) return true;
  const trimmed = text.trim();
  if (!trimmed || trimmed === '♪' || trimmed === '♫' || /^[\d\s.,/#!$%^&*;:{}=\-_`~()]+$/.test(trimmed)) {
    return true;
  }
  return false;
}

/**
 * Translate a single text string to the target language with fallback providers.
 * Returns null if translation is unavailable/failed without faking.
 */
export async function translateText(
  text: string,
  targetLang: string,
  timeoutMs = 4000
): Promise<string | null> {
  if (isNonTranslatable(text)) {
    return text;
  }

  const cleanText = text.trim();
  const cacheKey = `${targetLang.toLowerCase()}:${cleanText.toLowerCase()}`;
  const cached = lineCache.get(cacheKey);
  if (cached) return cached;

  const endpoints = [
    // 1. MyMemory API (public endpoint with Autodetect)
    `https://api.mymemory.translated.net/get?q=${encodeURIComponent(cleanText)}&langpair=Autodetect|${encodeURIComponent(targetLang)}`,
    // 2. Lingva primary
    `https://lingva.ml/api/v1/auto/${encodeURIComponent(targetLang)}/${encodeURIComponent(cleanText)}`,
    // 3. Lingva secondary fallback
    `https://translate.plausibility.cloud/api/v1/auto/${encodeURIComponent(targetLang)}/${encodeURIComponent(cleanText)}`,
  ];

  for (const url of endpoints) {
    try {
      const res = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!res.ok) continue;

      const data = await res.json();

      // Check MyMemory shape
      if (data?.responseData?.translatedText && typeof data.responseData.translatedText === 'string') {
        const translated = decodeHtmlEntities(data.responseData.translatedText).trim();
        // MyMemory returns uppercase error messages like "MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE BANDWIDTH"
        if (translated && !translated.startsWith('MYMEMORY WARNING:')) {
          lineCache.set(cacheKey, translated);
          return translated;
        }
      }

      // Check Lingva shape
      if (data?.translation && typeof data.translation === 'string') {
        const translated = decodeHtmlEntities(data.translation).trim();
        if (translated) {
          lineCache.set(cacheKey, translated);
          return translated;
        }
      }
    } catch {
      // Continue to next provider
    }
  }

  return null;
}

/**
 * Translate an array of synchronized LyricLines with batching and per-line caching.
 * Preserves timestamps, line order, words, and instrumental flags.
 */
export async function translateLyricLines(
  lines: LyricLine[],
  targetLang: string,
  onProgress?: (completed: number, total: number) => void
): Promise<LyricLine[]> {
  if (!lines || lines.length === 0) return [];

  const result: LyricLine[] = [];
  const BATCH_SIZE = 4;

  for (let i = 0; i < lines.length; i += BATCH_SIZE) {
    const batch = lines.slice(i, i + BATCH_SIZE);
    const translatedBatch = await Promise.all(
      batch.map(async (line) => {
        if (line.isInstrumental || isNonTranslatable(line.text)) {
          return { ...line };
        }
        const translated = await translateText(line.text, targetLang);
        return {
          ...line,
          translatedText: translated && translated.toLowerCase() !== line.text.toLowerCase() ? translated : undefined,
        };
      })
    );
    result.push(...translatedBatch);
    onProgress?.(Math.min(i + BATCH_SIZE, lines.length), lines.length);
  }

  return result;
}

/**
 * Translate plain (unsynced) lyrics line by line.
 */
export async function translatePlainLyrics(
  plain: string,
  targetLang: string
): Promise<string> {
  if (!plain) return '';
  const lines = plain.split('\n');
  const translatedLines: string[] = [];

  for (const line of lines) {
    if (isNonTranslatable(line)) {
      translatedLines.push(line);
      continue;
    }
    const translated = await translateText(line, targetLang);
    translatedLines.push(translated || line);
  }

  return translatedLines.join('\n');
}

/**
 * Translate full LyricsData object (either synced lines or plain text).
 */
export async function translateLyricsData(
  lyrics: LyricsData,
  targetLang: string
): Promise<LyricsData> {
  if (lyrics.syncType === 'plain' && lyrics.plainLyrics) {
    const translatedPlain = await translatePlainLyrics(lyrics.plainLyrics, targetLang);
    return {
      ...lyrics,
      translatedPlainLyrics: translatedPlain,
      translatedLanguage: targetLang,
    };
  }

  if (lyrics.lines.length > 0) {
    const translatedLines = await translateLyricLines(lyrics.lines, targetLang);
    return {
      ...lyrics,
      lines: translatedLines,
      translatedLanguage: targetLang,
    };
  }

  return lyrics;
}
