package app.slate.sheet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.slate.core.DocFormat
import app.slate.core.DocumentException
import app.slate.core.io.Cfb
import app.slate.core.io.Xml
import app.slate.core.io.Xml.attr
import app.slate.core.io.Zip
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SheetFormatCodecs {

    fun read(input: InputStream, format: DocFormat): WorkbookDocument = when (format) {
        DocFormat.XLSX -> readXlsx(input)
        DocFormat.ODS -> readOds(input)
        DocFormat.CSV -> readCsv(input)
        DocFormat.XLS -> readLegacyXls(input)
        else -> throw DocumentException("Unsupported spreadsheet format: ${format.label}")
    }

    fun write(wb: WorkbookDocument, format: DocFormat, out: OutputStream) = when (format) {
        DocFormat.XLSX -> writeXlsx(wb, out)
        DocFormat.ODS -> writeOds(wb, out)
        DocFormat.CSV -> writeCsv(wb.current, out)
        DocFormat.PDF -> renderPdf(wb, out)
        else -> throw DocumentException("Cannot write spreadsheet to ${format.label}")
    }

    // --------------------------------------------------------- XLSX READER & WRITER
    private fun readXlsx(input: InputStream): WorkbookDocument {
        val entries = Zip.readAll(input)
        val sharedStrings = ArrayList<String>()
        entries["xl/sharedStrings.xml"]?.let { ssBytes ->
            val p = Xml.parser(ssBytes.inputStream())
            var inSi = false
            val sb = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (p.name == "si") { inSi = true; sb.setLength(0) }
                        else if (p.name == "t" && inSi) sb.append(p.nextText())
                    }
                    XmlPullParser.END_TAG -> if (p.name == "si" && inSi) {
                        sharedStrings.add(sb.toString())
                        inSi = false
                    }
                }
            }
        }

        // Parse simple cell styles (bold/italic) if styles.xml is present
        val boldFonts = HashSet<Int>()
        val italicFonts = HashSet<Int>()
        val xfFontIds = ArrayList<Int>()
        entries["xl/styles.xml"]?.let { stBytes ->
            val p = Xml.parser(stBytes.inputStream())
            var inFonts = false
            var inCellXfs = false
            var fontIdx = -1
            var b = false
            var i = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "fonts" -> inFonts = true
                        "font" -> if (inFonts) { fontIdx++; b = false; i = false }
                        "b" -> if (inFonts) b = p.attr("val") != "0"
                        "i" -> if (inFonts) i = p.attr("val") != "0"
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) xfFontIds.add(p.attr("fontId")?.toIntOrNull() ?: 0)
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "font" -> if (inFonts) {
                            if (b) boldFonts.add(fontIdx)
                            if (i) italicFonts.add(fontIdx)
                        }
                        "fonts" -> inFonts = false
                        "cellXfs" -> inCellXfs = false
                    }
                }
            }
        }

        val relMap = HashMap<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { relBytes ->
            val p = Xml.parser(relBytes.inputStream())
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.attr("Id")
                    val target = p.attr("Target")
                    if (id != null && target != null) {
                        val norm = if (target.startsWith("/")) target.removePrefix("/")
                        else if (target.startsWith("xl/")) target else "xl/$target"
                        relMap[id] = norm
                    }
                }
            }
        }

        val sheetDefs = ArrayList<Pair<String, String>>()
        entries["xl/workbook.xml"]?.let { wbBytes ->
            val p = Xml.parser(wbBytes.inputStream())
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = p.attr("name") ?: "Sheet${sheetDefs.size + 1}"
                    val rId = p.attr("id")
                    val path = rId?.let { relMap[it] } ?: "xl/worksheets/sheet${sheetDefs.size + 1}.xml"
                    sheetDefs.add(name to path)
                }
            }
        }

        if (sheetDefs.isEmpty()) {
            entries.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
                .forEachIndexed { idx, path -> sheetDefs.add("Sheet${idx + 1}" to path) }
        }

        val tabs = sheetDefs.mapNotNull { (name, path) ->
            val bytes = entries[path] ?: return@mapNotNull null
            parseXlsxSheet(name, bytes, sharedStrings, xfFontIds, boldFonts, italicFonts)
        }
        return WorkbookDocument(sheets = tabs.ifEmpty { listOf(SheetTab("Sheet1")) })
    }

    private fun parseXlsxSheet(
        name: String,
        bytes: ByteArray,
        sharedStrings: List<String>,
        xfFontIds: List<Int>,
        boldFonts: Set<Int>,
        italicFonts: Set<Int>,
    ): SheetTab {
        val p = Xml.parser(bytes.inputStream())
        val cells = HashMap<Long, CellData>()
        var maxRow = 24
        var maxCol = 8

        var curRow = 0
        var curCol = 0
        var cellType: String? = null
        var styleIdx = 0
        var formula: String? = null
        var value: String? = null
        val inlineStr = StringBuilder()
        var inIs = false

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        val rAttr = p.attr("r")?.toIntOrNull()
                        if (rAttr != null) curRow = (rAttr - 1).coerceAtLeast(0)
                    }
                    "c" -> {
                        val ref = p.attr("r")
                        val rc = ref?.let { SheetTab.parseCellRef(it) }
                        if (rc != null) { curRow = rc.first; curCol = rc.second }
                        cellType = p.attr("t")
                        styleIdx = p.attr("s")?.toIntOrNull() ?: 0
                        formula = null
                        value = null
                        inlineStr.setLength(0)
                    }
                    "f" -> formula = p.nextText()
                    "v" -> value = p.nextText()
                    "is" -> inIs = true
                    "t" -> if (inIs) inlineStr.append(p.nextText())
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "is" -> inIs = false
                    "c" -> {
                        val raw = when {
                            !formula.isNullOrBlank() -> "=${formula!!.removePrefix("=")}"
                            cellType == "s" -> {
                                val idx = value?.toIntOrNull()
                                if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else value.orEmpty()
                            }
                            cellType == "inlineStr" -> inlineStr.toString()
                            cellType == "b" -> if (value == "1") "TRUE" else "FALSE"
                            else -> value.orEmpty()
                        }
                        val fId = xfFontIds.getOrElse(styleIdx) { 0 }
                        val fmt = CellFormat(bold = fId in boldFonts, italic = fId in italicFonts)
                        if (raw.isNotEmpty() || fmt != CellFormat()) {
                            cells[SheetTab.packKey(curRow, curCol)] = CellData(raw, fmt)
                            if (curRow + 1 > maxRow) maxRow = curRow + 1
                            if (curCol + 1 > maxCol) maxCol = curCol + 1
                        }
                        curCol++
                    }
                    "row" -> curRow++
                }
            }
        }
        return SheetTab(name = name, rows = maxRow, cols = maxCol, cells = cells)
    }

    private fun writeXlsx(wb: WorkbookDocument, out: OutputStream) {
        val stringTable = LinkedHashMap<String, Int>()
        fun intern(s: String): Int = stringTable.getOrPut(s) { stringTable.size }

        // Pre-intern all string literals
        for (sheet in wb.sheets) {
            for ((_, cell) in sheet.cells) {
                if (!cell.isFormula && cell.raw.isNotEmpty() && cell.raw.toDoubleOrNull() == null) {
                    intern(cell.raw)
                }
            }
        }

        Zip.Writer(out).use { z ->
            val ct = StringBuilder()
            ct.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            ct.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            ct.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            ct.append("""<Default Extension="xml" ContentType="application/xml"/>""")
            ct.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            ct.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            ct.append("""<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>""")
            wb.sheets.indices.forEach { i ->
                ct.append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            ct.append("</Types>")
            z.put("[Content_Types].xml", ct.toString())

            z.put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""".trimIndent()
            )

            // styles.xml: 0=Normal, 1=Bold, 2=Italic, 3=BoldItalic
            z.put(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="4">
                    <font><sz val="11"/><name val="Calibri"/></font>
                    <font><b/><sz val="11"/><name val="Calibri"/></font>
                    <font><i/><sz val="11"/><name val="Calibri"/></font>
                    <font><b/><i/><sz val="11"/><name val="Calibri"/></font>
                  </fonts>
                  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="4">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                    <xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                  </cellXfs>
                </styleSheet>""".trimIndent()
            )

            val wbXml = StringBuilder()
            wbXml.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            wbXml.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            val wbRels = StringBuilder()
            wbRels.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            wbRels.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            wbRels.append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            wbRels.append("""<Relationship Id="rIdSS" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>""")

            wb.sheets.forEachIndexed { idx, tab ->
                val sNum = idx + 1
                wbXml.append("""<sheet name="${Xml.escape(tab.name)}" sheetId="$sNum" r:id="rId$sNum"/>""")
                wbRels.append("""<Relationship Id="rId$sNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$sNum.xml"/>""")
                z.put("xl/worksheets/sheet$sNum.xml", buildXlsxWorksheetXml(tab, ::intern))
            }
            wbXml.append("</sheets></workbook>")
            wbRels.append("</Relationships>")
            z.put("xl/workbook.xml", wbXml.toString())
            z.put("xl/_rels/workbook.xml.rels", wbRels.toString())

            val ss = StringBuilder()
            ss.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            ss.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${stringTable.size}" uniqueCount="${stringTable.size}">""")
            for (s in stringTable.keys) {
                ss.append("""<si><t xml:space="preserve">${Xml.escape(s)}</t></si>""")
            }
            ss.append("</sst>")
            z.put("xl/sharedStrings.xml", ss.toString())
        }
    }

    private fun buildXlsxWorksheetXml(tab: SheetTab, intern: (String) -> Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        val byRow = tab.cells.entries.groupBy { SheetTab.unpackRow(it.key) }.toSortedMap()
        for ((r, entries) in byRow) {
            sb.append("""<row r="${r + 1}">""")
            val sortedCols = entries.sortedBy { SheetTab.unpackCol(it.key) }
            for ((k, cell) in sortedCols) {
                val c = SheetTab.unpackCol(k)
                val ref = "${SheetTab.colName(c)}${r + 1}"
                val sIdx = when {
                    cell.format.bold && cell.format.italic -> 3
                    cell.format.bold -> 1
                    cell.format.italic -> 2
                    else -> 0
                }
                val sAttr = if (sIdx > 0) """ s="$sIdx"""" else ""
                when {
                    cell.isFormula -> {
                        val fExpr = Xml.escape(cell.raw.substring(1))
                        val evaluated = tab.displayValue(r, c)
                        val numVal = evaluated.toDoubleOrNull()
                        if (numVal != null) {
                            sb.append("""<c r="$ref"$sAttr><f>$fExpr</f><v>$numVal</v></c>""")
                        } else {
                            sb.append("""<c r="$ref" t="str"$sAttr><f>$fExpr</f><v>${Xml.escape(evaluated)}</v></c>""")
                        }
                    }
                    cell.raw.toDoubleOrNull() != null -> {
                        sb.append("""<c r="$ref"$sAttr><v>${cell.raw}</v></c>""")
                    }
                    cell.raw.isNotEmpty() -> {
                        val idx = intern(cell.raw)
                        sb.append("""<c r="$ref" t="s"$sAttr><v>$idx</v></c>""")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // --------------------------------------------------------- ODS READER & WRITER
    private fun readOds(input: InputStream): WorkbookDocument {
        val entries = Zip.readAll(input)
        val content = entries["content.xml"] ?: throw DocumentException("Invalid ODS: missing content.xml")
        val p = Xml.parser(content.inputStream())

        val sheets = ArrayList<SheetTab>()
        var currentSheetName: String? = null
        var currentCells = HashMap<Long, CellData>()
        var r = 0
        var c = 0
        var maxR = 24
        var maxC = 8
        var repeatCols = 1
        var repeatRows = 1
        var formula: String? = null
        val cellText = StringBuilder()
        var inCell = false

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "table" -> {
                        currentSheetName = p.attr("name") ?: "Sheet${sheets.size + 1}"
                        currentCells = HashMap()
                        r = 0; maxR = 24; maxC = 8
                    }
                    "table-row" -> {
                        c = 0
                        repeatRows = (p.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtMost(200)
                    }
                    "table-cell" -> {
                        inCell = true
                        cellText.setLength(0)
                        repeatCols = (p.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(100)
                        formula = p.attr("formula")?.let { f ->
                            // ODF formulas look like "of:=SUM([.A1:.A5])"
                            f.removePrefix("of:").replace("[.", "").replace(":.", ":").replace("]", "")
                        }
                    }
                    "p" -> if (inCell && cellText.isNotEmpty()) cellText.append('\n')
                }
                XmlPullParser.TEXT -> if (inCell) cellText.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "table-cell" -> {
                        inCell = false
                        val raw = if (!formula.isNullOrBlank()) formula!! else cellText.toString()
                        if (raw.isNotEmpty()) {
                            for (dr in 0 until repeatRows.coerceAtMost(10)) {
                                for (dc in 0 until repeatCols.coerceAtMost(20)) {
                                    val rr = r + dr
                                    val cc = c + dc
                                    currentCells[SheetTab.packKey(rr, cc)] = CellData(raw)
                                    if (rr + 1 > maxR) maxR = rr + 1
                                    if (cc + 1 > maxC) maxC = cc + 1
                                }
                            }
                        }
                        c += repeatCols
                    }
                    "table-row" -> r += repeatRows
                    "table" -> if (currentSheetName != null) {
                        sheets.add(SheetTab(currentSheetName!!, maxR, maxC, currentCells))
                        currentSheetName = null
                    }
                }
            }
        }
        return WorkbookDocument(sheets = sheets.ifEmpty { listOf(SheetTab("Sheet1")) })
    }

    private fun writeOds(wb: WorkbookDocument, out: OutputStream) {
        val mime = "application/vnd.oasis.opendocument.spreadsheet"
        Zip.Writer(out).use { z ->
            z.stored("mimetype", mime.toByteArray(Charsets.US_ASCII))
            z.put(
                "META-INF/manifest.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>""".trimIndent()
            )
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.append("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:of="urn:oasis:names:tc:opendocument:xmlns:of:1.2" office:version="1.2">""")
            sb.append("<office:body><office:spreadsheet>")
            for (sheet in wb.sheets) {
                val maxUsedRow = (sheet.cells.keys.maxOfOrNull { SheetTab.unpackRow(it) } ?: 0)
                val maxUsedCol = (sheet.cells.keys.maxOfOrNull { SheetTab.unpackCol(it) } ?: 0)
                sb.append("""<table:table table:name="${Xml.escape(sheet.name)}">""")
                for (r in 0..maxUsedRow) {
                    sb.append("<table:table-row>")
                    for (c in 0..maxUsedCol) {
                        val cd = sheet.cell(r, c)
                        if (cd.raw.isEmpty()) {
                            sb.append("<table:table-cell/>")
                        } else {
                            val disp = sheet.displayValue(r, c)
                            val num = disp.toDoubleOrNull()
                            val fAttr = if (cd.isFormula) """ table:formula="of:${Xml.escape(cd.raw)}"""" else ""
                            if (num != null) {
                                sb.append("""<table:table-cell office:value-type="float" office:value="$num"$fAttr><text:p>${Xml.escape(disp)}</text:p></table:table-cell>""")
                            } else {
                                sb.append("""<table:table-cell office:value-type="string"$fAttr><text:p>${Xml.escape(disp)}</text:p></table:table-cell>""")
                            }
                        }
                    }
                    sb.append("</table:table-row>")
                }
                sb.append("</table:table>")
            }
            sb.append("</office:spreadsheet></office:body></office:document-content>")
            z.put("content.xml", sb.toString())
        }
    }

    // --------------------------------------------------------- CSV READER & WRITER (RFC 4180)
    private fun readCsv(input: InputStream): WorkbookDocument {
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        val rows = parseCsvRows(text)
        val cells = HashMap<Long, CellData>()
        var maxC = 8
        rows.forEachIndexed { r, row ->
            if (row.size > maxC) maxC = row.size
            row.forEachIndexed { c, v ->
                if (v.isNotEmpty()) cells[SheetTab.packKey(r, c)] = CellData(v)
            }
        }
        val tab = SheetTab("Sheet1", rows = maxOf(rows.size, 24), cols = maxC, cells = cells)
        return WorkbookDocument(listOf(tab))
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var curRow = ArrayList<String>()
        val curField = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        curField.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    curField.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        curRow.add(curField.toString())
                        curField.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        curRow.add(curField.toString())
                        curField.setLength(0)
                        rows.add(curRow)
                        curRow = ArrayList()
                    }
                    '\n' -> {
                        curRow.add(curField.toString())
                        curField.setLength(0)
                        rows.add(curRow)
                        curRow = ArrayList()
                    }
                    else -> curField.append(ch)
                }
            }
            i++
        }
        if (curField.isNotEmpty() || curRow.isNotEmpty()) {
            curRow.add(curField.toString())
            rows.add(curRow)
        }
        return rows
    }

    private fun writeCsv(tab: SheetTab, out: OutputStream) {
        val maxR = (tab.cells.keys.maxOfOrNull { SheetTab.unpackRow(it) } ?: 0)
        val maxC = (tab.cells.keys.maxOfOrNull { SheetTab.unpackCol(it) } ?: 0)
        val sb = StringBuilder()
        for (r in 0..maxR) {
            if (r > 0) sb.append("\r\n")
            for (c in 0..maxC) {
                if (c > 0) sb.append(',')
                val v = tab.displayValue(r, c)
                if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"')
                } else {
                    sb.append(v)
                }
            }
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    // --------------------------------------------------------- LEGACY XLS (BIFF8 OLE2) READER
    private fun readLegacyXls(input: InputStream): WorkbookDocument {
        val raw = input.readBytes()
        if (!Cfb.isCfb(raw)) {
            return readCsv(raw.inputStream())
        }
        val cfb = Cfb(raw)
        val wbBytes = cfb.stream("Workbook") ?: cfb.stream("Book")
            ?: throw DocumentException("Invalid XLS file: missing Workbook stream")
        return parseBiff8Workbook(wbBytes)
    }

    private fun parseBiff8Workbook(data: ByteArray): WorkbookDocument {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val sheetNames = ArrayList<String>()
        val sst = ArrayList<String>()
        val sheets = ArrayList<SheetTab>()

        var pos = 0
        var inWorksheet = false
        var currentCells = HashMap<Long, CellData>()
        var maxR = 24
        var maxC = 8
        var lastFormulaRow = -1
        var lastFormulaCol = -1

        while (pos + 4 <= data.size) {
            val recId = bb.getShort(pos).toInt() and 0xFFFF
            val recLen = bb.getShort(pos + 2).toInt() and 0xFFFF
            val bodyStart = pos + 4
            if (bodyStart + recLen > data.size) break

            when (recId) {
                0x0809 -> { // BOF
                    if (recLen >= 4) {
                        val dt = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                        if (dt == 0x0010) { // Worksheet substream
                            inWorksheet = true
                            currentCells = HashMap()
                            maxR = 24
                            maxC = 8
                        }
                    }
                }
                0x000A -> { // EOF
                    if (inWorksheet) {
                        val name = sheetNames.getOrElse(sheets.size) { "Sheet${sheets.size + 1}" }
                        sheets.add(SheetTab(name, maxR, maxC, currentCells))
                        inWorksheet = false
                    }
                }
                0x0085 -> { // BOUNDSHEET
                    if (recLen >= 8) {
                        val cch = data[bodyStart + 6].toInt() and 0xFF
                        val flags = data[bodyStart + 7].toInt() and 0xFF
                        val isUtf16 = (flags and 0x01) != 0
                        val strStart = bodyStart + 8
                        val name = if (isUtf16 && strStart + cch * 2 <= bodyStart + recLen) {
                            String(data, strStart, cch * 2, Charsets.UTF_16LE)
                        } else if (strStart + cch <= bodyStart + recLen) {
                            String(data, strStart, cch, Charsets.ISO_8859_1)
                        } else "Sheet${sheetNames.size + 1}"
                        sheetNames.add(name)
                    }
                }
                0x00FC -> { // SST (Shared String Table)
                    parseBiff8Sst(data, bodyStart, recLen, sst)
                }
                0x00FD -> if (inWorksheet && recLen >= 10) { // LABELSST
                    val r = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val c = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    val sstIdx = bb.getInt(bodyStart + 6)
                    val str = sst.getOrNull(sstIdx).orEmpty()
                    if (str.isNotEmpty()) {
                        currentCells[SheetTab.packKey(r, c)] = CellData(str)
                        if (r + 1 > maxR) maxR = r + 1
                        if (c + 1 > maxC) maxC = c + 1
                    }
                }
                0x0204 -> if (inWorksheet && recLen >= 8) { // LABEL
                    val r = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val c = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    val cch = bb.getShort(bodyStart + 6).toInt() and 0xFFFF
                    val str = readBiffString(data, bodyStart + 8, bodyStart + recLen, cch)
                    if (str.isNotEmpty()) {
                        currentCells[SheetTab.packKey(r, c)] = CellData(str)
                        if (r + 1 > maxR) maxR = r + 1
                        if (c + 1 > maxC) maxC = c + 1
                    }
                }
                0x0203 -> if (inWorksheet && recLen >= 14) { // NUMBER
                    val r = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val c = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    val v = bb.getDouble(bodyStart + 6)
                    currentCells[SheetTab.packKey(r, c)] = CellData(FormulaEvaluator.formatNumber(v))
                    if (r + 1 > maxR) maxR = r + 1
                    if (c + 1 > maxC) maxC = c + 1
                }
                0x027E -> if (inWorksheet && recLen >= 10) { // RK
                    val r = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val c = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    val rk = bb.getInt(bodyStart + 6)
                    val v = decodeRk(rk)
                    currentCells[SheetTab.packKey(r, c)] = CellData(FormulaEvaluator.formatNumber(v))
                    if (r + 1 > maxR) maxR = r + 1
                    if (c + 1 > maxC) maxC = c + 1
                }
                0x00BD -> if (inWorksheet && recLen >= 6) { // MULRK
                    val r = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val firstCol = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    val lastCol = bb.getShort(bodyStart + recLen - 2).toInt() and 0xFFFF
                    var off = bodyStart + 4
                    for (c in firstCol..lastCol) {
                        if (off + 6 > bodyStart + recLen - 2) break
                        val rk = bb.getInt(off + 2)
                        currentCells[SheetTab.packKey(r, c)] = CellData(FormulaEvaluator.formatNumber(decodeRk(rk)))
                        if (r + 1 > maxR) maxR = r + 1
                        if (c + 1 > maxC) maxC = c + 1
                        off += 6
                    }
                }
                0x0006 -> if (inWorksheet && recLen >= 14) { // FORMULA (cached numeric or next STRING record)
                    val r = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val c = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    lastFormulaRow = r
                    lastFormulaCol = c
                    val byte6 = data[bodyStart + 12].toInt() and 0xFF
                    val byte7 = data[bodyStart + 13].toInt() and 0xFF
                    if (byte6 != 0xFF || byte7 != 0xFFFF) {
                        val v = bb.getDouble(bodyStart + 6)
                        currentCells[SheetTab.packKey(r, c)] = CellData(FormulaEvaluator.formatNumber(v))
                    }
                    if (r + 1 > maxR) maxR = r + 1
                    if (c + 1 > maxC) maxC = c + 1
                }
                0x0207 -> if (inWorksheet && recLen >= 3 && lastFormulaRow >= 0) { // STRING result of FORMULA
                    val cch = bb.getShort(bodyStart).toInt() and 0xFFFF
                    val str = readBiffString(data, bodyStart + 2, bodyStart + recLen, cch)
                    currentCells[SheetTab.packKey(lastFormulaRow, lastFormulaCol)] = CellData(str)
                    lastFormulaRow = -1
                }
            }
            pos = bodyStart + recLen
        }
        return WorkbookDocument(sheets = sheets.ifEmpty { listOf(SheetTab("Sheet1")) })
    }

    private fun parseBiff8Sst(data: ByteArray, start: Int, len: Int, out: MutableList<String>) {
        if (len < 8) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val unique = bb.getInt(start + 4).coerceIn(0, 100_000)
        var p = start + 8
        val end = start + len
        repeat(unique) {
            if (p + 3 > end) return
            val cch = bb.getShort(p).toInt() and 0xFFFF
            val flags = data[p + 2].toInt() and 0xFF
            p += 3
            val isUtf16 = (flags and 0x01) != 0
            val hasExtended = (flags and 0x04) != 0
            val hasRich = (flags and 0x08) != 0
            var richRuns = 0
            var extSize = 0
            if (hasRich && p + 2 <= end) {
                richRuns = bb.getShort(p).toInt() and 0xFFFF
                p += 2
            }
            if (hasExtended && p + 4 <= end) {
                extSize = bb.getInt(p)
                p += 4
            }
            val byteLen = if (isUtf16) cch * 2 else cch
            if (p + byteLen > end) return
            val str = if (isUtf16) String(data, p, byteLen, Charsets.UTF_16LE)
            else String(data, p, byteLen, Charsets.ISO_8859_1)
            out.add(str)
            p += byteLen + richRuns * 4 + extSize
        }
    }

    private fun readBiffString(data: ByteArray, pos: Int, limit: Int, cch: Int): String {
        if (pos >= limit) return ""
        val flags = data[pos].toInt() and 0xFF
        val isUtf16 = (flags and 0x01) != 0
        val start = pos + 1
        val byteLen = if (isUtf16) cch * 2 else cch
        if (start + byteLen > limit) return ""
        return if (isUtf16) String(data, start, byteLen, Charsets.UTF_16LE)
        else String(data, start, byteLen, Charsets.ISO_8859_1)
    }

    private fun decodeRk(rk: Int): Double {
        val isDiv100 = (rk and 0x01) != 0
        val isInt = (rk and 0x02) != 0
        val raw = if (isInt) {
            (rk shr 2).toDouble()
        } else {
            java.lang.Double.longBitsToDouble((rk.toLong() and -4L) shl 32)
        }
        return if (isDiv100) raw / 100.0 else raw
    }

    // --------------------------------------------------------- PDF & THUMBNAIL RENDERING
    fun renderPdf(wb: WorkbookDocument, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val pageW = 842 // A4 landscape points
        val pageH = 595
        val margin = 36f
        val pdf = PdfDocument()
        try {
            var pageNum = 1
            val totalSheets = wb.sheets.size.coerceAtLeast(1)
            wb.sheets.forEachIndexed { sIdx, sheet ->
                onProgress((sIdx + 1).toFloat() / totalSheets)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum++).create())
                drawSheetOnCanvas(page.canvas, sheet, margin, margin, pageW - margin * 2, pageH - margin * 2)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun renderThumbnail(wb: WorkbookDocument, widthPx: Int): Bitmap {
        val heightPx = (widthPx * 0.75f).toInt()
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val scale = widthPx / 640f
        canvas.save()
        canvas.scale(scale, scale)
        drawSheetOnCanvas(canvas, wb.current, 18f, 18f, 604f, 444f)
        canvas.restore()
        return bmp
    }

    private fun drawSheetOnCanvas(canvas: Canvas, sheet: SheetTab, left: Float, top: Float, width: Float, height: Float) {
        canvas.drawColor(Color.WHITE)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF171717.toInt()
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(sheet.name, left, top + 13f, titlePaint)

        val gridTop = top + 24f
        val maxR = (sheet.cells.keys.maxOfOrNull { SheetTab.unpackRow(it) } ?: 8).coerceIn(6, 22)
        val maxC = (sheet.cells.keys.maxOfOrNull { SheetTab.unpackCol(it) } ?: 5).coerceIn(4, 9)
        val rowH = ((height - 28f) / (maxR + 2)).coerceIn(16f, 24f)
        val colW = (width / (maxC + 1)).coerceAtLeast(48f)

        val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = 0xFFD8D8D8.toInt() }
        val headerBg = Paint().apply { style = Paint.Style.FILL; color = 0xFFF3F3F1.toInt() }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF171717.toInt(); textSize = 9.5f }

        // Column header row
        for (c in 0..maxC) {
            val x = left + c * colW
            val rect = RectF(x, gridTop, x + colW, gridTop + rowH)
            canvas.drawRect(rect, headerBg)
            canvas.drawRect(rect, borderPaint)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(SheetTab.colName(c), x + colW / 2f - 4f, gridTop + rowH * 0.68f, textPaint)
        }

        for (r in 0..maxR) {
            val y = gridTop + (r + 1) * rowH
            if (y + rowH > top + height) break
            for (c in 0..maxC) {
                val x = left + c * colW
                val rect = RectF(x, y, x + colW, y + rowH)
                canvas.drawRect(rect, borderPaint)
                val cd = sheet.cell(r, c)
                val v = sheet.displayValue(r, c)
                if (v.isNotEmpty()) {
                    textPaint.typeface = if (cd.format.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    var clipped = v
                    while (clipped.length > 1 && textPaint.measureText(clipped) > colW - 8f) {
                        clipped = clipped.dropLast(1)
                    }
                    canvas.drawText(clipped, x + 4f, y + rowH * 0.68f, textPaint)
                }
            }
        }
    }
}
