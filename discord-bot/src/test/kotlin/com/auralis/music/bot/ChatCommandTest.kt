package com.auralis.music.bot

import kotlin.test.*

class ChatCommandTest {
    @Test fun readsTheCommandAndItsArguments() {
        assertEquals(ChatCommand("play", "blinding lights"), parseCommand("auplay blinding lights"))
        assertEquals(ChatCommand("play", "Mixed Case"), parseCommand("  AUPLAY   Mixed Case  "))
        assertEquals(ChatCommand("skip", ""), parseCommand("auskip"))
        assertEquals(ChatCommand("play", ""), parseCommand("auplay"))
    }

    @Test fun bothHelpWordsWork() {
        assertEquals(ChatCommand("commands", ""), parseCommand("aucommands"))
        assertEquals(ChatCommand("help", ""), parseCommand("auhelp"))
    }

    @Test fun commandListHasTwoHeadingsAndEveryCommand() {
        val sections = commandSections()
        assertEquals(listOf("🎵 Music Commands", "✨ Misc"), sections.map { it.first })
        val listed = sections.joinToString(" ") { it.second }
        COMMANDS.forEach { assertTrue("`au${it.name}" in listed, it.name) }
        assertTrue(sections.all { it.second.length <= 1024 })
        assertTrue(sections.last().second.startsWith("`aunp`, `aulyrics`"))
    }

    @Test fun leavesOrdinaryChatAlone() {
        listOf("audio is broken", "august", "austin", "au play song", "play song", "/play song", "", "auto").forEach {
            assertNull(parseCommand(it), it)
        }
    }

    @Test fun onlyTheFirstLineCounts() {
        assertEquals(ChatCommand("play", "first line"), parseCommand("auplay first line\nsecond line"))
        assertNull(parseCommand("hello\nauplay song"))
    }

    @Test fun everyCommandHasAUsageLine() {
        assertEquals("💡 Use: `auplay <song or link>`", usage("play"))
        COMMANDS.forEach { assertEquals(ChatCommand(it.name, ""), parseCommand("au${it.name}")) }
    }
}

class VoiceControlTest {
    @Test fun onlyPeopleInTheBotsChannelCanControlIt() {
        assertTrue(canControl(memberChannelId = 1, botChannelId = 1))
        assertFalse(canControl(memberChannelId = 2, botChannelId = 1))
        assertFalse(canControl(memberChannelId = null, botChannelId = 1))
    }

    @Test fun withoutABotChannelTheyStillNeedToBeInOne() {
        assertTrue(canControl(memberChannelId = 2, botChannelId = null))
        assertFalse(canControl(memberChannelId = null, botChannelId = null))
    }
}

class IdleTest {
    @Test fun playingToSomeoneIsNotIdle() {
        assertNull(idleReason(listeners = 1, playing = true, paused = false))
    }

    @Test fun nothingPlayingPausedOrAloneIsIdle() {
        assertEquals(IdleReason.NOTHING_PLAYING, idleReason(listeners = 1, playing = false, paused = false))
        assertEquals(IdleReason.PAUSED, idleReason(listeners = 1, playing = true, paused = true))
        assertEquals(IdleReason.ALONE, idleReason(listeners = 0, playing = true, paused = false))
    }
}

class PlaylistLinkTest {
    private val id = "PLabcdefghijklmnop"

    @Test fun readsPlaylistAndAlbumLinks() {
        assertEquals(PlaylistLink(id, null), AuralisCatalog.playlistLink("https://music.youtube.com/playlist?list=$id"))
        assertEquals(PlaylistLink(id, null), AuralisCatalog.playlistLink("youtube.com/playlist?list=$id&si=abc"))
        assertEquals(PlaylistLink("OLAK5uy_abc", null), AuralisCatalog.playlistLink("https://music.youtube.com/playlist?list=OLAK5uy_abc"))
        assertEquals(PlaylistLink("MPREb_abc", null), AuralisCatalog.playlistLink("https://music.youtube.com/browse/MPREb_abc"))
    }

    @Test fun keepsTheLinkedSongToStartFrom() {
        assertEquals(PlaylistLink(id, "dQw4w9WgXcQ"), AuralisCatalog.playlistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=$id"))
    }

    @Test fun mixesStaySongLinksButCuratedListsDont() {
        assertNull(AuralisCatalog.playlistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"))
        assertEquals("RDCLAK5uy_abc", AuralisCatalog.playlistLink("https://music.youtube.com/playlist?list=RDCLAK5uy_abc")?.id)
    }

    @Test fun shuffleWordComesBeforeTheLink() {
        val text = "SHUFFLE https://music.youtube.com/playlist?list=$id"
        assertTrue(AuralisCatalog.asksToShuffle(text))
        assertEquals(PlaylistLink(id, null), AuralisCatalog.playlistLink(text))
        assertFalse(AuralisCatalog.asksToShuffle("https://music.youtube.com/playlist?list=$id"))
        assertFalse(AuralisCatalog.asksToShuffle("shuffle"))
        assertNull(AuralisCatalog.playlistLink("shuffle dance"))
    }

    @Test fun shuffleOnASingleSongIsAPlainRequest() {
        assertEquals("blinding lights", AuralisCatalog.withoutShuffle("shuffle blinding lights"))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", AuralisCatalog.withoutShuffle("Shuffle   https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("shuffle", AuralisCatalog.withoutShuffle("shuffle"))
        assertEquals("blinding lights", AuralisCatalog.withoutShuffle("blinding lights"))
    }

    @Test fun songLinksAndPlainTextAreNotPlaylists() {
        assertNull(AuralisCatalog.playlistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(AuralisCatalog.playlistLink("https://youtu.be/dQw4w9WgXcQ"))
        listOf("FLOWERS", "PLASTIC LOVE", "LMFAO party rock").forEach { assertNull(AuralisCatalog.playlistLink(it), it) }
    }
}
