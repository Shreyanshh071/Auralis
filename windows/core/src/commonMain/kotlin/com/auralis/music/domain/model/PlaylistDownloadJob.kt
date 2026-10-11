package com.auralis.music.domain.model

/** A playlist being (or done being) downloaded for offline play, as the Library shows it. */
data class PlaylistDownloadJob(
    val jobId: String,
    val playlistId: String,
    val playlistName: String,
    /** DOWNLOADING, COMPLETED, PARTIAL_FAILURE, FAILED or CANCELLED. */
    val status: String,
    val completedCount: Int,
    val failedCount: Int,
    val skippedCount: Int
)
