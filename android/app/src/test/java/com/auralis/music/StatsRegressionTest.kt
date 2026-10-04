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
import io.mockk.coVerify
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
    fun statsWaitsForAllQueriesAndDistinguishesEmptyResultsFromLoading() {
        val statsRepo = mockk<com.auralis.music.domain.repository.StatsRepository>(relaxed = true)
        every { statsRepo.observeFirstEventTimestamp() } returns flowOf(null)
        var expected = com.auralis.music.domain.model.StatsOverview(120_000L, 2, 1, 0)
        every { statsRepo.observeStatsOverview(any(), any()) } answers {
            val result = expected
            kotlinx.coroutines.flow.flow {
                kotlinx.coroutines.delay(100)
                emit(result)
            }
        }
        every { statsRepo.observeTopSongs(any(), any(), any()) } answers {
            kotlinx.coroutines.flow.flow {
                kotlinx.coroutines.delay(200)
                emit(emptyList<com.auralis.music.domain.model.SongStat>())
            }
        }
        every { statsRepo.observeTopArtists(any(), any(), any()) } answers {
            kotlinx.coroutines.flow.flow {
                kotlinx.coroutines.delay(300)
                emit(emptyList<com.auralis.music.domain.model.ArtistStat>())
            }
        }
        val vm = StatsViewModel(statsRepo)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("stats", vm)
        try {
            assertNull(vm.statsContent.value)
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceTimeBy(200)
            testDispatcher.scheduler.runCurrent()
            assertNull("Summary alone must not expose partially loaded stats", vm.statsContent.value)
            testDispatcher.scheduler.advanceTimeBy(100)
            testDispatcher.scheduler.runCurrent()
            assertEquals(expected, vm.statsContent.value?.overview)

            for (option in OptionStats.entries) {
                expected = com.auralis.music.domain.model.StatsOverview(0L, 0, 0, 0)
                vm.selectOption(option)
                vm.selectChip(1)
                testDispatcher.scheduler.runCurrent()
                assertNull("Each selected period starts in loading", vm.statsContent.value)
                testDispatcher.scheduler.advanceTimeBy(300)
                testDispatcher.scheduler.runCurrent()
                assertEquals(option, vm.statsContent.value?.option)
                assertEquals(1, vm.statsContent.value?.chipIndex)
                assertEquals("Real empty stats must still display zero", expected, vm.statsContent.value?.overview)
            }
        } finally {
            store.clear()
        }
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
    fun openingStatsNeverFabricatesListensFromPlayCounts() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playbackEventDao = mockk<PlaybackEventDao>(relaxed = true)
        val historyDao = mockk<HistoryDao>(relaxed = true)
        val playCountDao = mockk<PlayCountDao>(relaxed = true)
        val track = TrackEntity(
            id = "dil_haara",
            title = "Dil Haara",
            artist = "Sukhwinder Singh",
            album = "Tashan",
            thumbnail = "",
            duration = 352L
        )
        // Five starts of a 5:52 song used to become 5 x 5:52 = 29:20 "listened", replacing the
        // real measured events.
        coEvery { playCountDao.getAllPlayCounts() } returns listOf(
            PlayCountWithTrackTuple(PlayCountEntity("dil_haara", 5, System.currentTimeMillis()), track)
        )
        coEvery { playbackEventDao.getEventCount() } returns 3
        every { playbackEventDao.getFirstEventTimestamp() } returns flowOf(System.currentTimeMillis() - 3600_000L)

        val repo = StatsRepositoryImpl(trackDao, playbackEventDao, historyDao, playCountDao)
        repo.removeEstimatedListens()

        coVerify(exactly = 1) { playbackEventDao.deleteEstimatedEvents() }
        coVerify(exactly = 0) { playbackEventDao.clearAllEvents() }
        coVerify(exactly = 0) { playbackEventDao.insertEvents(any()) }
        coVerify(exactly = 0) { playbackEventDao.insertEvent(any()) }
    }

    @Test
    fun loggingWithoutMeasuredTimeDoesNotAssumeFullLength() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playbackEventDao = mockk<PlaybackEventDao>(relaxed = true)
        val repo = StatsRepositoryImpl(trackDao, playbackEventDao, mockk(relaxed = true), mockk(relaxed = true))
        repo.logPlaybackEvent(com.auralis.music.domain.model.Track(id = "t", title = "T", artist = "A", duration = 240L), 0L)
        coVerify(exactly = 0) { playbackEventDao.insertEvent(any()) }
    }

    @Test
    fun clearingStatsDoesNotRestoreOldPlaysOnNextOpen() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playbackEventDao = mockk<PlaybackEventDao>(relaxed = true)
        val historyDao = mockk<HistoryDao>(relaxed = true)
        val playCountDao = mockk<PlayCountDao>(relaxed = true)
        val track = TrackEntity(
            id = "old_track",
            title = "Old listen",
            artist = "Artist",
            album = null,
            thumbnail = "",
            duration = 180L
        )
        var playCounts = listOf(
            PlayCountWithTrackTuple(
                playCount = PlayCountEntity("old_track", 2, System.currentTimeMillis()),
                track = track
            )
        )
        var eventCount = 2
        coEvery { playCountDao.getAllPlayCounts() } coAnswers { playCounts }
        coEvery { historyDao.getHistoryWithTracks() } returns emptyList()
        coEvery { playbackEventDao.getEventCount() } coAnswers { eventCount }
        every { playbackEventDao.getFirstEventTimestamp() } returns flowOf(0L)
        coEvery { playCountDao.clearPlayCounts() } coAnswers { playCounts = emptyList() }
        coEvery { playbackEventDao.clearAllEvents() } coAnswers { eventCount = 0 }

        val repo = StatsRepositoryImpl(trackDao, playbackEventDao, historyDao, playCountDao)
        repo.clearListeningStats()
        repo.removeEstimatedListens()

        assertEquals(0, eventCount)
        assertTrue(playCounts.isEmpty())
        coVerify(exactly = 1) { historyDao.clearHistory() }
        coVerify(exactly = 0) { playbackEventDao.insertEvents(any()) }
    }
}
