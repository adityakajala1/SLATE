package app.slate.engine

import app.slate.core.DocFormat
import app.slate.core.DocumentEngine
import app.slate.pdf.PdfEngine
import app.slate.sheet.SheetEngine
import app.slate.slides.SlidesEngine
import app.slate.word.WordEngine

/** Registry: one engine per document family. Each engine is only asked about formats it declares. */
object Engines {
    val all: List<DocumentEngine> by lazy { listOf(PdfEngine, WordEngine, SheetEngine, SlidesEngine) }

    fun forFormat(format: DocFormat): DocumentEngine? = all.firstOrNull { format in it.reads }

    /** Real conversion targets for [from] (excluding itself). */
    fun outputsFor(from: DocFormat): List<DocFormat> =
        (forFormat(from)?.outputsFor(from) ?: emptySet()).filter { it != from }.sortedBy { it.ordinal }
}