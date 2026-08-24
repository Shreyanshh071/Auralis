// Test YouTube Music InnerTube Parser

async function testParseYTM(query) {
  const res = await fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
      'Referer': 'https://music.youtube.com/',
      'Origin': 'https://music.youtube.com',
    },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
      query,
    }),
  });
  const data = await res.json();
  const sections = data.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];

  const songs = [];
  const artists = [];
  const playlists = [];
  const seenSongIds = new Set();
  const seenArtistIds = new Set();
  const seenPlaylistIds = new Set();

  function parseFlexItem(flex) {
    if (!flex) return;
    const col0Runs = flex.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
    const col1Runs = flex.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
    const title = col0Runs[0]?.text || '';
    const subText = col1Runs.map((r) => r.text).join('');
    const subParts = subText.split('•').map((s) => s.trim());
    const itemType = subParts[0]?.toLowerCase() || '';

    const nav = flex.navigationEndpoint;
    const browseId = nav?.browseEndpoint?.browseId || col0Runs[0]?.navigationEndpoint?.browseEndpoint?.browseId;
    const videoId =
      flex.playlistItemData?.videoId ||
      flex.doubleTapCommand?.watchEndpoint?.videoId ||
      flex.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId ||
      col0Runs[0]?.navigationEndpoint?.watchEndpoint?.videoId ||
      nav?.watchEndpoint?.videoId;

    const thumbs = flex.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
    const thumbUrl = thumbs.length > 0 ? thumbs[thumbs.length - 1]?.url : (videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : '');

    if (itemType.includes('artist') || (browseId && browseId.startsWith('UC') && !videoId)) {
      if (title && !seenArtistIds.has(title.toLowerCase())) {
        seenArtistIds.add(title.toLowerCase());
        artists.push({
          id: browseId || `yt:${title}`,
          name: title,
          thumbnail: thumbUrl || undefined,
          subscribers: subParts.find((s) => /subscribers|audience/i.test(s)),
          query: `${title} top songs`,
        });
      }
      return;
    }

    if (
      itemType.includes('album') ||
      itemType.includes('ep') ||
      itemType.includes('single') ||
      itemType.includes('playlist') ||
      (browseId && (browseId.startsWith('MPRE') || browseId.startsWith('VL') || browseId.startsWith('PL')))
    ) {
      if (title && !seenPlaylistIds.has(title.toLowerCase())) {
        seenPlaylistIds.add(title.toLowerCase());
        const author = subParts.length > 1 && !/^\d{4}$/.test(subParts[1]) ? subParts[1] : undefined;
        playlists.push({
          id: browseId || `pl:${title}`,
          title,
          thumbnail: thumbUrl || undefined,
          author,
          trackCount: undefined,
        });
      }
      return;
    }

    if (videoId && title && !seenSongIds.has(videoId)) {
      seenSongIds.add(videoId);
      let artist = 'YouTube Artist';
      if (subParts.length >= 2) {
        artist = subParts[1];
      } else if (col1Runs.length > 0) {
        const artistRun = col1Runs.find((r) => r.navigationEndpoint?.browseEndpoint?.browseId?.startsWith('UC'));
        if (artistRun) artist = artistRun.text;
      }
      let duration = 200;
      const durStr = subParts.find((s) => /^\d+:\d+$/.test(s));
      if (durStr) {
        const [m, s] = durStr.split(':').map(Number);
        duration = m * 60 + s;
      }
      songs.push({
        id: videoId,
        title,
        artist,
        duration,
        thumbnail: thumbUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
        source: 'youtube',
      });
    }
  }

  for (const sec of sections) {
    if (sec.musicCardShelfRenderer) {
      const card = sec.musicCardShelfRenderer;
      const title = card.title?.runs?.[0]?.text;
      const subText = card.subtitle?.runs?.map((r) => r.text).join('') || '';
      const subParts = subText.split('•').map((s) => s.trim());
      const cardType = subParts[0]?.toLowerCase() || '';

      const thumbs = card.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
      const thumbUrl = thumbs.length > 0 ? thumbs[thumbs.length - 1]?.url : '';

      if (cardType.includes('artist')) {
        if (title && !seenArtistIds.has(title.toLowerCase())) {
          seenArtistIds.add(title.toLowerCase());
          artists.unshift({
            id: card.onTap?.browseEndpoint?.browseId || `yt:${title}`,
            name: title,
            thumbnail: thumbUrl || undefined,
            subscribers: subParts.find((s) => /subscribers|audience/i.test(s)),
            query: `${title} top songs`,
          });
        }
      } else if (cardType.includes('album') || cardType.includes('playlist')) {
        if (title && !seenPlaylistIds.has(title.toLowerCase())) {
          seenPlaylistIds.add(title.toLowerCase());
          playlists.unshift({
            id: card.onTap?.browseEndpoint?.browseId || `pl:${title}`,
            title,
            thumbnail: thumbUrl || undefined,
            author: subParts[1],
            trackCount: undefined,
          });
        }
      } else {
        const videoId =
          card.onTap?.watchEndpoint?.videoId ||
          card.buttons?.[0]?.buttonRenderer?.command?.watchEndpoint?.videoId ||
          card.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.[0]?.url?.match(/\/vi\/([^\/]+)/)?.[1];
        if (videoId && title && !seenSongIds.has(videoId)) {
          seenSongIds.add(videoId);
          let artist = subParts[1] || 'YouTube Artist';
          let duration = 200;
          const durStr = subParts.find((s) => /^\d+:\d+$/.test(s));
          if (durStr) {
            const [m, s] = durStr.split(':').map(Number);
            duration = m * 60 + s;
          }
          songs.unshift({
            id: videoId,
            title,
            artist,
            duration,
            thumbnail: thumbUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
            source: 'youtube',
          });
        }
      }
    }

    if (sec.musicShelfRenderer) {
      for (const item of sec.musicShelfRenderer.contents || []) {
        parseFlexItem(item.musicResponsiveListItemRenderer);
      }
    }

    if (sec.itemSectionRenderer) {
      for (const item of sec.itemSectionRenderer.contents || []) {
        parseFlexItem(item.musicResponsiveListItemRenderer);
      }
    }
  }

  console.log(`Results for '${query}': ${songs.length} songs, ${artists.length} artists, ${playlists.length} albums/playlists`);
  console.log('Top 3 songs:', songs.slice(0, 3));
  console.log('Top 2 artists:', artists.slice(0, 2));
  console.log('Top 2 albums/playlists:', playlists.slice(0, 2));
}

testParseYTM('the weeknd');
testParseYTM('blinding lights');
