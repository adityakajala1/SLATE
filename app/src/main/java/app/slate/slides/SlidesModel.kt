package app.slate.slides

import android.graphics.BitmapFactory

enum class ShapeKind(val label: String) {
    TEXT_BOX("Text Box"),
    RECTANGLE("Rectangle"),
    ROUND_RECT("Rounded Card"),
    ELLIPSE("Circle / Oval"),
    CALLOUT("Callout Banner"),
    LINE("Divider / Line"),
    TABLE("Table"),
}

enum class SlideTextAlign { LEFT, CENTER, RIGHT }
enum class SlideVerticalAlign { TOP, MIDDLE, BOTTOM }

data class SlideRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSizePt: Float = 18f,
    val colorArgb: Int = 0xFF171717.toInt(),
)

data class SlideParagraph(
    val runs: List<SlideRun> = emptyList(),
    val align: SlideTextAlign = SlideTextAlign.LEFT,
    val bullet: String = "",
    val indentLevel: Int = 0,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 2f,
) {
    val plainText: String get() = runs.joinToString("") { it.text }
}

data class SlideTableCell(
    val paragraphs: List<SlideParagraph> = emptyList(),
    val fillArgb: Int? = null,
    val verticalAlign: SlideVerticalAlign = SlideVerticalAlign.MIDDLE,
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.plainText }
}

data class SlideTableRow(
    val cells: List<SlideTableCell>,
    val heightFraction: Float = 0.2f,
)

data class SlideTable(
    val colFractions: List<Float>,
    val rows: List<SlideTableRow>,
)

data class SlideItem(
    val id: Long = nextSlideItemId(),
    val kind: ShapeKind = ShapeKind.TEXT_BOX,
    /** Normalized coordinates on the slide canvas (0f..1f). */
    val x: Float = 0.08f,
    val y: Float = 0.15f,
    val w: Float = 0.84f,
    val h: Float = 0.22f,
    val text: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSizeSp: Int = 20,
    val fillArgb: Int? = null,
    val strokeColorArgb: Int? = null,
    val strokeWidthPt: Float = 1f,
    val textColorArgb: Int = 0xFF171717.toInt(),
    val textAlign: SlideTextAlign = SlideTextAlign.LEFT,
    val verticalAlign: SlideVerticalAlign = SlideVerticalAlign.TOP,
    val padLeftPt: Float = 7.2f,
    val padTopPt: Float = 3.6f,
    val padRightPt: Float = 7.2f,
    val padBottomPt: Float = 3.6f,
    val paragraphs: List<SlideParagraph> = emptyList(),
    val table: SlideTable? = null,
    val imageBytes: ByteArray? = null,
    val imageMime: String = "image/png",
) {
    val isImage: Boolean get() = imageBytes != null
    val isTable: Boolean get() = table != null

    /** Complete searchable text of this item (including paragraphs and table cells). */
    val fullText: String
        get() = when {
            table != null -> table.rows.joinToString("\n") { r -> r.cells.joinToString(" | ") { it.plainText } }
            paragraphs.isNotEmpty() -> paragraphs.joinToString("\n") { p ->
                if (p.bullet.isNotEmpty()) "${p.bullet} ${p.plainText}" else p.plainText
            }
            else -> text
        }

    /** Returns a copy with updated plain text, keeping rich paragraph styling synced for edits. */
    fun withEditedText(newText: String): SlideItem {
        val fallbackRun = paragraphs.firstOrNull()?.runs?.firstOrNull()
        val updatedParas = newText.split('\n').mapIndexed { idx, line ->
            val oldPara = paragraphs.getOrNull(idx)
            val paraRun = oldPara?.runs?.firstOrNull() ?: fallbackRun
            SlideParagraph(
                runs = listOf(
                    SlideRun(
                        text = line,
                        bold = paraRun?.bold ?: bold,
                        italic = paraRun?.italic ?: italic,
                        fontSizePt = paraRun?.fontSizePt ?: fontSizeSp.toFloat(),
                        colorArgb = paraRun?.colorArgb ?: textColorArgb,
                    )
                ),
                align = oldPara?.align ?: textAlign,
                bullet = oldPara?.bullet.orEmpty(),
                indentLevel = oldPara?.indentLevel ?: 0,
                spaceBeforePt = oldPara?.spaceBeforePt ?: 0f,
                spaceAfterPt = oldPara?.spaceAfterPt ?: 2f,
            )
        }
        return copy(text = newText, paragraphs = updatedParas)
    }

    fun decodeBitmap(maxPx: Int = 1280): android.graphics.Bitmap? {
        val bytes = imageBytes ?: return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > maxPx || opts.outHeight / sample > maxPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}

data class SlidePage(
    val id: Long = nextSlideItemId(),
    val items: List<SlideItem> = listOf(
        SlideItem(
            kind = ShapeKind.TEXT_BOX,
            x = 0.08f, y = 0.14f, w = 0.84f, h = 0.24f,
            text = "Slide Title",
            bold = true,
            fontSizeSp = 28,
        ),
        SlideItem(
            kind = ShapeKind.TEXT_BOX,
            x = 0.08f, y = 0.42f, w = 0.84f, h = 0.44f,
            text = "Tap to add slide points",
            fontSizeSp = 18,
        ),
    ),
    val notes: String = "",
    val backgroundArgb: Int = 0xFFFFFFFF.toInt(),
) {
    val summaryText: String
        get() = items.mapNotNull { it.fullText.trim().takeIf { t -> t.isNotEmpty() } }.joinToString("\n")
}

data class PresentationDocument(
    val title: String = "Presentation",
    val slides: List<SlidePage> = listOf(SlidePage()),
    val widthPt: Float = 960f,
    val heightPt: Float = 540f,
) {
    val aspectRatio: Float get() = (widthPt / heightPt.coerceAtLeast(1f)).coerceIn(0.5f, 3.0f)

    fun updateSlide(index: Int, transform: (SlidePage) -> SlidePage): PresentationDocument {
        if (index !in slides.indices) return this
        val next = slides.toMutableList().apply { set(index, transform(get(index))) }
        return copy(slides = next)
    }

    fun addSlide(afterIndex: Int): PresentationDocument {
        val pos = (afterIndex + 1).coerceIn(0, slides.size)
        val newSlide = SlidePage(
            items = listOf(
                SlideItem(x = 0.08f, y = 0.14f, w = 0.84f, h = 0.22f, text = "Slide ${slides.size + 1}", bold = true, fontSizeSp = 28),
                SlideItem(x = 0.08f, y = 0.40f, w = 0.84f, h = 0.45f, text = "Click to edit content", fontSizeSp = 18),
            )
        )
        val next = slides.toMutableList().apply { add(pos, newSlide) }
        return copy(slides = next)
    }

    fun duplicateSlide(index: Int): PresentationDocument {
        if (index !in slides.indices) return this
        val orig = slides[index]
        val dup = orig.copy(
            id = nextSlideItemId(),
            items = orig.items.map { it.copy(id = nextSlideItemId()) },
        )
        val next = slides.toMutableList().apply { add(index + 1, dup) }
        return copy(slides = next)
    }

    fun deleteSlide(index: Int): PresentationDocument {
        if (slides.size <= 1 || index !in slides.indices) return this
        val next = slides.filterIndexed { i, _ -> i != index }
        return copy(slides = next)
    }

    fun moveSlide(from: Int, to: Int): PresentationDocument {
        if (from !in slides.indices || to !in slides.indices || from == to) return this
        val next = slides.toMutableList()
        val item = next.removeAt(from)
        next.add(to, item)
        return copy(slides = next)
    }
}

private var slideIdSeq = 5000L
@Synchronized internal fun nextSlideItemId(): Long = ++slideIdSeq
