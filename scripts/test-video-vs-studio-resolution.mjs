import { strict as assert } from 'assert';

function testStudioPrioritization() {
  console.log('Testing Studio Track Prioritization & Video Deduplication...');

  const TitleCleaner = {
    cleanTitle(raw) {
      if (!raw) return '';
      return raw
        .replace(/\s*[\(\[\{]?(?:official\s*(?:music\s*)?video|official\s*audio|visualizer|lyric\s*video)[\)\]\}]?/gi, '')
        .trim();
    },
    cleanArtist(raw) {
      if (!raw) return '';
      return raw.replace(/\s*-\s*Topic/gi, '').trim();
    }
  };

  const TrackDeduplicator = {
    isInvalidArtistName(artist) {
      if (!artist) return true;
      const lower = artist.trim().toLowerCase();
      return ['unknown artist', 'various artists', 'topic', 'youtube music'].includes(lower);
    },
    isVideoOrBloatedTrack(track) {
      const lower = (track.title || '').toLowerCase();
      return lower.includes('official video') || lower.includes('music video') || lower.includes('(video)');
    },
    isBetterQualityTrack(candidate, current) {
      const candIsVideo = this.isVideoOrBloatedTrack(candidate);
      const currIsVideo = this.isVideoOrBloatedTrack(current);
      if (!candIsVideo && currIsVideo) return true;
      if (candIsVideo && !currIsVideo) return false;
      const candHasAlbum = Boolean(candidate.album);
      const currHasAlbum = Boolean(current.album);
      if (candHasAlbum && !currHasAlbum) return true;
      if (!candHasAlbum && currHasAlbum) return false;
      if (current.duration > 300 && candidate.duration >= 90 && candidate.duration <= 300) return true;
      return false;
    },
    getSongFingerprint(track) {
      const cleaned = TitleCleaner.cleanTitle(track.title);
      const normTitle = cleaned.toLowerCase().replace(/[^\p{L}\p{M}0-9]/gu, '');
      const cleanedArt = TitleCleaner.cleanArtist(track.artist);
      const normArtist = cleanedArt.toLowerCase().replace(/[^\p{L}\p{M}0-9]/gu, '');
      return { id: track.id, normTitle, normArtist, cleanedTitle: cleaned };
    },
    isDuplicateSong(a, b) {
      if (a.id && b.id && a.id === b.id) return true;
      const titlesMatch = (a.normTitle && a.normTitle === b.normTitle) || a.cleanedTitle.toLowerCase() === b.cleanedTitle.toLowerCase();
      if (!titlesMatch) return false;
      if (!a.normArtist || !b.normArtist) return true;
      if (a.normArtist === b.normArtist) return true;
      if (a.normArtist.includes(b.normArtist) || b.normArtist.includes(a.normArtist)) return true;
      return false;
    },
    isDuplicateTrack(a, b) {
      return this.isDuplicateSong(this.getSongFingerprint(a), this.getSongFingerprint(b));
    },
    deduplicateTracks(tracks) {
      const unique = [];
      const fps = [];
      for (const t of tracks) {
        const fp = this.getSongFingerprint(t);
        const idx = fps.findIndex(existing => this.isDuplicateSong(existing, fp));
        if (idx === -1) {
          unique.push(t);
          fps.push(fp);
        } else {
          const existing = unique[idx];
          if (this.isBetterQualityTrack(t, existing)) {
            unique[idx] = t;
            fps[idx] = fp;
          }
        }
      }
      return unique;
    }
  };

  // Test Case 1: Deduplication prioritizes studio album track over music video
  const officialAudio = { id: 'PvM79DJ2PmM', title: 'The Less I Know The Better', artist: 'Tame Impala', album: 'Currents', duration: 216 };
  const musicVideo = { id: 'sBzrzS1Ag_g', title: 'The Less I Know The Better (Official Video)', artist: 'Tame Impala', album: null, duration: 342 };
  const relatedSong = { id: 'NMRhx71bGo4', title: 'Let It Happen', artist: 'Tame Impala', album: 'Currents', duration: 468 };

  const deduplicated = TrackDeduplicator.deduplicateTracks([musicVideo, officialAudio, relatedSong]);
  assert.equal(deduplicated.length, 2, 'Must deduplicate to 2 tracks (1 target + 1 related)');
  assert.equal(deduplicated[0].id, 'PvM79DJ2PmM', 'Studio audio track must replace music video');
  assert.equal(deduplicated[0].duration, 216, 'Duration must be 3:36 (216s)');
  assert.equal(deduplicated[0].album, 'Currents', 'Album must be Currents');

  // Test Case 2: Top result resolution overrides video card with studio audio
  const topResultCard = musicVideo;
  const studioMatch = [officialAudio].find(t => TrackDeduplicator.isDuplicateTrack(t, topResultCard));
  const resolvedTop = (studioMatch && TrackDeduplicator.isBetterQualityTrack(studioMatch, topResultCard)) ? studioMatch : topResultCard;

  assert.equal(resolvedTop.id, 'PvM79DJ2PmM', 'Resolved top result must be studio audio track');
  assert.equal(resolvedTop.duration, 216, 'Resolved top duration must be 216s');

  console.log('✓ All Studio Track Prioritization and Deduplication checks passed successfully!');
}

testStudioPrioritization();
