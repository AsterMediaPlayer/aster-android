package com.astermediaplayer.astermediaplayer.data.disc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin's own conformance vectors (its `conformance/` folder), run against the Kotlin rules. */
class DiscMenuRulesTest {
    private fun load(name: String): JSONObject =
        JSONObject(checkNotNull(javaClass.getResourceAsStream("/disc/$name")) { "missing $name" }.readBytes().decodeToString())

    private fun cases(name: String): List<JSONObject> = load(name).getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }

    private fun position(o: JSONObject) = DiscPosition(o.getDouble("X"), o.getDouble("Y"), o.optDouble("W").takeIf { !it.isNaN() && !o.isNull("W") }, o.optDouble("H").takeIf { !it.isNaN() && !o.isNull("H") }, o.optString("Anchor").ifEmpty { null })

    private fun flow(o: JSONObject) = DiscFlow(position(o.getJSONObject("Region")), o.getInt("Columns"), o.getInt("Rows"))

    private fun entry(label: String, action: String) = DiscMenuEntry(action, label)

    @Test fun paginate() {
        for (c in cases("paginate.json")) {
            val entries = if (c.has("entries")) c.getJSONArray("entries").let { a -> List(a.length()) { entry(a.getJSONObject(it).getString("Label"), a.getJSONObject(it).getString("Action")) } }
            else List(c.getInt("n")) { entry("E${it + 1}", "playExtra") } +
                (if (c.getBoolean("back")) listOf(entry("Back", "back")) else emptyList()) +
                (if (c.getBoolean("home")) listOf(entry("Home", "home")) else emptyList())
            val got = DiscMenuRules.paginate(entries, c.getInt("slots"), c.getInt("maxPerPage"))
            val want = c.getJSONArray("pages")
            assertEquals(c.toString(), want.length(), got.size)
            for (i in got.indices) {
                val p = want.getJSONObject(i)
                assertEquals(labels(p.getJSONArray("items")), got[i].items.map { it.label })
                assertEquals(labels(p.getJSONArray("backs")), got[i].backs.map { it.label })
                assertEquals(p.getBoolean("prev"), got[i].prev)
                assertEquals(p.getBoolean("more"), got[i].more)
            }
        }
    }

    private fun labels(a: JSONArray) = List(a.length()) { a.get(it).toString() }

    @Test fun cells() {
        for (c in cases("cells.json")) {
            val got = DiscMenuRules.cellPosition(flow(c.getJSONObject("flow")), c.getInt("slot"), c.getBoolean("tall"))
            val want = position(c.getJSONObject("position"))
            assertEquals(c.toString(), want.x, got.x, 1e-6)
            assertEquals(c.toString(), want.y, got.y, 1e-6)
            assertEquals(c.toString(), want.w ?: Double.NaN, got.w ?: Double.NaN, 1e-6)
            assertEquals(c.toString(), want.h ?: Double.NaN, got.h ?: Double.NaN, 1e-6)
            assertEquals(want.anchor, got.anchor)
        }
    }

    @Test fun focus() {
        val dirs = mapOf("right" to (1 to 0), "left" to (-1 to 0), "down" to (0 to 1), "up" to (0 to -1))
        for (c in cases("focus.json")) {
            val rects = c.getJSONArray("rects").let { a ->
                List(a.length()) { a.getJSONObject(it).let { r -> DiscRect(r.getDouble("left"), r.getDouble("top"), r.getDouble("left") + r.getDouble("width"), r.getDouble("top") + r.getDouble("height")) } }
            }
            for ((name, d) in dirs) {
                assertEquals("${c.getString("layout")} from ${c.getInt("from")} $name", c.getJSONObject("expected").getInt(name), DiscMenuRules.moveFocus(rects, c.getInt("from"), d.first, d.second))
            }
        }
    }

    @Test fun time() {
        for (c in cases("time-format.json")) assertEquals(c.toString(), c.getString("text"), DiscMenuRules.formatTicks(c.getLong("ticks")))
    }

    @Test fun safety() {
        for (c in cases("safety.json")) {
            val v = c.getString("value")
            val got = when (c.getString("kind")) {
                "image" -> DiscMenuRules.isSafeImage(v)
                "audio" -> DiscMenuRules.isSafeAudio(v)
                "colour" -> DiscMenuRules.isHexColor(v)
                "tmdb" -> DiscMenuRules.isTmdbPath(v)
                "fanartId" -> DiscMenuRules.isFanartId(v)
                else -> DiscMenuRules.isItemId(v)
            }
            assertEquals(c.toString(), c.getBoolean("accepted"), got)
        }
    }

    /** The plugin's sample documents parse, and their screens lay out as the vectors say. */
    @Test fun layout() {
        val docs = mutableMapOf<String, DiscMenuDocument>()
        for (c in cases("layout.json")) {
            val name = c.getString("document")
            val doc = docs.getOrPut(name) { DiscMenuDocument.parse(checkNotNull(javaClass.getResourceAsStream("/disc/documents/$name")).readBytes().decodeToString()) }
            val laid = DiscMenuRules.entriesForPage(doc, doc.menus.getValue(c.getString("menu")), c.getInt("page"))
            assertEquals(c.toString(), c.getInt("pages"), laid.pageCount)
            val want = c.getJSONArray("entries")
            assertEquals(c.toString(), want.length(), laid.entries.size)
            for (i in laid.entries.indices) {
                val w = want.getJSONObject(i)
                assertEquals(w.getString("Label"), laid.entries[i].label)
                val wp = w.optJSONObject("Position")
                if (wp != null) {
                    val p = checkNotNull(laid.entries[i].position)
                    assertEquals(wp.getDouble("X"), p.x, 1e-6)
                    assertEquals(wp.getDouble("Y"), p.y, 1e-6)
                }
            }
        }
        assertTrue(docs.isNotEmpty())
    }
}
