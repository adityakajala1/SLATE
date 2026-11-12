package app.slate.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** A run of plain text located somewhere inside a document, used by content search. */
data class TextBlock(
    /** Human readable location: "Page 4", "Slide 2", "Sheet1!B7", "Paragraph 12". */
    val label: String,
    /** Opaque deep-link understood by the matching editor, e.g. "page=3", "slide=1", "sheet=0;row=6;col=1", "para=11". */
    val target: String,
    val text: String,
)

/** Thrown with a user-facing message when a document cannot be handled. */
class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Supplies a fresh stream every call (engines may need two passes). */
typealias InputProvider = () -> InputStream

/**
 * Contract implemented once per document family (PDF, Word, Sheet, Slides).
 * Conversions are always real re-encodings through the engine's document model –
 * never a rename of the file extension.
 */
interface DocumentEngine {
    val reads: Set<DocFormat>

    /** Output formats this engine can genuinely produce from [from]. */
    fun outputsFor(from: DocFormat): Set<DocFormat>

    /**
     * Convert. Implementations must be cancellable-friendly (check coroutine cancellation between
     * pages/sheets) and report progress in 0f..1f.
     */
    suspend fun convert(
        context: Context,
        input: InputProvider,
        from: DocFormat,
        to: DocFormat,
        out: OutputStream,
        progress: (Float) -> Unit = {},
    )

    /** Plain text with locations, for local content search. Should stream/limit for huge files. */
    suspend fun extractText(context: Context, input: InputProvider, from: DocFormat): List<TextBlock>

    /** Small preview of the first page/slide/sheet, or null when not meaningful. */
    suspend fun thumbnail(context: Context, input: InputProvider, from: DocFormat, widthPx: Int): Bitmap?
}

/** What an editor/viewer screen receives when a document is opened. */
data class OpenDoc(
    val uri: Uri,
    val name: String,
    val format: DocFormat,
    /** True when SLATE holds write permission on [uri] *and* the engine can write [format]. */
    val writable: Boolean,
    /** Deep-link from search ("page=3" ...), or null. */
    val target: String? = null,
    /** Search query to highlight, or null. */
    val query: String? = null,
    /** Last remembered position (page / slide / paragraph index). */
    val lastPosition: Int = 0,
    /** Set when the editor is reopening an auto-saved recovery draft instead of [uri]'s contents. */
    val recoveryFile: java.io.File? = null,
)

/** Parses a "k=v;k=v" target string. */
fun parseTarget(target: String?): Map<String, String> =
    target?.split(';')?.mapNotNull { part ->
        val i = part.indexOf('='); if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
    }?.toMap() ?: emptyMap()

/**
 * Bounded snapshot history for immutable editor models.
 * Editors call [push] with the new state after every user edit.
 */
class UndoStack<T>(initial: T, private val limit: Int = 100) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        current = next
    }

    /** Replace current without creating an undo step (e.g. while typing within one word). */
    fun replace(next: T) { current = next }

    fun undo(): T { if (undo.isNotEmpty()) { redo.addLast(current); current = undo.removeLast() }; return current }
    fun redo(): T { if (redo.isNotEmpty()) { undo.addLast(current); current = redo.removeLast() }; return current }
    fun reset(state: T) { undo.clear(); redo.clear(); current = state }
}
