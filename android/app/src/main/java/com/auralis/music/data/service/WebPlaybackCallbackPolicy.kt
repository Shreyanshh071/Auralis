package com.auralis.music.data.service

internal fun acceptsWebPlaybackCallback(
    activeVideoId: String?, activeRequestId: Long, callbackRequestId: Long
): Boolean = activeVideoId != null && activeRequestId == callbackRequestId

internal fun acceptsWebPlaybackPage(
    activeVideoId: String?, activeRequestId: Long, navigationRequestId: Long, pageVideoId: String?
): Boolean = activeVideoId != null && activeVideoId == pageVideoId && activeRequestId == navigationRequestId
