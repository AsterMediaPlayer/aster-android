package com.astermediaplayer.astermediaplayer.data.emby

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Emby's tags are named pairs, `TagItems: [{"Name": "aster:channel", "Id": 768}]`, which it sends and saves in place of
 * Jellyfin's `Tags: ["aster:channel"]`; a playlist's tags are what make it a channel. Read, they're given to the SDK as `Tags`;
 * saved, they go to Emby as `TagItems`. As the Windows app's.
 */
object EmbyTags {
    /** [json] with each item that has `TagItems` and no `Tags` given its tags' names as `Tags`, however deep it sits. */
    fun read(json: JsonElement): JsonElement = when (json) {
        is JsonArray -> JsonArray(json.map(::read))
        is JsonObject -> {
            val inner = JsonObject(json.mapValues { (_, value) -> read(value) })
            val items = inner["TagItems"] as? JsonArray
            if (items == null || (inner["Tags"] != null && inner["Tags"] !is JsonNull)) inner
            else JsonObject(inner + ("Tags" to JsonArray(items.mapNotNull { (it as? JsonObject)?.get("Name")?.jsonPrimitive?.contentOrNull?.let(::JsonPrimitive) })))
        }
        else -> json
    }

    /** An item being saved ([body], Jellyfin's shape) with its `Tags` as Emby's `TagItems`, which Emby keeps and `Tags` it ignores. */
    fun write(body: JsonElement): JsonElement {
        val item = body as? JsonObject ?: return body
        val tags = item["Tags"] as? JsonArray ?: return body
        val pairs = JsonArray(tags.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { name -> buildJsonObject { put("Name", JsonPrimitive(name)) } } })
        return JsonObject(item + ("TagItems" to pairs))
    }
}
