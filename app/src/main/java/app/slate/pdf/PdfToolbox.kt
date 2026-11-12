package app.slate.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.slate.SlateApp
import app.slate.core.DocFormat
import app.slate.core.DocumentException
import app.slate.core.io.DocIO
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.io.OutputStream

enum class PdfStrokeKind { PEN, HIGHLIGHT, SIGNATURE }

/** Normalized 0..1 coordinates on a page so annotations are resolution-independent. */
data class NormalizedPoint(val x: Float, val y: Float)

data class PdfStroke(
    val pageIndex: Int,
    val kind: PdfStrokeKind,
    val points: List<NormalizedPoint>,
    val colorArgb: Int,
    val strokeWidthNorm: Float = 0.005f,
)

data class PdfCommentNote(
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val colorArgb: Int = 0xFFFFE066.toInt(),
)

data class PdfPageTransform(
    /** Original 0-based page index in the source PDF. */
    val originalPageIndex: Int,
    /** Additional rotation in degrees (0, 90, 180, 270). */
    val extraRotationDeg: Int = 0,
)

data class PdfEditState(
    val pages: List<PdfPageTransform>,
    val strokes: List<PdfStroke> = emptyList(),
    val comments: List<PdfCommentNote> = emptyList(),
)

object PdfToolbox {

    fun extractPageTexts(context: Context, pdfFile: File): List<String> {
        SlateApp.get(context).ensurePdfBox()
        PDDocument.load(pdfFile).use { doc ->
            val stripper = PDFTextStripper()
            val out = ArrayList<String>(doc.numberOfPages)
            for (p in 1..doc.numberOfPages) {
                stripper.startPage = p
                stripper.endPage = p
                out.add(runCatching { stripper.getText(doc).trim() }.getOrDefault(""))
            }
            return out
        }
    }

    /**
     * Applies page reordering, deletion, rotation, ink/highlight/signature strokes, and comment notes
     * to [sourcePdf] and writes a valid PDF to [out].
     */
    fun writeEditedPdf(
        context: Context,
        sourcePdf: File,
        state: PdfEditState,
        out: OutputStream,
    ) {
        SlateApp.get(context).ensurePdfBox()
        PDDocument.load(sourcePdf).use { srcDoc ->
            PDDocument().use { dstDoc ->
                state.pages.forEachIndexed { newPageIdx, transform ->
                    if (transform.originalPageIndex !in 0 until srcDoc.numberOfPages) return@forEachIndexed
                    val srcPage = srcDoc.getPage(transform.originalPageIndex)
                    val imported = dstDoc.importPage(srcPage)
                    imported.rotation = (srcPage.rotation + transform.extraRotationDeg) % 360

                    val box: PDRectangle = imported.cropBox ?: imported.mediaBox
                    val pw = box.width
                    val ph = box.height

                    val pageStrokes = state.strokes.filter { it.pageIndex == newPageIdx && it.points.size >= 2 }
                    val pageComments = state.comments.filter { it.pageIndex == newPageIdx && it.text.isNotBlank() }

                    if (pageStrokes.isNotEmpty() || pageComments.isNotEmpty()) {
                        PDPageContentStream(dstDoc, imported, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            for (stroke in pageStrokes) {
                                val r = (stroke.colorArgb shr 16) and 0xFF
                                val g = (stroke.colorArgb shr 8) and 0xFF
                                val b = stroke.colorArgb and 0xFF
                                val gs = PDExtendedGraphicsState().apply {
                                    strokingAlphaConstant = if (stroke.kind == PdfStrokeKind.HIGHLIGHT) 0.38f else 0.95f
                                }
                                cs.setGraphicsStateParameters(gs)
                                cs.setStrokingColor(r, g, b)
                                cs.setLineWidth((stroke.strokeWidthNorm * pw).coerceIn(1f, 32f))
                                cs.setLineCapStyle(1)
                                cs.setLineJoinStyle(1)
                                val first = stroke.points.first()
                                cs.moveTo(box.lowerLeftX + first.x * pw, box.lowerLeftY + (1f - first.y) * ph)
                                for (pt in stroke.points.drop(1)) {
                                    cs.lineTo(box.lowerLeftX + pt.x * pw, box.lowerLeftY + (1f - pt.y) * ph)
                                }
                                cs.stroke()
                            }

                            for (note in pageComments) {
                                val gs = PDExtendedGraphicsState().apply {
                                    nonStrokingAlphaConstant = 0.92f
                                    strokingAlphaConstant = 1f
                                }
                                cs.setGraphicsStateParameters(gs)
                                val x = box.lowerLeftX + (note.x * pw).coerceIn(12f, (pw - 180f).coerceAtLeast(12f))
                                val y = box.lowerLeftY + ((1f - note.y) * ph).coerceIn(28f, (ph - 28f).coerceAtLeast(28f))
                                val boxW = 168f
                                val boxH = 22f
                                cs.setNonStrokingColor(255, 244, 179)
                                cs.addRect(x, y - boxH + 4f, boxW, boxH)
                                cs.fill()
                                cs.setStrokingColor(210, 180, 60)
                                cs.setLineWidth(0.8f)
                                cs.addRect(x, y - boxH + 4f, boxW, boxH)
                                cs.stroke()

                                cs.beginText()
                                cs.setNonStrokingColor(23, 23, 23)
                                cs.setFont(PDType1Font.HELVETICA_BOLD, 8.5f)
                                cs.newLineAtOffset(x + 5f, y - 10f)
                                val safe = note.text.replace('\n', ' ').filter { it.code in 32..126 }.take(34)
                                cs.showText(safe)
                                cs.endText()
                            }
                        }
                    }
                }
                dstDoc.save(out)
            }
        }
    }

    /** Merges two or more PDFs into [out]. */
    fun mergePdfs(context: Context, inputs: List<InputStream>, out: OutputStream) {
        SlateApp.get(context).ensurePdfBox()
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it) }
        merger.destinationStream = out
        merger.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly())
    }

    /** Extracts a 1-based inclusive page range or custom 0-based indices into [out]. */
    fun extractPages(context: Context, sourcePdf: File, zeroBasedPages: List<Int>, out: OutputStream) {
        SlateApp.get(context).ensurePdfBox()
        PDDocument.load(sourcePdf).use { src ->
            PDDocument().use { dst ->
                for (p in zeroBasedPages) {
                    if (p in 0 until src.numberOfPages) {
                        dst.importPage(src.getPage(p))
                    }
                }
                if (dst.numberOfPages == 0) throw DocumentException("No valid pages selected to extract")
                dst.save(out)
            }
        }
    }

    /**
     * Compresses a PDF by rendering pages at a balanced DPI and encoding with JPEG compression,
     * dramatically reducing large scanned PDFs while keeping text legible.
     */
    fun compressPdf(
        context: Context,
        sourcePdf: File,
        out: OutputStream,
        jpegQuality: Float = 0.62f,
        scaleFactor: Float = 1.25f,
        onProgress: (Float) -> Unit = {},
    ) {
        SlateApp.get(context).ensurePdfBox()
        ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                PDDocument().use { dst ->
                    val total = renderer.pageCount.coerceAtLeast(1)
                    for (i in 0 until renderer.pageCount) {
                        onProgress((i + 1).toFloat() / total)
                        renderer.openPage(i).use { page ->
                            val wPt = page.width.toFloat()
                            val hPt = page.height.toFloat()
                            val bmpW = (wPt * scaleFactor).toInt().coerceIn(400, 1800)
                            val bmpH = (hPt * scaleFactor).toInt().coerceIn(400, 2400)
                            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            val pdPage = com.tom_roush.pdfbox.pdmodel.PDPage(PDRectangle(wPt, hPt))
                            dst.addPage(pdPage)
                            val pdImg = JPEGFactory.createFromImage(dst, bmp, jpegQuality)
                            bmp.recycle()
                            PDPageContentStream(dst, pdPage).use { cs ->
                                cs.drawImage(pdImg, 0f, 0f, wPt, hPt)
                            }
                        }
                    }
                    dst.save(out)
                }
            }
        }
    }

    /** Renders a single page (0-based) to a Bitmap at [widthPx]. */
    fun renderPageBitmap(pdfFile: File, pageIndex: Int, widthPx: Int): Bitmap? {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val ratio = if (page.width > 0) page.height.toFloat() / page.width else 1.414f
                    val heightPx = (widthPx * ratio).toInt().coerceAtLeast(100)
                    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    /** Creates a PDF from one or more images (PNG/JPG). */
    fun imagesToPdf(imageStreams: List<InputStream>, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val pdf = PdfDocument()
        try {
            val total = imageStreams.size.coerceAtLeast(1)
            imageStreams.forEachIndexed { idx, stream ->
                onProgress((idx + 1).toFloat() / total)
                val bytes = stream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw DocumentException("Could not decode image #${idx + 1}")
                val pageW = 595
                val pageH = (595f * bmp.height.toFloat() / bmp.width.coerceAtLeast(1)).toInt().coerceIn(300, 1200)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bmp.recycle()
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun copyToTempPdf(context: Context, input: InputStream): File {
        val tmp = File.createTempFile("pdf_", ".pdf", DocIO.secureTemp(context))
        tmp.outputStream().buffered().use { out -> input.copyTo(out) }
        return tmp
    }

    fun copyUriToTempPdf(context: Context, uri: Uri): File =
        DocIO.open(context, uri).use { copyToTempPdf(context, it) }
}
