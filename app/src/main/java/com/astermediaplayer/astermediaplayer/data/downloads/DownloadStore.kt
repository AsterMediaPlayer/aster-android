package com.astermediaplayer.astermediaplayer.data.downloads

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID

// Movies, episodes and audiobooks saved for offline use, the Kotlin counterpart of the Apple apps' `DownloadStore` and
// `AudiobookDownloads`. The server prepares each video as an HLS stream at the chosen quality and Media3's download
// manager saves it segment by segment, in a foreground service, resuming after interruptions. A book's files are saved
// as they are. Both play from the device through the same cache, whether or not the server can be reached.

/** The quality downloads are made at. */
enum class DownloadQuality(val title: String, val detail: String, val maxBitrate: Int, val maxWidth: Int?) {
    Original("Original", "The video as it is, when the device can play it. Largest files.", 120_000_000, null),
    High("High · 1080p", "Up to 8 Mbps, about 3.5 GB an hour.", 8_000_000, 1920),
    Medium("Medium · 720p", "Up to 4 Mbps, about 1.8 GB an hour.", 4_000_000, 1280),
    Low("Low · 480p", "Up to 1.5 Mbps, about 0.7 GB an hour.", 1_500_000, 854),
}

enum class DownloadKind { Video, Book }

enum class DownloadState { Downloading, Finished, Failed }

/** A skip marker saved with a download, so Skip Intro works offline. */
data class SavedSegment(val intro: Boolean, val startTicks: Long, val endTicks: Long)

/** One downloaded (or downloading) video or book, kept in the store's index. */
data class DownloadRecord(
    /** The item's id (a book's is its first part's). */
    val id: String,
    val kind: DownloadKind,
    val title: String,
    val subtitle: String? = null,
    val state: DownloadState = DownloadState.Downloading,
    val progress: Double = 0.0,
    val quality: String? = null,
    /** The size expected before it finishes, then the size on disk. */
    val expectedBytes: Long = 0,
    val bytesOnDisk: Long = 0,
    val audioTitle: String? = null,
    val runTimeTicks: Long = 0,
    /** Where viewing got to, kept here so offline resumes work. */
    val positionTicks: Long = 0,
    val added: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    /** The stream's address for a video; the Media3 ids and weights of a book's files. */
    val uri: String? = null,
    val parts: List<String> = emptyList(),
    val partWeights: List<Double> = emptyList(),
    val segments: List<SavedSegment> = emptyList(),
    /** The text subtitle track saved alongside a video. */
    val subtitleFile: String? = null,
    val subtitleMime: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleLabel: String? = null,
    /** A book's author. */
    val author: String? = null,
    /** True while paused for the network the settings allow. */
    val waiting: Boolean = false,
    /** A book's place: which part, with [positionTicks] into it. */
    val positionPart: Int = 0,
    /** When that place was noted, so listening done offline goes to the server only when it.s newer than what the server heard. */
    val positionUpdated: Long = 0,
) {
    val isEpisode: Boolean get() = kind == DownloadKind.Video && subtitle != null && title != subtitle
}

sealed class DownloadException(message: String) : Exception(message) {
    class Disabled : DownloadException("Downloads are turned off in Settings › Downloads.")
    class NotAllowed : DownloadException("Your Jellyfin account isn't allowed to download media. The server's administrator can allow it in the user's settings.")
    class NoRoom(needed: Long, available: Long) : DownloadException("This needs about ${DownloadStore.format(needed)}, and the device has ${DownloadStore.format(available)} free.")
    class OverLimit(needed: Long, remaining: Long) : DownloadException("This needs about ${DownloadStore.format(needed)}, and ${DownloadStore.format(remaining)} of your downloads limit is left. Raise the limit or remove a download in Settings › Downloads.")
    class Server(message: String) : DownloadException(message)
}

@UnstableApi
class DownloadStore(private val context: Context, private val repo: AsterRepository, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = File(context.filesDir, "downloads").also { it.mkdirs() }
    private val indexFile = File(root, "index.json")
    private val databaseProvider = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(root, "cache"), NoOpCacheEvictor(), databaseProvider)

    private val _records = MutableStateFlow(loadIndex())
    /** Newest first. */
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    private val upstream = DataSource.Factory {
        DefaultHttpDataSource.Factory().setDefaultRequestProperties(repo.accessToken?.let { mapOf("X-Emby-Token" to it) }.orEmpty()).createDataSource()
    }

    /**
     * Reads the saved parts from the cache and goes to the server for the rest; nothing is written to it, so only
     * downloads fill it. A player built on this plays a download offline.
     */
    val cacheDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * Reads a download from the cache only. The server streams each HLS segment without saying how long it is, so the
     * cache can't tell where a segment ends and would ask the network for more; here whatever isn't cached reads as the
     * end of the data, so a download plays the same with the network on, off or flaky.
     */
    fun offlineMediaSourceFactory(): androidx.media3.exoplayer.source.DefaultMediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { CachedOnlyDataSource() }.setCacheWriteDataSinkFactory(null),
    )

    /** One download at a time reads the server's single-pass HLS preparation in order, as the Apple apps keep it. */
    val manager: DownloadManager = DownloadManager(
        context, databaseProvider, cache, upstream, Runnable::run,
    ).apply {
        maxParallelDownloads = 1
        minRetryCount = 5
        addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh(download.request.id, finalException)
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh(download.request.id, null)
            override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
                _records.value.filter { it.state == DownloadState.Downloading }.forEach { refresh(it.parts.firstOrNull() ?: it.id, null) }
            }
        })
    }

    init {
        // Wi-Fi only unless allowed; the manager pauses and carries on as the network comes and goes.
        scope.launch {
            settings.settings.map { it.downloadCellular }.distinctUntilChanged().collect { cellular ->
                manager.requirements = Requirements(if (cellular) Requirements.NETWORK else Requirements.NETWORK_UNMETERED)
            }
        }
        // Progress, while anything's coming down.
        scope.launch {
            while (true) {
                delay(1000)
                if (manager.currentDownloads.isNotEmpty()) refreshAll()
            }
        }
        // Whatever the index says is coming that the manager has lost (the app was cleared mid-download) failed.
        scope.launch {
            delay(500)
            _records.value.filter { it.state == DownloadState.Downloading }.forEach { refresh(it.parts.firstOrNull() ?: it.id, null) }
        }
        // Deletions cut short when the app last quit.
        scope.launch { root.listFiles { f -> f.name.startsWith(".trash-") }?.forEach { it.deleteRecursively() } }
    }

    // MARK: Looking up

    fun record(id: String): DownloadRecord? = _records.value.firstOrNull { it.id == id }

    /** The finished download of a video, ready to play. */
    fun playable(itemId: UUID): DownloadRecord? = record(itemId.toString())?.takeIf { it.kind == DownloadKind.Video && it.state == DownloadState.Finished }

    /** Whether a book's files are all here. */
    fun isBookSaved(bookId: UUID): Boolean = record(bookId.toString())?.let { it.kind == DownloadKind.Book && it.state == DownloadState.Finished } == true

    fun folder(id: String) = File(root, id)

    fun artwork(id: String): File? = File(folder(id), "artwork.jpg").takeIf { it.exists() }

    /** The saved stream as a player item, with its saved subtitle track. */
    fun mediaItem(record: DownloadRecord): MediaItem {
        val builder = MediaItem.Builder().setUri(record.uri).setMimeType(MimeTypes.APPLICATION_M3U8).setMediaId(record.id)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(record.title).build())
        val file = record.subtitleFile?.let { File(it) }?.takeIf { it.exists() }
        if (file != null) {
            builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(record.subtitleMime ?: MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(record.subtitleLanguage).setLabel(record.subtitleLabel).setId("128").setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build(),
            ))
        }
        return builder.build()
    }

    // MARK: Space

    /** What downloads take now, counting the expected size of those still coming, so a new one can't overrun the limit as they finish. */
    val reservedBytes: Long get() = _records.value.sumOf {
        when (it.state) { DownloadState.Finished -> it.bytesOnDisk; DownloadState.Downloading -> maxOf(it.expectedBytes, onDisk(it)); DownloadState.Failed -> 0L }
    }

    /** What downloads take on the device now, finished or not. */
    val usedBytes: Long get() = _records.value.sumOf {
        when (it.state) { DownloadState.Finished -> it.bytesOnDisk; DownloadState.Downloading -> onDisk(it); DownloadState.Failed -> 0L }
    }

    private fun onDisk(r: DownloadRecord) = (r.expectedBytes * r.progress).toLong()

    fun freeBytes(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)

    private suspend fun checkRoom(bytes: Long) {
        val s = settings.current()
        // Leave the device some breathing room.
        if (bytes > freeBytes() - 500_000_000) throw DownloadException.NoRoom(bytes, maxOf(freeBytes(), 0))
        if (s.downloadLimitGb > 0) {
            val limit = s.downloadLimitGb * 1_000_000_000L
            if (reservedBytes + bytes > limit) throw DownloadException.OverLimit(bytes, maxOf(limit - reservedBytes, 0))
        }
    }

    // MARK: Downloading

    /**
     * Asks the server for the title at the chosen quality and starts saving it, with its skip markers, artwork and, if
     * wanted, a subtitle track. [audioIndex] null takes the server's default track; [subtitleChoice] null follows the
     * server's default, -1 saves none. A picture-based subtitle (PGS, VobSub) is burned into the video.
     */
    suspend fun start(item: BaseItemDto, canDownload: Boolean, audioIndex: Int? = null, subtitleChoice: Int? = null) {
        val s = settings.current()
        if (!s.downloadsEnabled) throw DownloadException.Disabled()
        if (!canDownload) throw DownloadException.NotAllowed()
        val id = item.id.toString()
        record(id)?.let { if (it.state != DownloadState.Failed) return }

        val quality = s.downloadQuality
        val itemSource = item.mediaSources?.firstOrNull()
        val itemStreams = itemSource?.mediaStreams ?: item.mediaStreams.orEmpty()
        val burnIn = subtitleChoice?.takeIf { it >= 0 }?.let { c -> itemStreams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == c && !it.isTextSubtitleStream } }?.index
        val info = repo.downloadInfo(
            item.id, quality.maxBitrate, quality.maxWidth, s.downloadHevc,
            // Track choices apply only when the request names the file.
            mediaSourceId = if (audioIndex != null || burnIn != null) itemSource?.id else null,
            audioIndex = audioIndex, subtitleIndex = burnIn ?: -1,
        )
        info.errorCode?.let { throw DownloadException.Server("The server couldn't prepare this title ($it).") }
        val source = info.mediaSources.firstOrNull()
        val path = source?.transcodingUrl ?: throw DownloadException.Server("The server didn't offer a stream to download.")
        val url = repo.absoluteUrl(path)

        // The size to expect: the stream's bitrate over the running time.
        val ticks = item.runTimeTicks ?: source.runTimeTicks ?: 0L
        val bitrate = minOf(quality.maxBitrate, source.bitrate ?: quality.maxBitrate).toDouble()
        val expected = (bitrate * (ticks / 10_000_000.0) / 8).toLong()
        checkRoom(expected)

        val streams = source.mediaStreams.orEmpty()
        var subtitle: MediaStream? = null
        if (burnIn == null && (subtitleChoice != null || s.downloadSubtitles)) {
            val chosen = subtitleChoice ?: source.defaultSubtitleStreamIndex
            subtitle = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == chosen && it.isTextSubtitleStream }
        }
        val audioTrack = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == (audioIndex ?: source.defaultAudioStreamIndex) }

        val folder = folder(id)
        withContext(Dispatchers.IO) { folder.deleteRecursively(); folder.mkdirs() }
        val record = DownloadRecord(
            id = id, kind = DownloadKind.Video,
            title = if (item.type == BaseItemKind.EPISODE) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty(),
            subtitle = if (item.type == BaseItemKind.EPISODE) listOfNotNull(episodeCode(item), item.name).joinToString(" · ") else item.productionYear?.toString(),
            quality = quality.name, expectedBytes = expected, runTimeTicks = ticks,
            audioTitle = audioTrack?.let { it.title ?: it.displayTitle },
            uri = url, parts = listOf(id),
        )
        mutate { listOf(record) + it.filter { r -> r.id != id } }

        val request = DownloadRequest.Builder(id, Uri.parse(url)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        DownloadService.sendAddDownload(context, AsterDownloadService::class.java, request, /* foreground = */ true)

        // Artwork, skip markers and the subtitle track while the video downloads.
        scope.launch {
            val artworkUrl = listOf(ImageType.THUMB, ImageType.BACKDROP, ImageType.PRIMARY).firstNotNullOfOrNull { repo.imageUrl(item, it, 600) }
            artworkUrl?.let { runCatching { withContext(Dispatchers.IO) { File(folder, "artwork.jpg").writeBytes(URL(it).readBytes()) } } }
            val segments = runCatching { repo.segments(item.id) }.getOrDefault(emptyList()).mapNotNull {
                when (it.type) {
                    MediaSegmentType.INTRO -> SavedSegment(true, it.startTicks, it.endTicks)
                    MediaSegmentType.OUTRO -> SavedSegment(false, it.startTicks, it.endTicks)
                    else -> null
                }
            }
            var saved: File? = null
            var mime: String? = null
            if (subtitle != null && subtitle.index != null) runCatching {
                val codec = subtitle.codec?.lowercase().orEmpty()
                val (ext, type) = when (codec) {
                    "ass", "ssa" -> "ass" to MimeTypes.TEXT_SSA
                    "vtt", "webvtt" -> "vtt" to MimeTypes.TEXT_VTT
                    else -> "srt" to MimeTypes.APPLICATION_SUBRIP
                }
                val address = subtitle.deliveryUrl?.let(repo::absoluteUrl) ?: repo.subtitleUrl(item.id, source.id!!, subtitle.index!!, ext)
                val file = File(folder, "subtitles.$ext")
                withContext(Dispatchers.IO) { file.writeBytes(URL(address).readBytes()) }
                saved = file; mime = type
            }
            mutate { list -> list.map { if (it.id == id) it.copy(segments = segments, subtitleFile = saved?.path, subtitleMime = mime, subtitleLanguage = subtitle?.language, subtitleLabel = subtitle?.displayTitle ?: subtitle?.title) else it } }
        }
    }

    /** Saves a book's files as they are. */
    suspend fun startBook(book: Audiobook, canDownload: Boolean) {
        val s = settings.current()
        if (!s.downloadsEnabled) throw DownloadException.Disabled()
        if (!canDownload) throw DownloadException.NotAllowed()
        val id = book.id.toString()
        record(id)?.let { if (it.state != DownloadState.Failed) return }
        // The server's size is sometimes short of the file; the bitrate over the running time is the other estimate, and the larger is the safer to plan space by.
        val sizes = book.parts.map { part ->
            val source = part.mediaSources?.firstOrNull()
            val seconds = (part.runTimeTicks ?: 0L) / 10_000_000.0
            maxOf(source?.size ?: 0L, ((source?.bitrate ?: 0) * seconds / 8).toLong(), (seconds * 8_000).toLong())
        }
        val expected = sizes.sum()
        checkRoom(expected)
        val ids = book.parts.map { "book:$id:${it.id}" }
        val record = DownloadRecord(
            id = id, kind = DownloadKind.Book, title = book.title, author = book.author, expectedBytes = expected,
            runTimeTicks = (book.duration * 10_000_000).toLong(), parts = ids, partWeights = sizes.map { it.toDouble().coerceAtLeast(1.0) },
        )
        withContext(Dispatchers.IO) { folder(id).also { it.deleteRecursively(); it.mkdirs() } }
        mutate { listOf(record) + it.filter { r -> r.id != id } }
        book.parts.forEachIndexed { i, part ->
            DownloadService.sendAddDownload(context, AsterDownloadService::class.java, DownloadRequest.Builder(ids[i], Uri.parse(repo.audioStreamUrl(part.id))).build(), true)
        }
        // The book itself, so its page and player open with no connection.
        runCatching { withContext(Dispatchers.IO) { File(folder(id), "book.json").writeText(bookToJson(book)) } }
        scope.launch {
            repo.bookCoverUrl(book, 400)?.let { runCatching { withContext(Dispatchers.IO) { File(folder(id), "artwork.jpg").writeBytes(URL(it).readBytes()) } } }
            mutate { list -> list.map { it } }
        }
    }

    /** Carries on with a failed download: from the part already saved, which the manager keeps. */
    suspend fun retry(id: String, canDownload: Boolean, book: Audiobook? = null, item: BaseItemDto? = null) {
        val record = record(id) ?: return
        if (!canDownload) throw DownloadException.NotAllowed()
        mutate { list -> list.map { if (it.id == id) it.copy(state = DownloadState.Downloading, errorMessage = null) else it } }
        for (part in record.parts) {
            val d = manager.downloadIndex.getDownload(part)
            if (d != null) DownloadService.sendSetStopReason(context, AsterDownloadService::class.java, part, Download.STOP_REASON_NONE, true)
            else if (record.kind == DownloadKind.Video && record.uri != null) {
                val request = DownloadRequest.Builder(part, Uri.parse(record.uri)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                DownloadService.sendAddDownload(context, AsterDownloadService::class.java, request, true)
            }
        }
        // A failed download isn't restarted by the manager on its own: removing and adding again carries on from what's cached.
        for (part in record.parts) {
            val d = manager.downloadIndex.getDownload(part)
            if (d != null && d.state == Download.STATE_FAILED) DownloadService.sendAddDownload(context, AsterDownloadService::class.java, d.request, true)
        }
    }

    /** Stops a download, or deletes a finished one, and its files. */
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        val doomed = _records.value.filter { it.id in ids }
        mutate { list -> list.filter { it.id !in ids } }
        for (record in doomed) {
            for (part in record.parts) manager.removeDownload(part)
            val dir = folder(record.id)
            // Moved aside first (a quick rename), so a download of the same title started straight away can't lose its new files.
            val trash = File(root, ".trash-${UUID.randomUUID()}")
            if (dir.exists() && dir.renameTo(trash)) scope.launch { trash.deleteRecursively() }
        }
    }

    fun remove(id: String) = remove(setOf(id))
    fun removeAll() = remove(_records.value.map { it.id }.toSet())

    /** A downloaded book as it was saved, with where listening got to on this device. */
    fun savedBook(id: UUID): Audiobook? {
        val record = record(id.toString())?.takeIf { it.kind == DownloadKind.Book } ?: return null
        return runCatching {
            val o = JSONObject(File(folder(record.id), "book.json").readText())
            val parts = o.getJSONArray("parts").let { a -> List(a.length()) { i -> bookJson.decodeFromString(BaseItemDto.serializer(), a.getString(i)) } }
            Audiobook(
                id = UUID.fromString(record.id), title = o.getString("title"), author = o.string("author"), series = o.string("series"),
                seriesIndex = if (o.isNull("seriesIndex")) null else o.getInt("seriesIndex"), parts = placed(parts, record),
            )
        }.onFailure { android.util.Log.w("Aster", "Saved book unreadable", it) }.getOrNull()
    }

    /** [book] from the server, at the place listening got to on this device when that's newer than what the server heard. */
    fun withLocalProgress(book: Audiobook): Audiobook {
        val record = record(book.id.toString())?.takeIf { it.kind == DownloadKind.Book && it.positionUpdated > 0 } ?: return book
        val part = book.parts.getOrNull(record.positionPart) ?: return book
        return if (heardSince(part, record)) book else book.copy(parts = placed(book.parts, record))
    }

    /** Whether the server heard of [part] after this device noted [record]'s place, give or take a minute. */
    private fun heardSince(part: BaseItemDto, record: DownloadRecord): Boolean {
        val heard = part.userData?.lastPlayedDate?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: return false
        // A minute's grace: online, the server heard as it happened.
        return heard + 60_000 >= record.positionUpdated
    }

    /** [parts] at [record]'s place: the parts before it finished, the one it's in partway. */
    private fun placed(parts: List<BaseItemDto>, record: DownloadRecord): List<BaseItemDto> {
        fun State(part: BaseItemDto, played: Boolean, ticks: Long) = part.copy(
            userData = part.userData?.copy(played = played, playbackPositionTicks = ticks)
                ?: org.jellyfin.sdk.model.api.UserItemDataDto(playbackPositionTicks = ticks, playCount = 0, isFavorite = false, played = played, key = part.id.toString(), itemId = part.id),
        )
        return parts.mapIndexed { i, part ->
            when {
                record.positionTicks <= 0L && record.positionPart == 0 -> part
                i < record.positionPart -> State(part, played = true, ticks = 0)
                i == record.positionPart -> State(part, played = false, ticks = record.positionTicks)
                else -> part
            }
        }
    }

    /** What the book page and player show for a book: its downloaded copy when there is one and the library hasn't got it. */
    fun cover(book: Audiobook, width: Int): Any? = artwork(book.id.toString()) ?: repo.bookCoverUrl(book, width)

    /** Remembers where listening got to in a downloaded book, for resuming offline. */
    fun noteBookProgress(bookId: UUID, part: Int, seconds: Double) {
        val id = bookId.toString()
        if (record(id)?.kind != DownloadKind.Book) return
        mutate { list -> list.map { if (it.id == id) it.copy(positionPart = part, positionTicks = (seconds * 10_000_000).toLong(), positionUpdated = System.currentTimeMillis()) else it } }
    }

    /**
     * Hands listening done on this device to the server where it's newer than what the server has ([books] holds what it
     * has), so every device agrees, as the Apple apps do. It goes as a short playback session at the saved place, which is
     * how Jellyfin records a position.
     */
    fun syncBooks(books: List<Audiobook>) {
        val byId = books.associateBy { it.id.toString() }
        val saved = _records.value.filter { it.kind == DownloadKind.Book && it.positionUpdated > 0 }
        if (saved.isEmpty()) return
        scope.launch {
            for (record in saved) {
                val part = byId[record.id]?.parts?.getOrNull(record.positionPart) ?: continue
                if (heardSince(part, record)) continue
                val session = UUID.randomUUID().toString()
                repo.reportStart(
                    PlaybackStartInfo(
                        canSeek = true, itemId = part.id, playSessionId = session, playMethod = PlayMethod.DIRECT_PLAY,
                        isPaused = true, isMuted = false, positionTicks = record.positionTicks,
                        repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
                repo.reportStopped(PlaybackStopInfo(itemId = part.id, playSessionId = session, positionTicks = record.positionTicks, failed = false))
            }
        }
    }

    /** Remembers where viewing got to, for resuming offline. */
    fun notePosition(itemId: String, ticks: Long) {
        if (record(itemId) == null) return
        mutate { list -> list.map { if (it.id == itemId) it.copy(positionTicks = ticks) else it } }
    }

    // MARK: Keeping the records in step with the manager

    private fun refreshAll() = _records.value.filter { it.state == DownloadState.Downloading }.forEach { refresh(it.parts.firstOrNull() ?: it.id, null) }

    /** Brings the record that owns a Media3 download up to date with what the manager says. */
    private fun refresh(downloadId: String, error: Exception?) {
        val record = _records.value.firstOrNull { downloadId in it.parts } ?: return
        val downloads = record.parts.map { manager.downloadIndex.getDownload(it) }
        val failed = downloads.firstOrNull { it?.state == Download.STATE_FAILED }
        val done = downloads.all { it?.state == Download.STATE_COMPLETED }
        // A part the manager has no record of, in a download that isn't done, was lost.
        val lost = downloads.any { it == null } && !done
        val bytes = downloads.sumOf { it?.bytesDownloaded ?: 0L }
        val weights = record.partWeights.ifEmpty { record.parts.map { 1.0 } }
        val progress = if (done) 1.0 else downloads.indices.sumOf { i ->
            val d = downloads[i] ?: return@sumOf 0.0
            val p = if (d.percentDownloaded >= 0) d.percentDownloaded / 100.0 else if (record.expectedBytes > 0) (d.bytesDownloaded.toDouble() / record.expectedBytes).coerceAtMost(1.0) else 0.0
            p * weights[i]
        } / weights.sum()
        val waiting = !done && failed == null && downloads.any { it?.state == Download.STATE_QUEUED || it?.state == Download.STATE_STOPPED } && manager.isWaitingForRequirements
        mutate(save = done || failed != null || lost || record.state != DownloadState.Downloading) { list ->
            list.map {
                if (it.id != record.id) it
                else when {
                    done -> it.copy(state = DownloadState.Finished, progress = 1.0, bytesOnDisk = if (bytes > 0) bytes else it.expectedBytes, errorMessage = null, waiting = false)
                    failed != null -> it.copy(state = DownloadState.Failed, errorMessage = error?.message ?: "The download didn't finish.", waiting = false)
                    lost -> it.copy(state = DownloadState.Failed, errorMessage = "The download was interrupted.", waiting = false)
                    else -> it.copy(state = DownloadState.Downloading, progress = progress, waiting = waiting)
                }
            }
        }
    }

    // MARK: Files

    private fun mutate(save: Boolean = true, change: (List<DownloadRecord>) -> List<DownloadRecord>) {
        _records.update(change)
        if (save) saveIndex()
    }

    private fun loadIndex(): List<DownloadRecord> = runCatching {
        val array = JSONArray(indexFile.readText())
        List(array.length()) { fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveIndex() {
        runCatching {
            val array = JSONArray()
            _records.value.forEach { array.put(toJson(it)) }
            val temp = File(root, "index.json.tmp")
            temp.writeText(array.toString())
            temp.renameTo(indexFile)
        }
    }

    companion object {
        private val bookJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }

        private fun bookToJson(book: Audiobook): String = JSONObject().apply {
            put("title", book.title); put("author", book.author); put("series", book.series); put("seriesIndex", book.seriesIndex)
            put("parts", JSONArray().also { a -> book.parts.forEach { a.put(bookJson.encodeToString(BaseItemDto.serializer(), it)) } })
        }.toString()

        private fun JSONObject.string(key: String): String? = if (isNull(key)) null else getString(key)

        fun format(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
            bytes >= 1_000 -> "%d KB".format(bytes / 1000)
            else -> "$bytes bytes"
        }

        /** "S1, E4". */
        fun episodeCode(item: BaseItemDto): String? {
            val s = item.parentIndexNumber ?: return item.indexNumber?.let { "E$it" }
            val e = item.indexNumber ?: return "S$s"
            return "S$s, E$e"
        }

        private fun toJson(r: DownloadRecord) = JSONObject().apply {
            put("id", r.id); put("kind", r.kind.name); put("title", r.title); put("subtitle", r.subtitle); put("state", r.state.name)
            put("progress", r.progress); put("quality", r.quality); put("expectedBytes", r.expectedBytes); put("bytesOnDisk", r.bytesOnDisk)
            put("audioTitle", r.audioTitle); put("runTimeTicks", r.runTimeTicks); put("positionTicks", r.positionTicks); put("added", r.added)
            put("errorMessage", r.errorMessage); put("uri", r.uri); put("author", r.author); put("positionPart", r.positionPart); put("positionUpdated", r.positionUpdated)
            put("parts", JSONArray(r.parts)); put("partWeights", JSONArray(r.partWeights))
            put("segments", JSONArray().also { a -> r.segments.forEach { a.put(JSONArray(listOf(if (it.intro) 1 else 0, it.startTicks, it.endTicks))) } })
            put("subtitleFile", r.subtitleFile); put("subtitleMime", r.subtitleMime); put("subtitleLanguage", r.subtitleLanguage); put("subtitleLabel", r.subtitleLabel)
        }

        private fun fromJson(o: JSONObject) = DownloadRecord(
            id = o.getString("id"), kind = DownloadKind.valueOf(o.getString("kind")), title = o.getString("title"), subtitle = o.string("subtitle"),
            state = DownloadState.valueOf(o.getString("state")), progress = o.optDouble("progress"), quality = o.string("quality"),
            expectedBytes = o.optLong("expectedBytes"), bytesOnDisk = o.optLong("bytesOnDisk"), audioTitle = o.string("audioTitle"),
            runTimeTicks = o.optLong("runTimeTicks"), positionTicks = o.optLong("positionTicks"), added = o.optLong("added"),
            errorMessage = o.string("errorMessage"), uri = o.string("uri"), author = o.string("author"), positionPart = o.optInt("positionPart"), positionUpdated = o.optLong("positionUpdated"),
            parts = o.getJSONArray("parts").let { a -> List(a.length()) { a.getString(it) } },
            partWeights = o.getJSONArray("partWeights").let { a -> List(a.length()) { a.getDouble(it) } },
            segments = o.getJSONArray("segments").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { SavedSegment(it.getInt(0) == 1, it.getLong(1), it.getLong(2)) } } },
            subtitleFile = o.string("subtitleFile"), subtitleMime = o.string("subtitleMime"), subtitleLanguage = o.string("subtitleLanguage"), subtitleLabel = o.string("subtitleLabel"),
        )
    }
}

/** What's under a [CacheDataSource] that must never go to the network: local files are read, everything else is empty. */
@UnstableApi
private class CachedOnlyDataSource : DataSource {
    private val file = androidx.media3.datasource.FileDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = file.addTransferListener(transferListener)

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        current = if (dataSpec.uri.scheme == "file") file else null
        return current?.open(dataSpec) ?: 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: androidx.media3.common.C.RESULT_END_OF_INPUT
    override fun getUri(): Uri? = current?.uri
    override fun close() { current?.close(); current = null }
}
