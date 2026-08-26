import https from 'https';

async function fetchJson(url, headers = {}) {
  return new Promise((resolve, reject) => {
    https.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://spotifydown.com/',
        'Origin': 'https://spotifydown.com/',
        ...headers
      }
    }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, data: JSON.parse(data) });
        } catch (e) {
          resolve({ status: res.statusCode, raw: data });
        }
      });
    }).on('error', reject);
  });
}

async function run() {
  const playlistId = "37i9dQZF1DXcBWIGoYBM5M";
  console.log(`Testing api.spotifydown.com/trackList/playlist/${playlistId} ...`);
  const res = await fetchJson(`https://api.spotifydown.com/trackList/playlist/${playlistId}`);
  console.log("Status:", res.status);
  console.log("Success:", res.data?.success);
  console.log("TrackList count:", res.data?.trackList?.length);
  if (res.data?.trackList?.length > 0) {
    console.log("Track 1:", res.data.trackList[0]);
  }
  console.log("NextOffset:", res.data?.nextOffset);
}

run();
