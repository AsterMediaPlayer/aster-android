package com.astermediaplayer.astermediaplayer.data

import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

// The server's audiobooks, gathered into books, the Kotlin counterpart of the Apple apps' `AudiobookLibrary`. Jellyfin
// lists audiobook files, not books: an .m4b is a whole book with its chapters inside, but a book kept as a folder of
// MP3s is one item per file. Files in the same folder with the same album are taken as the parts of one book. Authors
// and series often aren't tagged, so they also come from the folders, laid out as Author/[Series/]Book/.

private const val TICKS_PER_SECOND = 10_000_000.0

/** A place in a book's chapter list: which part (file) it's in, and how far into that part it starts. */
data class AudiobookChapter(val id: Int, val part: Int, val startSeconds: Double, val title: String)

data class Audiobook(
    /** The file's id for a one-file book, or the first part's for the rest. */
    val id: UUID,
    val title: String,
    val author: String?,
    val series: String?,
    /** Its place in the series, when the file name or tags give one. */
    val seriesIndex: Int?,
    /** The book's files in order; never empty. */
    val parts: List<BaseItemDto>,
) {
    /** The item whose cover and description stand for the book. */
    val cover: BaseItemDto get() = parts[0]
    val overview: String? get() = parts.firstNotNullOfOrNull { it.overview }
    val year: Int? get() = cover.productionYear
    val dateAdded: String? get() = cover.dateCreated?.toString()

    /** Each part's length, in seconds. */
    val partDurations: List<Double> get() = parts.map { (it.runTimeTicks ?: 0L) / TICKS_PER_SECOND }
    val duration: Double get() = partDurations.sum()

    /** Seconds into the book where [part] begins. */
    fun offsetOfPart(part: Int): Double = partDurations.take(part.coerceAtLeast(0)).sum()

    /**
     * The chapters across every part: an .m4b's own chapters, and for a part without any, the part itself. Empty for a
     * single file with none.
     */
    val chapters: List<AudiobookChapter> by lazy {
        val list = mutableListOf<AudiobookChapter>()
        parts.forEachIndexed { index, part ->
            val own = part.chapters.orEmpty().sortedBy { it.startPositionTicks }
            if (own.isEmpty()) {
                if (parts.size > 1) list += AudiobookChapter(list.size, index, 0.0, part.name ?: "Part ${index + 1}")
            } else {
                own.forEach { chapter ->
                    val title = chapter.name?.takeIf { it.isNotEmpty() } ?: "Chapter ${list.size + 1}"
                    list += AudiobookChapter(list.size, index, chapter.startPositionTicks / TICKS_PER_SECOND, title)
                }
            }
        }
        list
    }

    /**
     * Where listening left off, from what Jellyfin recorded: the last part started and how far into it, or the start of
     * the part after the last one finished. Null before the book is started and once it's finished.
     */
    val resumePoint: Pair<Int, Double>?
        get() {
            val last = parts.indexOfLast { (it.userData?.playbackPositionTicks ?: 0L) > 0 || it.userData?.played == true }
            if (last < 0) return null
            val ticks = parts[last].userData?.playbackPositionTicks ?: 0L
            if (ticks > 0) return last to ticks / TICKS_PER_SECOND
            return if (last + 1 < parts.size) (last + 1) to 0.0 else null
        }

    val isFinished: Boolean get() = parts.last().userData?.played == true && resumePoint == null

    /** How much has been listened to, 0 to 1. */
    val progress: Double
        get() {
            if (duration <= 0) return 0.0
            if (isFinished) return 1.0
            val point = resumePoint ?: return 0.0
            return ((offsetOfPart(point.first) + point.second) / duration).coerceAtMost(1.0)
        }

    /** When any part was last played (ISO 8601, so it sorts as text). */
    val lastPlayed: String? get() = parts.mapNotNull { it.userData?.lastPlayedDate?.toString() }.maxOrNull()

    /** "8 hr 12 min" for the whole book. */
    val lengthText: String? get() = lengthText(duration)

    /** A copy with the parts' listening state refreshed from the server. */
    fun updating(fresh: List<BaseItemDto>): Audiobook {
        val byId = fresh.associateBy { it.id }
        return copy(parts = parts.map { byId[it.id] ?: it })
    }

    companion object {
        /** A book from one file, as Search finds them: no folder to read the author or series from. */
        fun of(item: BaseItemDto) = Audiobook(
            item.id, item.name.orEmpty(), item.albumArtist ?: item.artists?.firstOrNull(), null, null, listOf(item),
        )

        /** "8 hr 12 min", "45 min". */
        fun lengthText(seconds: Double): String? {
            val minutes = (seconds / 60).toInt()
            if (minutes <= 0) return null
            return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
        }

        /** "1:02:03" or "2:03". */
        fun clock(seconds: Double): String {
            val total = seconds.coerceAtLeast(0.0).toInt()
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        /** What a book needs from each file, beside the usual: chapters, its folder and when it was added. */
        val Fields = listOf(
            org.jellyfin.sdk.model.api.ItemFields.OVERVIEW,
            org.jellyfin.sdk.model.api.ItemFields.PARENT_ID,
            org.jellyfin.sdk.model.api.ItemFields.DATE_CREATED,
            org.jellyfin.sdk.model.api.ItemFields.CHAPTERS,
            org.jellyfin.sdk.model.api.ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
        )

        /** Groups files into books, and reads authors and series from the folders the files sit in. */
        fun group(files: List<BaseItemDto>, folders: List<BaseItemDto>): List<Audiobook> {
            val folderById = folders.associateBy { it.id }

            /** The folder names from just inside the library down to [id]. */
            fun chain(id: UUID?): List<String> {
                val names = ArrayDeque<String>()
                var current = id
                val seen = mutableSetOf<UUID>()
                while (current != null) {
                    val folder = folderById[current] ?: break
                    if (!seen.add(current)) break
                    // The library's own top folder has no folder above it.
                    val parent = folder.parentId
                    if (parent == null || folderById[parent] == null) break
                    names.addFirst(folder.name.orEmpty())
                    current = parent
                }
                return names.toList()
            }

            // Parts of one book share a folder and an album; a file with no album is a book of its own.
            val groups = files.groupBy { file -> "${file.parentId ?: ""}|${file.album?.takeIf { it.isNotEmpty() } ?: file.id}" }
            val booksPerFolder = groups.values.groupBy { it[0].parentId }.mapValues { it.value.size }

            return groups.values.map { group ->
                val parts = group.sortedWith(
                    compareBy<BaseItemDto>({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 }, { it.name.orEmpty() }),
                )
                val first = parts[0]
                val folderNames = chain(first.parentId).toMutableList()
                val authorFolder = if (folderNames.isEmpty()) null else folderNames.removeAt(0)
                // A folder holding just this book is the book's own, not a series.
                if (booksPerFolder[first.parentId] == 1 && folderNames.isNotEmpty()) folderNames.removeAt(folderNames.lastIndex)
                val series = folderNames.firstOrNull()
                Audiobook(
                    id = first.id,
                    title = if (parts.size > 1) first.album ?: first.name.orEmpty() else first.name.orEmpty(),
                    author = authorFolder ?: first.albumArtist ?: first.artists?.firstOrNull(),
                    series = series,
                    seriesIndex = if (series != null && parts.size == 1) first.indexNumber else null,
                    parts = parts,
                )
            }
        }
    }
}

/** An author's books, or a series in order: what an author or series page shows. */
data class AudiobookShelf(val kind: Kind, val name: String, val books: List<Audiobook>) {
    enum class Kind { Author, Series }
    val id: String get() = "$kind-$name"
}

/** The listening speed chosen for each book, kept on this device. */
object AudiobookRates {
    val choices = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** "1×", "1.25×". */
    fun label(rate: Float): String = (if (rate == rate.toInt().toFloat()) rate.toInt().toString() else "%.2f".format(rate).trimEnd('0')) + "×"
}
