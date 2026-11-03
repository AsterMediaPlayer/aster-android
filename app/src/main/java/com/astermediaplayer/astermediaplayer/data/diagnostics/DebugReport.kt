package com.astermediaplayer.astermediaplayer.data.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.data.DeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A bug report, in the shape Aster's report service takes (POST /api/bug-reports, answered 201 {"id": …}): what happened in the
 * viewer's words, the app and device, the server's kind and version, what was last played, the app's settings, and a log of
 * this run's notable moments, the last crash, what the device can play and the app's recent lines in Android's log. Everything
 * but the viewer's own words passes through [Redact], so sign-ins, addresses, names, ids and library file names stay behind.
 */
object DebugReport {
    /** Where reports go. */
    const val ENDPOINT = "https://astermediaplayer.com/api/bug-reports"
    private const val LOG_LINES = 1500
    /** The service takes up to 1 MB of logs; the newest are kept. */
    private const val MAX_LOGS = 1_000_000
    private const val MAX_DESCRIPTION = 10_000

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    /** What goes in it, said to the viewer before it's sent. */
    const val CONTENTS =
        "It holds the app's version, this device's model and what it can play, your server's kind and version, what was last " +
            "played, the app's settings, and the app's recent log. Server addresses, sign-ins, passwords, your name and your " +
            "library's file names are taken out before it leaves this device."

    /** The report, as sent. [description] is the viewer's own account; [contactEmail] an address they chose to give. */
    suspend fun build(context: Context, description: String, crash: String?, contactEmail: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val app = context.applicationContext as AsterApp
        val repo = app.repository
        val session = repo.session
        val names = listOfNotNull(session?.userName, session?.userId, session?.serverName)
        fun clean(s: String) = Redact.text(s, names)

        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val tv = com.astermediaplayer.astermediaplayer.ui.theme.isTelevision(context)
        val settings = runCatching { app.settings.current() }.getOrNull()

        JSONObject().apply {
            put("description", description.trim().ifEmpty { if (crash != null) "The app closed unexpectedly." else "Sent from Settings > About." }.take(MAX_DESCRIPTION))
            put("app", JSONObject().apply {
                put("version", info?.versionName)
                put("build", (if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else @Suppress("DEPRECATION") info?.versionCode?.toLong())?.toString())
                put("platform", if (tv) "androidtv" else "android")
                put("os", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" + (fireOs()?.let { ", Fire OS $it" } ?: ""))
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            })
            session?.let { s ->
                put("server", JSONObject().apply {
                    put("type", s.kind.name.lowercase())
                    runCatching { repo.serverVersion() }.getOrNull()?.let { put("version", it) }
                })
            }
            DebugLog.lastPlayback.takeIf { it.isNotEmpty() }?.let { last -> put("playback", JSONObject(last.mapValues { clean(it.value) })) }
            settings?.let { put("settings", JSONObject(fields(it).mapValues { (_, v) -> clean(v) })) }
            contactEmail?.trim()?.takeIf { it.isNotEmpty() }?.let { put("contactEmail", it) }
            put("logs", logs(context, crash, tv, pm).let(::clean).takeLast(MAX_LOGS))
        }
    }

    /** A settings object's fields by name, as text: the Kotlin data class read through Java's own reflection. */
    private fun fields(value: Any): Map<String, String> = value.javaClass.declaredFields
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .associate { f -> f.isAccessible = true; f.name to f.get(value).toString() }

    /** Fire OS's version on an Amazon device: its own property, as Amazon documents. */
    private fun fireOs(): String? = if (!Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) null else runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.build.version.fireos") as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun logs(context: Context, crash: String?, tv: Boolean, pm: PackageManager): String = buildString {
        val metrics = context.resources.displayMetrics
        appendLine("== Device")
        appendLine("TV: $tv, touchscreen: ${pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)}, screen ${metrics.widthPixels}x${metrics.heightPixels} at ${metrics.densityDpi}dpi")
        appendLine("Video decoders: " + DeviceCapabilities.video.joinToString { "${it.codec}${if (it.hardware) " hw" else " sw"} up to ${it.maxWidth}${if (it.tenBit) " 10-bit" else ""}" })
        appendLine("Surround: " + runCatching { DeviceCapabilities.surroundAudio() }.getOrDefault(emptyList()).ifEmpty { listOf("none") }.joinToString())
        if (crash != null) {
            appendLine()
            appendLine("== Crash (last run)")
            appendLine(crash)
        }
        appendLine()
        appendLine("== This run")
        DebugLog.recent().forEach(::appendLine)
        appendLine()
        appendLine("== Android log (this app)")
        appLog().forEach(::appendLine)
    }

    /** This app's own recent lines in Android's log, which an app may read without asking. */
    private fun appLog(): List<String> = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString(), "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readLines().also { process.waitFor(5, TimeUnit.SECONDS) }
    }.getOrDefault(emptyList())

    /** Sends [report]; the id the service gave it. */
    suspend fun send(report: JSONObject): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(ENDPOINT)
            .post(report.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Aster for Android")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            val answer = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) error(answer?.optString("error")?.ifEmpty { null }?.let { "The report service said: $it" } ?: "The report service answered ${response.code}")
            answer?.optString("id")?.ifEmpty { null } ?: error("The report service didn't give the report's id")
        }
    }
}
