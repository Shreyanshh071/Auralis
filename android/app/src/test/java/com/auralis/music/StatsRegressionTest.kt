package com.auralis.music

import com.auralis.music.data.local.dao.HistoryDao
import com.auralis.music.data.local.dao.LibraryDao
import com.auralis.music.data.local.dao.PlayCountDao
import com.auralis.music.data.local.dao.PlayCountWithTrackTuple
import com.auralis.music.data.local.dao.PlaybackEventDao
import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.entity.PlayCountEntity
import com.auralis.music.data.local.entity.TrackEntity
import com.auralis.music.data.network.ArtistPhotoProvider
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.repository.StatsRepositoryImpl
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.OptionStats
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.ui.viewmodel.StatsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StatsRegressionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testTimeRangeCalculation_continuousPeriods() {
        val statsRepo = mockk<com.auralis.music.domain.repository.StatsRepository>(relaxed = true)
        every { statsRepo.observeFirstEventTimestamp() } returns flowOf(null)
        val vm = StatsViewModel(statsRepo)

        val now = System.currentTimeMillis()

        // 0: 1 week (7 days)
        val range1Week = vm.getTimeRange(OptionStats.CONTINUOUS, 0)
        val expected1Week = now - 7L * 24 * 3600 * 1000L
        assertTrue("1 week range should start ~7 days ago", Math.abs(range1Week.first - expected1Week) < 5000L)
        assertTrue("1 week range should end now", Math.abs(range1Week.second - now) < 5000L)

        // 1: 1 month (30 days)
        val range1Month = vm.getTimeRange(OptionStats.CONTINUOUS, 1)
        val expected1Month = now - 30L * 24 * 3600 * 1000L
        assertTrue("1 month range should start ~30 days ago", Math.abs(range1Month.first - expected1Month) < 5000L)

        // 2: 3 months (90 days)
        val range3Months = vm.getTimeRange(OptionStats.CONTINUOUS, 2)
        val expected3Months = now - 90L * 24 * 3600 * 1000L
        assertTrue("3 months range should start ~90 days ago", Math.abs(range3Months.first - expected3Months) < 5000L)

        // 3: 6 months (180 days)
        val range6Months = vm.getTimeRange(OptionStats.CONTINUOUS, 3)
        val expected6Months = now - 180L * 24 * 3600 * 1000L
        assertTrue("6 months range should start ~180 days ago", Math.abs(range6Months.first - expected6Months) < 5000L)

        // 4: 1 year (365 days)
        val range1Year = vm.getTimeRange(OptionStats.CONTINUOUS, 4)
        val expected1Year = now - 365L * 24 * 3600 * 1000L
        assertTrue("1 year range should start ~365 days ago", Math.abs(range1Year.first - expected1Year) < 5000L)

        // 5: All time
        val rangeAllTime = vm.getTimeRange(OptionStats.CONTINUOUS, 5)
        assertEquals("All time should start at 0", 0L, rangeAllTime.first)
    }

    @Test
    fun testTimeRangeCalculation_monthsOption() {
        val statsRepo = mockk<com.auralis.music.domain.repository.StatsRepository>(relaxed = true)
        every { statsRepo.observeFirstEventTimestamp() } returns flowOf(null)
        val vm = StatsViewModel(statsRepo)

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val currentMonth = YearMonth.from(today)

        // idx 0: this month
        val rangeMonth0 = vm.getTimeRange(OptionStats.MONTHS, 0)
        val startOfThisMonth = currentMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("This month start should be 1st at 00:00", startOfThisMonth, rangeMonth0.first)

        // idx 1: previous month
        val prevMonth = currentMonth.minusMonths(1)
        val rangeMonth1 = vm.getTimeRange(OptionStats.MONTHS, 1)
        val startOfPrevMonth = prevMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfPrevMonth = prevMonth.atEndOfMonth().atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Previous month start should match", startOfPrevMonth, rangeMonth1.first)
        assertEquals("Previous month end should match", endOfPrevMonth, rangeMonth1.second)
    }

    @Test
    fun testDateChipsGeneration_continuousHasCorrectLabels() {
        val statsRepo = mockk<com.auralis.music.domain.repository.StatsRepository>(relaxed = true)
        every { statsRepo.observeFirstEventTimestamp() } returns flowOf(null)
        val vm = StatsViewModel(statsRepo)

        vm.selectOption(OptionStats.CONTINUOUS)
        val chips = vm.generateDateChips(null)
        val labels = chips.map { it.second }

        assertEquals(
            listOf("1 week", "1 month", "3 months", "6 months", "1 year", "All time"),
            labels
        )
    }

    @Test
    fun testArtistPhotoProvider_rejectsVideoThumbnails() = runBlocking {
        val innerTubeClient = mockk<InnerTubeClient>()
        ArtistPhotoProvider.resetForTesting(innerTubeClient)

        // Setup search returning artist with authentic photo
        coEvery { innerTubeClient.search("Tame Impala", InnerTubeClient.FILTER_ARTISTS) } returns SearchResults(
            artists = listOf(
                Artist(
                    id = "UCWycu_iWGP4B63q0Z7K-pQg",
                    name = "Tame Impala",
                    thumbnail = "https://lh3.googleusercontent.com/a-/ALV-UjWB123=s512-c-k-c0x00ffffff-no-rj-mo"
                )
            )
        )

        // Setup search returning video thumbnail (which must be rejected)
        coEvery { innerTubeClient.search("Video Artist", InnerTubeClient.FILTER_ARTISTS) } returns SearchResults(
            artists = listOf(
                Artist(
                    id = "UC123",
                    name = "Video Artist",
                    thumbnail = "https://i.ytimg.com/vi/abc12345/hqdefault.jpg"
                )
            )
        )

        // Test resolving valid artist
        val validPhoto = ArtistPhotoProvider.resolveArtistPhoto("Tame Impala")
        assertNotNull(validPhoto)
        assertTrue(validPhoto!!.contains("googleusercontent.com"))
        assertFalse(validPhoto.contains("i.ytimg.com/vi/"))

        // Test resolving video thumbnail artist (must be rejected / return null)
        val rejectedPhoto = ArtistPhotoProvider.resolveArtistPhoto("Video Artist")
        assertNull("Video thumbnails must be rejected for artists", rejectedPhoto)
    }

    @Test
    fun testSeedFromHistoryIfNeeded_distributesPlaysAcrossDays() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playbackEventDao = mockk<PlaybackEventDao>(relaxed = true)
        val historyDao = mockk<HistoryDao>(relaxed = true)
        val playCountDao = mockk<PlayCountDao>(relaxed = true)

        coEvery { playbackEventDao.getEventCount() } returns 0
        every { playbackEventDao.getFirstEventTimestamp() } returns flowOf(null)

        val trackEntity = TrackEntity(
            id = "track_tame_1",
            title = "New Person, Same Old Mistakes",
            artist = "Tame Impala",
            album = "Currents",
            thumbnail = "https://i.ytimg.com/vi/xyz/hqdefault.jpg",
            duration = 364L
        )

        val now = System.currentTimeMillis()
        val playCountTuple = PlayCountWithTrackTuple(
            playCount = PlayCountEntity(
                trackId = "track_tame_1",
                count = 16,
                lastPlayed = now
            ),
            track = trackEntity
        )

        coEvery { playCountDao.getAllPlayCounts() } returns listOf(playCountTuple)
        coEvery { historyDao.getHistoryWithTracks() } returns emptyList()

        val capturedEvents = slot<List<PlaybackEventEntity>>()
        coEvery { playbackEventDao.insertEvents(capture(capturedEvents)) } returns Unit

        val repo = StatsRepositoryImpl(trackDao, playbackEventDao, historyDao, playCountDao)
        repo.seedFromHistoryIfNeeded()

        assertTrue("Events should be inserted", capturedEvents.isCaptured)
        val events = capturedEvents.captured
        assertEquals(16, events.size)

        // Verify that events are NOT all placed in the last 16 hours:
        // First play is recent
        assertEquals(now, events.first().timestamp)
        // Last play (i = 15) must be spaced out across multiple weeks (15 * 36h = 540 hours = 22.5 days)
        val oldestTimestamp = events.last().timestamp
        val timeSpanDays = (now - oldestTimestamp) / (24L * 3600_000L)
        assertTrue("Plays should span at least 15 days across weeks/months, got $timeSpanDays days", timeSpanDays >= 15)
    }

    @Test
    fun testSeedFromHistoryIfNeeded_reseedsCompressedLegacyData() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playbackEventDao = mockk<PlaybackEventDao>(relaxed = true)
        val historyDao = mockk<HistoryDao>(relaxed = true)
        val playCountDao = mockk<PlayCountDao>(relaxed = true)

        val now = System.currentTimeMillis()

        // Simulate legacy database that had 56 events but ALL squeezed into the last 16 hours
        coEvery { playbackEventDao.getEventCount() } returns 56
        every { playbackEventDao.getFirstEventTimestamp() } returns flowOf(now - 16L * 3600_000L)

        val trackEntity = TrackEntity(
            id = "track_tame_1",
            title = "New Person, Same Old Mistakes",
            artist = "Tame Impala",
            album = "Currents",
            thumbnail = "https://i.ytimg.com/vi/xyz/hqdefault.jpg",
            duration = 364L
        )

        val playCountTuple = PlayCountWithTrackTuple(
            playCount = PlayCountEntity(
                trackId = "track_tame_1",
                count = 16,
                lastPlayed = now
            ),
            track = trackEntity
        )

        coEvery { playCountDao.getAllPlayCounts() } returns listOf(playCountTuple)
        coEvery { historyDao.getHistoryWithTracks() } returns emptyList()

        var clearedEvents = false
        coEvery { playbackEventDao.clearAllEvents() } answers { clearedEvents = true }

        val capturedEvents = slot<List<PlaybackEventEntity>>()
        coEvery { playbackEventDao.insertEvents(capture(capturedEvents)) } returns Unit

        val repo = StatsRepositoryImpl(trackDao, playbackEventDao, historyDao, playCountDao)
        repo.seedFromHistoryIfNeeded()

        assertTrue("Old compressed events should be cleared", clearedEvents)
        assertTrue("Re-seeded events should be inserted", capturedEvents.isCaptured)
        val events = capturedEvents.captured
        val timeSpanDays = (now - events.last().timestamp) / (24L * 3600_000L)
        assertTrue("Re-seeded plays should span across multiple weeks, got $timeSpanDays days", timeSpanDays >= 15)
    }
}
