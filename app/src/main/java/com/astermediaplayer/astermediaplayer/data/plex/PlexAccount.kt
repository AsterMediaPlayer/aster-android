package com.astermediaplayer.astermediaplayer.data.plex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Signing in to Plex: a link code the viewer enters at plex.tv/link, the account's servers (its own and friends' shared ones,
// each with its own token and addresses), and finding which of a server's addresses answers. The Kotlin counterpart of the Apple
// apps' `PlexAccount`.

/** One address a Plex server can be reached at. */
data class PlexConnection(val uri: String, val address: String?, val port: Int?, val local: Boolean, val relay: Boolean) {
    /** Home network first, then the internet, then Plex's relay. */
    val rank: Int get() = if (relay) 2 else if (local) 0 else 1

    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("local", local).put("relay", relay)
        .apply { address?.let { put("address", it) }; port?.let { put("port", it) } }

    companion object {
        fun fromJson(o: JSONObject) = PlexConnection(
            o.getString("uri"), o.str("address"), if (o.has("port") && !o.isNull("port")) o.getInt("port") else null,
            o.optBoolean("local"), o.optBoolean("relay"),
        )
    }
}

/**
 * What a saved Plex sign-in keeps beyond its token: the account's own token (plex.tv's, to ask for the server's addresses again
 * if the saved one stops answering), the server's machine id, and the addresses it listed.
 */
data class PlexLink(val accountToken: String, val machineId: String, val connections: List<PlexConnection>) {
    fun toJson(): JSONObject = JSONObject().put("accountToken", accountToken).put("machineId", machineId)
        .put("connections", JSONArray().also { a -> connections.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = PlexLink(
            o.getString("accountToken"), o.getString("machineId"),
            o.optJSONArray("connections")?.let { a -> List(a.length()) { PlexConnection.fromJson(a.getJSONObject(it)) } }.orEmpty(),
        )
    }
}

/** A link code: shown to the viewer, then asked about until plex.tv says it was entered. */
data class PlexPin(val id: Long, val code: String, val authToken: String?, val expiresInSeconds: Int?)

/** One of the account's devices that is a server. */
data class PlexResource(
    val name: String, val clientIdentifier: String,
    /** The token for this server, for this account: a shared server's is its own, not the account's. */
    val accessToken: String?,
    val owned: Boolean, val productVersion: String?, val connections: List<PlexConnection>,
) {
    /**
     * Its addresses, best first. Each home-network address is also tried as plain HTTP by its number, as Plex's own apps do: the
     * secure `plex.direct` name needs a lookup a home router can be slow with, or refuse.
     */
    val rankedConnections: List<PlexConnection>
        get() {
            val all = mutableListOf<PlexConnection>()
            for (c in connections) {
                all += c
                if (c.local && c.uri.startsWith("https") && c.address != null && c.port != null) all += PlexConnection("http://${c.address}:${c.port}", c.address, c.port, local = true, relay = false)
            }
            // Stable within a rank: plex.tv's order (secure first) is kept.
            return all.withIndex().sortedWith(compareBy({ it.value.rank }, { it.index })).map { it.value }
        }
}

/** This device as Plex knows it. */
class PlexDevice(val clientIdentifier: String, val deviceName: String, val version: String = "1.1") {
    /** The headers plex.tv and servers want on every request. */
    fun headers(token: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", version)
        put("X-Plex-Client-Identifier", clientIdentifier)
        put("X-Plex-Device-Name", deviceName)
        put("X-Plex-Platform", "Android")
        if (token != null) put("X-Plex-Token", token)
    }

    companion object { const val PRODUCT = "Aster" }
}

class PlexHttpException(val code: Int) : Exception("Plex answered $code")

/** plex.tv: the link code, the account, and its servers. */
class PlexAccount(private val device: PlexDevice, private val http: OkHttpClient = DEFAULT_HTTP) {
    private suspend fun send(path: String, method: String = "GET", query: Map<String, String> = emptyMap(), token: String? = null): String = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("plex.tv").addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url)
        device.headers(token).forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, if (method == "GET") null else ByteArray(0).toRequestBody())
        http.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) throw PlexHttpException(r.code)
            r.body.string()
        }
    }

    /** A new link code to show. */
    suspend fun startLink(): PlexPin = pin(JSONObject(send("api/v2/pins", "POST", mapOf("strong" to "false"))))

    /** The account's token once the code has been entered; null until then. */
    suspend fun linkedToken(pin: PlexPin): String? = pin(JSONObject(send("api/v2/pins/${pin.id}"))).authToken

    suspend fun accountName(token: String): Pair<Long, String> {
        val o = JSONObject(send("api/v2/user", token = token))
        return o.getLong("id") to (o.str("title") ?: o.str("username") ?: "Plex")
    }

    /** The servers this account can use, its own first. */
    suspend fun servers(token: String): List<PlexResource> =
        parseResources(send("api/v2/resources", query = mapOf("includeHttps" to "1", "includeRelay" to "1"), token = token))

    private fun pin(o: JSONObject) = parsePin(o)

    companion object {
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

        /** A link code's reply: until the code is entered its `authToken` is a JSON null, which `optString` would read as the text "null". */
        fun parsePin(o: JSONObject) = PlexPin(o.getLong("id"), o.getString("code"), o.str("authToken"), if (o.has("expiresIn") && !o.isNull("expiresIn")) o.getInt("expiresIn") else null)

        /** plex.tv's resource list, keeping the devices that are servers, the account's own first. */
        fun parseResources(json: String): List<PlexResource> {
            val array = JSONArray(json)
            val all = List(array.length()) { array.getJSONObject(it) }.filter { o -> (o.str("provides") ?: "").split(",").contains("server") }.map { o ->
                val connections = o.optJSONArray("connections")?.let { a ->
                    List(a.length()) { i ->
                        val c = a.getJSONObject(i)
                        PlexConnection(c.getString("uri"), c.str("address"), if (c.has("port") && !c.isNull("port")) c.getInt("port") else null, c.optBoolean("local"), c.optBoolean("relay"))
                    }
                }.orEmpty()
                PlexResource(
                    o.getString("name"), o.getString("clientIdentifier"), o.str("accessToken"),
                    o.optBoolean("owned"), o.str("productVersion"), connections,
                )
            }
            return all.sortedByDescending { it.owned }
        }
    }
}

/**
 * Finds which of a server's addresses answers. All are tried at once: a server can list a dozen addresses (one per network it's
 * on), most of which don't answer from here, and one at a time would take minutes.
 */
object PlexConnectionRace {
    /** The best address that answers, or null if none does: a home-network one the moment it answers, otherwise the best of those that did. */
    suspend fun fastest(connections: List<PlexConnection>, token: String, device: PlexDevice, http: OkHttpClient = PlexAccount.DEFAULT_HTTP, timeoutSeconds: Long = 10): PlexConnection? {
        if (connections.isEmpty()) return null
        val probe = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        return coroutineScope {
            val results = Channel<PlexConnection?>(connections.size)
            val jobs = connections.map { connection ->
                launch(Dispatchers.IO) { results.send(if (answers(probe, connection.uri, token, device)) connection else null) }
            }
            var best: PlexConnection? = null
            repeat(connections.size) {
                val answered = results.receive() ?: return@repeat
                if (answered.rank == 0) { jobs.forEach { it.cancel() }; return@coroutineScope answered }
                if (best == null || answered.rank < best!!.rank) best = answered
            }
            best
        }
    }

    private fun answers(http: OkHttpClient, uri: String, token: String, device: PlexDevice): Boolean = try {
        val builder = Request.Builder().url(uri.trimEnd('/') + "/identity")
        device.headers(token).forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { it.code == 200 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
