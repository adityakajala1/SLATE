package app.slate.word

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WordFormatCodecs {

    private const val EMU_PER_PT = 12700f
    private const val TWIPS_PER_PT = 20f

    fun read(input: InputStream, format: DocFormat, title: String = "Document"): WordDocument = when (format) {
        DocFormat.DOCX -> readDocx(input, title)
        DocFormat.ODT -> readOdt(input, title)
        DocFormat.RTF -> readRtf(input, title)
        DocFormat.TXT -> readTxt(input, title)
        DocFormat.DOC -> readLegacyDoc(input, title)
        else -> throw DocumentException("Unsupported Word format: ${format.label}")
    }

    fun write(doc: WordDocument, format: DocFormat, out: OutputStream) = when (format) {
        DocFormat.DOCX -> writeDocx(doc, out)
        DocFormat.ODT -> writeOdt(doc, out)
        DocFormat.RTF -> writeRtf(doc, out)
        DocFormat.TXT -> writeTxt(doc, out)
        DocFormat.PDF -> renderPdf(doc, out)
        else -> throw DocumentException("Cannot write Word document to ${format.label}")
    }

    // --------------------------------------------------------- Lightweight DOM Tree for WordprocessingML
    private class XmlNode(
        val name: String,
        val attrs: Map<String, String>,
        val children: MutableList<XmlNode> = ArrayList(),
        val textBuilder: StringBuilder = StringBuilder(),
    ) {
        val text: String get() = textBuilder.toString()
        fun attr(key: String): String? = attrs[key] ?: attrs.entries.firstOrNull { it.key.substringAfter(':') == key }?.value
        fun child(localName: String): XmlNode? = children.firstOrNull { it.name == localName }
        fun children(localName: String): List<XmlNode> = children.filter { it.name == localName }
        fun findFirst(localName: String): XmlNode? {
            if (name == localName) return this
            for (c in children) {
                val found = c.findFirst(localName)
                if (found != null) return found
            }
            return null
        }
        fun findAll(localName: String, out: MutableList<XmlNode> = ArrayList()): List<XmlNode> {
            if (name == localName) out.add(this)
            for (c in children) c.findAll(localName, out)
            return out
        }
    }

    private fun parseXmlTree(bytes: ByteArray): XmlNode? = runCatching {
        val p = Xml.parser(bytes.inputStream())
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val map = LinkedHashMap<String, String>(p.attributeCount)
                    for (i in 0 until p.attributeCount) {
                        val aName = p.getAttributeName(i)
                        val aPrefix = p.getAttributePrefix(i)
                        val aVal = p.getAttributeValue(i)
                        map[aName] = aVal
                        if (!aPrefix.isNullOrEmpty()) map["$aPrefix:$aName"] = aVal
                    }
                    val node = XmlNode(p.name ?: "", map)
                    stack.lastOrNull()?.children?.add(node)
                    stack.addLast(node)
                    if (root == null) root = node
                }
                XmlPullParser.TEXT -> {
                    stack.lastOrNull()?.textBuilder?.append(p.text.orEmpty())
                }
                XmlPullParser.END_TAG -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        root
    }.getOrNull()

    // --------------------------------------------------------- DOCX Style & Numbering Structures
    private data class ResolvedStyleProps(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strikethrough: Boolean? = null,
        val superscript: Boolean? = null,
        val subscript: Boolean? = null,
        val allCaps: Boolean? = null,
        val fontSizePt: Float? = null,
        val colorArgb: Int? = null,
        val backgroundColorArgb: Int? = null,
        val fontFamily: WordFontFamily? = null,
        val align: ParaAlign? = null,
        val leftIndentPt: Float? = null,
        val rightIndentPt: Float? = null,
        val firstLineIndentPt: Float? = null,
        val spaceBeforePt: Float? = null,
        val spaceAfterPt: Float? = null,
        val lineSpacingMultiplier: Float? = null,
        val paragraphShadingArgb: Int? = null,
        val borders: ParagraphBorders = ParagraphBorders(),
        val headingLevel: Int? = null,
        val numId: Int? = null,
        val ilvl: Int? = null,
        // Table style properties
        val tableHeaderFillArgb: Int? = null,
        val tableHeaderTextColorArgb: Int? = null,
        val tableHeaderBold: Boolean? = null,
        val tableBandFillArgb: Int? = null,
        val tableBorderColorArgb: Int? = null,
    ) {
        fun mergeOver(base: ResolvedStyleProps): ResolvedStyleProps = ResolvedStyleProps(
            bold = bold ?: base.bold,
            italic = italic ?: base.italic,
            underline = underline ?: base.underline,
            strikethrough = strikethrough ?: base.strikethrough,
            superscript = superscript ?: base.superscript,
            subscript = subscript ?: base.subscript,
            allCaps = allCaps ?: base.allCaps,
            fontSizePt = fontSizePt ?: base.fontSizePt,
            colorArgb = colorArgb ?: base.colorArgb,
            backgroundColorArgb = backgroundColorArgb ?: base.backgroundColorArgb,
            fontFamily = fontFamily ?: base.fontFamily,
            align = align ?: base.align,
            leftIndentPt = leftIndentPt ?: base.leftIndentPt,
            rightIndentPt = rightIndentPt ?: base.rightIndentPt,
            firstLineIndentPt = firstLineIndentPt ?: base.firstLineIndentPt,
            spaceBeforePt = spaceBeforePt ?: base.spaceBeforePt,
            spaceAfterPt = spaceAfterPt ?: base.spaceAfterPt,
            lineSpacingMultiplier = lineSpacingMultiplier ?: base.lineSpacingMultiplier,
            paragraphShadingArgb = paragraphShadingArgb ?: base.paragraphShadingArgb,
            borders = if (borders.hasAny) borders else base.borders,
            headingLevel = headingLevel ?: base.headingLevel,
            numId = numId ?: base.numId,
            ilvl = ilvl ?: base.ilvl,
            tableHeaderFillArgb = tableHeaderFillArgb ?: base.tableHeaderFillArgb,
            tableHeaderTextColorArgb = tableHeaderTextColorArgb ?: base.tableHeaderTextColorArgb,
            tableHeaderBold = tableHeaderBold ?: base.tableHeaderBold,
            tableBandFillArgb = tableBandFillArgb ?: base.tableBandFillArgb,
            tableBorderColorArgb = tableBorderColorArgb ?: base.tableBorderColorArgb,
        )
    }

    private data class NumberingLevelDef(
        val ilvl: Int,
        val start: Int = 1,
        val numFmt: String = "decimal", // bullet, decimal, lowerLetter, upperLetter, lowerRoman, upperRoman
        val lvlText: String = "%1.",
        val leftIndentPt: Float = 24f,
    )

    // --------------------------------------------------------- FULL DOM DOCX READER
    private fun readDocx(input: InputStream, title: String): WordDocument {
        val entries = Zip.readAll(input)
        val docBytes = entries["word/document.xml"]
            ?: throw DocumentException("Invalid DOCX: missing word/document.xml")

        // 1. Parse relationships
        val relMap = parseDocxRels(entries["word/_rels/document.xml.rels"], "word")

        // 2. Parse theme colors (word/theme/theme1.xml)
        val themePath = relMap.values.firstOrNull { it.contains("theme") && entries.containsKey(it) }
            ?: entries.keys.firstOrNull { it.startsWith("word/theme/") && it.endsWith(".xml") }
        val themeColors = parseThemeColors(themePath?.let { entries[it] })

        // 3. Parse styles.xml (docDefaults + style definitions with basedOn inheritance)
        val (docDefaults, styleMap) = parseDocxStyles(entries["word/styles.xml"], themeColors)

        // 4. Parse numbering.xml (abstractNum + num definitions)
        val numberingDefs = parseDocxNumbering(entries["word/numbering.xml"])
        val listCounters = HashMap<Pair<Int, Int>, Int>()

        // 5. Parse word/document.xml DOM
        val docRoot = parseXmlTree(docBytes)
            ?: throw DocumentException("Unable to parse word/document.xml")

        val bgNode = docRoot.child("background")
        val pageBgArgb = bgNode?.attr("color")?.let { parseHexColor(it) } ?: 0xFFFFFFFF.toInt()

        val bodyNode = docRoot.child("body")
            ?: return WordDocument(title = title, sourceFormat = DocFormat.DOCX)

        // Page geometry from <w:sectPr>
        val sectPr = bodyNode.findFirst("sectPr")
        val pgSz = sectPr?.child("pgSz")
        val pageWidthPt = (pgSz?.attr("w")?.toFloatOrNull()?.div(TWIPS_PER_PT) ?: 595f).coerceIn(300f, 1200f)
        val pageHeightPt = (pgSz?.attr("h")?.toFloatOrNull()?.div(TWIPS_PER_PT) ?: 842f).coerceIn(400f, 1600f)
        val pgMar = sectPr?.child("pgMar")
        val marginLeftPt = (pgMar?.attr("left")?.toFloatOrNull()?.div(TWIPS_PER_PT) ?: 54f).coerceIn(18f, 120f)
        val marginRightPt = (pgMar?.attr("right")?.toFloatOrNull()?.div(TWIPS_PER_PT) ?: 54f).coerceIn(18f, 120f)
        val marginTopPt = (pgMar?.attr("top")?.toFloatOrNull()?.div(TWIPS_PER_PT) ?: 54f).coerceIn(18f, 120f)
        val marginBottomPt = (pgMar?.attr("bottom")?.toFloatOrNull()?.div(TWIPS_PER_PT) ?: 54f).coerceIn(18f, 120f)

        // Parse headers & footers if present
        val headerParas = ArrayList<ParagraphNode>()
        val footerParas = ArrayList<ParagraphNode>()
        val headerPaths = entries.keys.filter { it.matches(Regex("word/header\\d*\\.xml")) }.sorted()
        val footerPaths = entries.keys.filter { it.matches(Regex("word/footer\\d*\\.xml")) }.sorted()
        headerPaths.firstOrNull()?.let { hp ->
            parseXmlTree(entries[hp]!!)?.findAll("p")?.forEach { pNode ->
                val parsed = parseDomParagraph(pNode, docDefaults, styleMap, themeColors, numberingDefs, listCounters, relMap, entries)
                headerParas.addAll(parsed.filterIsInstance<ParagraphNode>().filter { it.plainText.isNotBlank() })
            }
        }
        footerPaths.firstOrNull()?.let { fp ->
            parseXmlTree(entries[fp]!!)?.findAll("p")?.forEach { pNode ->
                val parsed = parseDomParagraph(pNode, docDefaults, styleMap, themeColors, numberingDefs, listCounters, relMap, entries)
                footerParas.addAll(parsed.filterIsInstance<ParagraphNode>().filter { it.plainText.isNotBlank() })
            }
        }

        // Walk body elements in document order
        val elements = ArrayList<DocElement>()
        fun walkBlockContainer(container: XmlNode) {
            for (child in container.children) {
                when (child.name) {
                    "p" -> {
                        elements.addAll(
                            parseDomParagraph(
                                pNode = child,
                                docDefaults = docDefaults,
                                styleMap = styleMap,
                                themeColors = themeColors,
                                numberingDefs = numberingDefs,
                                listCounters = listCounters,
                                relMap = relMap,
                                entries = entries,
                            )
                        )
                    }
                    "tbl" -> {
                        elements.add(
                            parseDomTable(
                                tblNode = child,
                                docDefaults = docDefaults,
                                styleMap = styleMap,
                                themeColors = themeColors,
                                numberingDefs = numberingDefs,
                                listCounters = listCounters,
                                relMap = relMap,
                                entries = entries,
                            )
                        )
                    }
                    "sdt" -> {
                        child.child("sdtContent")?.let { walkBlockContainer(it) }
                    }
                    "AlternateContent" -> {
                        val branch = child.child("Choice") ?: child.child("Fallback")
                        branch?.let { walkBlockContainer(it) }
                    }
                }
            }
        }
        walkBlockContainer(bodyNode)

        if (elements.isEmpty()) elements.add(ParagraphNode())
        return WordDocument(
            title = title,
            elements = elements,
            sourceFormat = DocFormat.DOCX,
            headerParagraphs = headerParas,
            footerParagraphs = footerParas,
            pageBackgroundArgb = pageBgArgb,
            pageWidthPt = pageWidthPt,
            pageHeightPt = pageHeightPt,
            marginLeftPt = marginLeftPt,
            marginRightPt = marginRightPt,
            marginTopPt = marginTopPt,
            marginBottomPt = marginBottomPt,
        )
    }

    private fun parseDocxRels(relBytes: ByteArray?, baseDir: String): Map<String, String> {
        if (relBytes == null) return emptyMap()
        val root = parseXmlTree(relBytes) ?: return emptyMap()
        val map = HashMap<String, String>()
        for (rel in root.children("Relationship")) {
            val id = rel.attr("Id") ?: continue
            val target = rel.attr("Target") ?: continue
            val mode = rel.attr("TargetMode")
            if (mode.equals("External", ignoreCase = true)) {
                map[id] = target
            } else {
                val resolved = when {
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("$baseDir/") -> target
                    target.startsWith("../") -> {
                        val parent = baseDir.substringBeforeLast('/', "")
                        val clean = target.removePrefix("../")
                        if (parent.isEmpty()) clean else "$parent/$clean"
                    }
                    else -> "$baseDir/$target"
                }
                map[id] = resolved
            }
        }
        return map
    }

    private fun parseThemeColors(themeBytes: ByteArray?): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        map["dk1"] = 0xFF000000.toInt()
        map["lt1"] = 0xFFFFFFFF.toInt()
        map["dk2"] = 0xFF1E293B.toInt()
        map["lt2"] = 0xFFF1F5F9.toInt()
        map["accent1"] = 0xFF2E74B5.toInt()
        map["accent2"] = 0xFFED7D31.toInt()
        map["accent3"] = 0xFFA5A5A5.toInt()
        map["accent4"] = 0xFFFFC000.toInt()
        map["accent5"] = 0xFF5B9BD5.toInt()
        map["accent6"] = 0xFF70AD47.toInt()
        map["hlink"] = 0xFF0563C1.toInt()
        map["folHlink"] = 0xFF954F72.toInt()

        if (themeBytes != null) {
            val root = parseXmlTree(themeBytes)
            val clrScheme = root?.findFirst("clrScheme")
            clrScheme?.children?.forEach { c ->
                val srgb = c.child("srgbClr")?.attr("val")
                val sys = c.child("sysClr")?.attr("lastClr") ?: c.child("sysClr")?.attr("val")
                val hex = srgb ?: sys
                parseHexColor(hex)?.let { map[c.name] = it }
            }
        }
        map["dark1"] = map["dk1"] ?: 0xFF000000.toInt()
        map["light1"] = map["lt1"] ?: 0xFFFFFFFF.toInt()
        map["dark2"] = map["dk2"] ?: 0xFF1E293B.toInt()
        map["light2"] = map["lt2"] ?: 0xFFF1F5F9.toInt()
        map["hyperlink"] = map["hlink"] ?: 0xFF0563C1.toInt()
        return map
    }

    private fun parseHexColor(hex: String?): Int? {
        if (hex == null) return null
        val clean = hex.trim().removePrefix("#")
        if (clean.equals("auto", ignoreCase = true) || clean.equals("none", ignoreCase = true)) return null
        if (clean.length == 6) {
            val rgb = clean.toIntOrNull(16) ?: return null
            return (0xFF000000.toInt() or rgb)
        }
        if (clean.length == 8) {
            return clean.toLongOrNull(16)?.toInt()
        }
        return null
    }

    private fun applyTintOrShade(argb: Int, tintHex: String?, shadeHex: String?): Int {
        var r = (argb shr 16) and 0xFF
        var g = (argb shr 8) and 0xFF
        var b = argb and 0xFF
        val tint = tintHex?.toIntOrNull(16)
        val shade = shadeHex?.toIntOrNull(16)
        if (tint != null && tint in 0..255) {
            val f = tint / 255f
            r = (r * f + 255f * (1f - f)).roundToInt().coerceIn(0, 255)
            g = (g * f + 255f * (1f - f)).roundToInt().coerceIn(0, 255)
            b = (b * f + 255f * (1f - f)).roundToInt().coerceIn(0, 255)
        } else if (shade != null && shade in 0..255) {
            val f = shade / 255f
            r = (r * f).roundToInt().coerceIn(0, 255)
            g = (g * f).roundToInt().coerceIn(0, 255)
            b = (b * f).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun resolveWordColor(node: XmlNode?, valAttr: String, themeAttr: String, tintAttr: String, shadeAttr: String, themeColors: Map<String, Int>): Int? {
        if (node == null) return null
        val direct = parseHexColor(node.attr(valAttr))
        if (direct != null) return direct
        val themeKey = node.attr(themeAttr) ?: return null
        val base = themeColors[themeKey] ?: return null
        return applyTintOrShade(base, node.attr(tintAttr), node.attr(shadeAttr))
    }

    private fun parseHighlightColor(name: String?): Int? = when (name?.lowercase()) {
        "yellow" -> 0xFFFFF59D.toInt()
        "green" -> 0xFFA7F3D0.toInt()
        "cyan" -> 0xFFA5F3FC.toInt()
        "magenta" -> 0xFFFBCFE8.toInt()
        "blue" -> 0xFFBFDBFE.toInt()
        "red" -> 0xFFFECACA.toInt()
        "lightgray", "lightgrey" -> 0xFFE2E8F0.toInt()
        "darkgray", "darkgrey" -> 0xFFCBD5E1.toInt()
        else -> null
    }

    private fun resolveFontFamily(rFonts: XmlNode?): WordFontFamily? {
        if (rFonts == null) return null
        val name = (rFonts.attr("ascii") ?: rFonts.attr("hAnsi") ?: rFonts.attr("cs") ?: rFonts.attr("asciiTheme") ?: "").lowercase()
        if (name.isEmpty()) return null
        return when {
            name.contains("consolas") || name.contains("courier") || name.contains("mono") || name.contains("menlo") || name.contains("lucida console") ->
                WordFontFamily.MONOSPACE
            name.contains("times") || name.contains("georgia") || name.contains("cambria") || name.contains("garamond") || name.contains("palatino") || name.contains("serif") && !name.contains("sans") ->
                WordFontFamily.SERIF
            else -> WordFontFamily.SANS_SERIF
        }
    }

    private fun extractRPr(rPr: XmlNode?, themeColors: Map<String, Int>): ResolvedStyleProps {
        if (rPr == null) return ResolvedStyleProps()
        val bNode = rPr.child("b") ?: rPr.child("bCs")
        val bold = bNode?.let { val v = it.attr("val"); v == null || (v != "0" && v != "false") }
        val iNode = rPr.child("i") ?: rPr.child("iCs")
        val italic = iNode?.let { val v = it.attr("val"); v == null || (v != "0" && v != "false") }
        val uNode = rPr.child("u")
        val underline = uNode?.let { (it.attr("val") ?: "single") != "none" }
        val strikeNode = rPr.child("strike") ?: rPr.child("dstrike")
        val strike = strikeNode?.let { val v = it.attr("val"); v == null || (v != "0" && v != "false") }
        val vertAlign = rPr.child("vertAlign")?.attr("val")
        val superscript = if (vertAlign == "superscript") true else null
        val subscript = if (vertAlign == "subscript") true else null
        val capsNode = rPr.child("caps") ?: rPr.child("smallCaps")
        val allCaps = capsNode?.let { val v = it.attr("val"); v == null || (v != "0" && v != "false") }
        val szHalfPt = (rPr.child("sz") ?: rPr.child("szCs"))?.attr("val")?.toFloatOrNull()
        val fontSizePt = szHalfPt?.let { (it / 2f).coerceIn(6f, 72f) }
        val colorArgb = resolveWordColor(rPr.child("color"), "val", "themeColor", "themeTint", "themeShade", themeColors)
        val highlightArgb = parseHighlightColor(rPr.child("highlight")?.attr("val"))
        val shdArgb = resolveWordColor(rPr.child("shd"), "fill", "themeFill", "themeFillTint", "themeFillShade", themeColors)
        val fontFamily = resolveFontFamily(rPr.child("rFonts"))

        return ResolvedStyleProps(
            bold = bold,
            italic = italic,
            underline = underline,
            strikethrough = strike,
            superscript = superscript,
            subscript = subscript,
            allCaps = allCaps,
            fontSizePt = fontSizePt,
            colorArgb = colorArgb,
            backgroundColorArgb = highlightArgb ?: shdArgb,
            fontFamily = fontFamily,
        )
    }

    private fun extractPPr(pPr: XmlNode?, themeColors: Map<String, Int>): ResolvedStyleProps {
        if (pPr == null) return ResolvedStyleProps()
        val jc = pPr.child("jc")?.attr("val")?.lowercase()
        val align = when (jc) {
            "center" -> ParaAlign.CENTER
            "right", "end" -> ParaAlign.RIGHT
            "both", "distribute", "justify" -> ParaAlign.JUSTIFY
            "left", "start" -> ParaAlign.LEFT
            else -> null
        }

        val ind = pPr.child("ind")
        val leftTwips = (ind?.attr("left") ?: ind?.attr("start"))?.toFloatOrNull()
        val rightTwips = (ind?.attr("right") ?: ind?.attr("end"))?.toFloatOrNull()
        val firstLineTwips = ind?.attr("firstLine")?.toFloatOrNull()
        val hangingTwips = ind?.attr("hanging")?.toFloatOrNull()

        val leftIndentPt = leftTwips?.div(TWIPS_PER_PT)?.coerceIn(0f, 240f)
        val rightIndentPt = rightTwips?.div(TWIPS_PER_PT)?.coerceIn(0f, 240f)
        val firstLineIndentPt = when {
            firstLineTwips != null -> (firstLineTwips / TWIPS_PER_PT).coerceIn(0f, 120f)
            hangingTwips != null -> -(hangingTwips / TWIPS_PER_PT).coerceIn(0f, 120f)
            else -> null
        }

        val spacing = pPr.child("spacing")
        val beforeTwips = spacing?.attr("before")?.toFloatOrNull()
        val afterTwips = spacing?.attr("after")?.toFloatOrNull()
        val lineVal = spacing?.attr("line")?.toFloatOrNull()
        val lineRule = spacing?.attr("lineRule") ?: "auto"
        val spaceBeforePt = beforeTwips?.div(TWIPS_PER_PT)?.coerceIn(0f, 72f)
        val spaceAfterPt = afterTwips?.div(TWIPS_PER_PT)?.coerceIn(0f, 72f)
        val lineMult = if (lineVal != null && lineVal > 0f) {
            if (lineRule == "auto") (lineVal / 240f).coerceIn(0.95f, 2.5f)
            else null
        } else null

        val shdArgb = resolveWordColor(pPr.child("shd"), "fill", "themeFill", "themeFillTint", "themeFillShade", themeColors)

        val pBdr = pPr.child("pBdr")
        fun parseBdr(side: String): Pair<Int?, Float> {
            val b = pBdr?.child(side) ?: return null to 0f
            val bVal = b.attr("val") ?: "none"
            if (bVal == "none" || bVal == "nil") return null to 0f
            val szEighths = b.attr("sz")?.toFloatOrNull() ?: 4f
            val widthPt = (szEighths / 8f).coerceIn(0.5f, 6f)
            val col = resolveWordColor(b, "color", "themeColor", "themeTint", "themeShade", themeColors)
                ?: 0xFF94A3B8.toInt()
            return col to widthPt
        }
        val (topCol, topW) = parseBdr("top")
        val (botCol, botW) = parseBdr("bottom")
        val (leftCol, leftW) = parseBdr("left")
        val (rightCol, rightW) = parseBdr("right")

        val outlineLvl = pPr.child("outlineLvl")?.attr("val")?.toIntOrNull()?.let { (it + 1).coerceIn(1, 6) }
        val numPr = pPr.child("numPr")
        val numId = numPr?.child("numId")?.attr("val")?.toIntOrNull()
        val ilvl = numPr?.child("ilvl")?.attr("val")?.toIntOrNull()

        val pRPr = extractRPr(pPr.child("rPr"), themeColors)

        return pRPr.copy(
            align = align,
            leftIndentPt = leftIndentPt,
            rightIndentPt = rightIndentPt,
            firstLineIndentPt = firstLineIndentPt,
            spaceBeforePt = spaceBeforePt,
            spaceAfterPt = spaceAfterPt,
            lineSpacingMultiplier = lineMult,
            paragraphShadingArgb = shdArgb,
            borders = ParagraphBorders(topCol, topW, botCol, botW, leftCol, leftW, rightCol, rightW),
            headingLevel = outlineLvl,
            numId = numId,
            ilvl = ilvl,
        )
    }

    private fun parseDocxStyles(
        stylesBytes: ByteArray?,
        themeColors: Map<String, Int>,
    ): Pair<ResolvedStyleProps, Map<String, ResolvedStyleProps>> {
        var defaults = ResolvedStyleProps(
            fontSizePt = 11f,
            colorArgb = 0xFF0F172A.toInt(),
            spaceBeforePt = 0f,
            spaceAfterPt = 6f,
            lineSpacingMultiplier = 1.18f,
        )
        if (stylesBytes == null) return defaults to emptyMap()
        val root = parseXmlTree(stylesBytes) ?: return defaults to emptyMap()

        // 1. docDefaults
        val docDefNode = root.child("docDefaults")
        if (docDefNode != null) {
            val rDef = extractRPr(docDefNode.child("rPrDefault")?.child("rPr"), themeColors)
            val pDef = extractPPr(docDefNode.child("pPrDefault")?.child("pPr"), themeColors)
            defaults = pDef.mergeOver(rDef).mergeOver(defaults)
        }

        // 2. Raw styles
        data class RawStyle(
            val id: String,
            val name: String,
            val basedOn: String?,
            val isDefault: Boolean,
            val type: String,
            val props: ResolvedStyleProps,
        )
        val rawMap = LinkedHashMap<String, RawStyle>()
        for (sNode in root.children("style")) {
            val styleId = sNode.attr("styleId") ?: continue
            val styleType = sNode.attr("type") ?: "paragraph"
            val isDef = sNode.attr("default") == "1" || sNode.attr("default") == "true"
            val nameVal = sNode.child("name")?.attr("val").orEmpty()
            val basedOn = sNode.child("basedOn")?.attr("val")

            val rProps = extractRPr(sNode.child("rPr"), themeColors)
            val pProps = extractPPr(sNode.child("pPr"), themeColors)

            val inferredHeading = when {
                styleId.equals("Title", true) || nameVal.equals("Title", true) -> 1
                styleId.equals("Subtitle", true) || nameVal.equals("Subtitle", true) -> 2
                styleId.startsWith("Heading", true) -> styleId.removePrefix("Heading").removePrefix("heading").trim().toIntOrNull()?.coerceIn(1, 6)
                nameVal.startsWith("heading ", true) -> nameVal.removePrefix("heading ").removePrefix("Heading ").trim().toIntOrNull()?.coerceIn(1, 6)
                else -> null
            }

            // Table style properties (<w:tblPr> and <w:tblStylePr w:type="firstRow|band1Horz">)
            var hdrFill: Int? = null
            var hdrTextCol: Int? = null
            var hdrBold: Boolean? = null
            var bandFill: Int? = null
            val tblBorders = sNode.child("tblPr")?.child("tblBorders")
            val tblBorderCol = resolveWordColor(tblBorders?.child("insideH") ?: tblBorders?.child("top"), "color", "themeColor", "themeTint", "themeShade", themeColors)

            for (tsPr in sNode.children("tblStylePr")) {
                when (tsPr.attr("type")) {
                    "firstRow" -> {
                        hdrFill = resolveWordColor(tsPr.child("tcPr")?.child("shd"), "fill", "themeFill", "themeFillTint", "themeFillShade", themeColors)
                        val trRPr = extractRPr(tsPr.child("rPr"), themeColors)
                        hdrTextCol = trRPr.colorArgb
                        hdrBold = trRPr.bold
                    }
                    "band1Horz" -> {
                        bandFill = resolveWordColor(tsPr.child("tcPr")?.child("shd"), "fill", "themeFill", "themeFillTint", "themeFillShade", themeColors)
                    }
                }
            }

            val combined = pProps.mergeOver(rProps).copy(
                headingLevel = pProps.headingLevel ?: inferredHeading,
                tableHeaderFillArgb = hdrFill,
                tableHeaderTextColorArgb = hdrTextCol,
                tableHeaderBold = hdrBold,
                tableBandFillArgb = bandFill,
                tableBorderColorArgb = tblBorderCol,
            )
            rawMap[styleId] = RawStyle(styleId, nameVal, basedOn, isDef, styleType, combined)
            if (isDef && styleType == "paragraph") {
                defaults = combined.mergeOver(defaults)
            }
        }

        // Resolve basedOn inheritance chains
        val resolvedMap = HashMap<String, ResolvedStyleProps>()
        fun resolve(id: String, visited: MutableSet<String>): ResolvedStyleProps {
            resolvedMap[id]?.let { return it }
            val raw = rawMap[id] ?: return ResolvedStyleProps()
            if (!visited.add(id)) return raw.props
            val parentProps = raw.basedOn?.let { resolve(it, visited) } ?: ResolvedStyleProps()
            val merged = raw.props.mergeOver(parentProps)
            resolvedMap[id] = merged
            return merged
        }
        for (id in rawMap.keys) {
            resolve(id, HashSet())
        }
        return defaults to resolvedMap
    }

    private fun parseDocxNumbering(numBytes: ByteArray?): Map<Pair<Int, Int>, NumberingLevelDef> {
        if (numBytes == null) return emptyMap()
        val root = parseXmlTree(numBytes) ?: return emptyMap()
        val abstractMap = HashMap<Pair<Int, Int>, NumberingLevelDef>()

        for (absNode in root.children("abstractNum")) {
            val absId = absNode.attr("abstractNumId")?.toIntOrNull() ?: continue
            for (lvlNode in absNode.children("lvl")) {
                val ilvl = lvlNode.attr("ilvl")?.toIntOrNull() ?: 0
                val start = lvlNode.child("start")?.attr("val")?.toIntOrNull() ?: 1
                val numFmt = lvlNode.child("numFmt")?.attr("val") ?: "decimal"
                val lvlText = lvlNode.child("lvlText")?.attr("val") ?: if (numFmt == "bullet") "•" else "%${ilvl + 1}."
                val leftTwips = lvlNode.child("pPr")?.child("ind")?.attr("left")?.toFloatOrNull() ?: ((ilvl + 1) * 720f)
                abstractMap[absId to ilvl] = NumberingLevelDef(
                    ilvl = ilvl,
                    start = start,
                    numFmt = numFmt,
                    lvlText = lvlText,
                    leftIndentPt = (leftTwips / TWIPS_PER_PT).coerceIn(12f, 180f),
                )
            }
        }

        val numMap = HashMap<Pair<Int, Int>, NumberingLevelDef>()
        for (numNode in root.children("num")) {
            val numId = numNode.attr("numId")?.toIntOrNull() ?: continue
            val absId = numNode.child("abstractNumId")?.attr("val")?.toIntOrNull() ?: continue
            for (ilvl in 0..8) {
                val def = abstractMap[absId to ilvl]
                if (def != null) numMap[numId to ilvl] = def
            }
        }
        return numMap
    }

    private fun formatNumberValue(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> {
            val idx = ((n - 1).coerceAtLeast(0)) % 26
            ('a' + idx).toString()
        }
        "upperLetter" -> {
            val idx = ((n - 1).coerceAtLeast(0)) % 26
            ('A' + idx).toString()
        }
        "lowerRoman" -> toRoman(n).lowercase()
        "upperRoman" -> toRoman(n)
        else -> n.toString()
    }

    private fun toRoman(num: Int): String {
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val literals = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = num.coerceIn(1, 3999)
        val sb = StringBuilder()
        for (i in values.indices) {
            while (n >= values[i]) {
                n -= values[i]
                sb.append(literals[i])
            }
        }
        return sb.toString()
    }

    private fun parseDomParagraph(
        pNode: XmlNode,
        docDefaults: ResolvedStyleProps,
        styleMap: Map<String, ResolvedStyleProps>,
        themeColors: Map<String, Int>,
        numberingDefs: Map<Pair<Int, Int>, NumberingLevelDef>,
        listCounters: MutableMap<Pair<Int, Int>, Int>,
        relMap: Map<String, String>,
        entries: Map<String, ByteArray>,
    ): List<DocElement> {
        val pPr = pNode.child("pPr")
        val pStyleId = pPr?.child("pStyle")?.attr("val")
        val pStyleProps = pStyleId?.let { styleMap[it] } ?: ResolvedStyleProps()
        val directPProps = extractPPr(pPr, themeColors)
        val effectivePara = directPProps.mergeOver(pStyleProps).mergeOver(docDefaults)

        val heading = effectivePara.headingLevel ?: 0
        val align = effectivePara.align ?: ParaAlign.LEFT

        // Resolve numbering / bullets from <w:numPr> or paragraph style
        val numId = effectivePara.numId ?: 0
        val ilvl = (effectivePara.ilvl ?: 0).coerceIn(0, 8)
        var listKind = ListKind.NONE
        var listPrefix = ""
        var listIndentPt = effectivePara.leftIndentPt ?: 0f

        if (numId > 0) {
            val def = numberingDefs[numId to ilvl]
            if (def != null) {
                if (def.numFmt == "bullet") {
                    listKind = ListKind.BULLET
                    val cleanBullet = def.lvlText.firstOrNull()?.let { ch ->
                        // Map Private Use Area Wingdings/Symbol bullet characters to clean Unicode bullets
                        if (ch.code in 0xF000..0xF0FF || ch == '' || ch == '') "•" else def.lvlText
                    } ?: "•"
                    listPrefix = "$cleanBullet  "
                } else {
                    listKind = ListKind.NUMBERED
                    // Reset deeper levels when a parent level advances
                    for (deeper in (ilvl + 1)..8) listCounters.remove(numId to deeper)
                    val nextVal = (listCounters[numId to ilvl] ?: (def.start - 1)) + 1
                    listCounters[numId to ilvl] = nextVal
                    val formatted = formatNumberValue(nextVal, def.numFmt)
                    val replaced = def.lvlText.replace("%${ilvl + 1}", formatted)
                        .replace(Regex("%\\d"), formatted)
                    listPrefix = if (replaced.isNotBlank()) "$replaced  " else "$formatted.  "
                }
                if (effectivePara.leftIndentPt == null) {
                    listIndentPt = def.leftIndentPt
                }
            } else {
                listKind = ListKind.BULLET
                listPrefix = "•  "
            }
        } else if (pStyleId?.contains("List", ignoreCase = true) == true) {
            listKind = ListKind.BULLET
            listPrefix = "•  "
        }

        val runs = ArrayList<TextRun>()
        val images = ArrayList<ImageNode>()
        val textBoxElements = ArrayList<DocElement>()
        var hasPageBreakBefore = pPr?.child("pageBreakBefore") != null
        var hasPageBreakInRuns = false

        fun walkInline(container: XmlNode, inheritedRunStyle: ResolvedStyleProps) {
            for (child in container.children) {
                when (child.name) {
                    "r" -> {
                        val rPr = child.child("rPr")
                        val rStyleId = rPr?.child("rStyle")?.attr("val")
                        val charStyle = rStyleId?.let { styleMap[it] } ?: ResolvedStyleProps()
                        val directRun = extractRPr(rPr, themeColors)
                        val mergedRun = directRun.mergeOver(charStyle).mergeOver(inheritedRunStyle)

                        val defaultHeadingSize = when (heading) {
                            1 -> 20f
                            2 -> 15f
                            3 -> 13f
                            else -> 11.5f
                        }
                        val resolvedFontPt = mergedRun.fontSizePt ?: defaultHeadingSize
                        val isBold = (mergedRun.bold ?: (heading in 1..3))
                        val spanStyle = SpanStyle(
                            bold = isBold,
                            italic = mergedRun.italic ?: false,
                            underline = mergedRun.underline ?: false,
                            strikethrough = mergedRun.strikethrough ?: false,
                            superscript = mergedRun.superscript ?: false,
                            subscript = mergedRun.subscript ?: false,
                            allCaps = mergedRun.allCaps ?: false,
                            fontSizeSp = resolvedFontPt.roundToInt().coerceIn(7, 64),
                            fontSizePt = resolvedFontPt.coerceIn(7f, 64f),
                            colorArgb = mergedRun.colorArgb,
                            backgroundColorArgb = mergedRun.backgroundColorArgb,
                            fontFamily = mergedRun.fontFamily ?: WordFontFamily.SANS_SERIF,
                        )

                        val sb = StringBuilder()
                        for (rChild in child.children) {
                            when (rChild.name) {
                                "t", "delText", "instrText" -> {
                                    if (rChild.name == "t") {
                                        val txt = if (spanStyle.allCaps) rChild.text.uppercase() else rChild.text
                                        sb.append(txt)
                                    }
                                }
                                "tab" -> sb.append("    ")
                                "br", "cr" -> {
                                    if (rChild.attr("type") == "page") {
                                        hasPageBreakInRuns = true
                                    } else {
                                        sb.append("\n")
                                    }
                                }
                                "noBreakHyphen" -> sb.append("-")
                                "softHyphen" -> Unit
                                "sym" -> {
                                    val chHex = rChild.attr("char")
                                    val code = chHex?.toIntOrNull(16)
                                    if (code != null && code !in 0xF000..0xF0FF) {
                                        sb.append(code.toChar())
                                    } else {
                                        sb.append("•")
                                    }
                                }
                                "drawing", "pict", "object" -> {
                                    extractDrawingImagesAndTextBoxes(
                                        drawingNode = rChild,
                                        paraAlign = align,
                                        docDefaults = docDefaults,
                                        styleMap = styleMap,
                                        themeColors = themeColors,
                                        numberingDefs = numberingDefs,
                                        listCounters = listCounters,
                                        relMap = relMap,
                                        entries = entries,
                                        outImages = images,
                                        outTextBoxes = textBoxElements,
                                    )
                                }
                            }
                        }
                        if (sb.isNotEmpty()) {
                            runs.add(TextRun(sb.toString(), spanStyle))
                        }
                    }
                    "hyperlink" -> {
                        val hStyle = styleMap["Hyperlink"] ?: ResolvedStyleProps(
                            colorArgb = 0xFF0563C1.toInt(),
                            underline = true,
                        )
                        walkInline(child, hStyle.mergeOver(inheritedRunStyle))
                    }
                    "fldSimple", "ins", "smartTag", "customXml" -> {
                        walkInline(child, inheritedRunStyle)
                    }
                    "sdt" -> {
                        child.child("sdtContent")?.let { walkInline(it, inheritedRunStyle) }
                    }
                    "oMathPara", "oMath" -> {
                        val mathText = collectMathText(child)
                        if (mathText.isNotBlank()) {
                            val mathPt = (inheritedRunStyle.fontSizePt ?: 12f).coerceIn(9f, 36f)
                            runs.add(
                                TextRun(
                                    text = mathText,
                                    style = SpanStyle(
                                        italic = true,
                                        fontSizeSp = mathPt.roundToInt(),
                                        fontSizePt = mathPt,
                                        colorArgb = inheritedRunStyle.colorArgb ?: 0xFF0F172A.toInt(),
                                        fontFamily = WordFontFamily.SERIF,
                                    )
                                )
                            )
                        }
                    }
                    "AlternateContent" -> {
                        val branch = child.child("Choice") ?: child.child("Fallback")
                        branch?.let { walkInline(it, inheritedRunStyle) }
                    }
                }
            }
        }

        walkInline(pNode, effectivePara)

        val out = ArrayList<DocElement>()
        val isPurePageBreak = hasPageBreakInRuns && runs.all { it.text.isBlank() } && images.isEmpty() && textBoxElements.isEmpty()

        if (isPurePageBreak) {
            out.add(
                ParagraphNode(
                    runs = emptyList(),
                    pageBreakBefore = true,
                    isPageBreakOnly = true,
                    spaceBeforePt = 0f,
                    spaceAfterPt = 0f,
                )
            )
            return out
        }

        if (runs.isNotEmpty() || (images.isEmpty() && textBoxElements.isEmpty())) {
            val defaultPt = (effectivePara.fontSizePt ?: 11.5f).coerceIn(7f, 64f)
            val effectiveRuns = if (runs.isEmpty()) {
                listOf(
                    TextRun(
                        "",
                        SpanStyle(
                            bold = effectivePara.bold ?: (heading > 0),
                            fontSizeSp = defaultPt.roundToInt(),
                            fontSizePt = defaultPt,
                            colorArgb = effectivePara.colorArgb,
                            fontFamily = effectivePara.fontFamily ?: WordFontFamily.SANS_SERIF,
                        )
                    )
                )
            } else runs

            out.add(
                ParagraphNode(
                    runs = effectiveRuns,
                    headingLevel = heading,
                    align = align,
                    listKind = listKind,
                    listLevel = ilvl,
                    listPrefix = listPrefix,
                    leftIndentPt = listIndentPt,
                    rightIndentPt = effectivePara.rightIndentPt ?: 0f,
                    firstLineIndentPt = effectivePara.firstLineIndentPt ?: 0f,
                    spaceBeforePt = effectivePara.spaceBeforePt ?: (if (heading > 0) 8f else 0f),
                    spaceAfterPt = effectivePara.spaceAfterPt ?: 6f,
                    lineSpacingMultiplier = effectivePara.lineSpacingMultiplier ?: 1.18f,
                    backgroundColorArgb = effectivePara.paragraphShadingArgb,
                    borders = effectivePara.borders,
                    pageBreakBefore = hasPageBreakBefore,
                )
            )
        }
        out.addAll(images)
        out.addAll(textBoxElements)
        if (hasPageBreakInRuns && !isPurePageBreak) {
            out.add(
                ParagraphNode(
                    runs = emptyList(),
                    pageBreakBefore = true,
                    isPageBreakOnly = true,
                    spaceBeforePt = 0f,
                    spaceAfterPt = 0f,
                )
            )
        }
        return out
    }

    private fun extractDrawingImagesAndTextBoxes(
        drawingNode: XmlNode,
        paraAlign: ParaAlign,
        docDefaults: ResolvedStyleProps,
        styleMap: Map<String, ResolvedStyleProps>,
        themeColors: Map<String, Int>,
        numberingDefs: Map<Pair<Int, Int>, NumberingLevelDef>,
        listCounters: MutableMap<Pair<Int, Int>, Int>,
        relMap: Map<String, String>,
        entries: Map<String, ByteArray>,
        outImages: MutableList<ImageNode>,
        outTextBoxes: MutableList<DocElement>,
    ) {
        // Handle <mc:AlternateContent> inside drawing without duplicating Choice & Fallback
        fun walkDrawing(node: XmlNode) {
            if (node.name == "AlternateContent") {
                val branch = node.child("Choice") ?: node.child("Fallback")
                branch?.let { walkDrawing(it) }
                return
            }
            if (node.name == "blip" || node.name == "imagedata") {
                val rId = node.attr("embed") ?: node.attr("r:embed") ?: node.attr("id") ?: node.attr("r:id")
                val target = rId?.let { relMap[it] }
                val imgBytes = target?.let { entries[it] }
                if (imgBytes != null) {
                    val extent = drawingNode.findFirst("extent") ?: drawingNode.findFirst("ext")
                    val cxEmu = extent?.attr("cx")?.toFloatOrNull()
                    val cyEmu = extent?.attr("cy")?.toFloatOrNull()
                    val wPt = cxEmu?.div(EMU_PER_PT)?.roundToInt()?.coerceIn(32, 520) ?: 380
                    val hPt = cyEmu?.div(EMU_PER_PT)?.roundToInt()?.coerceIn(24, 680) ?: 220
                    val mime = if (target.endsWith(".jpg", true) || target.endsWith(".jpeg", true)) "image/jpeg" else "image/png"
                    val docPr = drawingNode.findFirst("docPr")
                    val descr = docPr?.attr("descr") ?: docPr?.attr("title") ?: ""
                    outImages.add(
                        ImageNode(
                            bytes = imgBytes,
                            mimeType = mime,
                            caption = descr,
                            widthPt = wPt,
                            heightPt = hPt,
                            align = paraAlign,
                        )
                    )
                }
                return
            }
            if (node.name == "txbxContent") {
                for (p in node.children("p")) {
                    val parsed = parseDomParagraph(p, docDefaults, styleMap, themeColors, numberingDefs, listCounters, relMap, entries)
                    outTextBoxes.addAll(parsed)
                }
                return
            }
            for (c in node.children) walkDrawing(c)
        }
        walkDrawing(drawingNode)
    }

    private fun collectMathText(mathNode: XmlNode): String {
        val sb = StringBuilder()
        fun walk(n: XmlNode) {
            if (n.name == "t" && n.text.isNotEmpty()) {
                sb.append(n.text)
            } else {
                for (c in n.children) walk(c)
            }
        }
        walk(mathNode)
        return sb.toString()
    }

    private fun parseDomTable(
        tblNode: XmlNode,
        docDefaults: ResolvedStyleProps,
        styleMap: Map<String, ResolvedStyleProps>,
        themeColors: Map<String, Int>,
        numberingDefs: Map<Pair<Int, Int>, NumberingLevelDef>,
        listCounters: MutableMap<Pair<Int, Int>, Int>,
        relMap: Map<String, String>,
        entries: Map<String, ByteArray>,
    ): TableNode {
        val tblPr = tblNode.child("tblPr")
        val tblStyleId = tblPr?.child("tblStyle")?.attr("val")
        val tblStyle = tblStyleId?.let { styleMap[it] } ?: ResolvedStyleProps()
        val tblLook = tblPr?.child("tblLook")
        val hasFirstRowHeader = (tblLook?.attr("firstRow") ?: "1") != "0"

        // Table borders
        val tblBorders = tblPr?.child("tblBorders")
        val hasExplicitNoBorders = tblBorders != null && listOf("top", "bottom", "left", "right", "insideH", "insideV").all { side ->
            val v = tblBorders.child(side)?.attr("val")
            v == "none" || v == "nil"
        }
        val tblBorderColor = resolveWordColor(
            tblBorders?.child("insideH") ?: tblBorders?.child("top"),
            "color", "themeColor", "themeTint", "themeShade", themeColors
        ) ?: tblStyle.tableBorderColorArgb ?: 0xFFCBD5E1.toInt()

        // Column widths from <w:tblGrid><w:gridCol w:w="..."/>
        val gridCols = tblNode.child("tblGrid")?.children("gridCol")
            ?.mapNotNull { it.attr("w")?.toFloatOrNull() }
            .orEmpty()

        val trNodes = tblNode.children("tr")
        val richRows = ArrayList<List<WordTableCell>>()
        val plainRows = ArrayList<List<String>>()
        val firstRowColWidths = ArrayList<Float>()

        trNodes.forEachIndexed { rIdx, trNode ->
            val isHeaderRow = (rIdx == 0 && hasFirstRowHeader) || trNode.child("trPr")?.child("tblHeader") != null
            val tcNodes = trNode.children("tc")
            val rowCells = ArrayList<WordTableCell>()
            val rowStrings = ArrayList<String>()

            tcNodes.forEach { tcNode ->
                val tcPr = tcNode.child("tcPr")
                val gridSpan = tcPr?.child("gridSpan")?.attr("val")?.toIntOrNull()?.coerceIn(1, 12) ?: 1
                val vMergeNode = tcPr?.child("vMerge")
                val vMergeContinue = vMergeNode != null && (vMergeNode.attr("val") ?: "continue") == "continue"
                val tcW = tcPr?.child("tcW")?.attr("w")?.toFloatOrNull()
                if (rIdx == 0 && tcW != null && tcW > 0f) {
                    repeat(gridSpan) { firstRowColWidths.add(tcW / gridSpan) }
                }

                val directCellFill = resolveWordColor(
                    tcPr?.child("shd"),
                    "fill", "themeFill", "themeFillTint", "themeFillShade", themeColors
                )
                val effectiveFill = directCellFill ?: when {
                    isHeaderRow && tblStyle.tableHeaderFillArgb != null -> tblStyle.tableHeaderFillArgb
                    !isHeaderRow && rIdx % 2 == 1 && tblStyle.tableBandFillArgb != null -> tblStyle.tableBandFillArgb
                    else -> null
                }

                val vAlign = tcPr?.child("vAlign")?.attr("val")?.lowercase() ?: "top"
                val tcBorders = tcPr?.child("tcBorders")
                val cellBorderColor = resolveWordColor(
                    tcBorders?.child("bottom") ?: tcBorders?.child("top"),
                    "color", "themeColor", "themeTint", "themeShade", themeColors
                )

                // Override defaults for header rows if table style specifies header text color / bold
                val cellDefaults = if (isHeaderRow) {
                    docDefaults.copy(
                        bold = tblStyle.tableHeaderBold ?: docDefaults.bold,
                        colorArgb = tblStyle.tableHeaderTextColorArgb ?: docDefaults.colorArgb,
                        spaceBeforePt = 0f,
                        spaceAfterPt = 2f,
                    )
                } else {
                    docDefaults.copy(spaceBeforePt = 0f, spaceAfterPt = 2f)
                }

                val cellParas = ArrayList<ParagraphNode>()
                val cellImages = ArrayList<ImageNode>()
                for (child in tcNode.children) {
                    if (child.name == "p") {
                        val parsed = parseDomParagraph(
                            pNode = child,
                            docDefaults = cellDefaults,
                            styleMap = styleMap,
                            themeColors = themeColors,
                            numberingDefs = numberingDefs,
                            listCounters = listCounters,
                            relMap = relMap,
                            entries = entries,
                        )
                        parsed.forEach { el ->
                            when (el) {
                                is ParagraphNode -> if (!el.isPageBreakOnly) cellParas.add(el)
                                is ImageNode -> cellImages.add(el)
                                else -> Unit
                            }
                        }
                    }
                }
                if (cellParas.isEmpty()) {
                    cellParas.add(ParagraphNode(runs = listOf(TextRun("")), spaceAfterPt = 0f))
                }

                val cell = WordTableCell(
                    paragraphs = cellParas,
                    images = cellImages,
                    fillArgb = effectiveFill,
                    gridSpan = gridSpan,
                    vMergeContinue = vMergeContinue,
                    verticalAlign = vAlign,
                    borderColorArgb = cellBorderColor,
                )
                rowCells.add(cell)
                rowStrings.add(cell.plainText)
            }
            if (rowCells.isNotEmpty()) {
                richRows.add(rowCells)
                plainRows.add(rowStrings)
            }
        }

        if (plainRows.isEmpty()) {
            plainRows.add(listOf(""))
            richRows.add(listOf(WordTableCell()))
        }

        val maxCols = richRows.maxOfOrNull { r -> r.sumOf { it.gridSpan } }?.coerceAtLeast(1) ?: 1
        val rawColWidths = when {
            gridCols.size == maxCols && gridCols.sum() > 0f -> gridCols
            firstRowColWidths.size == maxCols && firstRowColWidths.sum() > 0f -> firstRowColWidths
            else -> List(maxCols) { 1f }
        }
        val totalW = rawColWidths.sum().coerceAtLeast(1f)
        val colFractions = rawColWidths.map { (it / totalW).coerceAtLeast(0.05f) }

        return TableNode(
            rows = plainRows,
            headerRow = hasFirstRowHeader,
            richRows = richRows,
            colWidthFractions = colFractions,
            borderColorArgb = tblBorderColor,
            hasBorders = !hasExplicitNoBorders,
        )
    }

    // --------------------------------------------------------- DOCX WRITER
    private fun writeDocx(doc: WordDocument, out: OutputStream) {
        Zip.Writer(out).use { z ->
            val images = doc.elements.filterIsInstance<ImageNode>()
            val hasJpg = images.any { it.mimeType.contains("jpeg") || it.mimeType.contains("jpg") }
            val contentTypes = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Default Extension="png" ContentType="image/png"/>""")
                if (hasJpg) append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
                append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
                append("""<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
                append("""</Types>""")
            }
            z.put("[Content_Types].xml", contentTypes)
            z.put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""".trimIndent()
            )
            z.put(
                "word/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
                  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:rPr><w:b/><w:sz w:val="44"/></w:rPr></w:style>
                  <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
                  <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
                </w:styles>""".trimIndent()
            )

            val docRels = StringBuilder()
            docRels.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            docRels.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            docRels.append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")

            val imgRelIds = HashMap<Long, String>()
            images.forEachIndexed { idx, img ->
                val ext = if (img.mimeType.contains("jpeg") || img.mimeType.contains("jpg")) "jpg" else "png"
                val relId = "rIdImg${idx + 1}"
                val mediaPath = "media/image${idx + 1}.$ext"
                imgRelIds[img.id] = relId
                docRels.append("""<Relationship Id="$relId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$mediaPath"/>""")
                z.put("word/$mediaPath", img.bytes)
            }
            docRels.append("""</Relationships>""")
            z.put("word/_rels/document.xml.rels", docRels.toString())

            val body = StringBuilder()
            body.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            body.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")

            for (el in doc.elements) {
                when (el) {
                    is ParagraphNode -> {
                        if (el.isPageBreakOnly) {
                            body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                            continue
                        }
                        body.append("<w:p><w:pPr>")
                        if (el.headingLevel in 1..3) body.append("""<w:pStyle w:val="Heading${el.headingLevel}"/>""")
                        val jc = when (el.align) {
                            ParaAlign.CENTER -> "center"
                            ParaAlign.RIGHT -> "right"
                            ParaAlign.JUSTIFY -> "both"
                            ParaAlign.LEFT -> "left"
                        }
                        body.append("""<w:jc w:val="$jc"/>""")
                        if (el.listKind != ListKind.NONE) {
                            val numId = if (el.listKind == ListKind.NUMBERED) 2 else 1
                            body.append("""<w:numPr><w:ilvl w:val="${el.listLevel}"/><w:numId w:val="$numId"/></w:numPr>""")
                        }
                        body.append("</w:pPr>")
                        for (run in el.runs) {
                            body.append("<w:r><w:rPr>")
                            if (run.style.bold || el.headingLevel > 0) body.append("<w:b/>")
                            if (run.style.italic) body.append("<w:i/>")
                            if (run.style.underline) body.append("""<w:u w:val="single"/>""")
                            if (run.style.colorArgb != null) {
                                val cHex = "%06X".format(run.style.colorArgb and 0xFFFFFF)
                                body.append("""<w:color w:val="$cHex"/>""")
                            }
                            val sz = (run.style.fontSizeSp * 2).coerceIn(14, 128)
                            body.append("""<w:sz w:val="$sz"/>""")
                            body.append("</w:rPr>")
                            val lines = run.text.split('\n')
                            lines.forEachIndexed { idx, line ->
                                if (idx > 0) body.append("<w:br/>")
                                body.append("""<w:t xml:space="preserve">${Xml.escape(line)}</w:t>""")
                            }
                            body.append("</w:r>")
                        }
                        body.append("</w:p>")
                    }
                    is TableNode -> {
                        body.append("<w:tbl><w:tblPr><w:tblBorders>")
                        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                            body.append("""<w:$side w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>""")
                        }
                        body.append("</w:tblBorders></w:tblPr>")
                        for (row in el.rows) {
                            body.append("<w:tr>")
                            for (cell in row) {
                                body.append("<w:tc><w:p><w:r><w:t xml:space=\"preserve\">")
                                body.append(Xml.escape(cell))
                                body.append("</w:t></w:r></w:p></w:tc>")
                            }
                            body.append("</w:tr>")
                        }
                        body.append("</w:tbl>")
                    }
                    is ImageNode -> {
                        val relId = imgRelIds[el.id]
                        if (relId != null) {
                            val cx = el.widthPt.toLong() * 12700L
                            val cy = el.heightPt.toLong() * 12700L
                            body.append(
                                """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="${el.id}" name="Image"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="${el.id}" name="Image"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
                            )
                        }
                    }
                }
            }
            body.append("</w:body></w:document>")
            z.put("word/document.xml", body.toString())
        }
    }

    // --------------------------------------------------------- ODT READER & WRITER
    private fun readOdt(input: InputStream, title: String): WordDocument {
        val entries = Zip.readAll(input)
        val contentBytes = entries["content.xml"]
            ?: throw DocumentException("Invalid ODT: missing content.xml")

        val p = Xml.parser(contentBytes.inputStream())
        val stylesBold = HashSet<String>()
        val stylesItalic = HashSet<String>()
        val stylesUnderline = HashSet<String>()
        val stylesSize = HashMap<String, Int>()
        val stylesAlign = HashMap<String, ParaAlign>()

        val elements = ArrayList<DocElement>()
        var currentStyleName: String? = null
        var inList: ListKind = ListKind.NONE

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "style" -> currentStyleName = p.attr("name")
                    "text-properties" -> currentStyleName?.let { sn ->
                        if (p.attr("font-weight") == "bold") stylesBold.add(sn)
                        if (p.attr("font-style") == "italic") stylesItalic.add(sn)
                        if ((p.attr("text-underline-style") ?: "none") != "none") stylesUnderline.add(sn)
                        p.attr("font-size")?.removeSuffix("pt")?.toFloatOrNull()?.toInt()?.let { stylesSize[sn] = it }
                    }
                    "paragraph-properties" -> currentStyleName?.let { sn ->
                        when (p.attr("text-align")) {
                            "center" -> stylesAlign[sn] = ParaAlign.CENTER
                            "end", "right" -> stylesAlign[sn] = ParaAlign.RIGHT
                            "justify" -> stylesAlign[sn] = ParaAlign.JUSTIFY
                        }
                    }
                    "list" -> inList = ListKind.BULLET
                    "h", "p" -> {
                        val isHeading = p.name == "h"
                        val level = if (isHeading) (p.attr("outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 3) else 0
                        val pStyle = p.attr("style-name")
                        val align = pStyle?.let { stylesAlign[it] } ?: ParaAlign.LEFT
                        val baseBold = isHeading || (pStyle != null && pStyle in stylesBold)
                        val baseItalic = pStyle != null && pStyle in stylesItalic
                        val baseUnder = pStyle != null && pStyle in stylesUnderline
                        val baseSize = pStyle?.let { stylesSize[it] } ?: when (level) { 1 -> 22; 2 -> 18; 3 -> 16; else -> 12 }
                        elements.addAll(
                            parseOdtPara(
                                p, level, align, inList,
                                SpanStyle(bold = baseBold, italic = baseItalic, underline = baseUnder, fontSizeSp = baseSize, fontSizePt = baseSize.toFloat()),
                                stylesBold, stylesItalic, stylesUnderline, stylesSize, entries
                            )
                        )
                    }
                    "table" -> elements.add(parseOdtTable(p))
                }
            } else if (p.eventType == XmlPullParser.END_TAG) {
                if (p.name == "list") inList = ListKind.NONE
                if (p.name == "style") currentStyleName = null
            }
        }
        if (elements.isEmpty()) elements.add(ParagraphNode())
        return WordDocument(title = title, elements = elements, sourceFormat = DocFormat.ODT)
    }

    private fun parseOdtPara(
        p: XmlPullParser,
        heading: Int,
        align: ParaAlign,
        listKind: ListKind,
        baseStyle: SpanStyle,
        stylesBold: Set<String>,
        stylesItalic: Set<String>,
        stylesUnderline: Set<String>,
        stylesSize: Map<String, Int>,
        entries: Map<String, ByteArray>,
    ): List<DocElement> {
        val runs = ArrayList<TextRun>()
        val images = ArrayList<ImageNode>()
        val sb = StringBuilder()
        var activeStyle = baseStyle
        var depth = 1

        fun flush() {
            if (sb.isNotEmpty()) {
                runs.add(TextRun(sb.toString(), activeStyle))
                sb.setLength(0)
            }
        }

        while (depth > 0) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            when (ev) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> {
                    depth++
                    when (p.name) {
                        "span" -> {
                            flush()
                            val sn = p.attr("style-name")
                            val sz = sn?.let { stylesSize[it] } ?: baseStyle.fontSizeSp
                            activeStyle = SpanStyle(
                                bold = baseStyle.bold || (sn != null && sn in stylesBold),
                                italic = baseStyle.italic || (sn != null && sn in stylesItalic),
                                underline = baseStyle.underline || (sn != null && sn in stylesUnderline),
                                fontSizeSp = sz,
                                fontSizePt = sz.toFloat(),
                            )
                        }
                        "s" -> {
                            val c = p.attr("c")?.toIntOrNull() ?: 1
                            repeat(c) { sb.append(' ') }
                        }
                        "tab" -> sb.append("    ")
                        "line-break" -> sb.append('\n')
                        "image" -> {
                            val href = p.attr("href")?.removePrefix("/")
                            val bytes = href?.let { entries[it] }
                            if (bytes != null) images.add(ImageNode(bytes = bytes))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "span") {
                        flush()
                        activeStyle = baseStyle
                    }
                    depth--
                }
            }
        }
        flush()
        val out = ArrayList<DocElement>()
        if (runs.isNotEmpty() || images.isEmpty()) {
            val r = if (runs.isEmpty()) listOf(TextRun("", baseStyle)) else runs
            out.add(
                ParagraphNode(
                    runs = r,
                    headingLevel = heading,
                    align = align,
                    listKind = listKind,
                    listPrefix = if (listKind == ListKind.BULLET) "•  " else "",
                )
            )
        }
        out.addAll(images)
        return out
    }

    private fun parseOdtTable(p: XmlPullParser): TableNode {
        val rows = ArrayList<List<String>>()
        var depth = 1
        var currentRow: ArrayList<String>? = null
        var currentCell: StringBuilder? = null
        while (depth > 0) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            when (ev) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (p.name) {
                        "table-row" -> currentRow = ArrayList()
                        "table-cell" -> currentCell = StringBuilder()
                        "p" -> if (currentCell != null && currentCell.isNotEmpty()) currentCell.append('\n')
                    }
                }
                XmlPullParser.TEXT -> currentCell?.append(p.text)
                XmlPullParser.END_TAG -> {
                    when (p.name) {
                        "table-cell" -> {
                            currentRow?.add(currentCell?.toString() ?: "")
                            currentCell = null
                        }
                        "table-row" -> {
                            currentRow?.let { if (it.isNotEmpty()) rows.add(it) }
                            currentRow = null
                        }
                    }
                    depth--
                }
            }
        }
        if (rows.isEmpty()) rows.add(listOf(""))
        return TableNode(rows = rows)
    }

    private fun writeOdt(doc: WordDocument, out: OutputStream) {
        val mime = "application/vnd.oasis.opendocument.text"
        Zip.Writer(out).use { z ->
            z.stored("mimetype", mime.toByteArray(Charsets.US_ASCII))
            val images = doc.elements.filterIsInstance<ImageNode>()
            val manifest = StringBuilder()
            manifest.append("""<?xml version="1.0" encoding="UTF-8"?>""")
            manifest.append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">""")
            manifest.append("""<manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/>""")
            manifest.append("""<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>""")
            val imgPaths = HashMap<Long, String>()
            images.forEachIndexed { i, img ->
                val path = "Pictures/image${i + 1}.png"
                imgPaths[img.id] = path
                z.put(path, img.bytes)
                manifest.append("""<manifest:file-entry manifest:full-path="$path" manifest:media-type="${img.mimeType}"/>""")
            }
            manifest.append("</manifest:manifest>")
            z.put("META-INF/manifest.xml", manifest.toString())

            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.append("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" office:version="1.2">""")
            sb.append("<office:automatic-styles>")
            for (a in ParaAlign.entries) {
                val fo = when (a) { ParaAlign.LEFT -> "start"; ParaAlign.CENTER -> "center"; ParaAlign.RIGHT -> "end"; ParaAlign.JUSTIFY -> "justify" }
                sb.append("""<style:style style:name="P_${a.name}" style:family="paragraph"><style:paragraph-properties fo:text-align="$fo"/></style:style>""")
            }
            val spanMap = LinkedHashMap<SpanStyle, String>()
            for (el in doc.elements) if (el is ParagraphNode) for (r in el.runs) {
                spanMap.getOrPut(r.style) { "T${spanMap.size + 1}" }
            }
            for ((st, name) in spanMap) {
                sb.append("""<style:style style:name="$name" style:family="text"><style:text-properties""")
                if (st.bold) sb.append(""" fo:font-weight="bold"""")
                if (st.italic) sb.append(""" fo:font-style="italic"""")
                if (st.underline) sb.append(""" style:text-underline-style="solid"""")
                sb.append(""" fo:font-size="${st.fontSizeSp}pt"/></style:style>""")
            }
            sb.append("</office:automatic-styles><office:body><office:text>")

            for (el in doc.elements) {
                when (el) {
                    is ParagraphNode -> {
                        if (el.isPageBreakOnly) continue
                        val tag = if (el.headingLevel > 0) "text:h" else "text:p"
                        val lvl = if (el.headingLevel > 0) """ text:outline-level="${el.headingLevel}"""" else ""
                        if (el.listKind != ListKind.NONE) sb.append("<text:list><text:list-item>")
                        sb.append("""<$tag text:style-name="P_${el.align.name}"$lvl>""")
                        for (r in el.runs) {
                            val sn = spanMap[r.style] ?: "T1"
                            sb.append("""<text:span text:style-name="$sn">${Xml.escape(r.text)}</text:span>""")
                        }
                        sb.append("</$tag>")
                        if (el.listKind != ListKind.NONE) sb.append("</text:list-item></text:list>")
                    }
                    is TableNode -> {
                        sb.append("<table:table>")
                        repeat(el.colCount) { sb.append("<table:table-column/>") }
                        for (row in el.rows) {
                            sb.append("<table:table-row>")
                            for (cell in row) {
                                sb.append("<table:table-cell office:value-type=\"string\"><text:p>")
                                sb.append(Xml.escape(cell))
                                sb.append("</text:p></table:table-cell>")
                            }
                            sb.append("</table:table-row>")
                        }
                        sb.append("</table:table>")
                    }
                    is ImageNode -> {
                        val path = imgPaths[el.id]
                        if (path != null) {
                            sb.append("""<text:p><draw:frame svg:width="${el.widthPt}pt" svg:height="${el.heightPt}pt"><draw:image xlink:href="$path" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame></text:p>""")
                        }
                    }
                }
            }
            sb.append("</office:text></office:body></office:document-content>")
            z.put("content.xml", sb.toString())
        }
    }

    // --------------------------------------------------------- RTF READER & WRITER
    private fun readRtf(input: InputStream, title: String): WordDocument {
        val raw = input.bufferedReader(Charsets.ISO_8859_1).readText()
        val elements = ArrayList<DocElement>()

        data class RtfState(
            var bold: Boolean = false,
            var italic: Boolean = false,
            var underline: Boolean = false,
            var fontSizeSp: Int = 12,
            var align: ParaAlign = ParaAlign.LEFT,
            var ignore: Boolean = false,
        )

        val stack = ArrayDeque<RtfState>()
        var state = RtfState()
        val runs = ArrayList<TextRun>()
        val cur = StringBuilder()

        fun flushRun() {
            if (cur.isNotEmpty()) {
                runs.add(
                    TextRun(
                        cur.toString(),
                        SpanStyle(
                            bold = state.bold,
                            italic = state.italic,
                            underline = state.underline,
                            fontSizeSp = state.fontSizeSp,
                            fontSizePt = state.fontSizeSp.toFloat(),
                        )
                    )
                )
                cur.setLength(0)
            }
        }

        fun flushPara() {
            flushRun()
            val r = if (runs.isEmpty()) listOf(TextRun("")) else ArrayList(runs)
            val h = when {
                r.first().style.fontSizeSp >= 21 -> 1
                r.first().style.fontSizeSp >= 17 -> 2
                else -> 0
            }
            elements.add(ParagraphNode(runs = r, headingLevel = h, align = state.align))
            runs.clear()
        }

        var i = 0
        val n = raw.length
        while (i < n) {
            when (val ch = raw[i]) {
                '{' -> {
                    flushRun()
                    stack.addLast(state.copy())
                    if (i + 2 < n && raw[i + 1] == '\\' && raw[i + 2] == '*') {
                        state.ignore = true
                        i += 2
                    }
                    i++
                }
                '}' -> {
                    flushRun()
                    if (stack.isNotEmpty()) state = stack.removeLast()
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val next = raw[i]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!state.ignore) cur.append(next)
                            i++
                        }
                        next == '\'' && i + 2 < n -> {
                            val hex = raw.substring(i + 1, i + 3)
                            val byteVal = hex.toIntOrNull(16)
                            if (byteVal != null && !state.ignore) cur.append(byteVal.toChar())
                            i += 3
                        }
                        next == '\n' || next == '\r' -> {
                            if (!state.ignore) flushPara()
                            i++
                        }
                        next.isLetter() -> {
                            val start = i
                            while (i < n && raw[i].isLetter()) i++
                            val word = raw.substring(start, i)
                            var sign = 1
                            if (i < n && (raw[i] == '-' || raw[i] == '+')) {
                                if (raw[i] == '-') sign = -1
                                i++
                            }
                            val numStart = i
                            while (i < n && raw[i].isDigit()) i++
                            val param = if (i > numStart) raw.substring(numStart, i).toIntOrNull()?.times(sign) else null
                            if (i < n && raw[i] == ' ') i++

                            when (word) {
                                "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer" -> state.ignore = true
                                "par", "sect" -> if (!state.ignore) flushPara()
                                "line" -> if (!state.ignore) cur.append('\n')
                                "tab" -> if (!state.ignore) cur.append("    ")
                                "b" -> if (!state.ignore) { flushRun(); state.bold = (param != 0) }
                                "i" -> if (!state.ignore) { flushRun(); state.italic = (param != 0) }
                                "ul" -> if (!state.ignore) { flushRun(); state.underline = (param != 0) }
                                "ulnone" -> if (!state.ignore) { flushRun(); state.underline = false }
                                "plain" -> if (!state.ignore) {
                                    flushRun()
                                    state.bold = false; state.italic = false; state.underline = false; state.fontSizeSp = 12
                                }
                                "fs" -> if (!state.ignore && param != null) {
                                    flushRun()
                                    state.fontSizeSp = (param / 2).coerceIn(8, 48)
                                }
                                "qc" -> if (!state.ignore) state.align = ParaAlign.CENTER
                                "qr" -> if (!state.ignore) state.align = ParaAlign.RIGHT
                                "qj" -> if (!state.ignore) state.align = ParaAlign.JUSTIFY
                                "ql", "pard" -> if (!state.ignore) state.align = ParaAlign.LEFT
                                "u" -> if (!state.ignore && param != null) {
                                    val code = if (param < 0) param + 65536 else param
                                    cur.append(code.toChar())
                                    if (i < n && raw[i] == '?') i++
                                }
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (!state.ignore) cur.append(ch)
                    i++
                }
            }
        }
        flushRun()
        if (runs.isNotEmpty()) elements.add(ParagraphNode(runs = runs, align = state.align))
        val cleaned = elements.dropWhile { it is ParagraphNode && it.plainText.isBlank() && elements.size > 1 }
        return WordDocument(title = title, elements = cleaned.ifEmpty { listOf(ParagraphNode()) }, sourceFormat = DocFormat.RTF)
    }

    private fun writeRtf(doc: WordDocument, out: OutputStream) {
        val sb = StringBuilder()
        sb.append("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Sans-Serif;}}\n")
        for (el in doc.elements) {
            when (el) {
                is ParagraphNode -> {
                    if (el.isPageBreakOnly) continue
                    sb.append("\\pard")
                    when (el.align) {
                        ParaAlign.CENTER -> sb.append("\\qc")
                        ParaAlign.RIGHT -> sb.append("\\qr")
                        ParaAlign.JUSTIFY -> sb.append("\\qj")
                        ParaAlign.LEFT -> sb.append("\\ql")
                    }
                    sb.append(" ")
                    val prefix = when (el.listKind) {
                        ListKind.BULLET -> "\\u8226? "
                        ListKind.NUMBERED -> "- "
                        ListKind.NONE -> ""
                    }
                    if (prefix.isNotEmpty()) sb.append(prefix)
                    for (r in el.runs) {
                        sb.append("{")
                        if (r.style.bold || el.headingLevel > 0) sb.append("\\b")
                        if (r.style.italic) sb.append("\\i")
                        if (r.style.underline) sb.append("\\ul")
                        sb.append("\\fs${(r.style.fontSizeSp * 2).coerceIn(16, 96)} ")
                        for (c in r.text) {
                            when {
                                c == '\\' || c == '{' || c == '}' -> sb.append('\\').append(c)
                                c == '\n' -> sb.append("\\line ")
                                c == '\t' -> sb.append("\\tab ")
                                c.code in 32..126 -> sb.append(c)
                                else -> sb.append("\\u${c.code.toShort()}?")
                            }
                        }
                        sb.append("}")
                    }
                    sb.append("\\par\n")
                }
                is TableNode -> {
                    for (row in el.rows) {
                        sb.append("\\pard ")
                        sb.append(row.joinToString(" \\tab ") { escapeRtfText(it) })
                        sb.append("\\par\n")
                    }
                }
                is ImageNode -> Unit
            }
        }
        sb.append("}")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun escapeRtfText(s: String): String = buildString {
        for (c in s) {
            when {
                c == '\\' || c == '{' || c == '}' -> append('\\').append(c)
                c.code in 32..126 -> append(c)
                else -> append("\\u${c.code.toShort()}?")
            }
        }
    }

    // --------------------------------------------------------- TXT READER & WRITER
    private fun readTxt(input: InputStream, title: String): WordDocument {
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paras = lines.map { line ->
            ParagraphNode(
                runs = listOf(TextRun(line, SpanStyle(fontSizeSp = 12, fontSizePt = 12f, fontFamily = WordFontFamily.MONOSPACE))),
                spaceAfterPt = 2f,
            )
        }
        return WordDocument(title = title, elements = paras.ifEmpty { listOf(ParagraphNode()) }, sourceFormat = DocFormat.TXT)
    }

    private fun writeTxt(doc: WordDocument, out: OutputStream) {
        val sb = StringBuilder()
        doc.elements.forEachIndexed { idx, el ->
            if (idx > 0) sb.append('\n')
            when (el) {
                is ParagraphNode -> {
                    if (!el.isPageBreakOnly) {
                        val prefix = if (el.listPrefix.isNotEmpty()) el.listPrefix else when (el.listKind) {
                            ListKind.BULLET -> "• "
                            ListKind.NUMBERED -> "${idx + 1}. "
                            ListKind.NONE -> ""
                        }
                        sb.append(prefix).append(el.plainText)
                    }
                }
                is TableNode -> {
                    el.rows.forEachIndexed { rIdx, row ->
                        if (rIdx > 0) sb.append('\n')
                        sb.append(row.joinToString("\t"))
                    }
                }
                is ImageNode -> sb.append("[Image: ${el.caption.ifBlank { "${el.widthPt}x${el.heightPt}" }}]")
            }
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    // --------------------------------------------------------- LEGACY DOC (Word 97-2003 OLE2) READER
    private fun readLegacyDoc(input: InputStream, title: String): WordDocument {
        val raw = input.readBytes()
        if (!Cfb.isCfb(raw)) {
            val head = raw.take(16).toByteArray().toString(Charsets.ISO_8859_1)
            if (head.startsWith("{\\rtf")) return readRtf(raw.inputStream(), title).copy(sourceFormat = DocFormat.DOC)
            return readTxt(raw.inputStream(), title).copy(sourceFormat = DocFormat.DOC)
        }
        val cfb = Cfb(raw)
        val wordStream = cfb.stream("WordDocument")
            ?: throw DocumentException("Invalid DOC file: missing WordDocument stream")
        val text = extractWord97Text(cfb, wordStream)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paras = lines.map { ParagraphNode(runs = listOf(TextRun(it)), spaceAfterPt = 4f) }
        return WordDocument(title = title, elements = paras.ifEmpty { listOf(ParagraphNode()) }, sourceFormat = DocFormat.DOC)
    }

    private fun extractWord97Text(cfb: Cfb, wordDoc: ByteArray): String {
        if (wordDoc.size < 0x200) return scanPrintableText(wordDoc)
        val bb = ByteBuffer.wrap(wordDoc).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.getShort(0x000A).toInt() and 0xFFFF
        val use1Table = (flags and 0x0200) != 0
        val tableStream = cfb.stream(if (use1Table) "1Table" else "0Table")
            ?: cfb.stream(if (use1Table) "0Table" else "1Table")

        if (tableStream != null && wordDoc.size >= 0x01AA) {
            val fcClx = bb.getInt(0x01A2)
            val lcbClx = bb.getInt(0x01A6)
            if (fcClx >= 0 && lcbClx > 0 && fcClx + lcbClx <= tableStream.size) {
                val clxText = parseClxPieceTable(wordDoc, tableStream, fcClx, lcbClx)
                if (clxText.isNotBlank()) return clxText
            }
        }
        val fcMin = bb.getInt(0x0018).coerceAtLeast(0)
        val ccpText = bb.getInt(0x004C).coerceAtLeast(0)
        if (fcMin in 1 until wordDoc.size && ccpText > 0) {
            val end1 = (fcMin + ccpText).coerceAtMost(wordDoc.size)
            val candidate = String(wordDoc, fcMin, end1 - fcMin, Charsets.ISO_8859_1)
            if (candidate.count { it.isLetterOrDigit() || it.isWhitespace() } > candidate.length * 0.7) {
                return cleanWordControlChars(candidate)
            }
        }
        return scanPrintableText(wordDoc)
    }

    private fun parseClxPieceTable(wordDoc: ByteArray, table: ByteArray, fcClx: Int, lcbClx: Int): String {
        val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var pos = fcClx
        val end = fcClx + lcbClx
        while (pos < end && table[pos].toInt() == 1) {
            val cbGrpprl = tb.getShort(pos + 1).toInt() and 0xFFFF
            pos += 3 + cbGrpprl
        }
        if (pos >= end || table[pos].toInt() != 2) return ""
        val lcb = tb.getInt(pos + 1)
        val plcStart = pos + 5
        if (lcb <= 4 || plcStart + lcb > end) return ""
        val n = (lcb - 4) / 12
        if (n <= 0) return ""
        val sb = StringBuilder()
        for (i in 0 until n) {
            val cpStart = tb.getInt(plcStart + i * 4)
            val cpEnd = tb.getInt(plcStart + (i + 1) * 4)
            val charCount = (cpEnd - cpStart).coerceAtLeast(0)
            val pcdOffset = plcStart + (n + 1) * 4 + i * 8
            val fcCompressed = tb.getInt(pcdOffset + 2)
            val isAnsi = (fcCompressed and 0x40000000) != 0
            val fc = fcCompressed and 0x3FFFFFFF
            if (isAnsi) {
                val byteOffset = fc / 2
                val len = charCount.coerceAtMost((wordDoc.size - byteOffset).coerceAtLeast(0))
                if (len > 0 && byteOffset >= 0 && byteOffset + len <= wordDoc.size) {
                    sb.append(String(wordDoc, byteOffset, len, Charsets.ISO_8859_1))
                }
            } else {
                val byteOffset = fc
                val byteLen = (charCount * 2).coerceAtMost((wordDoc.size - byteOffset).coerceAtLeast(0))
                if (byteLen > 0 && byteOffset >= 0 && byteOffset + byteLen <= wordDoc.size) {
                    sb.append(String(wordDoc, byteOffset, byteLen, Charsets.UTF_16LE))
                }
            }
        }
        return cleanWordControlChars(sb.toString())
    }

    private fun cleanWordControlChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c.code) {
                0x0D, 0x0B -> sb.append('\n')
                0x07 -> sb.append('\t')
                0x09 -> sb.append('\t')
                in 0x00..0x1F -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    private fun scanPrintableText(bytes: ByteArray): String {
        val sb = StringBuilder()
        val run = StringBuilder()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in ' '..'~' || c == '\n' || c == '\r' || c == '\t') {
                run.append(c)
            } else {
                if (run.length >= 6) sb.append(run).append('\n')
                run.setLength(0)
            }
        }
        if (run.length >= 6) sb.append(run)
        return sb.toString().trim()
    }

    // --------------------------------------------------------- PDF & THUMBNAIL RENDERING
    fun renderPdf(doc: WordDocument, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val pageW = doc.pageWidthPt.roundToInt().coerceAtLeast(595)
        val pageH = doc.pageHeightPt.roundToInt().coerceAtLeast(842)
        val marginL = doc.marginLeftPt
        val marginR = doc.marginRightPt
        val marginT = doc.marginTopPt
        val marginB = doc.marginBottomPt
        val contentW = (pageW - marginL - marginR).coerceAtLeast(200f)

        val pdf = PdfDocument()
        try {
            var pageNum = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            var canvas = page.canvas
            canvas.drawColor(doc.pageBackgroundArgb)
            var y = marginT

            fun nextPage() {
                drawPageFooter(canvas, pageW, pageH, pageNum)
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                canvas.drawColor(doc.pageBackgroundArgb)
                y = marginT
            }

            val total = doc.elements.size.coerceAtLeast(1)
            var numberedIndex = 0

            doc.elements.forEachIndexed { idx, el ->
                onProgress((idx + 1).toFloat() / total)
                when (el) {
                    is ParagraphNode -> {
                        if (el.isPageBreakOnly) {
                            if (y > marginT + 10f) nextPage()
                            return@forEachIndexed
                        }
                        if (el.pageBreakBefore && y > marginT + 10f) {
                            nextPage()
                        }
                        if (el.listKind == ListKind.NUMBERED) numberedIndex++ else numberedIndex = 0
                        y = drawParagraphOnCanvas(canvas, el, marginL, y, contentW, pageH - marginB, numberedIndex) {
                            nextPage()
                            y
                        }
                    }
                    is TableNode -> {
                        y = drawTableOnCanvas(canvas, el, marginL, y, contentW, pageH - marginB) {
                            nextPage()
                            y
                        }
                    }
                    is ImageNode -> {
                        val bmp = el.decodeBitmap(1200)
                        if (bmp != null) {
                            val drawW = el.widthPt.toFloat().coerceAtMost(contentW)
                            val ratio = if (el.widthPt > 0 && el.heightPt > 0) el.heightPt.toFloat() / el.widthPt
                            else if (bmp.width > 0) bmp.height.toFloat() / bmp.width else 0.66f
                            val drawH = (drawW * ratio).coerceAtMost(pageH - marginT - marginB - 20f)
                            if (y + drawH > pageH - marginB) nextPage()
                            val x = when (el.align) {
                                ParaAlign.CENTER -> marginL + (contentW - drawW) / 2f
                                ParaAlign.RIGHT -> marginL + contentW - drawW
                                else -> marginL
                            }
                            val dst = RectF(x, y, x + drawW, y + drawH)
                            canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                            y += drawH + 10f
                        }
                    }
                }
            }
            drawPageFooter(canvas, pageW, pageH, pageNum)
            pdf.finishPage(page)
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun renderThumbnail(doc: WordDocument, widthPx: Int): Bitmap {
        val heightPx = (widthPx * 1.38f).toInt()
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(doc.pageBackgroundArgb)
        val scale = widthPx / 595f
        canvas.save()
        canvas.scale(scale, scale)
        val margin = 44f
        val contentW = 595f - margin * 2
        var y = margin
        var num = 0
        for (el in doc.elements.take(24)) {
            if (y > 790f) break
            when (el) {
                is ParagraphNode -> {
                    if (el.isPageBreakOnly) continue
                    if (el.listKind == ListKind.NUMBERED) num++ else num = 0
                    y = drawParagraphOnCanvas(canvas, el, margin, y, contentW, 820f, num) { 820f }
                }
                is TableNode -> {
                    y = drawTableOnCanvas(canvas, el, margin, y, contentW, 820f) { 820f }
                }
                is ImageNode -> break
            }
        }
        canvas.restore()
        return bmp
    }

    private fun drawPageFooter(canvas: Canvas, pageW: Int, pageH: Int, pageNum: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(pageNum.toString(), pageW / 2f, pageH - 22f, paint)
    }

    private fun drawParagraphOnCanvas(
        canvas: Canvas,
        para: ParagraphNode,
        left: Float,
        startY: Float,
        width: Float,
        maxY: Float,
        numberedIndex: Int,
        onNewPage: () -> Float,
    ): Float {
        val baseRun = para.runs.firstOrNull()
        val sizePt = baseRun?.style?.fontSizePt ?: when (para.headingLevel) { 1 -> 20f; 2 -> 15f; 3 -> 13f; else -> 11.5f }
        val isBold = (baseRun?.style?.bold == true) || para.headingLevel > 0
        val isItalic = baseRun?.style?.italic == true
        val tfStyle = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val baseTf = when (baseRun?.style?.fontFamily) {
            WordFontFamily.SERIF -> Typeface.SERIF
            WordFontFamily.MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseRun?.style?.colorArgb ?: 0xFF0F172A.toInt()
            textSize = sizePt
            typeface = Typeface.create(baseTf, tfStyle)
            isUnderlineText = baseRun?.style?.underline == true
        }
        val prefix = if (para.listPrefix.isNotEmpty()) para.listPrefix else when (para.listKind) {
            ListKind.BULLET -> "•  "
            ListKind.NUMBERED -> "$numberedIndex.  "
            ListKind.NONE -> ""
        }
        val indent = max(para.leftIndentPt, if (para.listKind != ListKind.NONE) 18f else 0f)
        val fullText = prefix + para.plainText
        val lineH = sizePt * para.lineSpacingMultiplier.coerceIn(1.1f, 2.0f)
        var y = startY + para.spaceBeforePt

        val wrappedLines = wrapText(fullText, paint, (width - indent - para.rightIndentPt).coerceAtLeast(60f))
        for (line in wrappedLines) {
            if (y + lineH > maxY) y = onNewPage()
            val x = when (para.align) {
                ParaAlign.CENTER -> left + (width - paint.measureText(line)) / 2f
                ParaAlign.RIGHT -> left + width - para.rightIndentPt - paint.measureText(line)
                else -> left + indent
            }
            canvas.drawText(line, x, y + sizePt * 0.88f, paint)
            y += lineH
        }
        return y + para.spaceAfterPt
    }

    private fun drawTableOnCanvas(
        canvas: Canvas,
        table: TableNode,
        left: Float,
        startY: Float,
        width: Float,
        maxY: Float,
        onNewPage: () -> Float,
    ): Float {
        val cols = table.colCount.coerceAtLeast(1)
        val colFracs = if (table.colWidthFractions.size == cols) table.colWidthFractions else List(cols) { 1f / cols }
        val rowH = 22f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = table.borderColorArgb ?: 0xFFCBD5E1.toInt()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 10.5f
        }
        var y = startY + 4f
        table.rows.forEachIndexed { rIdx, row ->
            if (y + rowH > maxY) y = onNewPage()
            var curX = left
            for (cIdx in 0 until cols) {
                val colW = colFracs[cIdx] * width
                val cell = table.cellAt(rIdx, cIdx)
                val rect = RectF(curX, y, curX + colW, y + rowH)
                val bg = cell.fillArgb ?: if (rIdx == 0 && table.headerRow) 0xFFF1F5F9.toInt() else null
                if (bg != null) {
                    fillPaint.color = bg
                    canvas.drawRect(rect, fillPaint)
                }
                if (table.hasBorders) {
                    canvas.drawRect(rect, borderPaint)
                }
                val txt = row.getOrNull(cIdx).orEmpty().replace('\n', ' ')
                val firstRun = cell.paragraphs.firstOrNull()?.runs?.firstOrNull()
                textPaint.color = firstRun?.style?.colorArgb ?: 0xFF0F172A.toInt()
                textPaint.typeface = if ((firstRun?.style?.bold == true) || (rIdx == 0 && table.headerRow)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val clipped = clipToWidth(txt, textPaint, colW - 8f)
                canvas.drawText(clipped, curX + 4f, y + 15f, textPaint)
                curX += colW
            }
            y += rowH
        }
        return y + 10f
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            if (rawLine.isEmpty()) {
                out.add("")
                continue
            }
            val words = rawLine.split(' ')
            val line = StringBuilder()
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    line.setLength(0)
                    line.append(candidate)
                } else {
                    if (line.isNotEmpty()) {
                        out.add(line.toString())
                        line.setLength(0)
                    }
                    if (paint.measureText(word) <= maxWidth) {
                        line.append(word)
                    } else {
                        for (ch in word) {
                            if (paint.measureText(line.toString() + ch) > maxWidth && line.isNotEmpty()) {
                                out.add(line.toString())
                                line.setLength(0)
                            }
                            line.append(ch)
                        }
                    }
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out.ifEmpty { listOf("") }
    }

    private fun clipToWidth(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s, 0, end) > maxWidth - 10f) end--
        return s.substring(0, end) + "…"
    }
}
