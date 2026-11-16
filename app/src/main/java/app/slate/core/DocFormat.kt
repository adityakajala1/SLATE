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
        private val textExtensions = setOf(
            "txt", "text", "log", "md", "markdown", "mdown",
            "json", "xml", "html", "htm", "xhtml", "css", "scss", "sass", "less",
            "js", "jsx", "ts", "tsx", "mjs", "cjs",
            "py", "pyw", "java", "kt", "kts", "c", "cpp", "cc", "cxx", "h", "hpp", "hh",
            "cs", "go", "rs", "rb", "php", "sh", "bash", "zsh", "bat", "cmd", "ps1",
            "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties", "env",
            "sql", "gradle", "diff", "patch", "nfo", "srt", "vtt", "sub", "ass",
            "tex", "bib", "asc", "me", "1", "man", "rst", "org"
        )
        private val sheetExtensions = setOf("xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods", "fods", "ots", "csv", "tsv")
        private val slidesExtensions = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm", "ppt", "pot", "pps", "odp", "fodp", "otp")
        private val wordExtensions = setOf("docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "fodt", "ott", "rtf")
        private val imageExtensions = setOf("png", "jpg", "jpeg", "jpe", "jfif", "webp", "bmp", "gif", "ico", "svg", "tif", "tiff")
        private val pdfExtensions = setOf("pdf", "fdf", "xfdf")

        private val byMime = entries.associateBy { it.mime } + mapOf(
            "text/rtf" to RTF,
            "text/comma-separated-values" to CSV,
            "application/csv" to CSV,
            "image/jpg" to JPG,
            "image/jpeg" to JPG,
            "image/webp" to PNG,
            "image/bmp" to PNG,
            "image/x-ms-bmp" to PNG,
            "image/gif" to PNG,
            "image/svg+xml" to PNG,
            "application/x-pdf" to PDF,
            "application/json" to TXT,
            "application/xml" to TXT,
            "text/xml" to TXT,
            "text/html" to TXT,
            "text/markdown" to TXT,
            "text/x-markdown" to TXT,
            "application/x-sh" to TXT,
            "application/x-yaml" to TXT,
            "text/yaml" to TXT,
            "text/x-python" to TXT,
            "text/x-java-source" to TXT,
            "text/x-c" to TXT,
            "text/x-c++" to TXT,
        )

        fun fromName(name: String?): DocFormat? {
            if (name == null) return null
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext.isEmpty()) return null
            return when {
                ext in pdfExtensions -> PDF
                ext in wordExtensions -> when {
                    ext.startsWith("doc") && !ext.endsWith("x") && !ext.endsWith("m") -> DOC
                    ext.contains("odt") || ext.contains("ott") -> ODT
                    ext == "rtf" -> RTF
                    else -> DOCX
                }
                ext in sheetExtensions -> when {
                    ext == "xls" || ext == "xlt" -> XLS
                    ext.contains("ods") || ext.contains("ots") -> ODS
                    ext == "csv" || ext == "tsv" -> CSV
                    else -> XLSX
                }
                ext in slidesExtensions -> when {
                    ext == "ppt" || ext == "pot" || ext == "pps" -> PPT
                    ext.contains("odp") || ext.contains("otp") -> ODP
                    else -> PPTX
                }
                ext in imageExtensions -> when {
                    ext in setOf("jpg", "jpeg", "jpe", "jfif") -> JPG
                    else -> PNG
                }
                ext in textExtensions -> TXT
                else -> null
            }
        }

        fun fromMime(mime: String?): DocFormat? {
            if (mime == null) return null
            val lower = mime.lowercase(Locale.ROOT)
            byMime[lower]?.let { return it }
            return when {
                lower.startsWith("text/") -> TXT
                lower.startsWith("image/") -> if (lower.contains("jpeg") || lower.contains("jpg")) JPG else PNG
                else -> null
            }
        }

        fun detect(name: String?, mime: String?): DocFormat? = fromName(name) ?: fromMime(mime)

        /** Formats offered by the document browser (images are handled by the converter only). */
        val documents: List<DocFormat> = entries.filter { it.kind != DocKind.IMAGE }
    }
}

fun String.extension(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)
fun String.withoutExtension(): String = if (contains('.')) substringBeforeLast('.') else this
