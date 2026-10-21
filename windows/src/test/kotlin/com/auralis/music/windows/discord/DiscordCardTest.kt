package com.auralis.music.windows.discord

import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class DiscordCardTest {
    private val track = DiscordTrack(Track("abcdefghijk","Song","Artist",album="Album",albumId="MPRE-album",duration=200,
        thumbnail="https://example.com/cover.jpg"), "UCartist")
    private val cfg = DiscordCardSettings(enabled=true)
    private fun payload(c: DiscordCardSettings=cfg, t: DiscordTrack=track, playing:Boolean=true, played:Boolean=true) =
        discordActivity(t,c,playing,played,20000,200000,1000000)!!

    @Test fun allCardSlotsAndLinksFollowSelectedContent() {
        val p = payload(cfg.copy(appName="Custom app",statusLine="line1",line1="album",line2="artist_album",line3="title",
            linkLine1=true,linkLine2=true,linkCover=true,button1="artist",button2="album"))
        assertEquals("Custom app",p.getString("name"))
        assertEquals("Album",p.getString("details"))
        assertEquals("Artist — Album",p.getString("state"))
        assertEquals(1,p.getInt("status_display_type"))
        assertTrue(p.getString("details_url").endsWith("MPRE-album"))
        assertTrue(p.getString("state_url").endsWith("UCartist"))
        assertEquals("Song",p.getJSONObject("assets").getString("large_text"))
        assertTrue(p.getJSONObject("assets").getString("large_url").endsWith("MPRE-album"))
        assertEquals(2,p.getJSONArray("buttons").length())
        assertTrue(p.getJSONObject("assets").has("small_image"))
    }
    @Test fun artworkBadgeThirdLineAndProgressControlsAffectPayload() {
        assertFalse(payload(cfg.copy(cover=false)).has("assets"))
        val assets = payload(cfg.copy(badge=false,line3="off")).getJSONObject("assets")
        assertFalse(assets.has("small_image")); assertFalse(assets.has("large_text"))
        assertFalse(payload(cfg.copy(progress=false)).has("timestamps"))
        val ts = payload().getJSONObject("timestamps")
        assertEquals(980000,ts.getLong("start")); assertEquals(1180000,ts.getLong("end"))
    }
    @Test fun pausedRestoredDisabledAndPrivateStatesDoNotLeakDetails() {
        assertNull(discordActivity(track,cfg,false,true,0,200000))
        assertNull(discordActivity(track,cfg.copy(showPaused=true),false,false,0,200000))
        assertFalse(payload(cfg.copy(showPaused=true),playing=false).has("timestamps"))
        assertNull(discordActivity(track,cfg.copy(enabled=false),true,true,0,200000))
        val private = payload(cfg.copy(hideDetails=true,linkLine1=true,linkLine2=true,linkCover=true))
        assertEquals(setOf("type","name","status_display_type"),private.keySet())
        assertFalse(private.toString().contains("Song"))
    }
    @Test fun localFilesMissingMetadataAndDuplicateButtonsAreHandled() {
        val local = track.copy(track=track.track.copy(id="C:/Music/private.mp3",source=TrackSource.LOCAL,thumbnail="C:/Music/cover.png"))
        val p = payload(cfg.copy(linkLine1=true,linkLine2=true,linkCover=true,button1="listen",button2="artist"),local)
        assertFalse(p.has("details_url"));assertFalse(p.has("state_url"));assertFalse(p.has("buttons"));assertFalse(p.has("assets"))
        assertFalse(p.toString().contains("C:/"))
        val missing = track.copy(track=track.track.copy(album=null,albumId=null))
        val fallback=payload(cfg.copy(line1="album",line2="off",linkLine1=true),missing)
        assertEquals("Song",fallback.getString("details"));assertFalse(fallback.has("state"))
        assertTrue(fallback.getString("details_url").contains("watch?v="))
        assertEquals(1,payload(cfg.copy(button1="listen",button2="listen")).getJSONArray("buttons").length())
    }
    private class MemoryDisk:KeyValueStore {
        private val values=mutableMapOf<String,String>()
        override fun getString(key:String,default:String)=values[key] ?: default
        override fun putStrings(values:Map<String,String>) { this.values.putAll(values) }
        override fun clear()=values.clear()
    }
    @Test fun persistedControlsMigrateOnceAndResetKeepsEnablePreference() {
        val disk=MemoryDisk();val store=DiscordCardStore(disk)
        store.migrate(true)
        store.update { cfg.copy(appName="My player",line1="artist",linkLine1=true,hideDetails=true) }
        val reopened=DiscordCardStore(disk)
        assertEquals(store.settings.value,reopened.settings.value)
        reopened.migrate(false);assertTrue(reopened.settings.value.enabled)
        reopened.reset();assertEquals(cfg,reopened.settings.value)
        assertEquals(DiscordCardSettings(),DiscordCardSettings.parse("invalid"))
    }
    @Test fun desktopConnectionRetriesWithoutLoginAndDisablingClearsPresence() = runBlocking<Unit> {
        val disk=MemoryDisk();val store=DiscordCardStore(disk);store.update { cfg }
        val sends=CopyOnWriteArrayList<String>()
        val transport=object:DiscordTransport {
            @Volatile override var connected=false
            var attempts=0
            override fun connect():Boolean { connected=++attempts>1;return connected }
            override fun send(activity:JSONObject?):Boolean { sends += activity?.toString() ?: "clear";return true }
            override fun close() { connected=false }
        }
        val search=Proxy.newProxyInstance(SearchRepository::class.java.classLoader,arrayOf(SearchRepository::class.java)) { _,method,_ ->
            if(method.name=="searchArtists") emptyList<Artist>() else error("Unexpected search")
        } as SearchRepository
        val input=MutableStateFlow(DiscordNow(track,true,true,20000,200000))
        val controller=WindowsDiscordPresence(null,null,search,store,transport,input)
        try {
            withTimeout(5000) { while(sends.isEmpty()) delay(20) }
            assertTrue(transport.attempts>=2)
            assertEquals("Song",JSONObject(sends.first()).getString("details"))
            store.update { it.copy(enabled=false) }
            withTimeout(3000) { while("clear" !in sends || transport.connected) delay(20) }
            assertFalse(transport.connected)
        } finally { controller.close() }
    }
}
