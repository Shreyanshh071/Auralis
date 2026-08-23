import type { LyricLine, LyricWord } from '../types/music';

/**
 * Parse a TTML time expression (e.g., "00:03:14.571", "3:14.571", "27.173", "27s", "27173ms").
 * Returns time in seconds as a floating point number.
 */
export function parseTtmlTime(timeStr: string | null | undefined): number {
  if (!timeStr) return 0;
  const s = timeStr.trim();
  if (!s) return 0;

  // Handle explicit unit suffixes
  if (s.endsWith('ms')) {
    const val = parseFloat(s.slice(0, -2));
    return isNaN(val) ? 0 : val / 1000;
  }
  if (s.endsWith('s')) {
    const val = parseFloat(s.slice(0, -1));
    return isNaN(val) ? 0 : val;
  }

  // Handle colon-separated time: hh:mm:ss.ms or mm:ss.ms
  if (s.includes(':')) {
    const parts = s.split(':');
    if (parts.length === 3) {
      const h = parseFloat(parts[0]) || 0;
      const m = parseFloat(parts[1]) || 0;
      const sec = parseFloat(parts[2]) || 0;
      return h * 3600 + m * 60 + sec;
    }
    if (parts.length === 2) {
      const m = parseFloat(parts[0]) || 0;
      const sec = parseFloat(parts[1]) || 0;
      return m * 60 + sec;
    }
  }

  const num = parseFloat(s);
  return isNaN(num) ? 0 : num;
}

/**
 * Decode XML and HTML entities in text.
 */
export function decodeXmlEntities(text: string): string {
  if (!text) return '';
  return text
    .replace(/&apos;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#(\d+);/g, (_, code) => {
      const n = parseInt(code, 10);
      return isNaN(n) ? '' : String.fromCharCode(n);
    })
    .replace(/&#x([a-fA-F0-9]+);/g, (_, code) => {
      const n = parseInt(code, 16);
      return isNaN(n) ? '' : String.fromCharCode(n);
    });
}

export interface ParsedTtml {
  lines: LyricLine[];
  hasWordTiming: boolean;
  duration?: number;
}

/**
 * Parse W3C TTML XML string (e.g. from AMLL / Apple Music format) into LyricLine[] with LyricWord[] timing.
 * Guaranteed never to throw; returns { lines: [], hasWordTiming: false } on invalid input.
 */
export function parseTtml(xmlText: string): ParsedTtml {
  if (!xmlText || typeof xmlText !== 'string') {
    return { lines: [], hasWordTiming: false };
  }

  try {
    const lines: LyricLine[] = [];
    let foundWordTiming = false;

    // Optional duration from <body dur="..."> or <tt dur="...">
    let duration: number | undefined;
    const durMatch = xmlText.match(/<(?:body|tt)[^>]+dur="([^"]+)"/i);
    if (durMatch && durMatch[1]) {
      const parsedDur = parseTtmlTime(durMatch[1]);
      if (parsedDur > 0) duration = parsedDur;
    }

    // Match each <p> element (supports attributes and multi-line body)
    const pRegex = /<p\s+([^>]*?)>([\s\S]*?)<\/p>/gi;
    const spanRegex = /<span\s+([^>]*?)>([\s\S]*?)<\/span>/gi;

    let pMatch: RegExpExecArray | null;
    while ((pMatch = pRegex.exec(xmlText)) !== null) {
      const pAttrs = pMatch[1] || '';
      const pBody = pMatch[2] || '';

      const beginMatch = pAttrs.match(/begin="([^"]+)"/i);
      const lineBegin = beginMatch ? parseTtmlTime(beginMatch[1]) : null;

      const words: LyricWord[] = [];
      let fullLineText = '';

      // Check if <p> contains timed <span> elements
      spanRegex.lastIndex = 0;
      let sMatch: RegExpExecArray | null;
      let hasTimedSpans = false;

      while ((sMatch = spanRegex.exec(pBody)) !== null) {
        const sAttrs = sMatch[1] || '';
        const rawSpanText = decodeXmlEntities(sMatch[2] || '');

        const sBeginMatch = sAttrs.match(/begin="([^"]+)"/i);
        if (sBeginMatch) {
          hasTimedSpans = true;
          const wordTime = parseTtmlTime(sBeginMatch[1]);

          // Strip internal XML tags if any (e.g. nested ruby annotations)
          const textOnly = rawSpanText.replace(/<[^>]+>/g, '');
          fullLineText += textOnly;

          const trimmedWord = textOnly.trim();
          if (trimmedWord) {
            words.push({
              word: trimmedWord,
              time: wordTime,
            });
          }
        } else {
          fullLineText += rawSpanText.replace(/<[^>]+>/g, '');
        }
      }

      // If no timed spans found, fall back to plain text inside <p>
      if (!hasTimedSpans) {
        fullLineText = decodeXmlEntities(pBody.replace(/<[^>]+>/g, '')).trim();
      } else {
        fullLineText = fullLineText.trim();
        // If span texts had no inter-word spaces (e.g. "I've""been""tryna"), join words with spaces
        if (words.length > 1 && !fullLineText.includes(' ') && words.every(w => !w.word.includes(' '))) {
          fullLineText = words.map(w => w.word).join(' ');
        }
      }

      if (!fullLineText) continue;

      if (words.length > 0) {
        foundWordTiming = true;
      }

      // Determine line start time: line attribute, or first word timestamp
      const effectiveLineTime = lineBegin !== null ? lineBegin : (words.length > 0 ? words[0].time : 0);

      const isInstrumental =
        /^\(?(instrumental|solo|outro|intro|guitar|synth|interlude)\)?$/i.test(fullLineText) ||
        (fullLineText.startsWith('(') && fullLineText.endsWith(')') && /solo|instrumental|intro|outro|groove|break/i.test(fullLineText));

      lines.push({
        time: effectiveLineTime,
        text: fullLineText,
        words: words.length > 0 ? words : undefined,
        isInstrumental: isInstrumental || undefined,
      });
    }

    lines.sort((a, b) => a.time - b.time);

    return {
      lines,
      hasWordTiming: foundWordTiming,
      duration,
    };
  } catch {
    return { lines: [], hasWordTiming: false };
  }
}
