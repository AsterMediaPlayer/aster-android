package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Saving an item's tags means posting the whole item back to `POST /Items/{id}`, which the server reads as a full
 * replacement. The SDK can't be trusted to post it back as it came: it reads every date into the device's time zone and
 * writes it from there, and .NET can't read two of the results, so the server refuses the save with a 400 (report
 * 20261116-Q53259):
 * - Jellyfin's "no date", the first of January in year 1, comes back west of Greenwich in year 0.
 * - A date from before the device's zone kept standard time (1883 in the US, later in much of the world) is written
 *   with that zone's local mean time, an offset with seconds (-05:50:36), which .NET's ISO 8601 reader refuses. The
 *   "no date" falls here in most zones east of Greenwich.
 */

/**
 * [date] when the server can read it as the SDK will write it from [zone]; null (no date) when it can't: before year 2, or
 * at an offset with seconds.
 */
fun readableDate(date: LocalDateTime?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
    date?.takeIf { it.year >= 2 && zone.rules.getOffset(it).totalSeconds % 60 == 0 }

/** [item], the server's own JSON for an item, with [tags] as its tags and everything else exactly as it came. */
fun withTags(item: String, tags: List<String>): String = JSONObject(item).put("Tags", JSONArray(tags)).toString()

/**
 * [record] ready to post back with [tags], for servers reached through the SDK's model (Emby): dates the server couldn't
 * read are left out, which it takes as no date; and the parts the save never reads but that carry dates of their own
 * (the user's played data, the chapters' picture dates, a channel's current program) are left out too.
 */
fun forSaving(record: BaseItemDto, tags: List<String>, zone: ZoneId = ZoneId.systemDefault()): BaseItemDto {
    fun readable(date: LocalDateTime?) = readableDate(date, zone)
    return record.copy(
        tags = tags,
        dateCreated = readable(record.dateCreated),
        dateLastMediaAdded = readable(record.dateLastMediaAdded),
        premiereDate = readable(record.premiereDate),
        endDate = readable(record.endDate),
        startDate = readable(record.startDate),
        userData = null,
        chapters = null,
        currentProgram = null,
    )
}
