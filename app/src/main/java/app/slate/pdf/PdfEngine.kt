package app.slate.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.slate.core.DocFormat
import app.slate.core.DocumentEngine
import app.slate.core.DocumentException
import app.slate.core.InputProvider
import app.slate.core.TextBlock
import app.slate.word.ParagraphNode
import app.slate.word.SpanStyle
import app.slate.word.TextRun
import app.slate.word.WordDocument
import app.slate.word.WordFormatCodecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

object PdfEngine : DocumentEngine {
    override val reads: Set<DocFormat> = setOf(
        DocFormat.PDF,
        DocFormat.PNG,
        DocFormat.JPG,
    )

    override fun outputsFor(from: DocFormat): Set<DocFormat> = when (from) {
        DocFormat.PDF -> setOf(
            DocFormat.DOCX,
            DocFormat.ODT,
            DocFormat.RTF,
            DocFormat.TXT,
            DocFormat.PNG,
            DocFormat.JPG,
        )
        DocFormat.PNG, DocFormat.JPG -> setOf(DocFormat.PDF)
        else -> emptySet()
    }

    override suspend fun convert(
        context: Context,
        input: InputProvider,
        from: DocFormat,
        to: DocFormat,
        out: OutputStream,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (to !in outputsFor(from)) {
            throw DocumentException("Cannot convert ${from.label} to ${to.label}")
        }
        when {
            (from == DocFormat.PNG || from == DocFormat.JPG) && to == DocFormat.PDF -> {
                progress(0.25f)
                input().use { stream ->
                    PdfToolbox.imagesToPdf(listOf(stream), out) { p -> progress(0.25f + 0.75f * p) }
                }
            }
            from == DocFormat.PDF && (to == DocFormat.PNG || to == DocFormat.JPG) -> {
                val tmp = input().use { PdfToolbox.copyToTempPdf(context, it) }
                try {
                    progress(0.4f)
                    val bmp = PdfToolbox.renderPageBitmap(tmp, 0, 1600)
                        ?: throw DocumentException("PDF has no renderable pages")
                    val fmt = if (to == DocFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bmp.compress(fmt, 92, out)
                    bmp.recycle()
                    progress(1f)
                } finally {
                    tmp.delete()
                }
            }
            from == DocFormat.PDF -> {
                val tmp = input().use { PdfToolbox.copyToTempPdf(context, it) }
                try {
                    progress(0.2f)
                    val pageTexts = PdfToolbox.extractPageTexts(context, tmp)
                    progress(0.65f)
                    val elements = ArrayList<app.slate.word.DocElement>()
                    pageTexts.forEachIndexed { pageIdx, pageText ->
                        if (pageTexts.size > 1) {
                            elements.add(
                                ParagraphNode(
                                    runs = listOf(TextRun("Page ${pageIdx + 1}", SpanStyle(bold = true, fontSizeSp = 16))),
                                    headingLevel = 2,
                                )
                            )
                        }
                        val lines = pageText.replace("\r\n", "\n").split("\n\n")
                        for (block in lines) {
                            val cleaned = block.trim()
                            if (cleaned.isNotEmpty()) {
                                elements.add(ParagraphNode(runs = listOf(TextRun(cleaned))))
                            }
                        }
                    }
                    if (elements.isEmpty()) {
                        elements.add(ParagraphNode(runs = listOf(TextRun("[Scanned PDF without selectable text layer]"))))
                    }
                    val wordDoc = WordDocument(title = "Converted PDF", elements = elements, sourceFormat = to)
                    WordFormatCodecs.write(wordDoc, to, out)
                    progress(1f)
                } finally {
                    tmp.delete()
                }
            }
            else -> throw DocumentException("Unsupported conversion ${from.label} → ${to.label}")
        }
    }

    override suspend fun extractText(
        context: Context,
        input: InputProvider,
        from: DocFormat,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        if (from != DocFormat.PDF) return@withContext emptyList()
        val tmp = input().use { PdfToolbox.copyToTempPdf(context, it) }
        try {
            val pages = PdfToolbox.extractPageTexts(context, tmp)
            pages.mapIndexedNotNull { idx, text ->
                if (text.isBlank()) null
                else TextBlock(label = "Page ${idx + 1}", target = "page=$idx", text = text)
            }
        } finally {
            tmp.delete()
        }
    }

    override suspend fun thumbnail(
        context: Context,
        input: InputProvider,
        from: DocFormat,
        widthPx: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        when (from) {
            DocFormat.PDF -> {
                val tmp = input().use { PdfToolbox.copyToTempPdf(context, it) }
                try {
                    PdfToolbox.renderPageBitmap(tmp, 0, widthPx)
                } finally {
                    tmp.delete()
                }
            }
            DocFormat.PNG, DocFormat.JPG -> {
                val bytes = input().use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            else -> null
        }
    }
}
