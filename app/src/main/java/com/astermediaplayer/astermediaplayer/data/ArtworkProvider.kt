package com.astermediaplayer.astermediaplayer.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.astermediaplayer.astermediaplayer.AsterApp
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Covers for Android Auto's lists. The car reads browse pictures only from content addresses, not the web, so each
 * picture on the server is fetched here, kept in the cache, and handed over as a file. Only pictures from the server
 * signed in to are fetched, so no other app can use this to reach anywhere else.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val url = uri.getQueryParameter("u") ?: throw FileNotFoundException("No picture")
        val app = context?.applicationContext as? AsterApp ?: throw FileNotFoundException("Not ready")
        // A downloaded book's cover, which plays offline too: only those, so no other file of the app can be read.
        if (url.startsWith("file:", ignoreCase = true)) {
            val file = Uri.parse(url).path?.let { File(it).canonicalFile } ?: throw FileNotFoundException("No picture")
            if (!downloadedCover(file, File(app.filesDir, "downloads"))) throw FileNotFoundException("Not a downloaded cover")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val server = app.repository.session?.serverUrl ?: throw FileNotFoundException("Not signed in")
        if (!allowed(url, server)) throw FileNotFoundException("Not this server's picture")
        val dir = File(app.cacheDir, "car-art").apply { mkdirs() }
        val file = File(dir, name(url))
        if (!file.exists()) {
            val part = File(dir, "${file.name}.part")
            sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful) throw FileNotFoundException("Picture answered ${response.code}")
                part.outputStream().use { body.byteStream().copyTo(it) }
            }
            if (!part.renameTo(file)) part.delete()
            trim(dir)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** Keeps the newest 600 pictures (about 30 MB at the size the car asks for). */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> !f.name.endsWith(".part") } ?: return
        if (files.size <= 600) return
        files.sortedBy { it.lastModified() }.take(files.size - 600).forEach { it.delete() }
    }

    companion object {
        fun authority(context: Context) = "${context.packageName}.artwork"

        fun uriFor(context: Context, url: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).path("art").appendQueryParameter("u", url).build()

        /** A picture on the signed-in server: the same scheme, host and port, and under its path. */
        internal fun allowed(url: String, server: String): Boolean {
            val a = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val s = runCatching { java.net.URI(server) }.getOrNull() ?: return false
            if (a.scheme == null || !a.scheme.equals(s.scheme, true) || !a.host.equals(s.host, true) || a.port != s.port) return false
            val base = (s.path ?: "").trimEnd('/')
            val path = a.path ?: ""
            return base.isEmpty() || path == base || path.startsWith("$base/")
        }

        /** A cover saved with a download: `artwork.jpg` in one item's folder directly under the downloads folder. */
        internal fun downloadedCover(file: File, downloads: File): Boolean {
            val root = downloads.canonicalFile
            val target = file.canonicalFile
            return target.name == "artwork.jpg" && target.parentFile?.parentFile == root && target.isFile
        }

        private fun name(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40) + ".jpg"
    }
}
