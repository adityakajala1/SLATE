package app.slate.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import app.slate.core.DocFormat
import app.slate.core.io.DocIO
import app.slate.engine.Engines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Disk + memory thumbnail cache. Thumbnails are small WEBP files in cache/thumbs. */
object Thumbnails {
    private const val WIDTH = 240
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun dir(context: Context) = File(context.cacheDir, "thumbs").apply { mkdirs() }

    fun fileFor(context: Context, uri: Uri) = File(dir(context), DocIO.stableKey(uri) + ".webp")

    suspend fun generate(context: Context, uri: Uri, format: DocFormat?): File? = withContext(Dispatchers.IO) {
        format ?: return@withContext null
        val engine = Engines.forFormat(format) ?: return@withContext null
        val out = fileFor(context, uri)
        if (out.exists()) return@withContext out
        val bmp = runCatching { engine.thumbnail(context, { DocIO.open(context, uri) }, format, WIDTH) }.getOrNull()
            ?: return@withContext null
        out.outputStream().use {
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP, 82, it)
        }
        memory.put(out.absolutePath, bmp)
        out
    }

    fun load(path: String): Bitmap? {
        memory.get(path)?.let { return it }
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        memory.put(path, bmp)
        return bmp
    }

    fun clear(context: Context) { memory.evictAll(); dir(context).deleteRecursively() }
}
