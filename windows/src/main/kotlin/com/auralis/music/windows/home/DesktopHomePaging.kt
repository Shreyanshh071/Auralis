package com.auralis.music.windows.home

import com.auralis.music.domain.model.HomeSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HomePagingState(
    val sections: List<HomeSection> = emptyList(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val failed: Boolean = false
)

/** Only follows the Home page token, never an individual carousel's continuation. */
internal class DesktopHomePaging(private val fetch: suspend (String) -> List<HomeSection>) {
    private val mutable = MutableStateFlow(HomePagingState())
    val state = mutable.asStateFlow()
    private var token: String? = null
    private var generation = 0
    private val consumed = mutableSetOf<String>()
    private val knownItems = mutableSetOf<String>()
    private fun itemKeys(sections: List<HomeSection>) = sections.flatMap { section ->
        section.items.map { "track:${it.id}" } + section.albums.map { "collection:${it.id}" }
    }.toSet()

    fun reset(sections: List<HomeSection>) {
        generation++
        consumed.clear()
        knownItems.clear()
        knownItems += itemKeys(sections)
        token = sections.firstNotNullOfOrNull { it.continuationToken?.takeIf(String::isNotBlank) }
        mutable.value = HomePagingState(canLoadMore = token != null)
    }

    suspend fun loadMore() {
        val next = token ?: return
        if (mutable.value.loading || next in consumed) return
        val requestGeneration = generation
        mutable.value = mutable.value.copy(loading = true, failed = false)
        try {
            val page = fetch(next)
            if (requestGeneration != generation) return
            // The shared client represents an unsuccessful request as an empty page.
            if (page.isEmpty()) {
                mutable.value = mutable.value.copy(loading = false, failed = true)
                return
            }
            consumed += next
            val keys = itemKeys(page)
            if (keys.all { it in knownItems }) {
                token = null
                mutable.value = mutable.value.copy(loading = false, canLoadMore = false)
                return
            }
            knownItems += keys
            token = page.firstNotNullOfOrNull { it.continuationToken?.takeIf(String::isNotBlank) }
                ?.takeUnless { it in consumed }
            mutable.value = HomePagingState(mutable.value.sections + page, canLoadMore = token != null)
        } catch (cancelled: CancellationException) {
            if (requestGeneration == generation) mutable.value = mutable.value.copy(loading = false)
            throw cancelled
        } catch (_: Exception) {
            if (requestGeneration == generation) mutable.value = mutable.value.copy(loading = false, failed = true)
        }
    }
}
