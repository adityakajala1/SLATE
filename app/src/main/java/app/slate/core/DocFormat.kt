package app.slate.core

import java.util.Locale

/** High-level family used for icons, grouping, and routing to the right viewer/editor. */
enum class DocKind(val label: String) {
    PDF("PDF"), WORD("Document"), SHEET("Spreadsheet"), SLIDES("Presentation"), IMAGE("Image"), OTHER("File")
}

/**
 * Every format SLATE understands. [canRead] / [canWrite] describe what the bundled engines
 * genuinely support — they are used by the UI so that no button promises more than the engine does.
 */
enum class DocFormat(
    val ext: String,
    val mime: String,
    val kind: DocKind,
    val canRead: Boolean,
    val canWrite: Boolean,
    val label: String,
) {
    PDF("pdf", "application/pdf", DocKind.PDF, true, true, "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocKind.WORD, true, true, "Word (DOCX)"),
    DOC("doc", "application/msword", DocKind.WORD, true, false, "Word 97-2003 (DOC)"),
    ODT("odt", "application/vnd.oasis.opendocument.text", DocKind.WORD, true, true, "OpenDocument Text (ODT)"),
    RTF("rtf", "application/rtf", DocKind.WORD, true, true, "Rich Text (RTF)"),
    TXT("txt", "text/plain", DocKind.WORD, true, true, "Plain text (TXT)"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DocKind.SHEET, true, true, "Excel (XLSX)"),
    XLS("xls", "application/vnd.ms-excel", DocKind.SHEET, true, false, "Excel 97-2003 (XLS)"),
    ODS("ods", "application/vnd.oasis.opendocument.spreadsheet", DocKind.SHEET, true, true, "OpenDocument Sheet (ODS)"),
    CSV("csv", "text/csv", DocKind.SHEET, true, true, "CSV"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", DocKind.SLIDES, true, true, "PowerPoint (PPTX)"),
    PPT("ppt", "application/vnd.ms-powerpoint", DocKind.SLIDES, true, false, "PowerPoint 97-2003 (PPT)"),
    ODP("odp", "application/vnd.oasis.opendocument.presentation", DocKind.SLIDES, true, true, "OpenDocument Presentation (ODP)"),
    PNG("png", "image/png", DocKind.IMAGE, true, true, "PNG image"),
    JPG("jpg", "image/jpeg", DocKind.IMAGE, true, true, "JPEG image");

    companion object {
        private val byExt = entries.associateBy { it.ext } + mapOf("jpeg" to JPG, "text" to TXT, "log" to TXT, "md" to TXT)
        private val byMime = entries.associateBy { it.mime } + mapOf(
            "text/rtf" to RTF, "text/comma-separated-values" to CSV, "application/csv" to CSV,
            "image/jpg" to JPG, "application/x-pdf" to PDF,
        )

        fun fromName(name: String?): DocFormat? {
            if (name == null) return null
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return null
            return byExt[name.substring(dot + 1).lowercase(Locale.ROOT)]
        }

        fun fromMime(mime: String?): DocFormat? = mime?.let { byMime[it.lowercase(Locale.ROOT)] }

        fun detect(name: String?, mime: String?): DocFormat? = fromName(name) ?: fromMime(mime)

        /** Formats offered by the document browser (images are handled by the converter only). */
        val documents: List<DocFormat> = entries.filter { it.kind != DocKind.IMAGE }
    }
}

fun String.extension(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)
fun String.withoutExtension(): String = if (contains('.')) substringBeforeLast('.') else this
