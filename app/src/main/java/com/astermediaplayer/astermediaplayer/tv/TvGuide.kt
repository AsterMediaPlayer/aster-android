package com.astermediaplayer.astermediaplayer.tv

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.channels.ChannelTuner
import com.astermediaplayer.astermediaplayer.data.channels.PlaylistChannel
import com.astermediaplayer.astermediaplayer.data.channels.ProgramRole
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Aster's channels in the Android TV live guide: the channels themselves, and each one's schedule for the next day, which
 * is worked out on the device from the same lineup the Channels tab plays (so no server has to know about it).
 */
object TvGuide {
    private const val HOURS_AHEAD = 36L

    fun inputId(context: Context): String =
        TvContract.buildInputId(ComponentName(context, AsterTvInputService::class.java))

    /** Makes sure the app is signed in and its channels are loaded, as when the app itself opens. */
    suspend fun ensureChannels(context: Context): List<PlaylistChannel> {
        val app = context.applicationContext as AsterApp
        if (app.repository.session == null && !app.repository.restore()) return emptyList()
        if (ChannelTuner.orderedChannels.isEmpty()) app.channels.load(app.repository.session?.userName)
        return ChannelTuner.orderedChannels
    }

    /** Adds the channels to the guide (or updates the ones already there), then writes their schedules. Returns how many channels. */
    suspend fun sync(context: Context): Int {
        val channels = ensureChannels(context)
        if (channels.isEmpty()) return 0
        val resolver = context.contentResolver
        val input = inputId(context)
        val existing = mutableMapOf<String, Long>()
        resolver.query(TvContract.buildChannelsUriForInput(input), arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use {
            while (it.moveToNext()) it.getString(1)?.let { id -> existing[id] = it.getLong(0) }
        }
        for (channel in channels) {
            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, input)
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, channel.number.toString())
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.id)
            }
            val known = existing[channel.id]
            val rowId = if (known != null) {
                resolver.update(TvContract.buildChannelUri(known), values, null, null)
                known
            } else {
                resolver.insert(TvContract.Channels.CONTENT_URI, values)?.let { ContentUris.parseId(it) } ?: continue
            }
            writePrograms(context, rowId, channel)
        }
        // A channel that's gone from the lineup goes from the guide too.
        val current = channels.map { it.id }.toSet()
        existing.filterKeys { it !in current }.values.forEach { resolver.delete(TvContract.buildChannelUri(it), null, null) }
        return channels.size
    }

    private fun writePrograms(context: Context, channelRowId: Long, channel: PlaylistChannel) {
        val resolver = context.contentResolver
        resolver.delete(TvContract.buildProgramsUriForChannel(channelRowId), null, null)
        val from = PlaylistChannel.now()
        val rows = channel.programs(from - 3600, from + HOURS_AHEAD * 3600)
            .filter { it.role == ProgramRole.Programme }
            .map { program ->
                val item = program.item
                val overview = (item.source as? BaseItemDto)?.overview
                ContentValues().apply {
                    put(TvContract.Programs.COLUMN_CHANNEL_ID, channelRowId)
                    put(TvContract.Programs.COLUMN_TITLE, if (item.isEpisode) item.seriesName ?: item.displayTitle else item.displayTitle)
                    if (item.isEpisode) {
                        put(TvContract.Programs.COLUMN_EPISODE_TITLE, item.displayTitle)
                        item.parentIndexNumber?.let { put(TvContract.Programs.COLUMN_SEASON_DISPLAY_NUMBER, it.toString()) }
                        item.indexNumber?.let { put(TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString()) }
                    }
                    overview?.let { put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, it.take(256)) }
                    put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, (program.start * 1000).toLong())
                    put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, (program.end * 1000).toLong())
                }
            }
        if (rows.isNotEmpty()) resolver.bulkInsert(TvContract.Programs.CONTENT_URI, rows.toTypedArray())
    }
}
