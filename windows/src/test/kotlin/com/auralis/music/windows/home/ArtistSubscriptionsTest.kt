package com.auralis.music.windows.home

import com.auralis.music.windows.profile.YouTubeMusicClient
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ArtistSubscriptionsTest {
    @Test fun followSetsExplicitSubscriptionOnOfficialChannelInConnectedAccount() = runBlocking {
        val writes = mutableListOf<Pair<String, String>>()
        val client = YouTubeMusicClient("LOGIN_INFO=test; SAPISID=test") { url, body, headers ->
            when {
                body == null -> """{"LOGGED_IN":true,"DELEGATED_SESSION_ID":"brand","SESSION_INDEX":2}"""
                url.contains("/browse?") -> """{"subscribeButtonRenderer":{"channelId":"UCofficial","subscribed":true}}"""
                else -> {
                    assertEquals("2", headers["X-Goog-AuthUser"])
                    assertEquals("brand", headers["X-Goog-PageId"])
                    writes += url.substringAfter("/v1/").substringBefore('?') to body.getJSONArray("channelIds").getString(0)
                    "{}"
                }
            }
        }
        client.resolveScope()
        val subscriptions = ArtistSubscriptions({ true }, { client })
        subscriptions.load(listOf("UCartist"))
        subscriptions.setSubscribed("UCartist", true)
        assertEquals(true, subscriptions.subscribed("UCartist"))
        subscriptions.setSubscribed("UCartist", false)
        assertEquals(false, subscriptions.subscribed("UCartist"))
        assertEquals(listOf("subscription/subscribe" to "UCofficial", "subscription/unsubscribe" to "UCofficial"), writes,
            "Follow must set the desired state, even when YouTube is already subscribed")
    }

    @Test fun failedSubscriptionDoesNotClaimFollowingSucceeded() = runBlocking {
        val client = YouTubeMusicClient("SAPISID=test") { url, _, _ ->
            if (url.contains("/browse?")) """{"subscribeButtonRenderer":{"channelId":"UCofficial","subscribed":false}}"""
            else """{"error":{"code":403}}"""
        }
        val subscriptions = ArtistSubscriptions({ true }, { client })
        subscriptions.load(listOf("UCartist"))
        assertFailsWith<IllegalStateException> { subscriptions.setSubscribed("UCartist", true) }
        assertEquals(false, subscriptions.subscribed("UCartist"))
    }

    @Test fun disconnectedAccountCannotWriteSubscriptions() = runBlocking {
        val subscriptions = ArtistSubscriptions({ false }, { error("Must not request a client while disconnected") })
        assertFailsWith<IllegalStateException> { subscriptions.setSubscribed("UCartist", true) }
        assertNull(subscriptions.subscribed("UCartist"))
    }
}
