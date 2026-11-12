package app.slate.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import app.slate.core.DocFormat
import app.slate.core.DocKind
import app.slate.core.extension
import app.slate.core.io.DocIO
import app.slate.data.SortBy
import app.slate.pdf.PdfToolbox
import app.slate.sheet.SheetFormatCodecs
import app.slate.sheet.WorkbookDocument
import app.slate.slides.PresentationDocument
import app.slate.slides.SlidesFormatCodecs
import app.slate.word.WordDocument
import app.slate.word.WordFormatCodecs
import java.io.File

data class FileEntry(
    val id: String,
    val name: String,
    val uri: Uri,
    val file: File?,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val format: DocFormat?,
)

enum class StorageRoot(val label: String) {
    LIBRARY("SLATE Library"),
    DOWNLOADS("Downloads"),
    DOCUMENTS("Documents"),
    INTERNAL("Internal Storage"),
    SAF("Picked Folder"),
}

object FileManager {

    fun hasAllFilesPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun defaultRootDir(context: Context, root: StorageRoot): File = when (root) {
        StorageRoot.LIBRARY -> DocIO.libraryDir(context)
        StorageRoot.DOWNLOADS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        StorageRoot.DOCUMENTS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        StorageRoot.INTERNAL -> Environment.getExternalStorageDirectory()
        StorageRoot.SAF -> DocIO.libraryDir(context)
    }

    fun listDirectory(
        dir: File,
        showHidden: Boolean,
        kindFilter: DocKind?,
        sortBy: SortBy,
        descending: Boolean,
    ): List<FileEntry> {
        val children = dir.listFiles().orEmpty()
        val mapped = children.mapNotNull { f ->
            if (!showHidden && f.name.startsWith(".")) return@mapNotNull null
            val isDir = f.isDirectory
            val fmt = if (isDir) null else DocFormat.fromName(f.name)
            if (!isDir && fmt == null) return@mapNotNull null
            if (!isDir && kindFilter != null && fmt?.kind != kindFilter) return@mapNotNull null
            FileEntry(
                id = Uri.fromFile(f).toString(),
                name = f.name,
                uri = Uri.fromFile(f),
                file = f,
                isDirectory = isDir,
                size = if (isDir) 0L else f.length(),
                lastModified = f.lastModified(),
                format = fmt,
            )
        }
        return sortEntries(mapped, sortBy, descending)
    }

    fun listSafTree(
        context: Context,
        treeUri: Uri,
        showHidden: Boolean,
        kindFilter: DocKind?,
        sortBy: SortBy,
        descending: Boolean,
    ): List<FileEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val mapped = root.listFiles().mapNotNull { df ->
            val name = df.name ?: return@mapNotNull null
            if (!showHidden && name.startsWith(".")) return@mapNotNull null
            val isDir = df.isDirectory
            val fmt = if (isDir) null else DocFormat.detect(name, df.type)
            if (!isDir && fmt == null) return@mapNotNull null
            if (!isDir && kindFilter != null && fmt?.kind != kindFilter) return@mapNotNull null
            FileEntry(
                id = df.uri.toString(),
                name = name,
                uri = df.uri,
                file = null,
                isDirectory = isDir,
                size = df.length(),
                lastModified = df.lastModified(),
                format = fmt,
            )
        }
        return sortEntries(mapped, sortBy, descending)
    }

    /** Recursively collects all supported documents across SLATE Library, Downloads, and Documents. */
    fun collectAllDocuments(context: Context, showHidden: Boolean = false): List<FileEntry> {
        val roots = buildList {
            add(DocIO.libraryDir(context))
            if (hasAllFilesPermission()) {
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            }
        }.distinctBy { it.absolutePath }

        val out = ArrayList<FileEntry>()
        val seen = HashSet<String>()

        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (!showHidden && f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else {
                    val fmt = DocFormat.fromName(f.name)
                    if (fmt != null && fmt.kind != DocKind.IMAGE && seen.add(f.absolutePath)) {
                        out.add(
                            FileEntry(
                                id = Uri.fromFile(f).toString(),
                                name = f.name,
                                uri = Uri.fromFile(f),
                                file = f,
                                isDirectory = false,
                                size = f.length(),
                                lastModified = f.lastModified(),
                                format = fmt,
                            )
                        )
                    }
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    fun sortEntries(list: List<FileEntry>, sortBy: SortBy, descending: Boolean): List<FileEntry> {
        val comparator = Comparator<FileEntry> { a, b ->
            if (a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }
            val cmp = when (sortBy) {
                SortBy.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                SortBy.DATE -> a.lastModified.compareTo(b.lastModified)
                SortBy.SIZE -> a.size.compareTo(b.size)
                SortBy.TYPE -> a.name.extension().compareTo(b.name.extension())
            }
            if (descending) -cmp else cmp
        }
        return list.sortedWith(comparator)
    }

    fun createNewBlankDocument(dir: File, baseName: String, format: DocFormat): File {
        val cleanName = if (baseName.endsWith(".${format.ext}", ignoreCase = true)) baseName
        else "${baseName.removeSuffix(".")}.${format.ext}"
        val target = DocIO.uniqueFile(dir, cleanName)
        target.outputStream().buffered().use { out ->
            when (format.kind) {
                DocKind.WORD -> WordFormatCodecs.write(WordDocument(title = baseName), format, out)
                DocKind.SHEET -> SheetFormatCodecs.write(WorkbookDocument(), format, out)
                DocKind.SLIDES -> SlidesFormatCodecs.write(PresentationDocument(title = baseName), format, out)
                DocKind.PDF -> WordFormatCodecs.renderPdf(WordDocument(title = baseName), out)
                else -> WordFormatCodecs.write(WordDocument(title = baseName), DocFormat.DOCX, out)
            }
        }
        return target
    }

    fun importExternalUriToDir(context: Context, sourceUri: Uri, targetDir: File): File {
        val info = DocIO.info(context, sourceUri)
        val dst = DocIO.uniqueFile(targetDir, info.name)
        DocIO.open(context, sourceUri).use { input ->
            dst.outputStream().buffered().use { out -> input.copyTo(out) }
        }
        return dst
    }

    /** Seeds a welcome starter pack in SLATE Library on first launch so the user can test every format immediately. */
    fun ensureStarterSamples(context: Context) {
        val marker = File(context.filesDir, ".samples_v1")
        if (marker.exists()) return
        val lib = DocIO.libraryDir(context)
        runCatching {
            val welcomeWord = WordDocument(
                title = "Welcome to SLATE",
                elements = listOf(
                    app.slate.word.ParagraphNode(
                        runs = listOf(app.slate.word.TextRun("SLATE — Every document. One place.", app.slate.word.SpanStyle(bold = true, fontSizeSp = 22))),
                        headingLevel = 1,
                    ),
                    app.slate.word.ParagraphNode(
                        runs = listOf(app.slate.word.TextRun("Fast, offline-first, ad-free workspace for PDFs, Word documents, spreadsheets, and presentations.")),
                    ),
                    app.slate.word.TableNode(
                        rows = listOf(
                            listOf("Category", "Formats Supported", "Offline Engine"),
                            listOf("PDF", "PDF, Images", "PdfRenderer + PdfBox"),
                            listOf("Word", "DOCX, DOC, ODT, RTF, TXT", "Streaming XML & OLE2"),
                            listOf("Sheets", "XLSX, XLS, ODS, CSV", "Formula & Grid Engine"),
                            listOf("Slides", "PPTX, PPT, ODP", "16:9 Vector Stage"),
                        )
                    ),
                )
            )
            val docxFile = File(lib, "Welcome_to_SLATE.docx")
            if (!docxFile.exists()) docxFile.outputStream().use { WordFormatCodecs.write(welcomeWord, DocFormat.DOCX, it) }

            val pdfFile = File(lib, "SLATE_Quick_Guide.pdf")
            if (!pdfFile.exists()) pdfFile.outputStream().use { WordFormatCodecs.renderPdf(welcomeWord, it) }

            val sheetCells = mapOf(
                app.slate.sheet.SheetTab.packKey(0, 0) to app.slate.sheet.CellData("Item", app.slate.sheet.CellFormat(bold = true)),
                app.slate.sheet.SheetTab.packKey(0, 1) to app.slate.sheet.CellData("Q1", app.slate.sheet.CellFormat(bold = true)),
                app.slate.sheet.SheetTab.packKey(0, 2) to app.slate.sheet.CellData("Q2", app.slate.sheet.CellFormat(bold = true)),
                app.slate.sheet.SheetTab.packKey(1, 0) to app.slate.sheet.CellData("Design"),
                app.slate.sheet.SheetTab.packKey(1, 1) to app.slate.sheet.CellData("1250"),
                app.slate.sheet.SheetTab.packKey(1, 2) to app.slate.sheet.CellData("1480"),
                app.slate.sheet.SheetTab.packKey(2, 0) to app.slate.sheet.CellData("Engineering"),
                app.slate.sheet.SheetTab.packKey(2, 1) to app.slate.sheet.CellData("3400"),
                app.slate.sheet.SheetTab.packKey(2, 2) to app.slate.sheet.CellData("3950"),
                app.slate.sheet.SheetTab.packKey(3, 0) to app.slate.sheet.CellData("Total", app.slate.sheet.CellFormat(bold = true)),
                app.slate.sheet.SheetTab.packKey(3, 1) to app.slate.sheet.CellData("=SUM(B2:B3)", app.slate.sheet.CellFormat(bold = true)),
                app.slate.sheet.SheetTab.packKey(3, 2) to app.slate.sheet.CellData("=SUM(C2:C3)", app.slate.sheet.CellFormat(bold = true)),
            )
            val wb = WorkbookDocument(listOf(app.slate.sheet.SheetTab("Budget", rows = 15, cols = 6, cells = sheetCells)))
            val xlsxFile = File(lib, "Sample_Budget.xlsx")
            if (!xlsxFile.exists()) xlsxFile.outputStream().use { SheetFormatCodecs.write(wb, DocFormat.XLSX, it) }

            val pres = PresentationDocument(
                title = "SLATE Overview",
                slides = listOf(
                    app.slate.slides.SlidePage(
                        items = listOf(
                            app.slate.slides.SlideItem(x = 0.08f, y = 0.18f, w = 0.84f, h = 0.25f, text = "SLATE Workspace", bold = true, fontSizeSp = 30),
                            app.slate.slides.SlideItem(x = 0.08f, y = 0.48f, w = 0.84f, h = 0.30f, text = "Every document. One place.\n100% offline & ad-free.", fontSizeSp = 20),
                        )
                    )
                )
            )
            val pptxFile = File(lib, "SLATE_Overview.pptx")
            if (!pptxFile.exists()) pptxFile.outputStream().use { SlidesFormatCodecs.write(pres, DocFormat.PPTX, it) }

            marker.writeText("ok")
        }
    }
}
