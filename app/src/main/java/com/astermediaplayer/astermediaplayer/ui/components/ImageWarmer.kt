package com.astermediaplayer.astermediaplayer.ui.components

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fills the disk cache with pictures about to be needed, without decoding them, four at a time across the app, so eight of
 * the twelve connections to a server stay free for what's on screen (the Apple apps' `warm`). Cancelled with its caller.
 */
object ImageWarmer {
    private val permits = Semaphore(4)
    private val warmed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    suspend fun warm(context: Context, urls: List<String>) {
        val loader = SingletonImageLoader.get(context)
        coroutineScope {
            for (url in urls.distinct()) {
                if (!warmed.add(url)) continue
                launch {
                    permits.withPermit {
                        val request = ImageRequest.Builder(context).data(url)
                            // The disk cache keeps the whole file; decoded this small, the warming costs the CPU next to nothing.
                            .memoryCachePolicy(CachePolicy.DISABLED).size(8, 8)
                            .build()
                        if (loader.execute(request) is coil3.request.ErrorResult) warmed.remove(url)
                    }
                }
            }
        }
    }
}
