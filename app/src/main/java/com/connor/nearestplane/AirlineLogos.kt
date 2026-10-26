package com.connor.nearestplane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Airline logos from Kiwi.com's public image server, fetched the first time an
 * airline is seen and kept on the phone after that, so a familiar airline costs
 * no download at all.
 *
 * The URL is unofficial. If it ever stops answering, ensure() returns false and
 * the widget falls back to the callsign badge, so nothing breaks.
 */
object AirlineLogos {

    private const val URL_TEMPLATE = "https://images.kiwi.com/airlines/128/%s.png"
    private const val TIMEOUT_MS = 8_000

    /** A failed download is retried no sooner than this, so a missing logo isn't re-asked every tap. */
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000L

    private val VALID = Regex("^[A-Z0-9]{2}$")

    private fun dir(context: Context) = File(context.filesDir, "logos").apply { mkdirs() }
    private fun file(context: Context, code: String) = File(dir(context), "$code.png")
    private fun miss(context: Context, code: String) = File(dir(context), "$code.miss")

    /** True when the logo for [code] is on the phone, downloading it first if needed. */
    suspend fun ensure(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val c = code.trim().uppercase(Locale.US)
        if (!VALID.matches(c)) return@withContext false
        val f = file(context, c)
        if (f.length() > 0) return@withContext true
        val m = miss(context, c)
        if (m.exists() && System.currentTimeMillis() - m.lastModified() < MISS_TTL_MS) return@withContext false

        val bytes = runCatching { download(URL_TEMPLATE.format(Locale.US, c)) }.getOrNull()
        // Only keep something that really decodes as an image.
        val ok = bytes != null && bytes.isNotEmpty() &&
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
        if (ok) {
            val tmp = File(dir(context), "$c.tmp")
            tmp.writeBytes(bytes!!)
            tmp.renameTo(f)
            m.delete()
        } else {
            m.writeText("")
        }
        ok
    }

    /** The saved logo, or null. Read by the widget when it draws. */
    fun bitmap(context: Context, code: String): Bitmap? {
        if (code.isBlank()) return null
        val f = file(context, code.uppercase(Locale.US))
        if (f.length() <= 0) return null
        return runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "OverheadWidget/1.0")
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
