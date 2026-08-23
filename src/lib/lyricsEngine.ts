import type { LyricLine, LyricWord } from '../types/music';

/**
 * Pure Lyrics Timing Engine
 *
 * Deterministic algorithms for line indexing, word timestamp lookup,
 * and line progress calculation without React dependencies.
 */

/**
 * Find the active lyric line index for a given playback time and offset using binary search.
 * Returns -1 if playback is before the first timed line or if lines array is empty.
 */
export function findActiveLyricIndex(
  lines: readonly LyricLine[] | undefined | null,
  currentTime: number,
  offset: number = 0
): number {
  if (!lines || lines.length === 0) return -1;

  const adjustedTime = currentTime + offset;
  if (adjustedTime < lines[0].time) return -1;

  let low = 0;
  let high = lines.length - 1;
  let result = -1;

  while (low <= high) {
    const mid = (low + high) >> 1;
    if (lines[mid].time <= adjustedTime) {
      result = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  return result;
}

/**
 * Returns how many words in the line have elapsed by the current time.
 * Returns -1 if the line has no genuine word timestamps.
 */
export function getActiveWordCount(
  words: readonly LyricWord[] | undefined | null,
  currentTime: number,
  offset: number = 0
): number {
  if (!words || words.length === 0) return -1;

  const adjustedTime = currentTime + offset;
  let count = 0;

  for (let i = 0; i < words.length; i++) {
    if (adjustedTime >= words[i].time) {
      count++;
    } else {
      break;
    }
  }

  return count;
}

/**
 * Check whether an individual word timestamp has elapsed.
 */
export function isWordActive(
  wordTime: number,
  currentTime: number,
  offset: number = 0
): boolean {
  return currentTime + offset >= wordTime;
}

/**
 * Calculate normalized progress [0, 1] through the current line for smooth animation.
 */
export function getLineProgress(
  currentLine: LyricLine | undefined | null,
  nextLine: LyricLine | undefined | null,
  currentTime: number,
  offset: number = 0,
  defaultLineDuration: number = 4
): number {
  if (!currentLine) return 0;

  const adjustedTime = currentTime + offset;
  if (adjustedTime < currentLine.time) return 0;

  const lineDuration = nextLine
    ? Math.max(0.5, nextLine.time - currentLine.time)
    : defaultLineDuration;

  const progress = (adjustedTime - currentLine.time) / lineDuration;
  return Math.min(1, Math.max(0, progress));
}

/**
 * Check if there is an instrumental break or long silence before this line.
 */
export function isInstrumentalBreak(
  currentLine: LyricLine | undefined | null,
  prevLine: LyricLine | undefined | null,
  thresholdSeconds: number = 8
): boolean {
  if (!currentLine) return false;
  if (currentLine.isInstrumental) return true;
  if (!prevLine) return false;
  return currentLine.time - prevLine.time >= thresholdSeconds;
}
