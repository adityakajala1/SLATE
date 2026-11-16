package app.slate.core

import app.slate.core.io.Cfb
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Identifies a document by content when the name/mime type is missing or misleading. */
object FormatSniffer {
    fun sniff(open: () -> InputStream): DocFormat? = runCatching {
        val head = open().use { s -> ByteArray(16).also { var n = 0; while (n < 16) { val r = s.read(it, n, 16 - n); if (r < 0) break; n += r } } }
        when {
            head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> DocFormat.PDF
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> sniffZip(open)
            Cfb.isCfb(head) -> sniffCfb(open)
            head.size >= 3 && head[0] == '{'.code.toByte() && head[1] == '\\'.code.toByte() && head[2] == 'r'.code.toByte() -> DocFormat.RTF
            head.size >= 2 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> DocFormat.PNG
            head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> DocFormat.JPG
            head.size >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() -> DocFormat.PNG // WebP
            head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte() -> DocFormat.PNG // BMP
            head.size >= 3 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> DocFormat.PNG // GIF
            isLikelyText(head) -> DocFormat.TXT
            else -> DocFormat.TXT
        }
    }.getOrNull()

    private fun isLikelyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return bytes.all { b ->
            val u = b.toInt() and 0xFF
            u == 9 || u == 10 || u == 13 || (u in 32..126) || (u >= 128)
        }
    }

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
