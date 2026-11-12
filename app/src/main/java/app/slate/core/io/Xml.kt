package app.slate.core.io

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** XML helpers shared by all Office/OpenDocument engines. Pull parsing keeps memory flat. */
object Xml {
    /** Namespace-aware pull parser. Works on device and in JVM unit tests (kxml2 on the test classpath). */
    fun parser(input: InputStream): XmlPullParser {
        val p: XmlPullParser = try {
            android.util.Xml.newPullParser() ?: kxml()
        } catch (t: Throwable) {
            kxml()
        }
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        return p
    }

    fun parser(text: String): XmlPullParser = parser(text.byteInputStream(Charsets.UTF_8))

    private fun kxml(): XmlPullParser =
        Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') Unit else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Attribute by local name, ignoring namespace (handy for w:val, r:id, table:name ...). */
    fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == local) return getAttributeValue(i)
        return null
    }

    fun XmlPullParser.attr(ns: String, local: String): String? = getAttributeValue(ns, local)

    /** Skip the current START_TAG subtree. */
    fun XmlPullParser.skip() {
        var depth = 1
        while (depth != 0) {
            when (next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

/** Reads an entire ZIP (OOXML / ODF package) into memory as name -> bytes. Office packages are small. */
object Zip {
    fun readAll(input: InputStream, maxTotalBytes: Long = 400L * 1024 * 1024): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zis.readBytes()
                total += bytes.size
                if (total > maxTotalBytes) throw IllegalStateException("Document is too large to open")
                out[e.name.removePrefix("/")] = bytes
            }
        }
        return out
    }

    /** Small writer helper. ODF requires "mimetype" first and STORED; pass [storedFirst] for that. */
    class Writer(out: OutputStream) : AutoCloseable {
        private val zos = ZipOutputStream(out)

        fun stored(name: String, data: ByteArray) {
            val e = ZipEntry(name)
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            val crc = java.util.zip.CRC32(); crc.update(data); e.crc = crc.value
            zos.putNextEntry(e); zos.write(data); zos.closeEntry()
        }

        fun put(name: String, data: ByteArray) {
            zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
        }

        fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

        override fun close() { zos.finish(); zos.flush() }
    }
}

/** Builds an XML string without an XmlSerializer dependency. */
class XmlBuilder(initial: Int = 4096) {
    val sb = StringBuilder(initial)
    fun raw(s: String): XmlBuilder { sb.append(s); return this }
    fun text(s: String): XmlBuilder { sb.append(Xml.escape(s)); return this }
    override fun toString() = sb.toString()
}
