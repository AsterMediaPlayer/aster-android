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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A debug report: the app and device, the server's kind and version, the app's settings, this run's notable moments, the last
 * crash and the app's recent log, every line with what signs in, where the server is and who's using it taken out (see [Redact]).
 * Sent to Aster's report service, which answers with a short code to quote in a bug report.
 */
object DebugReport {
    /** Where reports go. */
    const val ENDPOINT = "https://astermediaplayer.com/api/reports"
    private const val LOG_LINES = 1500

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    /** What goes in it, said to the viewer before it's sent. */
    const val CONTENTS =
        "It holds the app's version, this device's model and what it can play, your server's kind and version, the app's settings, " +
            "recent playback attempts and errors, and the app's recent log. Server addresses, sign-ins, passwords, your name and " +
            "your library's file names are taken out before it leaves this device."

    /** The report, as sent. */
    suspend fun build(context: Context, crash: String?): JSONObject = withContext(Dispatchers.IO) {
        val app = context.applicationContext as AsterApp
        val repo = app.repository
        val session = repo.session
        val names = listOfNotNull(session?.userName, session?.userId, session?.serverName)
        fun clean(s: String) = Redact.text(s, names)

        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val metrics = context.resources.displayMetrics
        JSONObject().apply {
            put("app", JSONObject().apply {
                put("version", info?.versionName)
                put("build", if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else @Suppress("DEPRECATION") info?.versionCode?.toLong())
                put("store", runCatching { if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName) }.getOrNull() ?: "sideloaded")
            })
            put("device", JSONObject().apply {
                put("maker", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("tv", com.astermediaplayer.astermediaplayer.ui.theme.isTelevision(context))
                put("touchscreen", pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
                put("screen", "${metrics.widthPixels}x${metrics.heightPixels} at ${metrics.densityDpi}dpi")
                put("video", JSONArray(DeviceCapabilities.video.map { "${it.codec}${if (it.hardware) " hw" else " sw"} up to ${it.maxWidth}${if (it.tenBit) " 10-bit" else ""}" }))
                put("surround", JSONArray(runCatching { DeviceCapabilities.surroundAudio() }.getOrDefault(emptyList())))
            })
            put("server", JSONObject().apply {
                put("kind", session?.kind?.name ?: "none")
                put("version", runCatching { repo.serverVersion() }.getOrNull())
            })
            put("settings", clean(runCatching { app.settings.current().toString() }.getOrDefault("")))
            put("events", JSONArray(DebugLog.recent().map(::clean)))
            crash?.let { put("crash", clean(it)) }
            put("log", JSONArray(appLog().map(::clean)))
        }
    }

    /** This app's own recent lines in Android's log, which an app may read without asking. */
    private fun appLog(): List<String> = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString(), "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readLines().also { process.waitFor(5, TimeUnit.SECONDS) }
    }.getOrDefault(emptyList())

    /** Sends [report]; the code the service gave it. */
    suspend fun send(report: JSONObject): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(ENDPOINT)
            .post(report.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Aster for Android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The report service answered ${response.code}")
            JSONObject(response.body.string()).optString("code").ifEmpty { error("The report service didn't give a code") }
        }
    }
}
