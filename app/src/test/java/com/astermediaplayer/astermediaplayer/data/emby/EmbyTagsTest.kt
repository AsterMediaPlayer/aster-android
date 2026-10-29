package com.astermediaplayer.astermediaplayer.data.emby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Emby's tags as named pairs, read and written as Jellyfin's plain tags. As the Windows app's tests, from Emby 4.11. */
class EmbyTagsTest {
    @Test fun aPlaylistsTagItemsAreReadAsTags() {
        val reply = Json.parseToJsonElement("""{"Items":[{"Name":"Cute","Type":"Playlist","TagItems":[{"Name":"aster:channel","Id":768},{"Name":"aster:number=7","Id":769}]}],"TotalRecordCount":1}""")
        val item = EmbyTags.read(reply).jsonObject["Items"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("aster:channel", "aster:number=7"), item["Tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun tagsAlreadyThereAreKept() {
        val item = Json.parseToJsonElement("""{"Name":"X","Tags":["a"],"TagItems":[{"Name":"b"}]}""")
        assertEquals(listOf("a"), EmbyTags.read(item).jsonObject["Tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun savedTagsGoAsTagItems() {
        val body = Json.parseToJsonElement("""{"Name":"Cute","Tags":["aster:channel","aster:mode=shuffle"]}""")
        val pairs = EmbyTags.write(body).jsonObject["TagItems"] as JsonArray
        assertEquals(listOf("aster:channel", "aster:mode=shuffle"), pairs.map { (it as JsonObject)["Name"]!!.jsonPrimitive.content })
    }
}
