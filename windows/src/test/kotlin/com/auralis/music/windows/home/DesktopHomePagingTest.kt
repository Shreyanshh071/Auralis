package com.auralis.music.windows.home

import com.auralis.music.domain.model.HomeSection
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopHomePagingTest {
    private fun shelf(id: String, token: String?) = HomeSection(id, id,
        items = listOf(Track(id = id, title = id, artist = "Artist")), continuationToken = token)

    @Test fun followsNextPageAndStopsRepeatedTokens() = runBlocking {
        val requests = mutableListOf<String>()
        val paging = DesktopHomePaging { token -> requests += token; listOf(shelf(token, "two")) }
        paging.reset(listOf(shelf("first", "one")))
        paging.loadMore()
        paging.loadMore()
        paging.loadMore()
        assertEquals(listOf("one", "two"), requests)
        assertEquals(listOf("one", "two"), paging.state.value.sections.map { it.id })
        assertFalse(paging.state.value.canLoadMore)
    }

    @Test fun failedRequestCanRetryWithoutDiscardingLoadedContent() = runBlocking {
        var fail = true
        val paging = DesktopHomePaging { if (fail) emptyList() else listOf(shelf("page", null)) }
        paging.reset(listOf(shelf("first", "one")))
        paging.loadMore()
        assertTrue(paging.state.value.failed)
        assertFalse(paging.state.value.loading)
        fail = false
        paging.loadMore()
        assertEquals("page", paging.state.value.sections.single().id)
        assertFalse(paging.state.value.failed)
    }

    @Test fun identicalPageWithANewTokenDoesNotLoop() = runBlocking {
        val paging = DesktopHomePaging { listOf(shelf("first", "another-token")) }
        paging.reset(listOf(shelf("first", "one")))
        paging.loadMore()
        assertFalse(paging.state.value.canLoadMore)
        assertTrue(paging.state.value.sections.isEmpty())
    }

    @Test fun moodChangeRejectsOldInFlightPageAndDuplicateRequests() = runBlocking {
        val response = CompletableDeferred<List<HomeSection>>()
        var requests = 0
        val paging = DesktopHomePaging { requests++; response.await() }
        paging.reset(listOf(shelf("first", "one")))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { paging.loadMore() }
        paging.loadMore()
        assertEquals(1, requests)
        paging.reset(listOf(shelf("focus", null)))
        response.complete(listOf(shelf("stale", "two")))
        job.join()
        assertTrue(paging.state.value.sections.isEmpty())
        assertFalse(paging.state.value.canLoadMore)
    }

    @Test fun homeParserKeepsPageContinuationSeparateFromCarouselContinuation() {
        val json = org.json.JSONObject("""{"continuationContents":{"sectionListContinuation":{
            "continuations":[{"nextContinuationData":{"continuation":"page-two"}}],
            "contents":[{"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"For you"}]}}},
              "continuations":[{"nextContinuationData":{"continuation":"carousel-only"}}],
              "contents":[{"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"A song"}]},
                "subtitle":{"runs":[{"text":"Tame Impala"}]},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"track"}}
              }}]
            }}]}}}""")
        val parser = com.auralis.music.data.network.InnerTubeClient::class.java
            .getDeclaredMethod("parseHomePage", org.json.JSONObject::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sections = (parser.invoke(com.auralis.music.data.network.InnerTubeClient(), json)
            as Pair<*, List<HomeSection>>).second
        assertEquals("page-two", sections.single().continuationToken)
        assertEquals("track", sections.single().items.single().id)
    }
}
