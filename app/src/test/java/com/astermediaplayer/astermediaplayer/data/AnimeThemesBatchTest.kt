package com.astermediaplayer.astermediaplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AnimeThemes is asked for shows in batches of 50: one request, each name its own aliased search. */
class AnimeThemesBatchTest {
    @Test fun aBatchIsOneQueryWithAnAliasAndAVariablePerName() {
        val query = AnimeThemesLookup.query(3)
        assertTrue(query.startsWith("query(\$n0:String!,\$n1:String!,\$n2:String!){"))
        assertTrue("s0:search(search:\$n0,first:5)" in query && "s2:search(search:\$n2,first:5)" in query)
        assertEquals(1, query.split("query(").size - 1)
    }

    @Test fun aFullBatchIsFiftyAliases() {
        assertEquals(50, AnimeThemesLookup.BATCH)
        assertEquals(50, Regex("s\\d+:search").findAll(AnimeThemesLookup.query(AnimeThemesLookup.BATCH)).count())
    }

    private val reply = JSONObject("""
        {"s0":{"anime":[
          {"slug":"beastars","year":2019,"animethemes":[
            {"type":"OP","sequence":1,"song":{"title":{"romaji":"Wild Side"},"performances":[{"artist":{"name":{"main":"ALI"}}}]},"animethemeentries":[{"episodes":"1-12"}]},
            {"type":"ED","sequence":null,"song":null,"animethemeentries":[{"episodes":null}]},
            {"type":"IN","sequence":1,"song":null,"animethemeentries":[]}]},
          {"slug":"beastars_2nd_season","year":null,"animethemes":[]}]},
         "s1":{"anime":[]}}
    """)

    @Test fun eachNameGetsItsShowsAndTheirThemes() {
        val parsed = AnimeThemesLookup.parse(reply, 3)
        val beastars = parsed[0]!!
        assertEquals(listOf("beastars", "beastars_2nd_season"), beastars.map { it.slug })
        assertEquals(listOf(2019, null), beastars.map { it.year })
        // An insert song isn't an opening or ending.
        val themes = beastars[0].themes
        assertEquals(2, themes.size)
        assertEquals(KnownTheme(true, 1, "Wild Side", listOf("ALI"), "1-12"), themes[0])
        assertEquals(KnownTheme(false, 1, null, emptyList(), null), themes[1])
    }

    @Test fun aNameWithNoShowIsEmptyAndOneTheReplyLeftOutIsNull() {
        val parsed = AnimeThemesLookup.parse(reply, 3)
        assertEquals(emptyList<ShowCandidate>(), parsed[1])
        assertNull(parsed[2])
    }

    @Test fun whatTheStoreHoldsIsNotAsked() = runBlocking {
        val kept = mapOf("Prefetch Kept One" to listOf(ShowCandidate("a", 2020, emptyList())), "prefetch kept two" to emptyList())
        val store = object : ShowStore {
            override suspend fun candidates(name: String) = kept.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            override suspend fun put(shows: Map<String, List<ShowCandidate>>) = error("nothing was asked, so nothing is kept")
        }
        // No request is made (the client would fail), and the names are the same however they're cased.
        val asked = AnimeThemesLookup.prefetch(OkHttpClient(), listOf("Prefetch Kept One", "PREFETCH KEPT TWO", "prefetch kept one"), store, pauseMs = 0)
        assertEquals(Warmed(asked = 0, complete = true), asked)
    }
}
