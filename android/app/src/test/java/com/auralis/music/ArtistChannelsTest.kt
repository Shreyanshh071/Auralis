package com.auralis.music

import com.auralis.music.data.network.ArtistChannels
import com.auralis.music.data.network.ArtistRuns
import com.auralis.music.domain.model.Artist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ArtistChannelsTest {
    private val gorillaz = "UCfIXdjDQH9Fau7y99_Orpjw"
    private val deLaSoul = "UCzDm_PWrh8mGIVXTN6WaRoA"
    private val deLaSoulDecoy = "UCdecoyDeLaSoul0000000aa"

    @Before fun reset() = ArtistChannels.clear()
    @After fun cleanUp() = ArtistChannels.clear()

    private fun run(text: String, browseId: String? = null) = JSONObject().put("text", text).apply {
        if (browseId != null) put(
            "navigationEndpoint", JSONObject().put(
                "browseEndpoint", JSONObject().put("browseId", browseId).put(
                    "browseEndpointContextSupportedConfigs", JSONObject().put(
                        "browseEndpointContextMusicConfig", JSONObject().put("pageType", "MUSIC_PAGE_TYPE_ARTIST")
                    )
                )
            )
        )
    }

    @Test
    fun songLinksDecideWhichChannelANameOpens() {
        // Feel Good Inc.: [Gorillaz →UC] [", "] [De La Soul →UC] [" • "] [Demon Days →MPRE]
        ArtistRuns.fromRuns(
            JSONArray()
                .put(run("Gorillaz", gorillaz)).put(run(", ")).put(run("De La Soul", deLaSoul))
                .put(run(" • ")).put(run("Demon Days", "MPREb_demondays"))
        )
        assertEquals(deLaSoul, ArtistChannels.resolve(Artist(id = "", name = "De La Soul")).id)
        assertEquals(deLaSoul, ArtistChannels.resolve(Artist(id = "yt:de la soul", name = "de la soul")).id)
        assertEquals(gorillaz, ArtistChannels.channelFor("Gorillaz"))
    }

    @Test
    fun aKnownChannelIdIsNeverReplaced() {
        ArtistChannels.learn("De La Soul", deLaSoul)
        assertEquals(deLaSoulDecoy, ArtistChannels.resolve(Artist(id = deLaSoulDecoy, name = "De La Soul")).id)
    }

    @Test
    fun theChannelSongsLinkMostOftenWinsASharedName() {
        ArtistChannels.learn("Nirvana", "UCnirvanaSixtiesBand0000")
        repeat(3) { ArtistChannels.learn("Nirvana", "UCnirvanaGrungeBand00000") }
        assertEquals("UCnirvanaGrungeBand00000", ArtistChannels.channelFor("Nirvana"))
    }

    @Test
    fun unknownNamesStayUnresolved() {
        assertNull(ArtistChannels.channelFor("Nobody Linked"))
        ArtistChannels.learn("Not A Channel", "MPREb_album")
        assertNull(ArtistChannels.channelFor("Not A Channel"))
    }

    @Test
    fun aTinyExactNameAccountLosesToTheRealArtistsLongerChannelName() {
        val pick = ArtistChannels.pick(
            "De La Soul",
            listOf(
                Artist(id = deLaSoulDecoy, name = "De La Soul", subscribers = "19 subscribers"),
                Artist(id = deLaSoul, name = "WeAreDeLaSoul", subscribers = "333K subscribers")
            )
        )
        assertEquals(deLaSoul, pick?.id)
    }

    @Test
    fun theExactNameStillBeatsASmallerLookalike() {
        val pick = ArtistChannels.pick(
            "Juice WRLD",
            listOf(
                Artist(id = "UCbabyJuiceWrld000000000", name = "BABY juice WRLD", subscribers = "66 subscribers"),
                Artist(id = "UCjuiceWrldOfficial00000", name = "Juice WRLD", subscribers = "12M subscribers")
            )
        )
        assertEquals("UCjuiceWrldOfficial00000", pick?.id)
    }

    @Test
    fun anExactNameArtistOfRealSizeKeepsItsPageOverABiggerContainingName() {
        val pick = ArtistChannels.pick(
            "Drake",
            listOf(
                Artist(id = "UCdrakeOfficial000000000", name = "Drake", subscribers = "2M subscribers"),
                Artist(id = "UCdrakeBellOfficial00000", name = "Drake Bell Music", subscribers = "20M subscribers")
            )
        )
        assertEquals("UCdrakeOfficial000000000", pick?.id)
    }
}
