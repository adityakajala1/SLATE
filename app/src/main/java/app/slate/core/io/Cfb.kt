package app.slate.core.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, read-only reader for Microsoft Compound File Binary (OLE2) containers,
 * the wrapper used by legacy .doc, .xls and .ppt files. Implements FAT, mini-FAT,
 * DIFAT and the directory tree – enough to pull named streams out by name.
 */
class Cfb(private val data: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    class Entry(val name: String, val type: Int, val start: Int, val size: Long, val left: Int, val right: Int, val child: Int)

    init {
        require(data.size >= 512) { "Not a compound file" }
        val sig = bb.getLong(0)
        require(sig == SIGNATURE) { "Not a compound file" }
        sectorSize = 1 shl (bb.getShort(0x1E).toInt() and 0xFFFF)
        miniSectorSize = 1 shl (bb.getShort(0x20).toInt() and 0xFFFF)
        val numFatSectors = bb.getInt(0x2C)
        val firstDirSector = bb.getInt(0x30)
        miniCutoff = bb.getInt(0x38)
        val firstMiniFat = bb.getInt(0x3C)
        val numMiniFat = bb.getInt(0x40)
        var difatSector = bb.getInt(0x44)
        val numDifat = bb.getInt(0x48)

        // DIFAT: first 109 entries in the header, the rest chained.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = bb.getInt(0x4C + i * 4)
            if (s >= 0) fatSectors.add(s)
        }
        var guard = 0
        while (difatSector >= 0 && guard++ < numDifat + 1) {
            val off = sectorOffset(difatSector)
            val per = sectorSize / 4 - 1
            for (i in 0 until per) {
                val s = bb.getInt(off + i * 4)
                if (s >= 0) fatSectors.add(s)
            }
            difatSector = bb.getInt(off + per * 4)
        }
        val perSector = sectorSize / 4
        val fatList = IntArray(minOf(fatSectors.size, numFatSectors.coerceAtLeast(fatSectors.size)) * perSector)
        var idx = 0
        for (s in fatSectors) {
            val off = sectorOffset(s)
            if (off + sectorSize > data.size) break
            for (i in 0 until perSector) { if (idx < fatList.size) fatList[idx++] = bb.getInt(off + i * 4) }
        }
        fat = fatList

        val dirBytes = readChain(firstDirSector, -1)
        val list = ArrayList<Entry>()
        val dbb = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        var o = 0
        while (o + 128 <= dirBytes.size) {
            val nameLen = (dbb.getShort(o + 64).toInt() and 0xFFFF)
            val chars = (maxOf(0, nameLen - 2)) / 2
            val sb = StringBuilder()
            for (i in 0 until minOf(chars, 32)) sb.append(dbb.getChar(o + i * 2))
            val type = dirBytes[o + 66].toInt()
            val left = dbb.getInt(o + 68)
            val right = dbb.getInt(o + 72)
            val child = dbb.getInt(o + 76)
            val start = dbb.getInt(o + 116)
            val size = dbb.getInt(o + 120).toLong() and 0xFFFFFFFFL
            list.add(Entry(sb.toString(), type, start, size, left, right, child))
            o += 128
        }
        entries = list

        val mf = if (numMiniFat > 0 && firstMiniFat >= 0) readChain(firstMiniFat, -1) else ByteArray(0)
        val mbb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
        miniFat = IntArray(mf.size / 4) { mbb.getInt(it * 4) }
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) readChain(root.start, root.size) else ByteArray(0)
    }

    private fun sectorOffset(sector: Int): Int = (sector + 1) * sectorSize

    private fun readChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        val limit = data.size / sectorSize + 2
        while (s >= 0 && guard++ < limit) {
            val off = sectorOffset(s)
            if (off >= data.size) break
            val n = minOf(sectorSize, data.size - off)
            out.write(data, off, n)
            if (size >= 0 && out.size() >= size) break
            s = if (s < fat.size) fat[s] else -2
        }
        val bytes = out.toByteArray()
        return if (size in 0..bytes.size.toLong()) bytes.copyOf(size.toInt()) else bytes
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard++ < miniFat.size + 1) {
            val off = s * miniSectorSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
            if (out.size() >= size) break
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        return out.toByteArray().copyOf(size.toInt().coerceAtMost(out.size()))
    }

    fun names(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    /** Returns the stream with [name] (case-insensitive), or null. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        return if (e.size < miniCutoff) readMiniChain(e.start, e.size) else readChain(e.start, e.size)
    }

    companion object {
        private const val SIGNATURE = -0x1ee54e5e1fee3030L // D0 CF 11 E0 A1 B1 1A E1 (LE)
        fun isCfb(head: ByteArray): Boolean =
            head.size >= 8 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(0) == SIGNATURE
    }
}
