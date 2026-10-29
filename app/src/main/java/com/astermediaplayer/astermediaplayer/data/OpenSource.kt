package com.astermediaplayer.astermediaplayer.data

import android.content.Context

// Settings ▸ About ▸ Open Source & Licenses: Aster's own license, then every open-source component built into the app, what it
// does here, and its license in full. The texts ship in the app (assets/licenses), copied from each project, as their licenses
// ask. The Kotlin counterpart of the Apple apps' `OpenSourceComponent`.

/** One open-source component in the app. */
data class OpenSourceComponent(
    val name: String,
    /** What it does in Aster. */
    val role: String,
    val license: String,
    val url: String,
    /** The bundled texts (in assets/licenses, without ".txt"), in order. */
    val files: List<String>,
    /** Anything the license or its holders ask to be said. */
    val note: String? = null,
) {
    companion object {
        /** Every component built into the app, by what it's for. */
        val groups: List<Pair<String, List<OpenSourceComponent>>> = listOf(
            "Playback" to listOf(
                OpenSourceComponent(
                    "Media3 (ExoPlayer)", "Aster's player: plays video, music, audiobooks and live TV, and shows what's playing in the notification and on the lock screen.",
                    "Apache-2.0", "https://github.com/androidx/media", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "OkHttp and Okio", "Fetch everything from the server: video, music and pictures.",
                    "Apache-2.0", "https://github.com/square/okhttp", listOf("Apache-2.0"),
                ),
            ),
            "Styled subtitles" to listOf(
                OpenSourceComponent(
                    "libass", "Draws ASS/SSA subtitles as their makers styled them.",
                    "ISC", "https://github.com/libass/libass", listOf("libass"),
                ),
                OpenSourceComponent(
                    "libass-android", "Puts libass's subtitles on screen in the player.",
                    "MIT", "https://github.com/peerless2012/libass-android", listOf("libass-android"),
                ),
                OpenSourceComponent(
                    "libass-cmake", "Builds libass and the libraries below for Android.",
                    "MIT", "https://github.com/peerless2012/libass-cmake", listOf("libass-cmake"),
                ),
                OpenSourceComponent(
                    "FreeType", "Draws the subtitles' fonts.",
                    "FreeType License (FTL)", "https://freetype.org", listOf("freetype"),
                    note = "Portions of this software are copyright © The FreeType Project (www.freetype.org). All rights reserved.",
                ),
                OpenSourceComponent(
                    "HarfBuzz", "Lays out text in every script.",
                    "MIT (“Old MIT”)", "https://github.com/harfbuzz/harfbuzz", listOf("harfbuzz"),
                ),
                OpenSourceComponent(
                    "FriBidi", "Lays out right-to-left text.",
                    "LGPL-2.1 or later", "https://github.com/fribidi/fribidi", listOf("LGPL-2.1"),
                ),
                OpenSourceComponent(
                    "Fontconfig", "Finds the fonts subtitles ask for.",
                    "MIT-style", "https://gitlab.freedesktop.org/fontconfig/fontconfig", listOf("fontconfig"),
                ),
                OpenSourceComponent(
                    "Expat", "Reads Fontconfig's settings.",
                    "MIT", "https://github.com/libexpat/libexpat", listOf("expat"),
                ),
                OpenSourceComponent(
                    "libunibreak", "Finds where subtitle lines may break.",
                    "zlib", "https://github.com/adah1972/libunibreak", listOf("libunibreak"),
                ),
                OpenSourceComponent(
                    "LLVM libc++", "The C++ standard library the subtitle libraries run on.",
                    "Apache-2.0 with LLVM exceptions", "https://github.com/llvm/llvm-project", listOf("libcxx"),
                ),
            ),
            "Server and screens" to listOf(
                OpenSourceComponent(
                    "Jellyfin SDK for Kotlin", "Talks to Jellyfin and Emby servers.",
                    "LGPL-3.0", "https://github.com/jellyfin/jellyfin-sdk-kotlin", listOf("LGPL-3.0", "GPL-3.0"),
                ),
                OpenSourceComponent(
                    "AndroidX and Jetpack Compose", "Draw the app's screens, move between them, keep its settings and offer casting.",
                    "Apache-2.0", "https://github.com/androidx/androidx", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "Compose Multiplatform", "Parts of Compose the libraries above are built on.",
                    "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "Coil", "Loads and keeps the posters, artwork and photos.",
                    "Apache-2.0", "https://github.com/coil-kt/coil", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "Accompanist", "Draws Android pictures in Compose, for Coil.",
                    "Apache-2.0", "https://github.com/google/accompanist", listOf("Apache-2.0"),
                ),
            ),
            "Kotlin and Java libraries" to listOf(
                OpenSourceComponent(
                    "Kotlin standard library", "The language's own library.",
                    "Apache-2.0", "https://github.com/JetBrains/kotlin", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "kotlinx.coroutines", "Runs work in the background without holding up the screen.",
                    "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "kotlinx.serialization", "Reads and writes the server's JSON.",
                    "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "Guava", "Helpers Media3 and the casting libraries use.",
                    "Apache-2.0", "https://github.com/google/guava", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "kotlin-logging", "Logging for the Jellyfin SDK.",
                    "Apache-2.0", "https://github.com/oshai/kotlin-logging", listOf("Apache-2.0"),
                ),
                OpenSourceComponent(
                    "SLF4J", "Passes the Jellyfin SDK's log messages to Android's log.",
                    "MIT", "https://github.com/qos-ch/slf4j", listOf("slf4j"),
                ),
                OpenSourceComponent(
                    "Checker Framework qualifiers", "Annotations the libraries above are marked with.",
                    "MIT", "https://github.com/typetools/checker-framework", listOf("checker-qual"),
                ),
                OpenSourceComponent(
                    "JSpecify, JSR 305, Error Prone and J2ObjC annotations, javax.inject",
                    "Annotations the libraries above are marked with.",
                    "Apache-2.0", "https://jspecify.dev", listOf("Apache-2.0"),
                ),
            ),
            "Fonts" to listOf(
                OpenSourceComponent(
                    "Inter", "The app's typeface.",
                    "SIL Open Font License 1.1", "https://github.com/rsms/inter", listOf("Inter-OFL"),
                ),
            ),
        )

        /** Aster itself. */
        val aster = OpenSourceComponent(
            "Aster Media Player", "This app.", "Mozilla Public License 2.0", "https://github.com/AsterMediaPlayer/aster-android", listOf("Aster-MPL-2.0"),
        )

        /** Every component, Aster first, to find one by name. */
        val all: List<OpenSourceComponent> get() = listOf(aster) + groups.flatMap { it.second }

        /** A bundled license text; null if it's missing from the app. */
        fun text(context: Context, file: String): String? =
            runCatching { context.assets.open("licenses/$file.txt").bufferedReader().use { it.readText() } }.getOrNull()
    }
}
