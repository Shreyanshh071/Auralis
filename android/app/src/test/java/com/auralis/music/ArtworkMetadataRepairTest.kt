package com.auralis.music

import com.auralis.music.data.local.entity.TrackEntity
import com.auralis.music.data.local.entity.TrackMetadataGuard
import com.auralis.music.data.network.ArtworkCondition
import com.auralis.music.data.network.ArtworkIdentity
import com.auralis.music.data.network.ArtworkResolver
import com.auralis.music.data.network.ArtworkSourceEvidence
import com.auralis.music.data.network.CatalogReleaseCandidate
import com.auralis.music.data.network.LibraryArtworkClassifier
import com.auralis.music.data.network.LibraryArtworkRepair
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.data.network.VerifiedReleaseLookup
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ArtworkMetadataRepairTest {
    private val people = Track(id = "sp_01RGwdlx5dZRbbQTOeJf0k", title = "We Are The People",
        artist = "Empire Of The Sun", album = "Beach Day Chill", duration = 272,
        thumbnail = "https://yt3.googleusercontent.com/beach")
    private val babuaan = Track(id = "fWoHu4gUtE4", title = "Babuaan",
        artist = "PAWAN SINGH & SHILPI RAJ", album = "aaaaa", duration = 222,
        thumbnail = "https://yt3.googleusercontent.com/unrelated")

    @Test fun importedSourceCoverIsNotReplacedByAnAmbiguousCatalogEdition() = runBlocking {
        val song = Track(id = "sp_original", title = "Be My Baby", artist = "The Ronettes",
            album = "The Very Best", duration = 160,
            thumbnail = "https://i.scdn.co/image/source-release")
        val playlist = Playlist(id = "imported:spotify:remote", title = "My music",
            tracks = listOf(song))
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(song)
        every { repo.getPlaylists() } returns flowOf(listOf(playlist))
        var lookups = 0
        val repair = LibraryArtworkRepair(repo, lookup = { lookups++; song.copy(
            album = "A Different Edition", thumbnail = "https://is1-ssl.mzstatic.com/wrong") })

        assertEquals(0, repair.run())
        assertEquals(0, lookups)
        coVerify(exactly = 0) { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) }
    }

    @Test fun exactVideoIdentityIsRequiredBeforeReplacingStoredArtwork() {
        val century = Track(id = "vVllJT_n380", title = "Century", artist = "EsDeeKid",
            album = "Century", duration = 109,
            thumbnail = "https://yt3.googleusercontent.com/classical=w1200-h1200")
        val source = century.copy(thumbnail = "https://yt3.googleusercontent.com/century=w544-h544")
        assertTrue(ArtworkSourceEvidence.matchesExactVideo(century, source))
        assertFalse(ArtworkSourceEvidence.matchesExactVideo(century, source.copy(id = "other-video")))
        assertFalse(ArtworkSourceEvidence.matchesExactVideo(century, source.copy(artist = "Other Artist")))
        assertFalse(ArtworkSourceEvidence.matchesExactVideo(century, source.copy(duration = 180)))
        assertFalse(ArtworkSourceEvidence.sameArtwork(century.thumbnail, source.thumbnail))
        assertTrue(ArtworkSourceEvidence.sameArtwork(source.thumbnail,
            "https://yt3.googleusercontent.com/century=w1200-h1200-l90-rj"))
        val featuring = Track(id = "eeNv4wf2D1U",
            title = "love nwantiti [Remix] (feat. DJ Yo & AX'EL)", artist = "CKay",
            duration = 219, thumbnail = "old")
        assertTrue(ArtworkSourceEvidence.matchesExactVideo(featuring,
            featuring.copy(title = "love nwantiti [Remix]", thumbnail = "source")))
    }

    @Test fun importedPlaylistAlbumIsRepairedFromItsVerifiedSourceRelease() = runBlocking {
        val damaged = Track(id = "LKPcUfp2QqM", title = "Baby Ko Bass Pasand Hai",
            artist = "Vishal Dadlani", album = "SONGS", duration = 244,
            thumbnail = "https://example.com/playlist-cover.jpg")
        val video = damaged.copy(album = "Sultan", albumId = "MPRE-sultan",
            thumbnail = "https://example.com/video-frame.jpg")
        val album = PlaylistResult(id = "MPRE-sultan", title = "Sultan",
            author = "Vishal-Shekhar", thumbnail = "https://example.com/sultan-cover.jpg")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(damaged.id)) } returns listOf(video)
        coEvery { client.search("Sultan", InnerTubeClient.FILTER_ALBUMS) } returns
            SearchResults(albums = listOf(album))
        val repaired = VerifiedReleaseLookup(innerTube = client).verifiedSourceAlbum(damaged)
        assertEquals("Sultan", repaired?.album)
        assertEquals(album.thumbnail, repaired?.thumbnail)
        assertNotEquals(video.thumbnail, repaired?.thumbnail)
    }

    @Test fun exactAlbumBrowseIdResolvesWhenAlbumSearchMissesTheRelease() = runBlocking {
        val damaged = Track(id = "YfqJktv2nuA", title = "Pal Pal (with Talwiinder)",
            artist = "Afusic, Talwiinder & AliSoomroMusic", album = "SONGS",
            duration = 170, thumbnail = "old")
        val source = damaged.copy(album = "Pal Pal (with Talwiinder)",
            albumId = "MPRE-original", thumbnail = "video-image")
        val album = PlaylistResult(id = "MPRE-original", title = source.album!!,
            thumbnail = "album-image")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(damaged.id)) } returns listOf(source)
        coEvery { client.getAlbumById("MPRE-original") } returns album
        assertEquals("album-image", VerifiedReleaseLookup(innerTube = client)
            .verifiedSourceAlbum(damaged)?.thumbnail)
        coVerify(exactly = 0) { client.search(any(), any()) }
    }

    @Test fun exactAlbumBrowseIdAcceptsAddedFeaturedCreditButNotAnotherRelease() = runBlocking {
        val damaged = Track(id = "eeNv4wf2D1U", title = "love nwantiti [Remix] (feat. DJ Yo & AX'EL)",
            artist = "CKay", album = "SONGS", duration = 219, thumbnail = "old")
        val source = damaged.copy(title = "love nwantiti [Remix]",
            album = "love nwantiti (Remix)", albumId = "MPRE-exact",
            thumbnail = "video-image")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(damaged.id)) } returns listOf(source)
        coEvery { client.getAlbumById("MPRE-exact") } returns PlaylistResult(
            id = "MPRE-exact", title = "love nwantiti (Remix) (feat. DJ Yo & AX'EL)",
            author = "CKay", thumbnail = "album-image")
        val lookup = VerifiedReleaseLookup(innerTube = client)
        assertEquals("album-image", lookup.verifiedSourceAlbum(damaged)?.thumbnail)

        coEvery { client.getAlbumById("MPRE-exact") } returns PlaylistResult(
            id = "MPRE-exact", title = "love nwantiti (Acoustic) (feat. DJ Yo & AX'EL)",
            author = "CKay", thumbnail = "wrong-version")
        coEvery { client.search(any(), InnerTubeClient.FILTER_ALBUMS) } returns SearchResults()
        assertNull(lookup.verifiedSourceAlbum(damaged))
    }

    @Test fun exactVideoWithEditorialCollectionArtworkIsNotAnArtworkAuthority() = runBlocking {
        val damaged = Track(id = "r02mYOlfcCw", title = "We Are The People",
            artist = "Empire Of The Sun", album = "SONGS", duration = 267,
            thumbnail = "https://example.com/playlist-cover.jpg")
        val video = damaged.copy(album = "Beach Day Chill", albumId = "MPRE-beach",
            thumbnail = "https://example.com/beach-art.jpg")
        val album = PlaylistResult(id = "MPRE-beach", title = "Beach Day Chill",
            author = "YouTube Music", thumbnail = video.thumbnail)
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(damaged.id)) } returns listOf(video)
        coEvery { client.search("Beach Day Chill", InnerTubeClient.FILTER_ALBUMS) } returns
            SearchResults(albums = listOf(album))
        assertNull(VerifiedReleaseLookup(innerTube = client).verifiedSourceAlbum(damaged))
    }

    @Test fun filmCreditRejectsDifferentSourceAlbumEvenWithExactBrowseId() = runBlocking {
        val song = Track(id = "zlzR3AOhCmg",
            title = "Chhalakata Hamro Jawaniya - From \"Bhojpuriya Raja\"",
            artist = "Pawan Singh", album = "Pawan Singh Hit Songs", duration = 220,
            thumbnail = "old")
        val source = song.copy(albumId = "MPRE-hits", thumbnail = "video-image")
        val album = PlaylistResult(id = "MPRE-hits", title = source.album!!,
            thumbnail = "hits-image")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(song.id)) } returns listOf(source)
        coEvery { client.getAlbumById("MPRE-hits") } returns album
        assertTrue(LibraryArtworkClassifier.hasCreditedReleaseConflict(song))
        assertEquals(ArtworkCondition.SUSPICIOUS,
            LibraryArtworkClassifier.classify(song, listOf(song), emptyList()))
        assertNull(VerifiedReleaseLookup(innerTube = client).verifiedSourceAlbum(song))
        assertFalse(LibraryArtworkClassifier.hasCreditedReleaseConflict(song.copy(
            album = "Bhojpuriya Raja (Original Motion Picture Soundtrack)")))
    }

    @Test fun filmCreditConflictIsRemovedBeforeIndependentCatalogLookup() = runBlocking {
        val song = Track(id = "video-id", title = "Woh Din [From \"Chhichhore\"]",
            artist = "Singer", album = "Musical Tribute To Sushant Singh Rajput",
            duration = 215, thumbnail = "old")
        val official = song.copy(album = "Chhichhore (Original Motion Picture Soundtrack)",
            thumbnail = "official")
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(song)
        every { repo.getPlaylists() } returns flowOf(emptyList())
        coEvery { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) } returns true
        var queriedAlbum: String? = "not queried"
        val repair = LibraryArtworkRepair(repo, lookup = {
            queriedAlbum = it.album
            official
        })
        assertEquals(1, repair.run())
        assertNull(queriedAlbum)
        assertTrue(ArtworkSourceEvidence.sameRecordingTitle(song.title, "Woh Din"))
    }

    @Test fun catalogMayConfirmVersionNamedForTheCreditedSingerOnly() {
        val requested = Track(title = "Woh Din [From \"Chhichhore\"]",
            artist = "Arijit Singh", duration = 259, thumbnail = "old")
        val official = Track(title = "Woh Din (Arijit Singh Version)",
            artist = "Pritam & Arijit Singh", album = "Chhichhore (Original Motion Picture Soundtrack)",
            duration = 258, thumbnail = "official")
        assertTrue(ArtworkSourceEvidence.matchesCatalogRecording(requested, official))
        assertFalse(ArtworkSourceEvidence.matchesCatalogRecording(requested,
            official.copy(title = "Woh Din (Tushar Joshi Version)")))
        assertFalse(ArtworkSourceEvidence.matchesCatalogRecording(requested,
            official.copy(duration = 310)))
    }

    @Test fun catalogRepairChoosesOriginalReleaseOverEditorialOrHitsArtwork() {
        val damaged = Track(id = "same-video", title = "We Are The People",
            artist = "Empire Of The Sun", album = "Beach Day Chill", duration = 267,
            thumbnail = "https://example.com/beach.jpg")
        val hits = CatalogReleaseCandidate(damaged.copy(album = "Greatest Hits",
            thumbnail = "https://example.com/hits.jpg"), "2020-01-01", 20, "Various Artists")
        val original = CatalogReleaseCandidate(damaged.copy(album = "Walking On A Dream",
            thumbnail = "https://example.com/original.jpg"), "2008-01-01", 10,
            "Empire Of The Sun")
        val unrelated = CatalogReleaseCandidate(damaged.copy(title = "Other Song",
            thumbnail = "https://example.com/other.jpg"), "2000-01-01", 10,
            "Empire Of The Sun")
        val lookup = VerifiedReleaseLookup()
        assertEquals(original.track,
            lookup.selectPreferredCatalogRelease(damaged, listOf(unrelated, hits, original)))
        assertEquals(original.track,
            lookup.selectPreferredCatalogRelease(original.track, listOf(hits, original)))
        assertTrue(LibraryArtworkClassifier.isLikelyEditorialCollection(
            "Long Drive Bollywood Mix - Arijit Singh", "Arijit Singh", "Arijit Singh"))
        assertTrue(LibraryArtworkClassifier.isLikelyEditorialCollection(
            "Ultimate Love Songs - Arijit Singh", "Arijit Singh", "Arijit Singh"))
        assertTrue(LibraryArtworkClassifier.isLikelyEditorialCollection(
            "Baarish Vibes with Arijit Singh", "Arijit Singh", "Arijit Singh"))
    }

    @Test fun originalSoundtrackWinsWhenCatalogReusesTheSameRecordingOnCollections() {
        val song = Track(id = "movie-video", title = "Phir Mohabbat (From \"Murder 2\")",
            artist = "Mohammed Irfan, Arijit Singh & Saim Bhat", duration = 330,
            album = "Best of Arijit Singh", thumbnail = "old")
        val compilation = CatalogReleaseCandidate(song.copy(title = "Phir Mohabbat",
            artist = "Mohammed Irfan, Arijit & Saim Bhat", album = "Timeless Love Tunes",
            thumbnail = "https://is1-ssl.mzstatic.com/compilation"), "2011-05-30", 20,
            "Various Artists")
        val soundtrack = compilation.copy(track = compilation.track.copy(
            album = "Murder 2 (Original Motion Picture Soundtrack)",
            thumbnail = "https://is1-ssl.mzstatic.com/original"))
        assertTrue(ArtworkSourceEvidence.matchesCatalogRecording(song, soundtrack.track))
        assertEquals(soundtrack.track, VerifiedReleaseLookup().selectPreferredCatalogRelease(
            song, listOf(compilation, soundtrack)))
        val babuaanWithoutSecondArtist = Track(title = "Babuaan", artist = "Pawan Singh",
            duration = babuaan.duration, thumbnail = "art")
        assertFalse(ArtworkSourceEvidence.matchesCatalogRecording(babuaan,
            babuaanWithoutSecondArtist))
    }

    @Test fun appleArtworkMustBelongToTheSameSongAndRelease() {
        val october = Track(id = "FJX0JPXD2nM", title = "we fell in love in october",
            artist = "girl in red", album = "we fell in love in october / October Passed Me By",
            duration = 184, thumbnail = "https://is1-ssl.mzstatic.com/blue.jpg")
        val otherRelease = october.copy(album = "we fell in love in october / forget her - Single")
        assertFalse(ArtworkSourceEvidence.matchesProviderRelease(october, otherRelease))
        assertTrue(ArtworkSourceEvidence.matchesProviderRelease(october,
            october.copy(thumbnail = "https://is1-ssl.mzstatic.com/red.jpg")))
        assertTrue(ArtworkSourceEvidence.sameRelease("I Love You So", "I Love You So - Single"))
        assertTrue(ArtworkSourceEvidence.sameRelease("Raanjhanaa",
            "Raanjhanaa (Original Motion Picture Soundtrack)"))
        assertFalse(ArtworkSourceEvidence.sameRelease("The Eminem Show",
            "The Eminem Show (Expanded Edition)"))
        val sorry = Track(id = "ftUWLRU5rVM", title = "Sorry Sorry - From \"Bhojpuriya Raja\"",
            artist = "Pawan Singh", album = "Pawan Singh Hit Songs", duration = 151)
        val unrelated = sorry.copy(title = "Bhojpuriya Raja", duration = 417)
        assertFalse(ArtworkSourceEvidence.matchesProviderRelease(sorry, unrelated))
        assertTrue(ArtworkSourceEvidence.matchesExactVideo(sorry,
            sorry.copy(title = "Sorry Sorry (From \"Bhojpuriya Raja\")", thumbnail = "source")))
    }

    @Test fun unrelatedGoogleCatalogArtIsReplacedOnlyWithExactVideoRelease() = runBlocking {
        val tere = Track(id = "5U5Ru0nTiUM", title = "Tere Liye",
            artist = "Atif Aslam, Shreya Ghoshal", album = "Prince (Original Motion Picture Soundtrack)",
            duration = 279, thumbnail = "https://yt3.googleusercontent.com/prince-musician=w1200")
        val exact = tere.copy(artist = "Atif Aslam", albumId = "MPRE-film",
            thumbnail = "https://yt3.googleusercontent.com/prince-film=w1200")
        val unrelatedOwner = PlaylistResult(id = "MPRE-other", title = "The Very Best Of Prince",
            author = "Prince", thumbnail = tere.thumbnail)
        val filmAlbum = PlaylistResult(id = "MPRE-film", title = tere.album!!,
            author = "Atif Aslam", thumbnail = exact.thumbnail)
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(tere.id)) } returns listOf(exact)
        coEvery { client.search(any(), InnerTubeClient.FILTER_SONGS) } returns SearchResults()
        coEvery { client.search(any(), InnerTubeClient.FILTER_ALBUMS) } returns
            SearchResults(albums = listOf(unrelatedOwner, filmAlbum))
        assertEquals(tere.copy(albumId = filmAlbum.id, thumbnail = filmAlbum.thumbnail.orEmpty()),
            VerifiedReleaseLookup(innerTube = client).auditExisting(tere))
        val validOwner = unrelatedOwner.copy(title = tere.album!!)
        coEvery { client.search(any(), InnerTubeClient.FILTER_ALBUMS) } returns
            SearchResults(albums = listOf(validOwner, filmAlbum))
        assertEquals(tere, VerifiedReleaseLookup(innerTube = client).auditExisting(tere))
    }

    @Test fun exactVideoRepairsWrongSelfTitledSingleAndMovieArt() = runBlocking {
        val century = Track(id = "vVllJT_n380", title = "Century", artist = "EsDeeKid",
            album = "Century", duration = 109,
            thumbnail = "https://yt3.googleusercontent.com/classical=w1200")
        val centurySource = century.copy(albumId = "MPRE-century",
            thumbnail = "https://yt3.googleusercontent.com/real-century=w1200")
        val sorry = Track(id = "ftUWLRU5rVM", title = "Sorry Sorry - From \"Bhojpuriya Raja\"",
            artist = "Pawan Singh", album = "Pawan Singh Hit Songs", duration = 151,
            thumbnail = "https://is1-ssl.mzstatic.com/image/thumb/wrong/6161135858493.jpg/1200x1200bb.jpg")
        val sorrySource = sorry.copy(title = "Sorry Sorry",
            album = "Bhojpuriya Raja (Original Motion Picture Soundtrack)",
            albumId = "MPRE-movie",
            thumbnail = "https://yt3.googleusercontent.com/movie=w1200")
        val october = Track(id = "FJX0JPXD2nM", title = "we fell in love in october",
            artist = "girl in red", album = "we fell in love in october / October Passed Me By",
            duration = 184,
            thumbnail = "https://is1-ssl.mzstatic.com/image/thumb/blue/5054526166202.jpg/1200x1200bb.jpg")
        val octoberSource = october.copy(album = "we fell in love in october / forget her",
            thumbnail = "https://yt3.googleusercontent.com/other-blue=w1200")
        val compilation = sorry.copy(id = "nDmjLFj4YJY",
            title = "Sorry Sorry (From \"Bhojpuriya Raja\")",
            thumbnail = "https://yt3.googleusercontent.com/hits-collection=w1200")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(century.id)) } returns listOf(centurySource)
        coEvery { client.getQueue(listOf(sorry.id)) } returns listOf(sorrySource)
        coEvery { client.getQueue(listOf(october.id)) } returns listOf(octoberSource)
        coEvery { client.getQueue(listOf(compilation.id)) } returns listOf(compilation)
        coEvery { client.search(any(), any()) } returns SearchResults()
        val centuryAlbum = PlaylistResult(id = "MPRE-century", title = "Century",
            author = "EsDeeKid", thumbnail = centurySource.thumbnail)
        val movieAlbum = PlaylistResult(id = "MPRE-movie", title = sorrySource.album!!,
            author = "Pawan Singh", thumbnail = sorrySource.thumbnail)
        coEvery { client.search(any(), InnerTubeClient.FILTER_ALBUMS) } returns
            SearchResults(albums = listOf(centuryAlbum, movieAlbum))
        val lookup = VerifiedReleaseLookup(innerTube = client, appleArtworkEvidence = { false })
        assertEquals(century.copy(albumId = centuryAlbum.id,
            thumbnail = centuryAlbum.thumbnail.orEmpty()), lookup.auditExisting(century))
        assertEquals(sorry.copy(album = movieAlbum.title, albumId = movieAlbum.id,
            thumbnail = movieAlbum.thumbnail.orEmpty()), lookup.auditExisting(sorry))
        assertNull(lookup.auditExisting(october))
        coEvery { client.search(any(), InnerTubeClient.FILTER_SONGS) } returns
            SearchResults(songs = listOf(sorrySource))
        assertEquals(compilation.copy(album = sorrySource.album,
            thumbnail = movieAlbum.thumbnail.orEmpty()), lookup.auditExisting(compilation))
    }

    @Test fun movieCreditDoesNotDisplaceVerifiedCompilationArtwork() = runBlocking {
        val compilation = Track(id = "u5kabciSr0Y", title = "Sapna Jahan (From \"Brothers\")",
            artist = "Ajay-Atul, Sonu Nigam & Neeti Mohan",
            album = "I Love You (30 Biggest Love Songs)", duration = 344,
            thumbnail = "https://is1-ssl.mzstatic.com/image/thumb/valid/886446370187.jpg/1400x1400bb.jpg")
        val source = compilation.copy(artist = "Ajay-Atul", album = "YouTube Hits",
            thumbnail = "https://yt3.googleusercontent.com/source=w1200")
        val movie = source.copy(id = "other-video", album = "Brothers (Original Motion Picture Soundtrack)",
            albumId = "MPRE-brothers",
            thumbnail = "https://yt3.googleusercontent.com/movie=w1200")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(compilation.id)) } returns listOf(source)
        coEvery { client.getQueue(listOf(movie.id)) } returns listOf(movie)
        coEvery { client.search(any(), InnerTubeClient.FILTER_SONGS) } returns
            SearchResults(songs = listOf(movie))
        val movieAlbum = PlaylistResult(id = "MPRE-brothers", title = movie.album!!,
            author = "Ajay-Atul", thumbnail = "https://yt3.googleusercontent.com/brothers-album=w1200")
        coEvery { client.search(any(), InnerTubeClient.FILTER_ALBUMS) } returns
            SearchResults(albums = listOf(movieAlbum))
        assertEquals(compilation, VerifiedReleaseLookup(innerTube = client,
            appleArtworkEvidence = { true }).auditExisting(compilation))
        assertNull(VerifiedReleaseLookup(innerTube = client,
            appleArtworkEvidence = { null }).auditExisting(compilation))
        assertEquals(compilation.copy(album = movie.album,
            thumbnail = movieAlbum.thumbnail.orEmpty()),
            VerifiedReleaseLookup(innerTube = client,
                appleArtworkEvidence = { false }).auditExisting(compilation))
        val alreadyOnFilm = compilation.copy(album = "Brothers (Original Motion Picture Soundtrack)",
            thumbnail = "https://yt3.googleusercontent.com/film-art=w1200")
        coEvery { client.getQueue(listOf(alreadyOnFilm.id)) } returns listOf(source)
        coEvery { client.search(any(), InnerTubeClient.FILTER_ALBUMS) } returns SearchResults()
        assertNull(VerifiedReleaseLookup(innerTube = client).auditExisting(alreadyOnFilm))
        assertTrue(ArtworkSourceEvidence.sameRecordingTitle(
            "Sapna Jahan (From \"Brothers\")", "Sapna Jahan"))
    }

    @Test fun ambiguousAppleSingleIsNotReplacedWithAnotherThumbnail() = runBlocking {
        val single = Track(id = "4zsVKROnQfY", title = "MIDDLE OF THE NIGHT",
            artist = "Elley Duh\uFFFD", album = "MIDDLE OF THE NIGHT", duration = 183,
            thumbnail = "https://is1-ssl.mzstatic.com/image/thumb/valid/196589263315.jpg/1200x1200bb.jpg")
        val source = single.copy(artist = "Elley Duh\uFFFD", album = "MIDDLE OF THE NIGHT - Single",
            thumbnail = "https://yt3.googleusercontent.com/source=w1200")
        val client = mockk<InnerTubeClient>()
        coEvery { client.getQueue(listOf(single.id)) } returns listOf(source)
        assertNull(VerifiedReleaseLookup(innerTube = client,
            appleArtworkEvidence = { null }).auditExisting(single))
    }

    @Test fun populatedCatalogArtIsAuditedAndVerifiedCorrectionPropagates() = runBlocking {
        val century = Track(id = "vVllJT_n380", title = "Century", artist = "EsDeeKid",
            album = "Century", duration = 109,
            thumbnail = "https://yt3.googleusercontent.com/classical=w1200-h1200")
        val verified = century.copy(thumbnail = "https://yt3.googleusercontent.com/century=w1200-h1200")
        val valid = Track(id = "valid", title = "Valid", artist = "Singer", album = "Album",
            thumbnail = "https://example.com/valid.jpg")
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(century, valid)
        every { repo.getPlaylists() } returns flowOf(emptyList())
        coEvery { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) } returns true
        val inspected = mutableListOf<String>()
        val corrected = mutableListOf<Track>()
        val repair = LibraryArtworkRepair(repo, auditLookup = { track ->
            inspected += track.id
            if (track.id == century.id) verified else null
        }, lookup = { null })
        assertEquals(1, repair.run { corrected += it })
        assertEquals(listOf(century.id), inspected)
        assertEquals(listOf(verified), corrected)
        coVerify(exactly = 0) { repo.updateVerifiedTrackRelease(valid.id, any(), any(), any(), any()) }
    }

    @Test fun sharedArtworkOnOneSoundtrackIsNotMarkedSuspicious() {
        val tracks = (1..3).map { n -> Track(id = "soundtrack$n", title = "Film Song $n",
            artist = "Singer $n", album = "Student of the Year (Original Motion Picture Soundtrack)",
            thumbnail = "https://is1-ssl.mzstatic.com/soundtrack.jpg") }
        val conditions = LibraryArtworkClassifier.classifyAll(tracks, emptyList())
        assertTrue(tracks.all { conditions[it.id] == ArtworkCondition.TRUSTWORTHY })
    }

    @Test fun suspiciousButVerifiedAppleReleaseIsPreserved() = runBlocking {
        val verified = Track(id = "ZbACMANAaY0", title = "The Disco Song",
            artist = "Vishal & Shekhar", album = "Student of the Year (Original Motion Picture Soundtrack)",
            thumbnail = "https://is1-ssl.mzstatic.com/image/thumb/verified/886443601017.jpg/1200x1200bb.jpg")
        val conflict = Track(id = "other-video", title = "Different Song", artist = "Other Artist",
            album = "Other Album", thumbnail = verified.thumbnail)
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(verified, conflict)
        every { repo.getPlaylists() } returns flowOf(emptyList())
        val audited = mutableListOf<String>()
        val repair = LibraryArtworkRepair(repo, auditLookup = { track ->
            audited += track.id
            if (track.id == verified.id) verified else null
        }, lookup = { error("Populated release must not use a blind search fallback") })
        val outcome = repair.runDetailed()
        assertEquals(0, outcome.corrected)
        assertTrue(verified.id in audited)
        coVerify(exactly = 0) { repo.updateVerifiedTrackRelease(verified.id, any(), any(), any(), any()) }
    }

    @Test fun playlistFallbackCannotBecomeSongReleaseOrArt() {
        val items = JSONArray("""[{"itemV2":{"data":{"id":"song1","name":"We Are The People",
            "artists":{"items":[{"profile":{"name":"Empire Of The Sun"}}]}}}}]""")
        val tracks = mutableListOf<Track>()
        SpotifyPlaylistImporter().parsePathfinderPlaylistItems(items, "Beach Day Chill", tracks,
            "https://example.com/playlist-cover.jpg")
        assertEquals(1, tracks.size)
        assertNull(tracks.single().album)
        assertEquals("", tracks.single().thumbnail)

        val apiTracks = mutableListOf<Track>()
        SpotifyPlaylistImporter().parseApiTrackItems(JSONArray("""[{
            "track":{"id":"song2","name":"Babuaan","artists":[{"name":"Pawan Singh"}]}
        }]"""), "aaaaa", apiTracks)
        assertNull(apiTracks.single().album)
        assertEquals("", apiTracks.single().thumbnail)

        val embed = SpotifyPlaylistImporter().parseEmbedHtml(
            """<div>"uri":"spotify:track:abc123","title":"Song","subtitle":"Singer"</div>""",
            "playlist-id", com.auralis.music.data.network.SpotifyItemType.PLAYLIST)
        assertNull(embed?.tracks?.single()?.album)
    }

    @Test fun repairNeverWritesImportedPlaylistTitleOrCoverIntoSong() = runBlocking {
        val song = Track(id = "source-video", title = "Unrelated Song", artist = "Singer",
            album = "SONGS", duration = 200, thumbnail = "https://example.com/old.jpg")
        val playlist = Playlist(id = "imported", title = "SONGS",
            description = "Imported from YouTube Music",
            coverUrl = "https://example.com/playlist.jpg", tracks = listOf(song))
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(song)
        every { repo.getPlaylists() } returns flowOf(listOf(playlist))
        coEvery { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) } returns true
        val repair = LibraryArtworkRepair(repo, lookup = { requested ->
            requested.copy(album = "New Album", thumbnail = playlist.coverUrl.orEmpty())
        })
        val corrected = mutableListOf<Track>()
        assertEquals(1, repair.run { corrected += it })
        assertNull(corrected.single().album)
        assertEquals(song.thumbnail, corrected.single().thumbnail)
        coVerify(exactly = 1) {
            repo.updateVerifiedTrackRelease(song.id, "SONGS", song.thumbnail, null, song.thumbnail)
        }
    }

    @Test fun unresolvedImportedPlaylistCoverIsRemovedWithoutInventingSongArtwork() = runBlocking {
        val song = Track(id = "source-video", title = "Unrelated Song", artist = "Singer",
            album = "Beach Day Chill", thumbnail = "https://example.com/playlist.jpg")
        val playlist = Playlist(id = "imported", title = "Beach Day Chill",
            description = "Imported from YouTube Music", coverUrl = song.thumbnail,
            tracks = listOf(song))
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(song)
        every { repo.getPlaylists() } returns flowOf(listOf(playlist))
        coEvery { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) } returns true
        val corrected = mutableListOf<Track>()
        val outcome = LibraryArtworkRepair(repo, lookup = { null }).runDetailed { corrected += it }
        assertEquals(1, outcome.corrected)
        assertEquals(1, outcome.unresolved)
        assertNull(corrected.single().album)
        assertEquals("", corrected.single().thumbnail)
    }

    @Test fun importedPlaylistTitleIsRemovedBeforeReleaseLookup() = runBlocking {
        val song = Track(id = "video-id", title = "Last Christmas", artist = "Wham!",
            album = "gili gili", duration = 270,
            thumbnail = "https://example.com/old.jpg")
        val playlist = Playlist(id = "imported", title = "gili gili",
            description = "Imported from Spotify", tracks = listOf(song))
        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns listOf(song)
        every { repo.getPlaylists() } returns flowOf(listOf(playlist))
        coEvery { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) } returns true
        var receivedAlbum: String? = "not called"
        val repair = LibraryArtworkRepair(repo, lookup = { requested ->
            receivedAlbum = requested.album
            requested.copy(album = "Last Christmas", thumbnail = "https://example.com/single.jpg")
        })
        assertEquals(1, repair.run())
        assertNull(receivedAlbum)
    }

    @Test fun unrelatedFirstResultAndDifferentCreditsAreRejected() {
        val wrong = Track(title = "Beach Day Chill", artist = "Other Artist",
            thumbnail = "https://example.com/wrong.jpg")
        val right = Track(title = "We Are The People", artist = "Empire Of The Sun",
            duration = 269, thumbnail = "https://example.com/right.jpg")
        assertEquals(right, listOf(wrong, right).firstOrNull { ArtworkIdentity.matches(people, it) })
        assertFalse(ArtworkIdentity.matches(babuaan,
            Track(title = "Babuaan", artist = "Pawan Singh", thumbnail = "wrong")))
        assertFalse(ArtworkIdentity.matches(babuaan,
            Track(title = "Babuaan (Remix)", artist = babuaan.artist, thumbnail = "wrong")))
    }

    @Test fun sourceAndReleaseArePartOfArtworkCacheIdentity() {
        assertNotEquals(ArtworkIdentity.cacheKey(people),
            ArtworkIdentity.cacheKey(people.copy(id = "another-recording")))
        assertNotEquals(ArtworkIdentity.cacheKey(people),
            ArtworkIdentity.cacheKey(people.copy(album = "Walking On A Dream")))
        assertNotEquals(ArtworkIdentity.cacheKey(people),
            ArtworkIdentity.cacheKey(people.copy(artist = "Other Artist")))
        val first = people.copy(thumbnail = "", album = "First Release")
        val second = first.copy(id = "another-recording", album = "Second Release")
        ArtworkResolver.clearCache()
        ArtworkResolver.cacheArtwork(first, "https://example.com/first.jpg")
        assertNull(ArtworkResolver.getArtwork(second))
    }

    @Test fun youtubePlaybackMatchDoesNotRedefineSpotifyRelease() = runBlocking {
        val source = people.copy(album = "Walking On A Dream",
            thumbnail = "https://i.scdn.co/image/spotify-release")
        val youtubeHit = Track(id = "r02mYOlfcCw", title = source.title, artist = source.artist,
            album = "Beach Day Chill", duration = source.duration,
            thumbnail = "https://example.com/playlist-or-video.jpg")
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults(songs = listOf(youtubeHit))
        val enriched = SpotifyPlaylistImporter(innerTubeClient = client)
            .enrichTracksWithYouTubeData(listOf(source)).single()
        assertEquals(youtubeHit.id, enriched.id)
        assertEquals(source.album, enriched.album)
        assertEquals(source.thumbnail, enriched.thumbnail)
    }

    @Test fun staleTrackCannotOverwriteExistingReleaseInRoomUpsertGuard() {
        val verified = TrackEntity(id = babuaan.id, title = babuaan.title, artist = babuaan.artist,
            album = "Babuaan (From Sooryavansham)", duration = 221,
            thumbnail = "https://example.com/verified.jpg")
        val stale = verified.copy(album = "aaaaa", thumbnail = babuaan.thumbnail)
        val merged = TrackMetadataGuard.merge(verified, stale)
        assertEquals(verified.album, merged.album)
        assertEquals(verified.thumbnail, merged.thumbnail)
        val cleared = verified.copy(album = null)
        assertNull(TrackMetadataGuard.merge(cleared, stale).album)
    }

    @Test fun verifiedRepairUpdatesQueuedCopiesWithoutChangingPlaybackPosition() {
        val queue = AudioQueueManager()
        queue.setQueue(listOf(people, babuaan), startIndex = 1, isUserQueue = true)
        val before = queue.state
        assertTrue(queue.applyVerifiedMetadata(babuaan.copy(
            album = "Babuaan (From Sooryavansham)", thumbnail = "verified")))
        assertEquals(before.currentIndex, queue.state.currentIndex)
        assertEquals(before.isUserQueue, queue.state.isUserQueue)
        assertEquals("verified", queue.state.currentTrack?.thumbnail)
        assertEquals("Babuaan (From Sooryavansham)", queue.state.currentTrack?.album)
    }

    @Test fun mixedLibrarySelectivelyRepairsOnlySuspiciousAndMissing() = runBlocking {
        val valid = (0 until 500).map { n -> Track(id = "valid$n", title = "Song $n",
            artist = "Artist $n", album = "Album $n", thumbnail = "https://example.com/$n.jpg") }
        val missing = Track(id = "missing", title = "Missing Song", artist = "Missing Artist")
        val bogusAlbum = listOf(babuaan,
            Track(id = "other1", title = "Other 1", artist = "Other Artist 1", album = "aaaaa", thumbnail = "art1"),
            Track(id = "other2", title = "Other 2", artist = "Other Artist 2", album = "aaaaa", thumbnail = "art2"))
        val all = valid + missing + people + bogusAlbum
        val playlist = Playlist(id = "pl", title = "Beach Day Chill",
            coverUrl = "https://example.com/playlist-cover.jpg", tracks = listOf(people))
        assertEquals(ArtworkCondition.TRUSTWORTHY,
            LibraryArtworkClassifier.classify(valid.first(), all, listOf(playlist)))
        assertEquals(ArtworkCondition.MISSING,
            LibraryArtworkClassifier.classify(missing, all, listOf(playlist)))
        assertEquals(ArtworkCondition.SUSPICIOUS,
            LibraryArtworkClassifier.classify(people, all, listOf(playlist)))
        assertEquals(ArtworkCondition.SUSPICIOUS,
            LibraryArtworkClassifier.classify(babuaan, all, listOf(playlist)))
        val soundtrack = listOf(
            Track(id = "soundtrack1", title = "Track One", artist = "Singer One",
                album = "Film Soundtrack", thumbnail = "shared-official-art"),
            Track(id = "soundtrack2", title = "Track Two", artist = "Singer Two",
                album = "Film Soundtrack", thumbnail = "shared-official-art"),
            Track(id = "soundtrack3", title = "Track Three", artist = "Singer Three",
                album = "Film Soundtrack", thumbnail = "shared-official-art"))
        assertEquals(ArtworkCondition.TRUSTWORTHY,
            LibraryArtworkClassifier.classify(soundtrack.first(), soundtrack, emptyList()))

        val repo = mockk<LibraryRepository>(relaxed = true)
        coEvery { repo.getAllTracks() } returns all
        every { repo.getPlaylists() } returns flowOf(listOf(playlist))
        coEvery { repo.updateVerifiedTrackRelease(any(), any(), any(), any(), any()) } returns true
        val lookedUp = mutableListOf<String>()
        val repair = LibraryArtworkRepair(repo) { track ->
            lookedUp += track.id
            when (track.id) {
                people.id -> track.copy(album = "Walking On A Dream",
                    thumbnail = "https://example.com/people.jpg")
                babuaan.id -> track.copy(album = "Babuaan (From Sooryavansham)",
                    thumbnail = "https://example.com/babuaan.jpg")
                missing.id -> track.copy(album = "Missing Album", thumbnail = "https://example.com/missing.jpg")
                else -> null
            }
        }
        assertEquals(3, repair.run())
        assertTrue(lookedUp.none { it.startsWith("valid") })
        coVerify(exactly = 0) { repo.updateVerifiedTrackRelease(match { it.startsWith("valid") }, any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.updateVerifiedTrackRelease(people.id, any(), any(), "Walking On A Dream", any()) }
        coVerify(exactly = 1) { repo.updateVerifiedTrackRelease(babuaan.id, any(), any(), "Babuaan (From Sooryavansham)", any()) }
    }

    @Test fun importedCollectionNamesDoNotBecomeTrustedSongAlbums() {
        val imported = Playlist(id = "imported:spotify:playlist", title = "gili gili",
            description = "Imported from Spotify")
        val song = Track(id = "youtube-id", title = "Attention", artist = "Charlie Puth",
            album = "gili gili", thumbnail = "https://yt3.googleusercontent.com/cover")
        assertEquals(ArtworkCondition.CLEARLY_MISMATCHED,
            LibraryArtworkClassifier.classify(song, listOf(song), listOf(imported)))
        assertTrue(LibraryArtworkClassifier.isPlaceholderAlbum("SONGS"))

        val validAlbum = song.copy(id = "other-id", album = "Currents")
        val savedAlbum = Playlist(id = "album", title = "Currents",
            description = "Album by Tame Impala")
        assertEquals(ArtworkCondition.TRUSTWORTHY,
            LibraryArtworkClassifier.classify(validAlbum, listOf(validAlbum), listOf(savedAlbum)))
    }

    @Test fun conflictingYoutubeMetadataIsSuspiciousWhenSpotifyReleaseExists() {
        val spotify = Track(id = "sp_release", title = "Attention", artist = "Charlie Puth",
            album = "Voicenotes", thumbnail = "https://i.scdn.co/image/official")
        val contaminated = Track(id = "youtube-id", title = spotify.title, artist = spotify.artist,
            album = "Hits Noviembre 2023",
            thumbnail = "https://yt3.googleusercontent.com/compilation")
        val classes = LibraryArtworkClassifier.classifyAll(listOf(spotify, contaminated), emptyList())
        assertEquals(ArtworkCondition.TRUSTWORTHY, classes[spotify.id])
        assertEquals(ArtworkCondition.SUSPICIOUS, classes[contaminated.id])
    }
}
