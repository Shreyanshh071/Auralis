package com.auralis.music.data.network

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleArtistPhotoTest {
    private val photo = "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages211/v4/5a/ami-identity_cropped.png"

    @Test fun `same named artist must share catalogue titles before a portrait is used`() = kotlinx.coroutines.runBlocking {
        val pages = mutableListOf<String>()
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body = when (url.encodedPath) {
                "/search" -> """{"results":[
                    {"artistId":1,"artistName":"Krsna","artistLinkUrl":"https://music.apple.com/us/artist/wrong/1"},
                    {"artistId":2,"artistName":"KR${'$'}NA","artistLinkUrl":"https://music.apple.com/us/artist/right/2"}] }"""
                "/lookup" -> if (url.queryParameter("id") == "1")
                    """{"results":[{"trackName":"Unrelated song"}]}"""
                    else """{"results":[{"trackName":"Still Here"}]}"""
                else -> {
                    pages += url.encodedPath
                    """<meta property="og:image" content="$photo/1200x630cw.png">"""
                }
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        assertEquals("$photo/1200x1200bb.jpg", AppleArtistPhoto.find("KR${'$'}NA", listOf("Still Here"), http))
        assertEquals(listOf("/us/artist/right/2"), pages)
    }

    @Test fun `live verified portrait downloads at high resolution`() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_VERIFY_APPLE_PHOTOS") == "1")
        val start = System.nanoTime()
        val found = AppleArtistPhoto.find("Tame Impala", listOf("The Less I Know the Better", "Currents"))
        org.junit.Assert.assertNotNull(found)
        // The core test runtime has no Skia DLL; validate the downloaded JPEG with the JDK decoder.
        val image = NetworkClientProvider.okHttpClient.newCall(okhttp3.Request.Builder().url(found!!).build())
            .execute().use { response ->
                org.junit.Assert.assertTrue("Portrait HTTP ${response.code}", response.isSuccessful)
                javax.imageio.ImageIO.read(response.body!!.byteStream())
            }
        org.junit.Assert.assertNotNull("Portrait must decode as an image", image)
        org.junit.Assert.assertTrue("Portrait was ${image.width}x${image.height}", image.width >= 1000 && image.height >= 1000)
        println("Verified Apple portrait: ${image.width}x${image.height}, ${(System.nanoTime() - start) / 1_000_000}ms including lookup and decode")
    }

    @Test
    fun `the page's wide crop becomes the whole photo`() {
        val head = """<meta property="og:image" content="$photo/1200x630cw.png">"""
        assertEquals("$photo/1200x1200bb.jpg", AppleArtistPhoto.parsePhoto(head))
    }

    @Test
    fun `a record cover is not a photo`() {
        val cover = "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/aa/cover.jpg/1200x630cw.png"
        assertFalse(AppleArtistPhoto.isArtistPhoto(cover))
        assertNull(AppleArtistPhoto.parsePhoto("""<meta property="og:image" content="$cover">"""))
    }

    @Test
    fun `apple's two photo folders both count`() {
        assertTrue(AppleArtistPhoto.isArtistPhoto(photo))
        assertTrue(AppleArtistPhoto.isArtistPhoto("https://is1-ssl.mzstatic.com/image/thumb/Features115/v4/e1/pr_source.png/1200x630cw.png"))
    }

    @Test
    fun `names compare on letters and digits`() {
        assertEquals(AppleArtistPhoto.matchKey("Vishal & Shekhar"), AppleArtistPhoto.matchKey("Vishal-Shekhar"))
        assertEquals(AppleArtistPhoto.matchKey("KR\$NA"), AppleArtistPhoto.matchKey("Krsna"))
        assertEquals(AppleArtistPhoto.matchKey("Beyoncé"), AppleArtistPhoto.matchKey("Beyonce"))
    }

    @Test
    fun `titles drop what apple adds and prove nothing when short`() {
        assertEquals("blinding", AppleArtistPhoto.titleKey("Blinding - Single"))
        assertEquals("blinding", AppleArtistPhoto.titleKey("Blinding (feat. Someone)"))
        assertNull(AppleArtistPhoto.titleKey("War"))
    }
}
