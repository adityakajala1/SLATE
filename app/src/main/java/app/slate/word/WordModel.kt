package app.slate.word

import android.graphics.BitmapFactory
import app.slate.core.DocFormat

enum class ParaAlign { LEFT, CENTER, RIGHT, JUSTIFY }
enum class ListKind { NONE, BULLET, NUMBERED }
enum class WordFontFamily { SANS_SERIF, SERIF, MONOSPACE }

data class SpanStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val allCaps: Boolean = false,
    val fontSizeSp: Int = 12,
    val fontSizePt: Float = fontSizeSp.toFloat(),
    val colorArgb: Int? = null,
    val backgroundColorArgb: Int? = null,
    val fontFamily: WordFontFamily = WordFontFamily.SANS_SERIF,
)

data class TextRun(
    val text: String,
    val style: SpanStyle = SpanStyle(),
)

data class ParagraphBorders(
    val topColorArgb: Int? = null,
    val topWidthPt: Float = 0f,
    val bottomColorArgb: Int? = null,
    val bottomWidthPt: Float = 0f,
    val leftColorArgb: Int? = null,
    val leftWidthPt: Float = 0f,
    val rightColorArgb: Int? = null,
    val rightWidthPt: Float = 0f,
) {
    val hasAny: Boolean
        get() = topWidthPt > 0f || bottomWidthPt > 0f || leftWidthPt > 0f || rightWidthPt > 0f
}

sealed interface DocElement {
    val id: Long
}

data class ParagraphNode(
    override val id: Long = nextId(),
    val runs: List<TextRun> = listOf(TextRun("")),
    val headingLevel: Int = 0, // 0 = normal, 1..6 = H1..H6
    val align: ParaAlign = ParaAlign.LEFT,
    val listKind: ListKind = ListKind.NONE,
    val listLevel: Int = 0,
    val listPrefix: String = "",
    val leftIndentPt: Float = 0f,
    val rightIndentPt: Float = 0f,
    val firstLineIndentPt: Float = 0f,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 6f,
    val lineSpacingMultiplier: Float = 1.22f,
    val backgroundColorArgb: Int? = null,
    val borders: ParagraphBorders = ParagraphBorders(),
    val pageBreakBefore: Boolean = false,
    val isPageBreakOnly: Boolean = false,
) : DocElement {
    val plainText: String get() = runs.joinToString("") { it.text }

    fun withPlainText(newText: String, defaultStyle: SpanStyle? = null): ParagraphNode {
        val baseStyle = defaultStyle ?: runs.firstOrNull()?.style ?: SpanStyle(
            bold = headingLevel > 0,
            fontSizeSp = when (headingLevel) { 1 -> 22; 2 -> 18; 3 -> 16; else -> 12 },
            fontSizePt = when (headingLevel) { 1 -> 22f; 2 -> 18f; 3 -> 16f; else -> 12f },
        )
        return copy(runs = listOf(TextRun(newText, baseStyle)), isPageBreakOnly = false)
    }

    fun applyStyle(transform: (SpanStyle) -> SpanStyle): ParagraphNode {
        val updated = if (runs.isEmpty()) listOf(TextRun("", transform(SpanStyle())))
        else runs.map { it.copy(style = transform(it.style)) }
        return copy(runs = updated)
    }
}

data class WordTableCell(
    val paragraphs: List<ParagraphNode> = listOf(ParagraphNode()),
    val images: List<ImageNode> = emptyList(),
    val fillArgb: Int? = null,
    val gridSpan: Int = 1,
    val vMergeContinue: Boolean = false,
    val verticalAlign: String = "top", // "top", "center", "bottom"
    val borderColorArgb: Int? = null,
    val padLeftPt: Float = 5.4f,
    val padRightPt: Float = 5.4f,
    val padTopPt: Float = 3.5f,
    val padBottomPt: Float = 3.5f,
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.plainText }
}

data class TableNode(
    override val id: Long = nextId(),
    val rows: List<List<String>>,
    val headerRow: Boolean = true,
    val richRows: List<List<WordTableCell>> = emptyList(),
    val colWidthFractions: List<Float> = emptyList(),
    val borderColorArgb: Int? = 0xFFCBD5E1.toInt(),
    val hasBorders: Boolean = true,
) : DocElement {
    val rowCount: Int get() = rows.size
    val colCount: Int get() = rows.maxOfOrNull { it.size } ?: 0

    fun cellAt(r: Int, c: Int): WordTableCell {
        val rich = richRows.getOrNull(r)?.getOrNull(c)
        if (rich != null) return rich
        val text = rows.getOrNull(r)?.getOrNull(c).orEmpty()
        val isHdr = r == 0 && headerRow
        return WordTableCell(
            paragraphs = listOf(
                ParagraphNode(
                    runs = listOf(
                        TextRun(
                            text = text,
                            style = SpanStyle(bold = isHdr, fontSizeSp = 11, fontSizePt = 11f),
                        )
                    ),
                    spaceAfterPt = 0f,
                )
            ),
            fillArgb = if (isHdr) 0xFFF1F5F9.toInt() else null,
        )
    }

    fun withCell(r: Int, c: Int, value: String): TableNode {
        val cols = colCount.coerceAtLeast(c + 1)
        val newRows = rows.mapIndexed { ri, row ->
            val padded = if (row.size < cols) row + List(cols - row.size) { "" } else row
            if (ri == r) padded.mapIndexed { ci, old -> if (ci == c) value else old } else padded
        }
        val newRichRows = if (richRows.isNotEmpty()) {
            richRows.mapIndexed { ri, rCells ->
                if (ri == r) {
                    rCells.mapIndexed { ci, cell ->
                        if (ci == c) {
                            val firstPara = cell.paragraphs.firstOrNull() ?: ParagraphNode(spaceAfterPt = 0f)
                            cell.copy(paragraphs = listOf(firstPara.withPlainText(value)))
                        } else cell
                    }
                } else rCells
            }
        } else emptyList()
        return copy(rows = newRows, richRows = newRichRows)
    }

    fun addRow(): TableNode {
        val cols = colCount.coerceAtLeast(1)
        val addedRich = if (richRows.isNotEmpty()) {
            richRows + listOf(List(cols) { WordTableCell(paragraphs = listOf(ParagraphNode(spaceAfterPt = 0f))) })
        } else emptyList()
        return copy(rows = rows + listOf(List(cols) { "" }), richRows = addedRich)
    }

    fun addCol(): TableNode {
        val addedRich = if (richRows.isNotEmpty()) {
            richRows.map { it + WordTableCell(paragraphs = listOf(ParagraphNode(spaceAfterPt = 0f))) }
        } else emptyList()
        return copy(rows = rows.map { it + "" }, richRows = addedRich, colWidthFractions = emptyList())
    }

    fun removeRow(r: Int): TableNode {
        if (rows.size <= 1) return this
        val remRich = if (richRows.size == rows.size) richRows.filterIndexed { idx, _ -> idx != r } else emptyList()
        return copy(rows = rows.filterIndexed { idx, _ -> idx != r }, richRows = remRich)
    }

    fun removeCol(c: Int): TableNode {
        if (colCount <= 1) return this
        val remRich = if (richRows.isNotEmpty()) richRows.map { row -> row.filterIndexed { idx, _ -> idx != c } } else emptyList()
        return copy(
            rows = rows.map { row -> row.filterIndexed { idx, _ -> idx != c } },
            richRows = remRich,
            colWidthFractions = emptyList(),
        )
    }
}

data class ImageNode(
    override val id: Long = nextId(),
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val caption: String = "",
    val widthPt: Int = 360,
    val heightPt: Int = 240,
    val align: ParaAlign = ParaAlign.CENTER,
) : DocElement {
    fun decodeBitmap(maxPx: Int = 1400): android.graphics.Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > maxPx || opts.outHeight / sample > maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

data class WordDocument(
    val title: String = "Untitled",
    val elements: List<DocElement> = listOf(ParagraphNode()),
    val sourceFormat: DocFormat = DocFormat.DOCX,
    val headerParagraphs: List<ParagraphNode> = emptyList(),
    val footerParagraphs: List<ParagraphNode> = emptyList(),
    val pageBackgroundArgb: Int = 0xFFFFFFFF.toInt(),
    val pageWidthPt: Float = 595f,
    val pageHeightPt: Float = 842f,
    val marginLeftPt: Float = 54f,
    val marginRightPt: Float = 54f,
    val marginTopPt: Float = 54f,
    val marginBottomPt: Float = 54f,
) {
    fun fullPlainText(): String = elements.mapNotNull { el ->
        when (el) {
            is ParagraphNode -> if (el.isPageBreakOnly) null else el.plainText
            is TableNode -> el.rows.joinToString("\n") { it.joinToString("\t") }
            is ImageNode -> null
        }
    }.joinToString("\n\n")

    fun replaceAll(find: String, replaceWith: String, ignoreCase: Boolean = true): Pair<WordDocument, Int> {
        if (find.isEmpty()) return this to 0
        var count = 0
        val updated = elements.map { el ->
            when (el) {
                is ParagraphNode -> {
                    val txt = el.plainText
                    var idx = txt.indexOf(find, 0, ignoreCase)
                    if (idx < 0) el else {
                        while (idx >= 0) {
                            count++
                            idx = txt.indexOf(find, idx + find.length, ignoreCase)
                        }
                        el.withPlainText(txt.replace(find, replaceWith, ignoreCase = ignoreCase))
                    }
                }
                is TableNode -> {
                    val newRows = el.rows.map { row ->
                        row.map { cell ->
                            var idx = cell.indexOf(find, 0, ignoreCase)
                            if (idx < 0) cell else {
                                while (idx >= 0) {
                                    count++
                                    idx = cell.indexOf(find, idx + find.length, ignoreCase)
                                }
                                cell.replace(find, replaceWith, ignoreCase = ignoreCase)
                            }
                        }
                    }
                    val newRich = el.richRows.map { rCells ->
                        rCells.map { cell ->
                            cell.copy(
                                paragraphs = cell.paragraphs.map { p ->
                                    if (p.plainText.contains(find, ignoreCase = ignoreCase)) {
                                        p.withPlainText(p.plainText.replace(find, replaceWith, ignoreCase = ignoreCase))
                                    } else p
                                }
                            )
                        }
                    }
                    el.copy(rows = newRows, richRows = newRich)
                }
                is ImageNode -> el
            }
        }
        return copy(elements = updated) to count
    }
}

private var idCounter = 1000L
@Synchronized private fun nextId(): Long = ++idCounter
