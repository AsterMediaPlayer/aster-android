package com.astermediaplayer.astermediaplayer.ui.home

import com.astermediaplayer.astermediaplayer.data.seerr.SeerrStatus
import com.astermediaplayer.astermediaplayer.data.seerr.SeerrTitle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SnapshotTest {
    private fun item(name: String, kind: BaseItemKind = BaseItemKind.MOVIE) = BaseItemDto(
        id = UUID.nameUUIDFromBytes(name.toByteArray()), type = kind, name = name,
        imageTags = mapOf(ImageType.PRIMARY to "tag-$name"), productionYear = 2024,
    )

    @Test fun homeReadsBackAsItWasKept() {
        val home = HomeContent(
            resume = listOf(item("Dune")), nextUp = listOf(item("Frieren", BaseItemKind.EPISODE)), movies = listOf(item("Dune"), item("Arrival")),
            shows = emptyList(), anime = listOf(item("Frieren", BaseItemKind.SERIES)),
            libraryShelves = listOf(item("Cartoons", BaseItemKind.COLLECTION_FOLDER) to listOf(item("Bluey", BaseItemKind.SERIES))),
        )
        assertEquals(home, HomeContent.decode(home.encode()))
    }

    @Test fun anUnreadableHomeIsNone() {
        assertNull(HomeContent.decode("not json"))
        assertNull(HomeContent.decode("home-1\nresume\t[{\"Id\":\"nope\"}]"))
    }

    @Test fun seerrTitlesReadBackAsTheyWereKept() {
        val title = SeerrTitle(42, "tv", name = "Frieren", firstAirDate = "2023-09-29", posterPath = "/p.jpg", overview = "An elf.", status = SeerrStatus.Pending, status4k = SeerrStatus.Unknown)
        assertEquals(title, SeerrTitle.parse(title.toJson()))
    }
}
