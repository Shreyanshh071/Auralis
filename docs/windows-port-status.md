# Auralis for Windows — feature status

Status of every phone screen and action on Windows. **Working** = the phone's UI and logic run on
Windows. **Partly** = reachable, some parts missing. **Missing** = not reachable yet. Blockers are
the platform reasons a part cannot simply be reused; everything else is porting work.

Last updated: 2026-10-10 (after 0bde9dc).

## Main pages

| Feature | Windows | Notes / blockers |
|---|---|---|
| Home (Speed Dial, Quick picks, shelves, chips, pins) | Working | Listening-time shelves stay empty: Windows doesn't measure listen time yet (never estimated). Library artwork repair doesn't run (phone database + offline copies). |
| Search: results, typing suggestions, recent searches | Working | |
| Search: voice search / song recognition | Missing | Needs a desktop microphone adapter (javax.sound) for the recognizer. |
| Explore / Moods & genres pages | Missing | Pure UI + network; porting work. |
| Album, Artist pages | Working | |
| Library: playlists, Liked, Most played, albums, artists, playlist pages, sort, grid/list, New Playlist | Working | Most-played playlists need measured listens (see Stats). |
| Library: Downloads hub, offline folders | Missing | Phone uses its download manager + WorkManager; needs a desktop downloader. |
| Song options sheet | Working | Download entry says "not on Windows yet". |
| Playlist options sheet (edit, cover photo, export, delete) | Working | Playlist download not on Windows yet. |

## Player

| Feature | Windows | Notes / blockers |
|---|---|---|
| Playback, seek, volume, queue, media keys, Windows media overlay | Working | VLC (bundled) + loopback relay. |
| Word-synced lyrics (shared lyrics race, ExperimentalLyricsView) | Working | |
| Phone player designs (Classic, Immersive, Ambient), mini-player variants | Missing | Windows has its own player and bar; the phone views are large, porting work. |
| Lyrics provider sheet, manual lyrics search | Missing | Pure UI; porting work. |
| Lyric share card | Missing | Card renderer draws with android.graphics (Bitmap, StaticLayout) and saves to MediaStore; needs a Skia/Compose renderer and a file save. |
| Audio output sheet | Missing | Android audio routing/Bluetooth; a desktop version would list VLC output devices. |
| Liquid glass lens/blur | Partly | Shapes and fallbacks draw; the refraction shader is Android RenderEffect (desktop stubs). A Skia port is possible. |

## Account, Profile, social

| Feature | Windows | Notes / blockers |
|---|---|---|
| Profile sheet / Auralis hub | Missing | UI is portable. Sign-in uses the Firebase Android SDK + Credential Manager (Google), which don't exist on desktop. |
| Google sign-in, account sync | Missing | **Blocker:** Firebase Auth Android SDK. Desktop needs Firebase Auth over REST plus a Google OAuth "Desktop" client in the Firebase project's Google Cloud console (owner action). |
| Email sign-in | Missing | Possible via Firebase Auth REST (the web API key is in google-services.json). |
| Listen Together | Missing | **Blocker:** Firestore realtime listeners (Android SDK). Desktop needs Firestore REST + polling or a JVM Firestore client, and the account above. |
| Discord status | Missing | Phone uses Discord's Social SDK native library (Android). Windows can use Discord's local IPC (the desktop Discord app) with the same application ID; setting online/idle status needs the Social SDK and stays phone-only. |
| History sheet | Missing | Desktop history exists; UI is portable (privacy pause setting needed). |
| Stats, Wrapped | Missing | Needs measured listening events on desktop (playback controller can record them); UI is portable. |

## Imports

| Feature | Windows | Notes / blockers |
|---|---|---|
| YouTube Music / Spotify playlist link import | Working | From Library (shared view model). |
| Library backup export / import (JSON) | Working | |
| Import screen (account libraries) | Missing | **Blocker:** YouTube and Spotify sign-in run in an Android WebView and read its cookies. Compose Desktop has no WebView; needs an embedded browser (JCEF, ~100 MB) or a paste-cookie flow. |
| YouTube account sign-in (age-restricted songs) | Missing | Same WebView blocker. |

## Settings

| Page | Windows | Notes / blockers |
|---|---|---|
| Settings hub | Missing | Phone settings live in Jetpack DataStore files; DataStore Preferences runs on desktop JVM, so the stores can be shared. |
| Appearance, Theme & colours | Missing | Material You wallpaper colours are Android-only. |
| Player & audio | Missing | Gapless/skip silence/spatial are ExoPlayer features; VLC equivalents differ. |
| Content (language, region, proxy) | Missing | Per-app language uses Android LocaleManager. |
| AI lyrics translation | Missing | Portable (API keys + HTTP). |
| Privacy | Missing | Portable except clearing the phone database. |
| Storage | Missing | Cache sizes/clearing are platform paths. |
| Updater | Missing | Phone installs APKs; Windows would download the MSI. |
| About | Missing | Portable. |
| Open system app settings | n/a | Android only. |

## Onboarding

| Feature | Windows | Notes / blockers |
|---|---|---|
| Welcome / sign-in flow | Missing | Depends on Firebase sign-in (above). |
