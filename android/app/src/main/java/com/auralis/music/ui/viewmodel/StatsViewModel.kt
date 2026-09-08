package com.auralis.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.domain.model.ArtistStat
import com.auralis.music.domain.model.OptionStats
import com.auralis.music.domain.model.SongStat
import com.auralis.music.domain.model.StatsOverview
import com.auralis.music.domain.repository.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val statsRepository: StatsRepository
) : ViewModel() {

    val selectedOption = MutableStateFlow(OptionStats.CONTINUOUS)
    val selectedChipIndex = MutableStateFlow(0)

    val firstEventTimestamp: StateFlow<Long?> = statsRepository.observeFirstEventTimestamp()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val resolvedArtistPhotos = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        viewModelScope.launch {
            statsRepository.seedFromHistoryIfNeeded()
        }
    }

    fun getTimeRange(option: OptionStats, index: Int): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        return when (option) {
            OptionStats.CONTINUOUS -> {
                val from = when (index) {
                    0 -> now - 7L * 24 * 3600 * 1000L      // 1 week
                    1 -> now - 30L * 24 * 3600 * 1000L     // 1 month
                    2 -> now - 90L * 24 * 3600 * 1000L     // 3 months
                    3 -> now - 180L * 24 * 3600 * 1000L    // 6 months
                    4 -> now - 365L * 24 * 3600 * 1000L    // 1 year
                    else -> 0L                             // All time
                }
                Pair(from, now)
            }
            OptionStats.WEEKS -> {
                val endDay = today.minusDays(index.toLong() * 7L)
                val startDay = endDay.minusDays(6)
                val from = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = if (index == 0) now else endDay.atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
                Pair(from, to)
            }
            OptionStats.MONTHS -> {
                val targetYearMonth = YearMonth.from(today).minusMonths(index.toLong())
                val from = targetYearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val to = if (index == 0) {
                    now
                } else {
                    targetYearMonth.atEndOfMonth().atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
                }
                Pair(from, to)
            }
            OptionStats.YEARS -> {
                val targetYear = today.year - index
                val from = LocalDate.of(targetYear, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
                val to = if (index == 0) {
                    now
                } else {
                    LocalDate.of(targetYear, 12, 31).atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
                }
                Pair(from, to)
            }
        }
    }

    private val timeRangeFlow = combine(selectedOption, selectedChipIndex) { option, idx ->
        getTimeRange(option, idx)
    }

    val statsOverview: StateFlow<StatsOverview> = timeRangeFlow.flatMapLatest { (from, to) ->
        statsRepository.observeStatsOverview(from, to)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsOverview(0L, 0, 0, 0))

    val topSongs: StateFlow<List<SongStat>> = timeRangeFlow.flatMapLatest { (from, to) ->
        statsRepository.observeTopSongs(from, to, limit = 20)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawTopArtists: Flow<List<ArtistStat>> = timeRangeFlow.flatMapLatest { (from, to) ->
        statsRepository.observeTopArtists(from, to, limit = 15)
    }

    val topArtists: StateFlow<List<ArtistStat>> = combine(rawTopArtists, resolvedArtistPhotos) { artists, photos ->
        artists.map { artist ->
            val photo = photos[artist.name.trim().lowercase()] ?: artist.thumbnailUrl
            if (photo != null) artist.copy(thumbnailUrl = photo) else artist
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            rawTopArtists.collect { list ->
                list.forEach { artist ->
                    val key = artist.name.trim().lowercase()
                    if (key.isNotBlank() && !resolvedArtistPhotos.value.containsKey(key)) {
                        val cached = com.auralis.music.data.network.ArtistPhotoProvider.getCachedPhoto(artist.name)
                        if (cached != null) {
                            resolvedArtistPhotos.update { it + (key to cached) }
                        } else {
                            viewModelScope.launch(Dispatchers.IO) {
                                val photo = com.auralis.music.data.network.ArtistPhotoProvider.resolveArtistPhoto(artist.name)
                                if (photo != null) {
                                    resolvedArtistPhotos.update { it + (key to photo) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val topSong: StateFlow<SongStat?> = topSongs.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val topArtist: StateFlow<ArtistStat?> = topArtists.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectOption(option: OptionStats) {
        selectedOption.value = option
        selectedChipIndex.value = 0
    }

    fun selectChip(index: Int) {
        selectedChipIndex.value = index
    }

    fun generateDateChips(firstEventTs: Long?): List<Pair<Int, String>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstEventDate = if (firstEventTs != null && firstEventTs > 0) {
            Instant.ofEpochMilli(firstEventTs).atZone(zone).toLocalDate()
        } else {
            today.minusMonths(3)
        }

        return when (selectedOption.value) {
            OptionStats.CONTINUOUS -> listOf(
                0 to "1 week",
                1 to "1 month",
                2 to "3 months",
                3 to "6 months",
                4 to "1 year",
                5 to "All time"
            )
            OptionStats.WEEKS -> {
                val list = mutableListOf<Pair<Int, String>>()
                val weekFormatter = DateTimeFormatter.ofPattern("d MMM")
                var idx = 0
                var endDay = today
                while (idx < 12 && (endDay.isAfter(firstEventDate.minusWeeks(1)) || idx < 4)) {
                    val startDay = endDay.minusDays(6)
                    val label = "${weekFormatter.format(startDay)} - ${weekFormatter.format(endDay)}"
                    list.add(idx to label)
                    endDay = endDay.minusDays(7)
                    idx++
                }
                list
            }
            OptionStats.MONTHS -> {
                val list = mutableListOf<Pair<Int, String>>()
                var ym = YearMonth.from(today)
                val firstYm = YearMonth.from(firstEventDate)
                val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
                var idx = 0
                while (idx < 12 && (!ym.isBefore(firstYm) || idx < 4)) {
                    val label = monthFormatter.format(ym)
                    list.add(idx to label)
                    ym = ym.minusMonths(1)
                    idx++
                }
                list
            }
            OptionStats.YEARS -> {
                val list = mutableListOf<Pair<Int, String>>()
                var year = today.year
                var idx = 0
                val minYear = firstEventDate.year.coerceAtMost(year - 1)
                while (year >= minYear && idx < 5) {
                    list.add(idx to "$year")
                    year--
                    idx++
                }
                list
            }
        }
    }
}
