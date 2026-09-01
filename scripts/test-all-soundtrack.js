async function test() {
  const songs = [
    { title: 'Deva Deva (From "Brahmastra")', artist: 'Arijit Singh, Pritam, Amitabh Bhattacharya', cleanTitle: 'Deva Deva', primaryArtist: 'Arijit Singh', dur: 279 },
    { title: 'Kesariya (From "Brahmastra")', artist: 'Pritam, Arijit Singh, Amitabh Bhattacharya', cleanTitle: 'Kesariya', primaryArtist: 'Pritam', dur: 268 },
    { title: 'Chaleya (From "Jawan")', artist: 'Anirudh Ravichander, Arijit Singh, Shilpa Rao', cleanTitle: 'Chaleya', primaryArtist: 'Anirudh Ravichander', dur: 200 }
  ];

  for (const s of songs) {
    console.log(`\nTesting: ${s.cleanTitle} by ${s.primaryArtist}`);
    
    // 1. LRCLib exact
    try {
      const url = `https://lrclib.net/api/get?track_name=${encodeURIComponent(s.cleanTitle)}&artist_name=${encodeURIComponent(s.primaryArtist)}&duration=${s.dur}`;
      const res = await fetch(url, { headers: { 'User-Agent': 'Auralis-Music-Android/2.0.0' } });
      const data = await res.json();
      console.log(`[LRCLib exact] status=${res.status}, hasSynced=${Boolean(data.syncedLyrics)}`);
    } catch (e) {
      console.log(`[LRCLib exact] error: ${e.message}`);
    }

    // 2. LRCLib search
    try {
      const url = `https://lrclib.net/api/search?q=${encodeURIComponent(s.cleanTitle + ' ' + s.primaryArtist)}`;
      const res = await fetch(url, { headers: { 'User-Agent': 'Auralis-Music-Android/2.0.0' } });
      const data = await res.json();
      const match = data.find(x => x.syncedLyrics);
      console.log(`[LRCLib search] matches=${data.length}, bestSynced=${match ? match.trackName + ' - ' + match.artistName : 'none'}`);
    } catch (e) {
      console.log(`[LRCLib search] error: ${e.message}`);
    }

    // 3. JioSaavn search
    try {
      const q = encodeURIComponent(s.cleanTitle + ' ' + s.primaryArtist);
      const url = `https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&ctx=android&n=5&p=1&q=${q}`;
      const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14)' } });
      const data = await res.json();
      const results = data.results || [];
      console.log(`[JioSaavn search] results=${results.length}, first=${results[0]?.title}`);
    } catch (e) {
      console.log(`[JioSaavn search] error: ${e.message}`);
    }
  }
}
test();
