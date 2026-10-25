package com.astermediaplayer.astermediaplayer.data.emby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID

/**
 * The Jellyfin SDK's client, with two jobs. It runs every request on the IO dispatcher: the SDK reads a response's body on the
 * thread that resumed it, which on the main thread throws over plain HTTP/1.1 (an HTTP/2 body arrives through memory, which is why
 * HTTPS servers got away with it). And for an Emby [kind] it forwards each call to [inner] with two changes: the few routes Emby
 * keeps under the user (`/Users/{id}/Views`, where Jellyfin has `/UserViews`) are swapped in, and item ids, which Emby writes as
 * plain numbers, travel as the UUIDs the SDK's models want (see [EmbyIds]), turned back into numbers on the way out.
 */
class ServerApiClient(private val inner: ApiClient, private val kind: ServerKind, private val userId: () -> String?) : ApiClient() {
    private val emby get() = kind == ServerKind.Emby
    override val baseUrl: String? get() = inner.baseUrl
    override val accessToken: String? get() = inner.accessToken
    override val clientInfo: ClientInfo get() = inner.clientInfo
    override val deviceInfo: DeviceInfo get() = inner.deviceInfo
    override val httpClientOptions: HttpClientOptions get() = inner.httpClientOptions
    override val webSocket: SocketApi get() = inner.webSocket

    override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) =
        inner.update(baseUrl, accessToken, clientInfo, deviceInfo)

    /** Image and stream addresses are built here too, so their ids go out as Emby's numbers. */
    override fun createUrl(pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, ignorePathParameters: Boolean): String =
        if (!emby) inner.createUrl(pathTemplate, pathParameters, queryParameters, ignorePathParameters)
        else inner.createUrl(pathTemplate, pathParameters.mapValues { outgoing(it.value) }, queryParameters.mapValues { outgoing(it.value) }, ignorePathParameters)

    override suspend fun request(
        method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, requestBody: Any?,
    ): RawResponse = withContext(Dispatchers.IO) {
        if (!emby) return@withContext inner.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
        val user = userId()
        val route = user?.let { EmbyRoutes.rewrite(method, pathTemplate) }
        val paths = pathParameters.mapValues { outgoing(it.value) }.let { if (route != null) it + ("userId" to user) else it }
        val body = requestBody?.let { ApiSerializer.encodeRequestBody(it) }?.let { Json.parseToJsonElement(EmbyIds.forServer(it)) }
        val response = try {
            inner.request(method, route ?: pathTemplate, paths, queryParameters.mapValues { outgoing(it.value) }, body)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Aster", "Emby $method ${route ?: pathTemplate} failed: $e")
            throw e
        }
        forSdk(response)
    }

    private fun outgoing(value: Any?): Any? = when (value) {
        is UUID -> EmbyIds.decode(value.toString())
        is String -> EmbyIds.decode(value)
        is Collection<*> -> value.map(::outgoing)
        is Array<*> -> value.map(::outgoing)
        else -> value
    }

    /** A JSON response with its ids made into UUIDs; pictures, streams and anything else pass through. */
    private fun forSdk(response: RawResponse): RawResponse {
        val body = response.body
        val first = body.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() }?.toInt()?.toChar()
        if (first != '{' && first != '[') return response
        val ids = EmbyIds.forSdk(body.decodeToString())
        val filled = EmbyShapes.fill(Json.parseToJsonElement(ids))
        return RawResponse(filled.toString().encodeToByteArray(), response.status, response.headers)
    }
}

/** The routes Emby keeps under the user, where Jellyfin 10.9+ has them at the top (the Apple apps' `route(_:emby:)`). */
object EmbyRoutes {
    private val routes: Map<Pair<HttpMethod, String>, String> = buildMap {
        put(HttpMethod.GET to "/UserViews", "/Users/{userId}/Views")
        put(HttpMethod.GET to "/Items/{itemId}", "/Users/{userId}/Items/{itemId}")
        put(HttpMethod.GET to "/Items/Latest", "/Users/{userId}/Items/Latest")
        put(HttpMethod.GET to "/UserItems/Resume", "/Users/{userId}/Items/Resume")
        put(HttpMethod.GET to "/Items/{itemId}/SpecialFeatures", "/Users/{userId}/Items/{itemId}/SpecialFeatures")
        for (method in listOf(HttpMethod.POST, HttpMethod.DELETE)) {
            put(method to "/UserFavoriteItems/{itemId}", "/Users/{userId}/FavoriteItems/{itemId}")
            put(method to "/UserPlayedItems/{itemId}", "/Users/{userId}/PlayedItems/{itemId}")
        }
        for (method in listOf(HttpMethod.GET, HttpMethod.POST)) put(method to "/UserItems/{itemId}/UserData", "/Users/{userId}/Items/{itemId}/UserData")
        put(HttpMethod.GET to "/Users/Me", "/Users/{userId}")
        put(HttpMethod.POST to "/Users/Configuration", "/Users/{userId}/Configuration")
    }

    /** Emby's path for a Jellyfin route, with `{userId}` in it; null for the many routes the two share. */
    fun rewrite(method: HttpMethod, template: String): String? = routes[method to template]
}
