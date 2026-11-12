package app.slate.sheet

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

object SheetEngine : DocumentEngine {
    override val reads: Set<DocFormat> = setOf(
        DocFormat.XLSX,
        DocFormat.XLS,
        DocFormat.ODS,
        DocFormat.CSV,
    )

    private val outputs: Set<DocFormat> = setOf(
        DocFormat.PDF,
        DocFormat.XLSX,
        DocFormat.ODS,
        DocFormat.CSV,
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
        val wb = input().use { SheetFormatCodecs.read(it, from) }
        progress(0.6f)
        if (to == DocFormat.PDF) {
            SheetFormatCodecs.renderPdf(wb, out) { p -> progress(0.6f + 0.4f * p) }
        } else {
            SheetFormatCodecs.write(wb, to, out)
            progress(1f)
        }
    }

    override suspend fun extractText(
        context: Context,
        input: InputProvider,
        from: DocFormat,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        val wb = input().use { SheetFormatCodecs.read(it, from) }
        val blocks = ArrayList<TextBlock>()
        wb.sheets.forEachIndexed { sIdx, tab ->
            for ((k, _) in tab.cells) {
                val r = SheetTab.unpackRow(k)
                val c = SheetTab.unpackCol(k)
                val v = tab.displayValue(r, c).trim()
                if (v.isNotEmpty()) {
                    val ref = "${SheetTab.colName(c)}${r + 1}"
                    blocks.add(
                        TextBlock(
                            label = "${tab.name}!$ref",
                            target = "sheet=$sIdx;row=$r;col=$c",
                            text = v,
                        )
                    )
                }
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
        val wb = input().use { SheetFormatCodecs.read(it, from) }
        SheetFormatCodecs.renderThumbnail(wb, widthPx)
    }
}
