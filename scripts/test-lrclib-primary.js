async function test() {
  const cleanTitle = 'Deva Deva';
  const primaryArtist = 'Arijit Singh';

  const exactUrl = 'https://lrclib.net/api/get?track_name=' + encodeURIComponent(cleanTitle) + '&artist_name=' + encodeURIComponent(primaryArtist) + '&duration=279';
  const exactRes = await fetch(exactUrl, { headers: { 'User-Agent': 'Auralis-Music-Android/2.0.0' } });
  const exactJson = await exactRes.json();
  console.log('Exact GET result:', { id: exactJson.id, trackName: exactJson.trackName, artistName: exactJson.artistName, hasSynced: Boolean(exactJson.syncedLyrics) });

  const searchUrl = 'https://lrclib.net/api/search?q=' + encodeURIComponent(cleanTitle + ' ' + primaryArtist);
  const searchRes = await fetch(searchUrl, { headers: { 'User-Agent': 'Auralis-Music-Android/2.0.0' } });
  const searchJson = await searchRes.json();
  console.log('Search matches count:', searchJson.length);
  const syncedMatch = searchJson.find(x => x.syncedLyrics);
  console.log('Synced match:', { trackName: syncedMatch?.trackName, artistName: syncedMatch?.artistName, hasSynced: Boolean(syncedMatch?.syncedLyrics) });
}
test();
