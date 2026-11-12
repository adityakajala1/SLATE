package app.slate.core

import app.slate.core.io.Cfb
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Identifies a document by content when the name/mime type is missing or misleading. */
object FormatSniffer {
    fun sniff(open: () -> InputStream): DocFormat? = runCatching {
        val head = open().use { s -> ByteArray(8).also { var n = 0; while (n < 8) { val r = s.read(it, n, 8 - n); if (r < 0) break; n += r } } }
        when {
            head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> DocFormat.PDF
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> sniffZip(open)
            Cfb.isCfb(head) -> sniffCfb(open)
            head[0] == '{'.code.toByte() && head[1] == '\\'.code.toByte() && head[2] == 'r'.code.toByte() -> DocFormat.RTF
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> DocFormat.PNG
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> DocFormat.JPG
            else -> null
        }
    }.getOrNull()

    private fun sniffZip(open: () -> InputStream): DocFormat? {
        ZipInputStream(open()).use { z ->
            var guard = 0
            while (guard++ < 400) {
                val e = z.nextEntry ?: break
                when {
                    e.name == "mimetype" -> {
                        val m = z.readBytes().toString(Charsets.US_ASCII).trim()
                        return when {
                            m.endsWith("opendocument.text") -> DocFormat.ODT
                            m.endsWith("opendocument.spreadsheet") -> DocFormat.ODS
                            m.endsWith("opendocument.presentation") -> DocFormat.ODP
                            else -> null
                        }
                    }
                    e.name.startsWith("word/") -> return DocFormat.DOCX
                    e.name.startsWith("xl/") -> return DocFormat.XLSX
                    e.name.startsWith("ppt/") -> return DocFormat.PPTX
                }
            }
        }
        return null
    }

    private fun sniffCfb(open: () -> InputStream): DocFormat? {
        val bytes = open().use { it.readBytes() }
        val names = Cfb(bytes).names().map { it.lowercase() }
        return when {
            "worddocument" in names -> DocFormat.DOC
            "workbook" in names || "book" in names -> DocFormat.XLS
            "powerpoint document" in names -> DocFormat.PPT
            else -> null
        }
    }
}
