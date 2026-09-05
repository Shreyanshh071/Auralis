package com.auralis.music

import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Test
import java.io.File

class TimingInvestigationTest {

    @Test
    fun auditLoveMeNotAndCreep() {
        println("\n=======================================================")
        println("AUDIT 1: Radiohead - Creep (\"in a beautiful world\")")
        println("=======================================================")
        val creepFile = File("c:/Users/shrey/OneDrive/Desktop/Auralis/creep_raw.ttml")
        if (creepFile.exists()) {
            val lyrics = TtmlParser.parse(creepFile.readText(), LyricsProvider.BETTER_LYRICS)
            val line = lyrics.lines.firstOrNull { it.text.contains("beautiful", ignoreCase = true) }
            if (line != null) {
                println("LINE: \"${line.text}\" (time=${line.time}ms)")
                val spans = LyricsEngine.mapWordsToLineSpans(line.text, line.words)
                println("MAPPED SPANS COUNT: ${spans.size}")
                val words = line.words ?: emptyList()
                for (i in words.indices) {
                    val w = words[i]
                    val prevEnd = if (i > 0) words[i - 1].let { (it.duration?.let { d -> it.time + d }) ?: it.time } else null
                    val nextStart = if (i < words.size - 1) words[i + 1].time else null
                    val end = w.duration?.let { w.time + it }
                    val span = spans.firstOrNull { it.word === w }
                    println(
                        "WORD: \"${w.word}\" | charRange=[${span?.startIndex}, ${span?.endIndex}) " +
                            "| start=${w.time} | end=$end | dur=${w.duration}ms " +
                            "| prevEnd=$prevEnd | nextStart=$nextStart"
                    )
                }
            } else {
                println("Could not find line with 'beautiful' in Creep")
            }
        } else {
            println("creep_raw.ttml does not exist")
        }

        println("\n=======================================================")
        println("AUDIT 2: Ravyn Lenae - Love Me Not (\"see, right now... / i'll meet you...\")")
        println("=======================================================")
        val lmnFile = File("C:/Users/shrey/.gemini/antigravity-ide/brain/11fe7950-e4f6-477b-b08d-8884078404a2/scratch/love_me_not.ttml")
        if (lmnFile.exists()) {
            val lyrics = TtmlParser.parse(lmnFile.readText(), LyricsProvider.BETTER_LYRICS)
            val line = lyrics.lines.firstOrNull { it.text.contains("need you", ignoreCase = true) || it.text.contains("meet you", ignoreCase = true) }
            if (line != null) {
                println("LINE: \"${line.text}\" (time=${line.time}ms)")
                val spans = LyricsEngine.mapWordsToLineSpans(line.text, line.words)
                println("MAPPED SPANS COUNT: ${spans.size}")
                val words = line.words ?: emptyList()
                for (i in words.indices) {
                    val w = words[i]
                    val prevEnd = if (i > 0) words[i - 1].let { (it.duration?.let { d -> it.time + d }) ?: it.time } else null
                    val nextStart = if (i < words.size - 1) words[i + 1].time else null
                    val end = w.duration?.let { w.time + it }
                    val span = spans.firstOrNull { it.word === w }
                    println(
                        "WORD: \"${w.word}\" | charRange=[${span?.startIndex}, ${span?.endIndex}) " +
                            "| start=${w.time} | end=$end | dur=${w.duration}ms " +
                            "| prevEnd=$prevEnd | nextStart=$nextStart"
                    )
                }
            } else {
                println("Could not find target line in Love Me Not")
            }
        } else {
            println("love_me_not.ttml does not exist")
        }
        println("=======================================================\n")
    }
}
