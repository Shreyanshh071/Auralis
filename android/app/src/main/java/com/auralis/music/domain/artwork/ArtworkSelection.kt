package com.auralis.music.domain.artwork

/** Explicit artwork decision, independent of playback identity. No provider ranking is implied.
 * VERIFIED_RELEASE evidence must be established by the caller; this model does not verify catalogs.
 */
data class ArtworkSelection(
    val trackId: String,
    val thumbnail: String,
    val album: String?,
    val provider: String,
    val providerItemId: String,
    val releaseId: String?,
    val evidence: String,
    val basis: ArtworkSelectionBasis,
    val revision: Long = 0
)

enum class ArtworkSelectionBasis { USER_SELECTION, VERIFIED_RELEASE }
