package app.slate

import app.slate.core.DocFormat
import app.slate.core.FormatSniffer
import app.slate.core.UndoStack
import app.slate.sheet.*
import app.slate.slides.*
import app.slate.word.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SlateEngineTests {

    @Test
    fun testWordDocxOdtRtfTxtRoundTripsAndFindReplace() {
        val original = WordDocument(
            title = "Spec",
            elements = listOf(
                ParagraphNode(
                    runs = listOf(TextRun("Project SLATE Architecture", SpanStyle(bold = true, fontSizeSp = 22))),
                    headingLevel = 1,
                    align = ParaAlign.CENTER,
                ),
                ParagraphNode(
                    runs = listOf(TextRun("Offline-first document workspace for Android.", SpanStyle(italic = true, underline = true, fontSizeSp = 14))),
                    listKind = ListKind.BULLET,
                ),
                TableNode(
                    rows = listOf(
                        listOf("Module", "Status"),
                        listOf("PDF", "Complete"),
                        listOf("Office", "Complete"),
                    )
                ),
            )
        )

        // DOCX round-trip
        val docxBytes = ByteArrayOutputStream().also { WordFormatCodecs.write(original, DocFormat.DOCX, it) }.toByteArray()
        assertEquals(DocFormat.DOCX, FormatSniffer.sniff { docxBytes.inputStream() })
        val fromDocx = WordFormatCodecs.read(docxBytes.inputStream(), DocFormat.DOCX, "Spec")
        assertTrue(fromDocx.elements.size >= 3)
        assertEquals("Project SLATE Architecture", (fromDocx.elements[0] as ParagraphNode).plainText)
        val tbl = fromDocx.elements.filterIsInstance<TableNode>().first()
        assertEquals("Complete", tbl.rows[1][1])

        // DOCX -> ODT round-trip
        val odtBytes = ByteArrayOutputStream().also { WordFormatCodecs.write(fromDocx, DocFormat.ODT, it) }.toByteArray()
        assertEquals(DocFormat.ODT, FormatSniffer.sniff { odtBytes.inputStream() })
        val fromOdt = WordFormatCodecs.read(odtBytes.inputStream(), DocFormat.ODT, "Spec")
        assertEquals("Project SLATE Architecture", (fromOdt.elements[0] as ParagraphNode).plainText)
        val odtTable = fromOdt.elements.filterIsInstance<TableNode>().first()
        assertEquals("Office", odtTable.rows[2][0])

        // ODT -> RTF round-trip
        val rtfBytes = ByteArrayOutputStream().also { WordFormatCodecs.write(fromOdt, DocFormat.RTF, it) }.toByteArray()
        assertEquals(DocFormat.RTF, FormatSniffer.sniff { rtfBytes.inputStream() })
        val fromRtf = WordFormatCodecs.read(rtfBytes.inputStream(), DocFormat.RTF, "Spec")
        assertTrue(fromRtf.elements.filterIsInstance<ParagraphNode>().any { it.plainText.contains("Project SLATE Architecture") })

        // RTF -> TXT round-trip
        val txtBytes = ByteArrayOutputStream().also { WordFormatCodecs.write(fromRtf, DocFormat.TXT, it) }.toByteArray()
        val fromTxt = WordFormatCodecs.read(txtBytes.inputStream(), DocFormat.TXT, "Spec")
        assertTrue(fromTxt.elements.filterIsInstance<ParagraphNode>().any { it.plainText.contains("Offline-first") })

        // Find & Replace
        val (replaced, count) = fromDocx.replaceAll("Complete", "Verified")
        assertEquals(2, count)
        val updatedTbl = replaced.elements.filterIsInstance<TableNode>().first()
        assertEquals("Verified", updatedTbl.rows[1][1])
    }

    @Test
    fun testSpreadsheetFormulasSortingAndXlsxOdsCsvConversions() {
        var sheet = SheetTab("Financials", rows = 10, cols = 5)
            .withCellRaw(0, 0, "Department")
            .withCellRaw(0, 1, "Cost")
            .withCellRaw(1, 0, "R&D")
            .withCellRaw(1, 1, "400")
            .withCellRaw(2, 0, "Marketing")
            .withCellRaw(2, 1, "150")
            .withCellRaw(3, 0, "Operations")
            .withCellRaw(3, 1, "250")
            .withCellRaw(4, 0, "Total")
            .withCellRaw(4, 1, "=SUM(B2:B4)")
            .withCellRaw(5, 1, "=AVERAGE(B2:B4)")
            .withCellRaw(6, 1, "=MAX(B2:B4)-MIN(B2:B4)")
            .withCellRaw(7, 1, "=IF(B5>500, 100, 0)")

        assertEquals("800", sheet.displayValue(4, 1))
        assertEquals("266.66666667", sheet.displayValue(5, 1))
        assertEquals("250", sheet.displayValue(6, 1))
        assertEquals("100", sheet.displayValue(7, 1))

        val wb = WorkbookDocument(listOf(sheet))

        // XLSX round-trip
        val xlsxBytes = ByteArrayOutputStream().also { SheetFormatCodecs.write(wb, DocFormat.XLSX, it) }.toByteArray()
        assertEquals(DocFormat.XLSX, FormatSniffer.sniff { xlsxBytes.inputStream() })
        val fromXlsx = SheetFormatCodecs.read(xlsxBytes.inputStream(), DocFormat.XLSX)
        assertEquals("Financials", fromXlsx.current.name)
        assertEquals("=SUM(B2:B4)", fromXlsx.current.cell(4, 1).raw)
        assertEquals("800", fromXlsx.current.displayValue(4, 1))

        // XLSX -> ODS round-trip
        val odsBytes = ByteArrayOutputStream().also { SheetFormatCodecs.write(fromXlsx, DocFormat.ODS, it) }.toByteArray()
        assertEquals(DocFormat.ODS, FormatSniffer.sniff { odsBytes.inputStream() })
        val fromOds = SheetFormatCodecs.read(odsBytes.inputStream(), DocFormat.ODS)
        assertEquals("800", fromOds.current.displayValue(4, 1))

        // ODS -> CSV round-trip
        val csvBytes = ByteArrayOutputStream().also { SheetFormatCodecs.write(fromOds, DocFormat.CSV, it) }.toByteArray()
        val fromCsv = SheetFormatCodecs.read(csvBytes.inputStream(), DocFormat.CSV)
        assertEquals("800", fromCsv.current.displayValue(4, 1))
        assertEquals("Marketing", fromCsv.current.displayValue(2, 0))

        // Sort test
        val onlyRows = SheetTab("SortTest")
            .withCellRaw(0, 0, "Name").withCellRaw(0, 1, "Score")
            .withCellRaw(1, 0, "Charlie").withCellRaw(1, 1, "30")
            .withCellRaw(2, 0, "Alice").withCellRaw(2, 1, "10")
            .withCellRaw(3, 0, "Bob").withCellRaw(3, 1, "20")
        val sorted = onlyRows.sortByColumn(0, ascending = true)
        assertEquals("Alice", sorted.displayValue(1, 0))
        assertEquals("Bob", sorted.displayValue(2, 0))
        assertEquals("Charlie", sorted.displayValue(3, 0))
    }

    @Test
    fun testPresentationsPptxAndOdpRoundTrips() {
        var pres = PresentationDocument(
            title = "Roadmap",
            slides = listOf(
                SlidePage(
                    items = listOf(
                        SlideItem(kind = ShapeKind.TEXT_BOX, text = "SLATE Q4 Launch", bold = true, fontSizeSp = 28),
                        SlideItem(kind = ShapeKind.ROUND_RECT, text = "Zero Ads • 100% Local", fontSizeSp = 18, fillArgb = 0xFFEDEDEB.toInt()),
                    )
                )
            )
        )
        pres = pres.addSlide(0).duplicateSlide(0)
        assertEquals(3, pres.slides.size)

        // PPTX round-trip
        val pptxBytes = ByteArrayOutputStream().also { SlidesFormatCodecs.write(pres, DocFormat.PPTX, it) }.toByteArray()
        assertEquals(DocFormat.PPTX, FormatSniffer.sniff { pptxBytes.inputStream() })
        val fromPptx = SlidesFormatCodecs.read(pptxBytes.inputStream(), DocFormat.PPTX, "Roadmap")
        assertEquals(3, fromPptx.slides.size)
        assertTrue(fromPptx.slides[0].summaryText.contains("SLATE Q4 Launch"))

        // PPTX -> ODP round-trip
        val odpBytes = ByteArrayOutputStream().also { SlidesFormatCodecs.write(fromPptx, DocFormat.ODP, it) }.toByteArray()
        assertEquals(DocFormat.ODP, FormatSniffer.sniff { odpBytes.inputStream() })
        val fromOdp = SlidesFormatCodecs.read(odpBytes.inputStream(), DocFormat.ODP, "Roadmap")
        assertEquals(3, fromOdp.slides.size)
        assertTrue(fromOdp.slides[0].summaryText.contains("SLATE Q4 Launch"))
    }

    @Test
    fun testLegacyOle2ContainersDocXlsPpt() {
        // Build a valid OLE2 Compound File containing a BIFF8 Workbook stream and verify XLS reader
        val biffStream = buildMinimalBiff8Stream()
        val oleXls = buildSingleStreamCfb("Workbook", biffStream)
        assertEquals(DocFormat.XLS, FormatSniffer.sniff { oleXls.inputStream() })
        val wb = SheetFormatCodecs.read(oleXls.inputStream(), DocFormat.XLS)
        assertEquals("LegacySheet", wb.current.name)
        assertEquals("Hello XLS", wb.current.displayValue(0, 0))
        assertEquals("42.5", wb.current.displayValue(1, 0))

        // Build a valid OLE2 Compound File containing a PowerPoint Document stream and verify PPT reader
        val pptStream = buildMinimalPptStream("Legacy PPT Slide Title", "Bullet point from binary PPT")
        val olePpt = buildSingleStreamCfb("PowerPoint Document", pptStream)
        assertEquals(DocFormat.PPT, FormatSniffer.sniff { olePpt.inputStream() })
        val pres = SlidesFormatCodecs.read(olePpt.inputStream(), DocFormat.PPT)
        assertTrue(pres.slides.first().summaryText.contains("Legacy PPT Slide Title"))

        // Build a valid OLE2 Compound File containing a WordDocument stream and verify DOC reader
        val wordStream = ByteArray(1024)
        val msg = "Legacy Word 97-2003 Document Content".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(msg, 0, wordStream, 512, msg.size)
        val bb = ByteBuffer.wrap(wordStream).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x0018, 512) // fcMin
        bb.putInt(0x004C, msg.size) // ccpText
        val oleDoc = buildSingleStreamCfb("WordDocument", wordStream)
        assertEquals(DocFormat.DOC, FormatSniffer.sniff { oleDoc.inputStream() })
        val doc = WordFormatCodecs.read(oleDoc.inputStream(), DocFormat.DOC)
        assertTrue(doc.elements.filterIsInstance<ParagraphNode>().any { it.plainText.contains("Legacy Word 97-2003") })
    }

    @Test
    fun testUndoStackBoundedOperations() {
        val stack = UndoStack("v1", limit = 5)
        stack.push("v2")
        stack.push("v3")
        assertTrue(stack.canUndo)
        assertEquals("v2", stack.undo())
        assertTrue(stack.canRedo)
        assertEquals("v3", stack.redo())
    }

    @Test
    fun testRealExternalSampleDocumentsOnDisk() {
        val dir = java.io.File("../sample_documents").takeIf { it.exists() }
            ?: java.io.File("sample_documents").takeIf { it.exists() }
            ?: return

        val docx = WordFormatCodecs.read(java.io.File(dir, "Sample_Document.docx").inputStream(), DocFormat.DOCX)
        assertTrue(docx.elements.filterIsInstance<ParagraphNode>().any { it.plainText.contains("SLATE Word Document") })

        val odt = WordFormatCodecs.read(java.io.File(dir, "Sample_Document.odt").inputStream(), DocFormat.ODT)
        assertTrue(odt.elements.filterIsInstance<ParagraphNode>().any { it.plainText.contains("SLATE OpenDocument Text") })

        val rtf = WordFormatCodecs.read(java.io.File(dir, "Sample_Document.rtf").inputStream(), DocFormat.RTF)
        assertTrue(rtf.elements.filterIsInstance<ParagraphNode>().any { it.plainText.contains("SLATE Rich Text") })

        val xlsx = SheetFormatCodecs.read(java.io.File(dir, "Sample_Spreadsheet.xlsx").inputStream(), DocFormat.XLSX)
        assertEquals("4000", xlsx.current.displayValue(3, 1))
        assertEquals("4600", xlsx.current.displayValue(3, 2))

        val xls = SheetFormatCodecs.read(java.io.File(dir, "Sample_Legacy.xls").inputStream(), DocFormat.XLS)
        assertEquals("LegacyBudget", xls.current.name)
        assertEquals("Hardware", xls.current.displayValue(1, 0))
        assertEquals("950.5", xls.current.displayValue(1, 1))

        val ods = SheetFormatCodecs.read(java.io.File(dir, "Sample_Spreadsheet.ods").inputStream(), DocFormat.ODS)
        assertEquals("Metrics", ods.current.name)
        assertEquals("1000", ods.current.displayValue(1, 1))

        val csv = SheetFormatCodecs.read(java.io.File(dir, "Sample_Data.csv").inputStream(), DocFormat.CSV)
        assertEquals("Alpha", csv.current.displayValue(1, 1))

        val pptx = SlidesFormatCodecs.read(java.io.File(dir, "Sample_Presentation.pptx").inputStream(), DocFormat.PPTX, "Sample")
        assertTrue(pptx.slides.first().summaryText.contains("SLATE Presentation"))

        val odp = SlidesFormatCodecs.read(java.io.File(dir, "Sample_Presentation.odp").inputStream(), DocFormat.ODP, "Sample")
        assertTrue(odp.slides.first().summaryText.contains("SLATE OpenDocument Presentation"))

        // Also test real complex UROP presentation if present in workspace root
        val uropFile = listOf(
            java.io.File("../../Machine_Learning_Dynamic_Portfolio_Optimization_UROP.pptx"),
            java.io.File("../Machine_Learning_Dynamic_Portfolio_Optimization_UROP.pptx"),
        ).firstOrNull { it.exists() }
        if (uropFile != null) {
            val urop = SlidesFormatCodecs.read(uropFile.inputStream(), DocFormat.PPTX, uropFile.name)
            assertEquals(10, urop.slides.size)
            assertEquals(960f, urop.widthPt, 1f)
            assertEquals(540f, urop.heightPt, 1f)

            // Verify Slide 1 title text box has transparent fill (noFill) and 32pt font from defRPr
            val slide1 = urop.slides[0]
            assertTrue(slide1.summaryText.contains("Machine Learning-Based Dynamic"))
            assertTrue(slide1.summaryText.contains("Portfolio Optimization"))
            val titleItem = slide1.items.first { it.fullText.contains("Machine Learning-Based Dynamic") }
            assertNull("Transparent text box must have null fillArgb", titleItem.fillArgb)
            assertEquals(32, titleItem.fontSizeSp)

            // Verify Slide 7 has a parsed Table (<p:graphicFrame><a:tbl>) with all cells intact
            val slide7 = urop.slides[6]
            val tableItem = slide7.items.firstOrNull { it.isTable }
            assertNotNull("Slide 7 must contain a parsed table", tableItem)
            assertEquals(4, tableItem!!.table!!.colFractions.size)
            assertTrue(tableItem.fullText.contains("Key Capability"))
            assertTrue(tableItem.fullText.contains("Financial No-Arbitrage"))

            // Verify all 10 slides have complete non-empty content
            urop.slides.forEachIndexed { idx, s ->
                assertTrue("Slide ${idx + 1} summaryText should not be blank", s.summaryText.isNotBlank())
            }
        }

        // Verify real complex DOCX files if present in Downloads
        val docx1011 = java.io.File("C:/Users/adity/Downloads/1011 (1) - Copy (1).docx")
        if (docx1011.exists()) {
            val parsed1011 = WordFormatCodecs.read(docx1011.inputStream(), DocFormat.DOCX, docx1011.name)
            val paras = parsed1011.elements.filterIsInstance<ParagraphNode>()
            val tables = parsed1011.elements.filterIsInstance<TableNode>()
            assertTrue("1011 docx must have paragraphs", paras.size > 100)
            assertEquals("1011 docx must have 1 rich table", 1, tables.size)
            val srmPara = paras.first { it.plainText.contains("SRM INSTITUTE") }
            assertEquals(36f, srmPara.leftIndentPt, 0.5f)
            assertTrue(srmPara.runs.first().style.bold)
            assertEquals(WordFontFamily.SERIF, srmPara.runs.first().style.fontFamily)
            val facPara = paras.first { it.plainText.contains("FACULTY OF ENGINEERING") }
            assertEquals(ParaAlign.CENTER, facPara.align)
            val tbl = tables.first()
            assertEquals(31, tbl.rows.size)
            assertTrue("Rich table cells must preserve paragraphs", tbl.richRows.isNotEmpty())
        }

        val docxTut7 = java.io.File("C:/Users/adity/Downloads/Tutorial_7_Aditya_RA2511026010831.docx")
        if (docxTut7.exists()) {
            val parsedTut7 = WordFormatCodecs.read(docxTut7.inputStream(), DocFormat.DOCX, docxTut7.name)
            val paras = parsedTut7.elements.filterIsInstance<ParagraphNode>()
            val images = parsedTut7.elements.filterIsInstance<ImageNode>()
            assertEquals("Tutorial 7 must extract all 5 embedded output screenshots", 5, images.size)
            assertTrue("All extracted images must have valid dimensions", images.all { it.widthPt > 200f && it.heightPt > 80f && it.bytes.isNotEmpty() })
            val pageBreaks = paras.count { it.isPageBreakOnly || it.pageBreakBefore }
            assertTrue("Tutorial 7 must preserve explicit page breaks", pageBreaks >= 4)
            val headingPara = paras.first { it.headingLevel == 2 }
            assertEquals("Heading2 run must have 14pt Times New Roman bold", 14f, headingPara.runs.first().style.fontSizePt, 0.1f)
            assertEquals(WordFontFamily.SERIF, headingPara.runs.first().style.fontFamily)
            assertEquals(0xFF000000.toInt(), headingPara.runs.first().style.colorArgb)
        }
    }

    private fun buildMinimalBiff8Stream(): ByteArray {
        val out = ByteArrayOutputStream()
        fun rec(id: Int, payload: ByteArray) {
            val hdr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            hdr.putShort(id.toShort())
            hdr.putShort(payload.size.toShort())
            out.write(hdr.array())
            out.write(payload)
        }
        // Workbook BOF (0x0809, type=0x0005)
        rec(0x0809, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(0x0600).putShort(0x0005).array())
        // BOUNDSHEET (0x0085) "LegacySheet"
        val nameBytes = "LegacySheet".toByteArray(Charsets.ISO_8859_1)
        val bs = ByteBuffer.allocate(8 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bs.putInt(0).putShort(0).put(nameBytes.size.toByte()).put(0.toByte()).put(nameBytes)
        rec(0x0085, bs.array())
        // EOF (0x000A)
        rec(0x000A, ByteArray(0))

        // Worksheet BOF (0x0809, type=0x0010)
        rec(0x0809, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(0x0600).putShort(0x0010).array())
        // LABEL (0x0204) at r=0, c=0 "Hello XLS"
        val lblStr = "Hello XLS".toByteArray(Charsets.ISO_8859_1)
        val lbl = ByteBuffer.allocate(9 + lblStr.size).order(ByteOrder.LITTLE_ENDIAN)
        lbl.putShort(0).putShort(0).putShort(0).putShort(lblStr.size.toShort()).put(0.toByte()).put(lblStr)
        rec(0x0204, lbl.array())
        // NUMBER (0x0203) at r=1, c=0 = 42.5
        val num = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        num.putShort(1).putShort(0).putShort(0).putDouble(42.5)
        rec(0x0203, num.array())
        // EOF
        rec(0x000A, ByteArray(0))
        return out.toByteArray()
    }

    private fun buildMinimalPptStream(title: String, body: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun textAtoms(s: String): ByteArray {
            val bytes = s.toByteArray(Charsets.UTF_16LE)
            val bb = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(0).putShort(0x0FA0.toShort()).putInt(bytes.size).put(bytes)
            return bb.array()
        }
        val payload = textAtoms(title) + textAtoms(body)
        val slideContainer = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        slideContainer.putShort(0x000F).putShort(0x03EE.toShort()).putInt(payload.size).put(payload)
        out.write(slideContainer.array())
        return out.toByteArray()
    }

    /** Builds a valid 512-byte-sector OLE2 Compound File with a single stream > 4096 bytes. */
    private fun buildSingleStreamCfb(streamName: String, content: ByteArray): ByteArray {
        val paddedContent = if (content.size < 4096) content.copyOf(4096) else content
        val numStreamSectors = (paddedContent.size + 511) / 512
        // Sector layout:
        // Sector 0: Directory
        // Sector 1: FAT
        // Sectors 2 .. 2+numStreamSectors-1: Stream data
        val totalSectors = 2 + numStreamSectors
        val fileBytes = ByteArray((1 + totalSectors) * 512)
        val bb = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        bb.putLong(0, -0x1ee54e5e1fee3030L)
        bb.putShort(0x18, 0x003E)
        bb.putShort(0x1A, 0x0003)
        bb.putShort(0x1C, (-2).toShort())
        bb.putShort(0x1E, 9) // 512-byte sectors
        bb.putShort(0x20, 6) // 64-byte mini sectors
        bb.putInt(0x2C, 1)   // 1 FAT sector
        bb.putInt(0x30, 0)   // Dir starts at sector 0
        bb.putInt(0x38, 4096) // Mini cutoff
        bb.putInt(0x3C, -2)  // No mini FAT
        bb.putInt(0x44, -2)  // No DIFAT chain
        for (i in 0 until 109) bb.putInt(0x4C + i * 4, -1)
        bb.putInt(0x4C, 1)   // First FAT sector = sector 1

        // Sector 0 (offset 512): Directory (Root Entry + Stream Entry)
        val dirOff = 512
        writeDirEntry(bb, dirOff, "Root Entry", 5, -2, 0, child = 1)
        writeDirEntry(bb, dirOff + 128, streamName, 2, 2, paddedContent.size, child = -1)

        // Sector 1 (offset 1024): FAT
        val fatOff = 1024
        for (i in 0 until 128) bb.putInt(fatOff + i * 4, -1)
        bb.putInt(fatOff + 0 * 4, -2) // Sector 0 (Dir) = ENDOFCHAIN
        bb.putInt(fatOff + 1 * 4, -3) // Sector 1 (FAT) = FATSECT
        for (i in 0 until numStreamSectors) {
            val sec = 2 + i
            val next = if (i == numStreamSectors - 1) -2 else sec + 1
            bb.putInt(fatOff + sec * 4, next)
        }

        // Sectors 2..: Stream data
        System.arraycopy(paddedContent, 0, fileBytes, (1 + 2) * 512, paddedContent.size)
        return fileBytes
    }

    private fun writeDirEntry(bb: ByteBuffer, offset: Int, name: String, type: Int, startSec: Int, size: Int, child: Int) {
        for (i in name.indices) bb.putChar(offset + i * 2, name[i])
        bb.putChar(offset + name.length * 2, 0.toChar())
        bb.putShort(offset + 64, ((name.length + 1) * 2).toShort())
        bb.put(offset + 66, type.toByte())
        bb.put(offset + 67, 1.toByte())
        bb.putInt(offset + 68, -1) // left
        bb.putInt(offset + 72, -1) // right
        bb.putInt(offset + 76, child)
        bb.putInt(offset + 116, startSec)
        bb.putInt(offset + 120, size)
    }
}
