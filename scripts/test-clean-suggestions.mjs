// Test Clean Music Suggestions Filter

const NON_MUSIC_TERMS = /\b(game|games|gameplay|gaming|minecraft|roblox|gta|vlog|vlogs|challenge|reaction|reactions|funny moments|walkthrough|episode|episodes|fears to fathom|subnautica|god of war|granny|horror|horror game|shorts|stream|streamer|live stream|unboxing|prank|meme)\b/i;

function cleanMusicSuggestions(query, rawList) {
  const qClean = query.trim().toLowerCase();
  const filtered = rawList.filter((s) => {
    if (!s || typeof s !== 'string') return false;
    if (NON_MUSIC_TERMS.test(s)) return false;
    return true;
  });

  const results = [];
  results.push(query.trim());

  for (const s of filtered) {
    if (s.toLowerCase() !== qClean && !results.includes(s)) {
      results.push(s);
    }
  }

  // If gaming terms pruned most suggestions, supplement with music-specific completions
  if (results.length < 3) {
    const musicFallbacks = [`${query.trim()} songs`, `${query.trim()} music`, `${query.trim()} playlist`];
    for (const fb of musicFallbacks) {
      if (!results.includes(fb) && results.length < 6) {
        results.push(fb);
      }
    }
  }

  return results.slice(0, 6);
}

async function test(query) {
  const res = await fetch('https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
      'Referer': 'https://music.youtube.com/',
      'Origin': 'https://music.youtube.com',
    },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
      input: query,
    }),
  });
  const data = await res.json();
  const rawList = [];
  const contents = data.contents?.[0]?.searchSuggestionsSectionRenderer?.contents || [];
  for (const item of contents) {
    const s = item.searchSuggestionRenderer?.suggestion?.runs?.map((r) => r.text).join('');
    if (s) rawList.push(s);
  }
  console.log(`Processed music suggestions for '${query}':`, cleanMusicSuggestions(query, rawList));
}

test('beastboyshub');
test('the weeknd');
test('starboy');
