/**
 * YouTube Account Sync Service
 *
 * Integrates with the YouTube Data API v3 to import the user's playlists,
 * liked songs, and subscriptions from their Google / YouTube account.
 *
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  MANUAL CONFIGURATION REQUIRED                                        ║
 * ║                                                                        ║
 * ║  This service requires the YouTube Data API v3 to be enabled in the   ║
 * ║  Google Cloud Console and the `youtube.readonly` scope to be added     ║
 * ║  to the OAuth consent screen. See .env.example for details.            ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * Architecture:
 * - The Google Sign-In flow (Firebase Auth) is extended with an additional
 *   `youtube.readonly` scope. This gives us a Google OAuth access token that
 *   can call the YouTube Data API.
 * - The access token is obtained from the Firebase Auth credential and stored
 *   in memory (not persisted to localStorage — it expires in ~1 hour anyway).
 * - Each import method accepts an access token and calls the YouTube Data API
 *   directly via `fetch()`.
 */

import { Capacitor } from '@capacitor/core';
import {
  GoogleAuthProvider,
  signInWithCredential,
  signInWithPopup,
  type Auth,
} from 'firebase/auth';
import type { Track, Playlist } from '../types/music';
import { cleanTitle } from './lyrics';

const YT_API_BASE = 'https://www.googleapis.com/youtube/v3';

/** Maximum items per page for YouTube API calls. */
const MAX_RESULTS = 50;

/** YouTube sync connection state, persisted to localStorage. */
export interface YouTubeConnectionState {
  /** Whether the user has ever successfully connected YouTube. */
  connected: boolean;
  /** The YouTube channel name, if known. */
  channelName?: string;
  /** Timestamp of the last successful import. */
  lastImportedAt?: number;
}

const STORAGE_KEY = 'auralis_youtube_sync';

// ─── Connection State ────────────────────────────────────────────────────────

export function getYouTubeConnectionState(): YouTubeConnectionState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { connected: false };
    return JSON.parse(raw);
  } catch {
    return { connected: false };
  }
}

export function setYouTubeConnectionState(state: YouTubeConnectionState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // localStorage unavailable — state will be lost on reload.
  }
}

export function clearYouTubeConnectionState(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore
  }
}

/**
 * Requests a Google OAuth access token with the `youtube.readonly` scope.
 * Works across Web (Firebase popup) and Native (Capacitor Google Auth).
 */
export async function requestYouTubeAccessToken(auth: Auth): Promise<string> {
  if (Capacitor.isNativePlatform()) {
    const { FirebaseAuthentication } = await import(
      '@capacitor-firebase/authentication'
    );
    const result = await FirebaseAuthentication.signInWithGoogle({
      scopes: ['https://www.googleapis.com/auth/youtube.readonly'],
      skipNativeAuth: true,
      customParameters: [{ key: 'prompt', value: 'consent' }],
    });

    const accessToken = result.credential?.accessToken;
    if (!accessToken) {
      throw new Error(
        'Google OAuth did not return an access token. ' +
          'The YouTube Data API v3 must be enabled and the youtube.readonly scope must be configured.',
      );
    }

    const idToken = result.credential?.idToken;
    if (idToken) {
      const credential = GoogleAuthProvider.credential(idToken, accessToken);
      await signInWithCredential(auth, credential);
    }

    return accessToken;
  }

  const provider = new GoogleAuthProvider();
  provider.addScope('https://www.googleapis.com/auth/youtube.readonly');
  provider.setCustomParameters({ prompt: 'consent' });

  const result = await signInWithPopup(auth, provider);
  const credential = GoogleAuthProvider.credentialFromResult(result);
  const accessToken = credential?.accessToken;

  if (!accessToken) {
    throw new Error(
      'Google OAuth did not return an access token. ' +
        'The YouTube Data API v3 must be enabled and the youtube.readonly scope must be configured.',
    );
  }

  return accessToken;
}

// ─── YouTube API Helpers ─────────────────────────────────────────────────────

/** Thrown when the YouTube Data API is not enabled or the access token lacks the required scope. */
export class YouTubeApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = 'YouTubeApiError';
    this.status = status;
  }
}

async function ytApiFetch<T>(
  endpoint: string,
  accessToken: string,
  params: Record<string, string> = {},
): Promise<T> {
  const url = new URL(`${YT_API_BASE}/${endpoint}`);
  for (const [k, v] of Object.entries(params)) {
    url.searchParams.set(k, v);
  }

  const res = await fetch(url.toString(), {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      Accept: 'application/json',
    },
    signal: AbortSignal.timeout(10000),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => '');
    let detail = '';
    try {
      const parsed = JSON.parse(body);
      detail = parsed?.error?.message || '';
    } catch {
      // Non-JSON response body
    }

    if (res.status === 403) {
      if (detail.toLowerCase().includes('quota')) {
        throw new YouTubeApiError(`YouTube Data API quota exceeded: ${detail}`, 403);
      }
      throw new YouTubeApiError(
        detail ||
          'YouTube Data API v3 is not enabled for this project, or the access token lacks the youtube.readonly scope. ' +
            'Enable the API in Google Cloud Console → APIs & Services → Library, and add the youtube.readonly scope to the OAuth consent screen.',
        403,
      );
    }
    if (res.status === 401) {
      throw new YouTubeApiError(
        detail ||
          'YouTube access token is expired or invalid. Please reconnect your YouTube account.',
        401,
      );
    }
    throw new YouTubeApiError(
      detail || `YouTube API returned ${res.status}: ${body.slice(0, 200)}`,
      res.status,
    );
  }

  return res.json() as Promise<T>;
}

// ─── YouTube Data Types ──────────────────────────────────────────────────────

interface YTPagedResponse<T> {
  items: T[];
  nextPageToken?: string;
  pageInfo?: { totalResults: number; resultsPerPage: number };
}

interface YTPlaylistItem {
  id: string;
  snippet: {
    title: string;
    description?: string;
    thumbnails?: { high?: { url: string }; medium?: { url: string }; default?: { url: string } };
    channelTitle?: string;
    resourceId?: { videoId: string };
    publishedAt?: string;
  };
  contentDetails?: {
    itemCount?: number;
    videoId?: string;
    videoPublishedAt?: string;
    duration?: string;
  };
  status?: { privacyStatus: string };
}

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Fetches the user's channel info (to display their YouTube name).
 */
export async function fetchYouTubeChannelInfo(
  accessToken: string,
): Promise<{ name: string; thumbnail?: string }> {
  const data = await ytApiFetch<YTPagedResponse<{
    snippet: { title: string; thumbnails?: { default?: { url: string } } };
  }>>(
    'channels',
    accessToken,
    { part: 'snippet', mine: 'true' },
  );

  const channel = data.items?.[0];
  if (!channel) throw new YouTubeApiError('No YouTube channel found for this account.', 404);

  return {
    name: channel.snippet.title,
    thumbnail: channel.snippet.thumbnails?.default?.url,
  };
}

/**
 * Fetches the user's playlists (public + private + unlisted).
 */
export async function fetchYouTubePlaylists(
  accessToken: string,
): Promise<Array<{ id: string; title: string; trackCount: number; thumbnail?: string }>> {
  const playlists: Array<{ id: string; title: string; trackCount: number; thumbnail?: string }> = [];
  let pageToken: string | undefined;

  do {
    const params: Record<string, string> = {
      part: 'snippet,contentDetails',
      mine: 'true',
      maxResults: String(MAX_RESULTS),
    };
    if (pageToken) params.pageToken = pageToken;

    const data = await ytApiFetch<YTPagedResponse<YTPlaylistItem>>('playlists', accessToken, params);

    for (const item of data.items) {
      playlists.push({
        id: item.id,
        title: item.snippet.title,
        trackCount: item.contentDetails?.itemCount ?? 0,
        thumbnail:
          item.snippet.thumbnails?.high?.url ??
          item.snippet.thumbnails?.medium?.url ??
          item.snippet.thumbnails?.default?.url,
      });
    }

    pageToken = data.nextPageToken;
  } while (pageToken && playlists.length < 200);

  return playlists;
}

/**
 * Fetches all items from a specific YouTube playlist and converts them to Tracks.
 */
export async function fetchYouTubePlaylistItems(
  accessToken: string,
  playlistId: string,
): Promise<Track[]> {
  const tracks: Track[] = [];
  let pageToken: string | undefined;

  do {
    const params: Record<string, string> = {
      part: 'snippet,contentDetails',
      playlistId,
      maxResults: String(MAX_RESULTS),
    };
    if (pageToken) params.pageToken = pageToken;

    const data = await ytApiFetch<YTPagedResponse<YTPlaylistItem>>('playlistItems', accessToken, params);

    for (const item of data.items) {
      const videoId = item.snippet?.resourceId?.videoId ?? item.contentDetails?.videoId;
      if (!videoId) continue;

      const rawTitle = item.snippet.title || 'Untitled';
      if (rawTitle === 'Deleted video' || rawTitle === 'Private video') continue;

      let artist = item.snippet.channelTitle || 'YouTube Artist';
      let title = cleanTitle(rawTitle) || rawTitle;

      if (rawTitle.includes(' - ')) {
        const parts = rawTitle.split(' - ');
        artist = parts[0].trim();
        title = cleanTitle(parts.slice(1).join(' - ')) || parts[1].trim();
      }

      const thumbnail =
        item.snippet.thumbnails?.high?.url ??
        item.snippet.thumbnails?.medium?.url ??
        `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;

      tracks.push({
        id: videoId,
        title,
        artist,
        album: 'YouTube',
        duration: 210, // Default — exact duration requires a separate videos.list call
        thumbnail,
        source: 'youtube',
      });
    }

    pageToken = data.nextPageToken;
  } while (pageToken && tracks.length < 500);

  return tracks;
}

/**
 * Fetches the user's liked songs (the "LL" playlist).
 */
export async function fetchYouTubeLikedSongs(accessToken: string): Promise<Track[]> {
  return fetchYouTubePlaylistItems(accessToken, 'LL');
}

/**
 * Imports a YouTube playlist as an Auralis Playlist.
 */
export async function importYouTubePlaylistAsAuralis(
  accessToken: string,
  ytPlaylist: { id: string; title: string; thumbnail?: string },
): Promise<Playlist> {
  const tracks = await fetchYouTubePlaylistItems(accessToken, ytPlaylist.id);

  return {
    id: `yt-sync-${ytPlaylist.id}-${Date.now()}`,
    title: ytPlaylist.title,
    description: `Imported from YouTube (${tracks.length} songs)`,
    cover: ytPlaylist.thumbnail || tracks[0]?.thumbnail,
    tracks,
    createdAt: Date.now(),
    isCustom: true,
  };
}

/**
 * Fetches the user's YouTube subscriptions (channels).
 */
export async function fetchYouTubeSubscriptions(
  accessToken: string,
): Promise<Array<{ channelId: string; title: string; thumbnail?: string }>> {
  const subs: Array<{ channelId: string; title: string; thumbnail?: string }> = [];
  let pageToken: string | undefined;

  do {
    const params: Record<string, string> = {
      part: 'snippet',
      mine: 'true',
      maxResults: String(MAX_RESULTS),
    };
    if (pageToken) params.pageToken = pageToken;

    const data = await ytApiFetch<YTPagedResponse<{
      snippet: {
        resourceId?: { channelId: string };
        title: string;
        thumbnails?: { default?: { url: string } };
      };
    }>>('subscriptions', accessToken, params);

    for (const item of data.items) {
      subs.push({
        channelId: item.snippet.resourceId?.channelId || '',
        title: item.snippet.title,
        thumbnail: item.snippet.thumbnails?.default?.url,
      });
    }

    pageToken = data.nextPageToken;
  } while (pageToken && subs.length < 200);

  return subs;
}
