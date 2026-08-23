/**
 * High-Precision Interpolating Playback Clock
 *
 * Provides smooth, sub-frame playback time interpolation between YouTube IFrame
 * `getCurrentTime()` polling ticks. This eliminates jitter and enables 60fps lyrics
 * synchronization and animations without needing high-frequency React state updates.
 */

export class PlaybackClock {
  private anchorTime: number = 0;
  private anchorTimestamp: number = 0;
  private playbackRate: number = 1;
  private isPlaying: boolean = false;
  private duration: number = 0;

  constructor() {
    this.anchorTimestamp = typeof performance !== 'undefined' ? performance.now() : Date.now();
  }

  /**
   * Update the clock with an authoritative timestamp from the media player.
   */
  public updateAnchor(time: number, isPlaying: boolean, rate: number = 1, duration: number = 0): void {
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    this.anchorTime = Math.max(0, time);
    this.anchorTimestamp = now;
    this.isPlaying = isPlaying;
    this.playbackRate = rate > 0 ? rate : 1;
    if (duration > 0) {
      this.duration = duration;
    }
  }

  /**
   * Update playing state without drifting anchor position.
   */
  public setPlaying(isPlaying: boolean): void {
    if (this.isPlaying === isPlaying) return;
    const current = this.getCurrentInterpolatedTime();
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    this.anchorTime = current;
    this.anchorTimestamp = now;
    this.isPlaying = isPlaying;
  }

  /**
   * Update playback rate scaling smoothly.
   */
  public setPlaybackRate(rate: number): void {
    if (rate <= 0 || this.playbackRate === rate) return;
    const current = this.getCurrentInterpolatedTime();
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    this.anchorTime = current;
    this.anchorTimestamp = now;
    this.playbackRate = rate;
  }

  /**
   * Handle user seek jumps.
   */
  public seekTo(time: number): void {
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    this.anchorTime = Math.max(0, time);
    this.anchorTimestamp = now;
  }

  /**
   * Calculate current high-precision interpolated playback time in seconds.
   */
  public getCurrentInterpolatedTime(): number {
    if (!this.isPlaying) {
      return this.anchorTime;
    }
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    const elapsedMs = Math.max(0, now - this.anchorTimestamp);
    const elapsedSec = (elapsedMs / 1000) * this.playbackRate;
    const estimated = this.anchorTime + elapsedSec;

    if (this.duration > 0 && estimated > this.duration) {
      return this.duration;
    }
    return Math.max(0, estimated);
  }

  /**
   * Reset clock state (e.g. on track change).
   */
  public reset(): void {
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    this.anchorTime = 0;
    this.anchorTimestamp = now;
    this.playbackRate = 1;
    this.isPlaying = false;
    this.duration = 0;
  }
}

export const globalPlaybackClock = new PlaybackClock();
