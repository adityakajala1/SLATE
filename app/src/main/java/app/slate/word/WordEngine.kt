package app.slate.word

import android.content.Context
import android.graphics.Bitmap
import app.slate.core.DocFormat
import app.slate.core.DocumentEngine
import app.slate.core.DocumentException
import app.slate.core.InputProvider
import app.slate.core.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

object WordEngine : DocumentEngine {
    override val reads: Set<DocFormat> = setOf(
        DocFormat.DOCX,
        DocFormat.DOC,
        DocFormat.ODT,
        DocFormat.RTF,
        DocFormat.TXT,
    )

    private val outputs: Set<DocFormat> = setOf(
        DocFormat.PDF,
        DocFormat.DOCX,
        DocFormat.ODT,
        DocFormat.RTF,
        DocFormat.TXT,
    )

    override fun outputsFor(from: DocFormat): Set<DocFormat> =
        if (from in reads) outputs - from else emptySet()

    override suspend fun convert(
        context: Context,
        input: InputProvider,
        from: DocFormat,
        to: DocFormat,
        out: OutputStream,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (from !in reads || to !in outputs) {
            throw DocumentException("Cannot convert ${from.label} to ${to.label}")
        }
        progress(0.15f)
        val doc = input().use { WordFormatCodecs.read(it, from) }
        progress(0.55f)
        if (to == DocFormat.PDF) {
            WordFormatCodecs.renderPdf(doc, out) { p -> progress(0.55f + 0.45f * p) }
        } else {
            WordFormatCodecs.write(doc, to, out)
            progress(1f)
        }
    }

    override suspend fun extractText(
        context: Context,
        input: InputProvider,
        from: DocFormat,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        val doc = input().use { WordFormatCodecs.read(it, from) }
        val blocks = ArrayList<TextBlock>()
        doc.elements.forEachIndexed { idx, el ->
            when (el) {
                is ParagraphNode -> {
                    val txt = el.plainText.trim()
                    if (txt.isNotEmpty()) {
                        blocks.add(TextBlock(label = "Paragraph ${idx + 1}", target = "para=$idx", text = txt))
                    }
                }
                is TableNode -> {
                    val txt = el.rows.joinToString(" | ") { it.joinToString(", ") }.trim()
                    if (txt.isNotEmpty()) {
                        blocks.add(TextBlock(label = "Table at block ${idx + 1}", target = "para=$idx", text = txt))
                    }
                }
                is ImageNode -> Unit
            }
        }
        blocks
    }

    override suspend fun thumbnail(
        context: Context,
        input: InputProvider,
        from: DocFormat,
        widthPx: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val doc = input().use { WordFormatCodecs.read(it, from) }
        WordFormatCodecs.renderThumbnail(doc, widthPx)
    }
}
