package com.auralis.music.data.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Which YouTube recording actually plays for a given id: official videos redirected to their
 * studio audio, and Spotify tracks matched to a YouTube recording. Lyrics timing and search read
 * it; the platform's stream resolver fills [matchedVideoIds] / [matchedDurations] as it matches
 * (and persists them however the platform stores settings).
 */
object RecordingMatches {
    val KNOWN_STUDIO_REPLACEMENTS = mapOf(
        "sBzrzS1Ag_g" to "PvM79DJ2PmM", // The Less I Know The Better (Official Video -> Studio Audio)
        "2g5xkLqIElU" to "rymYToIEL9o", // Borderline (Official Video -> Studio Audio)
        "pFptt7Cargc" to "NMRhx71bGo4", // Let It Happen (Official Video -> Studio Audio)
        "ila-hAUXR5U" to "cxKs2b5lRsA", // Flashing Lights (Official Video -> Studio Audio)
        "Co0tTeuUVhU" to "s40BTpfAELs", // Heartless (Official Video -> Studio Audio)
        "PsO6Zn4V07g" to "12hLNbXKCs4", // Stronger (Official Video -> Studio Audio)
        "LK7-_dgAVQE" to "N6_EvGT0ZfM", // Tauba Tauba (Official Video -> Studio Audio)
        "cWMxFX7QCbw" to "U4qD41gPQMU", // Softly (Official Video -> Studio Audio)
        "vX2cDW8up2g" to "0DS5jYQeiw0", // Winning Speech (Official Video -> Studio Audio)
        "XFkzRNyygfk" to "9RfVp-GhKfs", // Creep (Official Video -> Studio Audio)
        "1uYWYWPc9HU" to "nbCOAPR33ME", // Karma Police (Official Video -> Studio Audio)
        "u5CVsCnxyXg" to "7374CZQoS2Y", // No Surprises (Official Video -> Studio Audio)
        "n5h0qHwNrHk" to "6gDhsUWCHrg", // Fake Plastic Trees (Official Video -> Studio Audio)
        "QjQ_rG_c43A" to "6Zv9mSiZGBU", // No Cap (Official Video -> Studio Audio)
        "yS3vYw4oXG8" to "brXz6f3EPFM", // Prarthana (Official Video -> Studio Audio)
        "z6bEwQjU_Qc" to "mLaQwQHpP6A", // I Guess (Official Video -> Studio Audio)
        "BddP6PYo2gs" to "NJAv_7lHUIU", // Kesariya (Official Video -> Studio Audio)
        "IJq0yyWug1k" to "fsiPzT50ZiM", // Tum Hi Ho (Official Video -> Studio Audio)
        "ElZfdU54Cp8" to "YALvuUpY_b0", // Apna Bana Le (Official Video -> Studio Audio)
        "5i_Wc3uE6G0" to "zv-tbc4F818", // Zara Sa (Official Video -> Studio Audio)
        "2wVf4nUu8s8" to "XPu9ZE4Onzc", // Kya Mujhe Pyar Hai (Official Video -> Studio Audio)
        "M4-Ecx6h0tU" to "12pMB_mCBOo", // Labon Ko (Official Video -> Studio Audio)
        "z3UHfi9mpsg" to "1If9aw74Tj4", // Sunn Raha Hai (Official Video -> Studio Audio)
        "d8ITb6mZbi4" to "MEjnFgMh3qE", // Manwa Laage (Official Video -> Studio Audio)
        "h6lHUn20J5g" to "eSu6HHRn1UE", // Deewani Mastani (Official Video -> Studio Audio)
        "a18py61EcP4" to "qmBW9-fUvag", // Tajdar-e-Haram (Official Video -> Studio Audio)
        "vpO8sZdxOGI" to "3M3o3Ak1qBY", // Jeene Laga Hoon (Official Video -> Studio Audio)
        "BadBAMnPXSc" to "swcCuuQKGJ4", // Pehli Nazar Mein (Official Video -> Studio Audio)
        "cswfR85D7jM" to "HfpR4tAmI7E", // Love Me Not (Official Video -> Studio Audio)
        "tvTRZJ-4EyI" to "18_J_7v0i4k", // HUMBLE. (Official Video -> Studio Audio)
        "xFYQQPAOz7Y" to "4wOLVrGHiIU"  // Lose Yourself (Official Video -> Studio Audio)
    )

    val KNOWN_STUDIO_DURATIONS = mapOf(
        "4wOLVrGHiIU" to 322L, // Lose Yourself (Official Studio Audio)
        "3Mr0pDNVms0" to 216L, // Heaven Knows I'm Miserable Now (2008 Remaster)
        "HfpR4tAmI7E" to 213L, // Love Me Not (Studio Audio)
        "18_J_7v0i4k" to 177L, // HUMBLE. (Studio Audio)
        "6gDhsUWCHrg" to 290L, // Fake Plastic Trees (Studio Audio)
        "9RfVp-GhKfs" to 239L, // Creep (Studio Audio)
        "nbCOAPR33ME" to 261L, // Karma Police (Studio Audio)
        "7374CZQoS2Y" to 229L, // No Surprises (Studio Audio)
        "PvM79DJ2PmM" to 216L, // The Less I Know The Better (Studio Audio)
        "rymYToIEL9o" to 238L, // Borderline (Studio Audio)
        "NMRhx71bGo4" to 467L, // Let It Happen (Studio Audio)
        "cxKs2b5lRsA" to 238L, // Flashing Lights (Studio Audio)
        "s40BTpfAELs" to 211L, // Heartless (Studio Audio)
        "12hLNbXKCs4" to 312L  // Stronger (Studio Audio)
    )

    /** Spotify (or redirected) id -> the YouTube recording it plays. */
    val matchedVideoIds = ConcurrentHashMap<String, String>()

    /** Spotify id -> the matched recording's length in seconds. */
    val matchedDurations = ConcurrentHashMap<String, Long>()

    fun getMatchedVideoId(id: String): String? = matchedVideoIds[id] ?: KNOWN_STUDIO_REPLACEMENTS[id]

    fun getEffectiveDurationSec(videoId: String, originalDurationSec: Long): Long {
        val matchedId = getMatchedVideoId(videoId) ?: videoId
        return matchedDurations[videoId] ?: KNOWN_STUDIO_DURATIONS[matchedId] ?: originalDurationSec
    }
}
