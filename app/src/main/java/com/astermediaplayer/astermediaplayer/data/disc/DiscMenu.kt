package com.astermediaplayer.astermediaplayer.data.disc

import org.json.JSONArray
import org.json.JSONObject

// The resolved disc menu the Aster Disc (JellyDisc) plugin serves at `GET /DiscMenus/{itemId}/Menu`: the Kotlin counterpart
// of the Apple apps' `DiscMenuModels`. Unknown properties are ignored; unknown enum values fall back to defaults at use.

data class DiscPosition(val x: Double, val y: Double, val w: Double? = null, val h: Double? = null, val anchor: String? = null)

data class DiscBackground(
    val source: String? = null,
    val imageType: String? = null,
    val index: Int? = null,
    val tmdbFilePath: String? = null,
    val tmdbSize: String? = null,
    val fanartId: String? = null,
    val color: String? = null,
    val image: String? = null,
    val trailerIndex: Int? = null,
    val muted: Boolean? = null,
    val poster: String? = null,
    val dim: Double? = null,
)

data class DiscTheme(
    val accent: String? = null,
    val align: String? = null,
    val textColor: String? = null,
    val font: String? = null,
    val fontSize: Double? = null,
    val uppercase: Boolean? = null,
    val bold: Boolean? = null,
    val letterSpacing: Double? = null,
)

data class DiscFlow(val region: DiscPosition, val columns: Int, val rows: Int, val moreLabel: String? = null, val previousLabel: String? = null)

data class DiscTransition(val style: String? = null, val durationMs: Int? = null)

data class DiscLayer(
    val type: String? = null,
    val position: DiscPosition? = null,
    val fill: String? = null,
    val opacity: Double? = null,
    val borderColor: String? = null,
    val borderWidth: Double? = null,
    val radius: Double? = null,
    val image: String? = null,
    val fit: String? = null,
)

data class DiscLayout(
    val titlePosition: DiscPosition? = null,
    val hideTitle: Boolean? = null,
    val buttonStyle: String? = null,
    val flow: DiscFlow? = null,
    val transition: DiscTransition? = null,
    val layers: List<DiscLayer>? = null,
)

data class DiscMusic(val source: String? = null, val file: String? = null, val volume: Double? = null)

data class DiscSounds(val preset: String? = null, val volume: Double? = null, val move: String? = null, val select: String? = null, val back: String? = null)

data class DiscAudio(val music: DiscMusic? = null, val sounds: DiscSounds? = null)

data class DiscChapter(val index: Int, val name: String?, val startTicks: Long, val hasImage: Boolean?, val imageStamp: Long?)

data class DiscTrailer(val kind: String?, val itemId: String?, val videoId: String?, val name: String?)

/** What a button does. `PlayChapter`, `PagePrev` and `PageNext` are the client's own; the server never sends them. */
enum class DiscAction(val raw: String) {
    PlayFeature("playFeature"), PlayExtra("playExtra"), PlaySequence("playSequence"), Submenu("submenu"), Chapters("chapters"),
    Back("back"), Home("home"), PlayChapter("playChapter"), PagePrev("pagePrev"), PageNext("pageNext"), Unknown("");

    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw && raw.isNotEmpty() } ?: Unknown }
}

data class DiscMenuEntry(
    val action: String,
    val label: String,
    val position: DiscPosition? = null,
    val style: String? = null,
    val image: String? = null,
    val imageFocus: String? = null,
    val startChapter: Int? = null,
    val itemId: String? = null,
    val itemIds: List<String>? = null,
    val menu: String? = null,
    val perPage: Int? = null,
    /** Made by the client for scene buttons: the start time under the name, the thumbnail, and where it starts. */
    val sub: String? = null,
    val thumbUrl: String? = null,
    val startTicks: Long? = null,
) {
    val kind: DiscAction get() = DiscAction.of(action)

    /** The cell should be given a height: artwork or a chapter thumbnail. */
    val isTall: Boolean get() = !image.isNullOrEmpty() || thumbUrl != null
}

data class DiscMenuScreenSpec(
    val title: String,
    val entries: List<DiscMenuEntry>,
    val background: DiscBackground? = null,
    val theme: DiscTheme? = null,
    val layout: DiscLayout? = null,
    val audio: DiscAudio? = null,
    val maxPerPage: Int? = null,
)

data class DiscMenuDocument(
    val menuId: String?,
    val root: String,
    val menus: Map<String, DiscMenuScreenSpec>,
    val background: DiscBackground?,
    val theme: DiscTheme?,
    val layout: DiscLayout?,
    val audio: DiscAudio?,
    val chapters: List<DiscChapter>?,
    val trailers: List<DiscTrailer>?,
    val themeSongs: List<String>?,
) {
    companion object {
        fun parse(json: String): DiscMenuDocument {
            val o = JSONObject(json)
            val menus = o.optJSONObject("Menus")
            return DiscMenuDocument(
                menuId = o.str("MenuId"),
                root = o.str("Root").orEmpty(),
                menus = buildMap { menus?.keys()?.forEach { key -> menus.optJSONObject(key)?.let { put(key, screen(it)) } } },
                background = o.obj("Background")?.let(::background),
                theme = o.obj("Theme")?.let(::theme),
                layout = o.obj("Layout")?.let(::layout),
                audio = o.obj("Audio")?.let(::audio),
                chapters = o.arr("Chapters")?.map { c ->
                    DiscChapter(c.optInt("Index"), c.str("Name"), c.optLong("StartTicks"), c.bool("HasImage"), c.long("ImageStamp"))
                },
                trailers = o.arr("Trailers")?.map { DiscTrailer(it.str("Kind"), it.str("ItemId"), it.str("VideoId"), it.str("Name")) },
                themeSongs = o.optJSONArray("ThemeSongs")?.let { a -> List(a.length()) { a.optString(it) } },
            )
        }

        private fun screen(o: JSONObject) = DiscMenuScreenSpec(
            title = o.str("Title").orEmpty(),
            entries = o.arr("Entries")?.map(::entry).orEmpty(),
            background = o.obj("Background")?.let(::background),
            theme = o.obj("Theme")?.let(::theme),
            layout = o.obj("Layout")?.let(::layout),
            audio = o.obj("Audio")?.let(::audio),
            maxPerPage = o.int("MaxPerPage"),
        )

        private fun entry(o: JSONObject) = DiscMenuEntry(
            action = o.str("Action").orEmpty(),
            label = o.str("Label").orEmpty(),
            position = o.obj("Position")?.let(::position),
            style = o.str("Style"),
            image = o.str("Image"),
            imageFocus = o.str("ImageFocus"),
            startChapter = o.int("StartChapter"),
            itemId = o.str("ItemId"),
            itemIds = o.optJSONArray("ItemIds")?.let { a -> List(a.length()) { a.optString(it) } },
            menu = o.str("Menu"),
            perPage = o.int("PerPage"),
        )

        private fun position(o: JSONObject) = DiscPosition(o.optDouble("X", 0.0), o.optDouble("Y", 0.0), o.dbl("W"), o.dbl("H"), o.str("Anchor"))

        private fun background(o: JSONObject) = DiscBackground(
            o.str("Source"), o.str("ImageType"), o.int("Index"), o.str("TmdbFilePath"), o.str("TmdbSize"), o.str("FanartId"),
            o.str("Color"), o.str("Image"), o.int("TrailerIndex"), o.bool("Muted"), o.str("Poster"), o.dbl("Dim"),
        )

        private fun theme(o: JSONObject) = DiscTheme(
            o.str("Accent"), o.str("Align"), o.str("TextColor"), o.str("Font"), o.dbl("FontSize"), o.bool("Uppercase"), o.bool("Bold"), o.dbl("LetterSpacing"),
        )

        private fun layout(o: JSONObject) = DiscLayout(
            titlePosition = o.obj("TitlePosition")?.let(::position),
            hideTitle = o.bool("HideTitle"),
            buttonStyle = o.str("ButtonStyle"),
            flow = o.obj("Flow")?.let { f ->
                f.obj("Region")?.let { DiscFlow(position(it), f.optInt("Columns", 1), f.optInt("Rows", 1), f.str("MoreLabel"), f.str("PreviousLabel")) }
            },
            transition = o.obj("Transition")?.let { DiscTransition(it.str("Style"), it.int("DurationMs")) },
            layers = o.arr("Layers")?.map { l ->
                DiscLayer(l.str("Type"), l.obj("Position")?.let(::position), l.str("Fill"), l.dbl("Opacity"), l.str("BorderColor"), l.dbl("BorderWidth"), l.dbl("Radius"), l.str("Image"), l.str("Fit"))
            },
        )

        private fun audio(o: JSONObject) = DiscAudio(
            music = o.obj("Music")?.let { DiscMusic(it.str("Source"), it.str("File"), it.dbl("Volume")) },
            sounds = o.obj("Sounds")?.let { DiscSounds(it.str("Preset"), it.dbl("Volume"), it.str("Move"), it.str("Select"), it.str("Back")) },
        )

        // Properties that would be null are omitted, but a null may still arrive: both mean "not given".
        private fun JSONObject.has2(key: String) = has(key) && !isNull(key)
        private fun JSONObject.str(key: String): String? = if (has2(key)) optString(key) else null
        private fun JSONObject.int(key: String): Int? = if (has2(key)) optInt(key) else null
        private fun JSONObject.long(key: String): Long? = if (has2(key)) optLong(key) else null
        private fun JSONObject.dbl(key: String): Double? = if (has2(key)) optDouble(key).takeIf { !it.isNaN() } else null
        private fun JSONObject.bool(key: String): Boolean? = if (has2(key)) optBoolean(key) else null
        private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
        private fun JSONObject.arr(key: String): List<JSONObject>? = optJSONArray(key)?.let { a: JSONArray -> List(a.length()) { a.optJSONObject(it) ?: JSONObject() } }
    }
}

/** Ids come with dashes or as 32 hex digits. */
fun parseUuid(id: String): java.util.UUID? = runCatching {
    java.util.UUID.fromString(if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id)
}.getOrNull()
