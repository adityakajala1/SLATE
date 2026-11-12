package app.slate.core.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.slate.core.DocFormat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class UriInfo(val name: String, val size: Long, val mime: String?, val modified: Long)

/** All content-URI and file I/O goes through here so the rest of the app never touches raw paths. */
object DocIO {

    fun info(context: Context, uri: Uri): UriInfo {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path!!)
            return UriInfo(f.name, f.length(), null, f.lastModified())
        }
        var name: String? = null
        var size = -1L
        var modified = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                        ?.let { if (!c.isNull(it)) modified = c.getLong(it) }
                }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return UriInfo(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document", size, mime, modified)
    }

    fun open(context: Context, uri: Uri): InputStream =
        if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).inputStream().buffered()
        else (context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())).buffered()

    fun readBytes(context: Context, uri: Uri): ByteArray = open(context, uri).use { it.readBytes() }

    /** Whether SLATE can write back to [uri] (file we own, or SAF grant with write flag). */
    fun canWrite(context: Context, uri: Uri): Boolean {
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(uri.path!!).canWrite()
            ContentResolver.SCHEME_CONTENT -> {
                if (uri.authority == context.packageName + ".files") return true
                val persisted = context.contentResolver.persistedUriPermissions.any { it.isWritePermission && (it.uri == uri || uri.toString().startsWith(it.uri.toString())) }
                if (persisted) return true
                context.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            else -> false
        }
    }

    /**
     * Writes a document safely: renders into a temp file first, then copies over the target,
     * so a crash during encoding never truncates the user's original.
     */
    fun writeSafely(context: Context, target: Uri, writer: (OutputStream) -> Unit) {
        val tmp = File.createTempFile("save", ".tmp", secureTemp(context))
        try {
            tmp.outputStream().buffered().use(writer)
            if (target.scheme == ContentResolver.SCHEME_FILE) {
                val dst = File(target.path!!)
                tmp.copyTo(dst, overwrite = true)
            } else {
                val os = context.contentResolver.openOutputStream(target, "wt")
                    ?: context.contentResolver.openOutputStream(target, "w")
                    ?: throw java.io.IOException("Cannot write to this location")
                os.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            }
        } finally {
            tmp.delete()
        }
    }

    /** App-private temp dir; cleared by Settings › Clear cache and on start-up. */
    fun secureTemp(context: Context): File = File(context.cacheDir, "tmp").apply { mkdirs() }

    /** Directory for files produced inside SLATE (conversions, scans, new documents). */
    fun libraryDir(context: Context): File = File(context.filesDir, "library").apply { mkdirs() }

    /** Unique, non-clobbering file in [dir]. Never overwrites an existing document. */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++ }
        return f
    }

    /** content:// URI for a file inside our private storage, shareable with other apps. */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun stableKey(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun mimeOf(format: DocFormat?) = format?.mime ?: "application/octet-stream"
}

/**
 * Auto-save / crash-recovery drafts. Editors periodically write their current model in the native
 * format into private storage; on reopen the user is offered to restore it.
 */
object RecoveryStore {
    private fun dir(context: Context) = File(context.filesDir, "recovery").apply { mkdirs() }

    fun draftFile(context: Context, uri: Uri, format: DocFormat): File =
        File(dir(context), DocIO.stableKey(uri) + "." + format.ext)

    fun saveDraft(context: Context, uri: Uri, format: DocFormat, writer: (OutputStream) -> Unit) {
        val f = draftFile(context, uri, format)
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.outputStream().buffered().use(writer)
        tmp.renameTo(f) || run { tmp.copyTo(f, true); tmp.delete() }
    }

    /** A draft newer than the source file, if one exists. */
    fun pendingDraft(context: Context, uri: Uri, format: DocFormat): File? =
        draftFile(context, uri, format).takeIf { it.exists() && it.length() > 0 }

    fun clear(context: Context, uri: Uri, format: DocFormat) { draftFile(context, uri, format).delete() }

    /** Any draft for [uri], whatever format it was saved in. */
    fun findDraft(context: Context, uri: Uri): File? {
        val key = DocIO.stableKey(uri)
        return dir(context).listFiles()?.firstOrNull { it.name.startsWith("$key.") && !it.name.endsWith(".part") && it.length() > 0 }
    }

    fun clearAll(context: Context, uri: Uri) {
        val key = DocIO.stableKey(uri)
        dir(context).listFiles()?.filter { it.name.startsWith("$key.") }?.forEach { it.delete() }
    }
}

/** Input/format the editor should load: the recovery draft when restoring, otherwise the document itself. */
fun app.slate.core.OpenDoc.sourceFormat(): DocFormat =
    recoveryFile?.let { DocFormat.fromName(it.name) } ?: format

fun app.slate.core.OpenDoc.openSource(context: Context): InputStream =
    recoveryFile?.inputStream()?.buffered() ?: DocIO.open(context, uri)
