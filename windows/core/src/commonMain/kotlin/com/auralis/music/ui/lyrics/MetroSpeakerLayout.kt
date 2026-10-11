package com.auralis.music.ui.lyrics

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsAnimationMode

/** Which edge a speaker's lines hug in the MetroLyrics speaker-aware layout. */
enum class MetroSpeakerSide { START, END, CENTER }

/**
 * Presentation of one lyric line in a multi-vocalist song.
 *
 * Derived only from the provider's own vocal agent ids ([LyricLine.agent]) — never
 * from the lyric text or line order.
 *
 * @property speakerKey normalized agent id, or `null` for a line the source left unattributed.
 * @property slot stable per-speaker ordinal (0 = first individual vocalist). `-1` for the
 *   ensemble agent and for unattributed lines.
 * @property side edge the line aligns to; `null` means "use the regular text position".
 * @property runId identifies a consecutive run of lines by the same speaker.
 * @property isRunStart first line of its run (a speaker change happens right before it).
 * @property isRunEnd last line of its run.
 */
data class MetroSpeakerStyle(
    val speakerKey: String?,
    val slot: Int,
    val side: MetroSpeakerSide?,
    val runId: Int,
    val isRunStart: Boolean,
    val isRunEnd: Boolean
) {
    val isEnsemble: Boolean get() = speakerKey == MetroSpeakerLayout.ENSEMBLE_AGENT
}

/**
 * Builds the MetroLyrics speaker-aware presentation model from parsed lyric lines.
 *
 * The layout is only engaged when the source names at least two distinct individual
 * vocalists. Anything less (no agents, a single agent, a single agent plus the ensemble)
 * returns `null`, and MetroLyrics keeps its existing layout untouched.
 */
object MetroSpeakerLayout {

    /** Apple Music / AMLL TTML id for "all vocalists together". */
    const val ENSEMBLE_AGENT = "v1000"

    private val NUMBERED_AGENT = Regex("""^v?(\d+)$""")

    /**
     * Speaker layout for the lyrics view under [appearance]: MetroLyrics only, and owned
     * entirely by the lyric data. The regular lyrics alignment (Centre/Left/Right) and
     * `respectAgentPositioning` deliberately play no part in whether it engages.
     */
    fun forAppearance(lines: List<LyricLine>, appearance: AppearanceSettings): List<MetroSpeakerStyle>? =
        if (LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation) == LyricsAnimationMode.METRO_LYRICS) {
            build(lines)
        } else null

    fun normalize(agent: String?): String? =
        agent?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * @return one style per input line (same indices), or `null` when the song does not
     *   carry reliable multi-speaker metadata.
     */
    fun build(lines: List<LyricLine>): List<MetroSpeakerStyle>? {
        if (lines.isEmpty()) return null

        // Background ad-libs normally carry their paragraph's agent; one that does not
        // belongs to the lead line it accompanies.
        val keys = arrayOfNulls<String>(lines.size)
        var lastLeadKey: String? = null
        lines.forEachIndexed { i, line ->
            val own = normalize(line.agent)
            keys[i] = if (line.isBackground) own ?: lastLeadKey else own
            if (!line.isBackground) lastLeadKey = own
        }

        // Speakers are counted from lead lines only: an ad-lib alone never makes a duet.
        val firstSeen = LinkedHashMap<String, Int>()
        lines.forEachIndexed { i, line ->
            val key = keys[i] ?: return@forEachIndexed
            if (!line.isBackground && key != ENSEMBLE_AGENT && key !in firstSeen) {
                firstSeen[key] = i
            }
        }
        if (firstSeen.size < 2) return null

        // Numbered ids keep the TTML convention (v1 first, v2 second, ...) regardless of
        // who opens the song; any other ids follow in order of first appearance.
        val slots = firstSeen.keys
            .sortedWith(
                compareBy<String>(
                    { NUMBERED_AGENT.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                    { firstSeen.getValue(it) }
                )
            )
            .withIndex()
            .associate { (slot, key) -> key to slot }

        val runIds = IntArray(lines.size)
        var runId = 0
        for (i in lines.indices) {
            if (i > 0 && keys[i] != keys[i - 1]) runId++
            runIds[i] = runId
        }

        return lines.indices.map { i ->
            val key = keys[i]
            val slot = key?.let { slots[it] } ?: -1
            MetroSpeakerStyle(
                speakerKey = key,
                slot = slot,
                side = when {
                    key == ENSEMBLE_AGENT -> MetroSpeakerSide.CENTER
                    slot < 0 -> null
                    slot % 2 == 0 -> MetroSpeakerSide.START
                    else -> MetroSpeakerSide.END
                },
                runId = runIds[i],
                isRunStart = i == 0 || runIds[i - 1] != runIds[i],
                isRunEnd = i == lines.lastIndex || runIds[i + 1] != runIds[i]
            )
        }
    }
}
