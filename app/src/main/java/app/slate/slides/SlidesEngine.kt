package app.slate.slides

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

object SlidesEngine : DocumentEngine {
    override val reads: Set<DocFormat> = setOf(
        DocFormat.PPTX,
        DocFormat.PPT,
        DocFormat.ODP,
    )

    private val outputs: Set<DocFormat> = setOf(
        DocFormat.PDF,
        DocFormat.PPTX,
        DocFormat.ODP,
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
        progress(0.2f)
        val pres = input().use { SlidesFormatCodecs.read(it, from) }
        progress(0.6f)
        if (to == DocFormat.PDF) {
            SlidesFormatCodecs.renderPdf(pres, out) { p -> progress(0.6f + 0.4f * p) }
        } else {
            SlidesFormatCodecs.write(pres, to, out)
            progress(1f)
        }
    }

    override suspend fun extractText(
        context: Context,
        input: InputProvider,
        from: DocFormat,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        val pres = input().use { SlidesFormatCodecs.read(it, from) }
        val blocks = ArrayList<TextBlock>()
        pres.slides.forEachIndexed { idx, slide ->
            val txt = slide.summaryText.trim()
            if (txt.isNotEmpty()) {
                blocks.add(
                    TextBlock(
                        label = "Slide ${idx + 1}",
                        target = "slide=$idx",
                        text = txt,
                    )
                )
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
        val pres = input().use { SlidesFormatCodecs.read(it, from) }
        SlidesFormatCodecs.renderThumbnail(pres, widthPx)
    }
}
