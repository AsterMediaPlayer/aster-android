package com.astermediaplayer.astermediaplayer.ui.audiobooks

import com.astermediaplayer.astermediaplayer.data.AsterRepository
import com.astermediaplayer.astermediaplayer.data.Audiobook
import com.astermediaplayer.astermediaplayer.data.AudiobookShelf
import com.astermediaplayer.astermediaplayer.ui.library.TitleSections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.BaseItemDto
import java.text.Collator
import java.util.UUID

data class AudiobookLibraryState(
    val books: List<Audiobook> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Books started and not finished, most recently played first. */
    val continueListening: List<Audiobook> get() = books.filter { it.resumePoint != null }.sortedByDescending { it.lastPlayed.orEmpty() }
    val recentlyAdded: List<Audiobook> get() = books.sortedByDescending { it.dateAdded.orEmpty() }.take(20)
    val authors: List<AudiobookShelf> get() = shelves(AudiobookShelf.Kind.Author) { it.author }
    val series: List<AudiobookShelf> get() = shelves(AudiobookShelf.Kind.Series) { it.series }

    /** The hero's books: those partway through, then the newest, as Home picks its titles. */
    val featured: List<Audiobook> get() = (continueListening.take(4) + recentlyAdded.take(5)).distinctBy { it.id }.take(8)

    fun book(id: UUID): Audiobook? = books.firstOrNull { it.id == id }

    fun shelf(id: String): AudiobookShelf? = (authors + series).firstOrNull { it.id == id }

    private fun shelves(kind: AudiobookShelf.Kind, by: (Audiobook) -> String?): List<AudiobookShelf> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return books.filter { by(it) != null }.groupBy { by(it)!! }.map { (name, books) ->
            val ordered = if (kind == AudiobookShelf.Kind.Series) books.sortedWith(compareBy<Audiobook>({ it.seriesIndex ?: Int.MAX_VALUE }, { it.title }))
            else books.sortedWith { a, b -> collator.compare(a.title, b.title) }
            AudiobookShelf(kind, name, ordered)
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
}

/** The server's audiobooks, shared by the Audiobooks tab and the pages it opens. */
class AudiobookLibrary(private val repo: AsterRepository) {
    private val _state = MutableStateFlow(AudiobookLibraryState())
    val state: StateFlow<AudiobookLibraryState> = _state.asStateFlow()

    /** Forgets one server's books, for another's. */
    fun reset() { _state.value = AudiobookLibraryState() }

    /** Fetches every book. Keeps what's showing while it reloads, so coming back to the tab doesn't flash empty. */
    suspend fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        try {
            val books = repo.audiobooks(repo.bookLibraries())
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            _state.value = AudiobookLibraryState(books.sortedWith { a, b -> collator.compare(sortTitle(a), sortTitle(b)) }, loaded = true)
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loaded = true, error = if (it.books.isEmpty()) (e.message ?: "Couldn't load") else null) }
        }
    }

    /** A book's parts with their listening state fresh from the server. */
    suspend fun refresh(book: Audiobook): Audiobook {
        val fresh = runCatching { repo.audiobookParts(book.parts.map { it.id }) }.getOrNull().orEmpty()
        return if (fresh.isEmpty()) book else book.updating(fresh)
    }

    private fun sortTitle(book: Audiobook): String = TitleSections.sortKey(book.cover.copy(name = book.title, sortName = null))


}
