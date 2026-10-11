package com.auralis.music.data.service

fun acceptsWebPlaybackCallback(
    activeVideoId: String?, activeRequestId: Long, callbackRequestId: Long
): Boolean = activeVideoId != null && activeRequestId == callbackRequestId

fun acceptsWebPlaybackPage(
    activeVideoId: String?, activeRequestId: Long, navigationRequestId: Long, pageVideoId: String?
): Boolean = activeVideoId != null && activeVideoId == pageVideoId && activeRequestId == navigationRequestId
