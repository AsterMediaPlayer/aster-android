package com.astermediaplayer.astermediaplayer.ui.library

import org.jellyfin.sdk.model.api.BaseItemDto
import java.text.Collator
import java.text.Normalizer

/** One letter's titles. */
data class IndexSection(val key: String, val items: List<BaseItemDto>)

/**
 * A library sorted by name, in letter sections with an index to jump between them (the Apple apps' `LibraryIndex`).
 * Titles sort as Jellyfin's sort name has them, which has no leading "The", "A" or "An"; accented letters go with their
 * letter, other writing systems get their own sections after Latin, and numbers and symbols go under #.
 */
object TitleSections {
    const val OTHER = "#"
    private val articles = listOf("the ", "a ", "an ")

    /** What a title sorts by: Jellyfin's sort name when it has one, else the title without a leading article. */
    fun sortKey(item: BaseItemDto): String {
        item.sortName?.takeIf { it.isNotEmpty() }?.let { return it }
        val title = item.name.orEmpty().trim()
        val lowered = title.lowercase()
        articles.firstOrNull { lowered.startsWith(it) && lowered.length > it.length }?.let { return title.drop(it.length) }
        return title
    }

    fun sections(items: List<BaseItemDto>): List<IndexSection> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val sorted = items.map { it to sortKey(it) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
        val grouped = LinkedHashMap<String, MutableList<BaseItemDto>>()
        for ((item, key) in sorted) grouped.getOrPut(sectionKey(key)) { mutableListOf() }.add(item)
        // Latin letters first, then other writing systems, # last.
        val order = grouped.keys.sortedWith(
            compareBy<String> { it == OTHER }.thenBy { !isLatinLetter(it) }.thenComparator { a, b -> collator.compare(a, b) },
        )
        return order.map { IndexSection(it, grouped.getValue(it)) }
    }

    /** The section a sort key goes in. */
    fun sectionKey(text: String): String {
        val first = text.firstOrNull { it.isLetterOrDigit() } ?: return OTHER
        if (first.isDigit()) return OTHER
        val folded = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).firstOrNull { it.isLetter() } ?: first
        return folded.uppercaseChar().toString()
    }

    private fun isLatinLetter(key: String) = key.length == 1 && key[0] in 'A'..'Z'
}
