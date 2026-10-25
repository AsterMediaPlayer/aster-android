package com.astermediaplayer.astermediaplayer.data.emby

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserItemDataDto

/**
 * The Jellyfin SDK's models list fields as required that Emby leaves out (a play count, a stream's "is original", a query
 * result's start index). Rather than a list of them, this reads the models' own definitions and gives each missing required field a
 * neutral value (false, zero, empty), so an Emby response decodes into the same models as a Jellyfin one.
 */
object EmbyShapes {
    /** [json] with the missing required fields of whichever model it is filled in; anything that isn't one is returned as it was. */
    fun fill(json: JsonElement): JsonElement {
        val descriptor = modelFor(json) ?: return json
        return if (json is JsonArray) JsonArray(json.mapNotNull { fillOrNull(it, descriptor) }) else fill(json, descriptor)
    }

    /** The model a response is, from its shape (the routes aren't known here); null when it's none the app reads this way. */
    private fun modelFor(json: JsonElement): SerialDescriptor? = when (json) {
        is JsonObject -> when {
            "Items" in json && "TotalRecordCount" in json -> BaseItemDtoQueryResult.serializer().descriptor
            "AccessToken" in json && "User" in json -> AuthenticationResult.serializer().descriptor
            "MediaSources" in json && "PlaySessionId" in json -> PlaybackInfoResponse.serializer().descriptor
            "Policy" in json || "HasPassword" in json -> UserDto.serializer().descriptor
            "Name" in json && "Id" in json -> BaseItemDto.serializer().descriptor
            "IsFavorite" in json && "PlaybackPositionTicks" in json -> UserItemDataDto.serializer().descriptor
            else -> null
        }
        is JsonArray -> json.firstOrNull()?.let(::modelFor)
        else -> null
    }

    private fun fill(json: JsonElement, descriptor: SerialDescriptor): JsonElement = fillOrNull(json, descriptor) ?: json

    /** [fill], or null when [json] is a model with a value the SDK can't hold in a field it requires (an unknown kind of item or stream). */
    private fun fillOrNull(json: JsonElement, descriptor: SerialDescriptor): JsonElement? = when (descriptor.kind) {
        StructureKind.CLASS -> if (json is JsonObject) fillClass(json, descriptor) else json
        // An entry the SDK can't hold (a tag among the items, an attachment among the streams) is left out of its list.
        StructureKind.LIST ->
            if (json is JsonArray && descriptor.elementsCount > 0) {
                val element = descriptor.getElementDescriptor(0)
                // A list of enum values (a library's locked fields) keeps those the SDK knows; Emby has more ("SortName").
                if (element.kind == SerialKind.ENUM) JsonArray(json.filter { knows(element, it) })
                else JsonArray(json.mapNotNull { fillOrNull(it, element) })
            } else json
        StructureKind.MAP -> if (json is JsonObject && descriptor.elementsCount > 1) JsonObject(json.mapValues { (_, v) -> fill(v, descriptor.getElementDescriptor(1)) }) else json
        else -> json
    }

    private fun fillClass(json: JsonObject, descriptor: SerialDescriptor): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>(json.size + 4)
        out.putAll(json)
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = descriptor.getElementDescriptor(i)
            val present = json[name]
            when {
                present != null && present !is JsonNull -> {
                    if (child.kind == SerialKind.ENUM && !knows(child, present)) {
                        // An unknown value in a field that may be empty is emptied; in one that may not, the whole entry can't be held.
                        if (child.isNullable || descriptor.isElementOptional(i)) out.remove(name) else return null
                    } else {
                        out[name] = fillOrNull(present, child) ?: return null
                    }
                }
                !descriptor.isElementOptional(i) -> out[name] = defaultFor(child)
            }
        }
        return JsonObject(out)
    }

    /** Whether [value] names one of the enum's members. */
    private fun knows(enum: SerialDescriptor, value: JsonElement): Boolean {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return (0 until enum.elementsCount).any { enum.getElementName(it) == text }
    }

    /** What stands in for a required field the server didn't send. */
    private fun defaultFor(descriptor: SerialDescriptor): JsonElement {
        if (descriptor.isNullable) return JsonNull
        return when (val kind = descriptor.kind) {
            PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(0)
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> {
                val name = descriptor.serialName
                JsonPrimitive(
                    if (name.contains("UUID", ignoreCase = true)) "00000000-0000-0000-0000-000000000000"
                    else if (name.contains("DateTime", ignoreCase = true)) "0001-01-01T00:00:00.0000000Z"
                    else "",
                )
            }
            StructureKind.LIST -> JsonArray(emptyList())
            StructureKind.MAP -> JsonObject(emptyMap())
            StructureKind.CLASS -> fillClass(JsonObject(emptyMap()), descriptor) ?: JsonNull
            SerialKind.ENUM -> JsonPrimitive(descriptor.getElementName(0))
            is PolymorphicKind, SerialKind.CONTEXTUAL -> JsonNull
            else -> JsonNull
        }
    }
}
