package com.astermediaplayer.astermediaplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtworkProviderTest {
    @Test fun picturesFromTheServerAreFetched() {
        assertTrue(ArtworkProvider.allowed("http://192.168.1.10:8096/Items/abc/Images/Primary?maxWidth=400", "http://192.168.1.10:8096"))
        assertTrue(ArtworkProvider.allowed("https://media.example.com/jellyfin/Items/abc/Images/Primary", "https://media.example.com/jellyfin/"))
        assertTrue(ArtworkProvider.allowed("HTTP://Media.Example.com:8096/Items/a", "http://media.example.com:8096"))
    }

    @Test fun anywhereElseIsRefused() {
        assertFalse(ArtworkProvider.allowed("http://evil.example.com:8096/Items/a", "http://192.168.1.10:8096"))
        assertFalse(ArtworkProvider.allowed("http://192.168.1.10:9999/Items/a", "http://192.168.1.10:8096"))
        assertFalse(ArtworkProvider.allowed("https://192.168.1.10:8096/Items/a", "http://192.168.1.10:8096"))
        assertFalse(ArtworkProvider.allowed("https://media.example.com/jellyfinx/Items/a", "https://media.example.com/jellyfin"))
        assertFalse(ArtworkProvider.allowed("file:///data/data/x", "http://192.168.1.10:8096"))
        assertFalse(ArtworkProvider.allowed("not a url", "http://192.168.1.10:8096"))
    }

    @get:Rule val temp = TemporaryFolder()

    @Test fun downloadedCoversAreShared() {
        val downloads = temp.newFolder("downloads")
        val cover = File(downloads, "book-1/artwork.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        assertTrue(ArtworkProvider.downloadedCover(cover, downloads))
    }

    @Test fun otherFilesAreRefused() {
        val downloads = temp.newFolder("downloads")
        val index = File(downloads, "index.json").apply { writeText("{}") }
        val nested = File(downloads, "book-1/deeper/artwork.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        val outside = File(temp.root, "secret/artwork.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        val missing = File(downloads, "book-2/artwork.jpg")
        assertFalse(ArtworkProvider.downloadedCover(index, downloads))
        assertFalse(ArtworkProvider.downloadedCover(nested, downloads))
        assertFalse(ArtworkProvider.downloadedCover(outside, downloads))
        assertFalse(ArtworkProvider.downloadedCover(File(downloads, "book-1/../../secret/artwork.jpg"), downloads))
        assertFalse(ArtworkProvider.downloadedCover(missing, downloads))
    }
}
