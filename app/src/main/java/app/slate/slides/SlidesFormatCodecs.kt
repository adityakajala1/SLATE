package app.slate.slides

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

/**
 * Complete Presentation reader & writer for PPTX, PPT (OLE2 binary), and ODP,
 * plus high-fidelity proportional Canvas and PDF slide rendering.
 */
object SlidesFormatCodecs {

    private const val DEFAULT_SLIDE_W_EMU = 12192000f // 13.333 in (16:9 widescreen = 960 pt)
    private const val DEFAULT_SLIDE_H_EMU = 6858000f  // 7.5 in (540 pt)
    private const val EMU_PER_PT = 12700f

    fun read(input: InputStream, format: DocFormat, title: String = "Presentation"): PresentationDocument = when (format) {
        DocFormat.PPTX -> readPptx(input, title)
        DocFormat.ODP -> readOdp(input, title)
        DocFormat.PPT -> readLegacyPpt(input, title)
        else -> readPptx(input, title)
    }

    fun write(pres: PresentationDocument, format: DocFormat, out: OutputStream) = when (format) {
        DocFormat.PPTX, DocFormat.PPT -> writePptx(pres, out)
        DocFormat.ODP -> writeOdp(pres, out)
        DocFormat.PDF -> renderPdf(pres, out)
        else -> writePptx(pres, out)
    }

    // --------------------------------------------------------- LIGHTWEIGHT XML DOM FOR PPTX
    private class XmlNode(
        val name: String,
        val attrs: Map<String, String>,
        val children: MutableList<XmlNode> = ArrayList(),
        val textBuilder: StringBuilder = StringBuilder(),
    ) {
        val text: String get() = textBuilder.toString()
        fun attr(key: String): String? = attrs[key]
        fun child(tag: String): XmlNode? = children.firstOrNull { it.name == tag }
        fun children(tag: String): List<XmlNode> = children.filter { it.name == tag }
    }

    private fun parseXmlTree(bytes: ByteArray): XmlNode? = runCatching {
        val p = Xml.parser(bytes.inputStream())
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val map = if (p.attributeCount > 0) {
                        HashMap<String, String>(p.attributeCount).also { m ->
                            for (i in 0 until p.attributeCount) {
                                val rawName = p.getAttributeName(i)
                                val local = rawName.substringAfterLast(':')
                                m[local] = p.getAttributeValue(i)
                                if (rawName != local) m[rawName] = p.getAttributeValue(i)
                            }
                        }
                    } else emptyMap()
                    val node = XmlNode(p.name.substringAfterLast(':'), map)
                    stack.lastOrNull()?.children?.add(node)
                    stack.addLast(node)
                    if (root == null) root = node
                }
                XmlPullParser.TEXT -> {
                    stack.lastOrNull()?.textBuilder?.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
            }
        }
        root
    }.getOrNull()

    // --------------------------------------------------------- PPTX READER & WRITER
    private data class PlaceholderSpec(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val defaultFontPt: Float,
        val defaultBold: Boolean,
    )

    private fun readPptx(input: InputStream, title: String): PresentationDocument {
        val entries = Zip.readAll(input)

        // 1. Parse slide dimensions & slide order from ppt/presentation.xml
        var slideWEmu = DEFAULT_SLIDE_W_EMU
        var slideHEmu = DEFAULT_SLIDE_H_EMU
        val orderedSlidePaths = ArrayList<String>()

        val presRels = parseRels(entries["ppt/_rels/presentation.xml.rels"])
        entries["ppt/presentation.xml"]?.let { presBytes ->
            val root = parseXmlTree(presBytes)
            val sldSz = root?.child("sldSz")
            sldSz?.attr("cx")?.toFloatOrNull()?.let { if (it > 0f) slideWEmu = it }
            sldSz?.attr("cy")?.toFloatOrNull()?.let { if (it > 0f) slideHEmu = it }

            root?.child("sldIdLst")?.children("sldId")?.forEach { sldId ->
                val rId = sldId.attr("id") ?: sldId.attr("r:id")
                val target = rId?.let { presRels[it] }
                if (target != null) {
                    val normalized = resolvePptPath("ppt", target)
                    if (entries.containsKey(normalized)) orderedSlidePaths.add(normalized)
                }
            }
        }

        // Fallback if sldIdLst wasn't resolved
        if (orderedSlidePaths.isEmpty()) {
            entries.keys
                .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 999 }
                .forEach { orderedSlidePaths.add(it) }
        }

        // 2. Parse theme colors from ppt/theme/theme1.xml
        val themeColors = parseThemeColors(entries["ppt/theme/theme1.xml"])

        // 3. Parse each slide
        val slides = orderedSlidePaths.mapNotNull { path ->
            val xml = entries[path] ?: return@mapNotNull null
            val slideNum = path.removePrefix("ppt/slides/slide").removeSuffix(".xml")
            val relsPath = "ppt/slides/_rels/slide$slideNum.xml.rels"
            val slideRels = parseRels(entries[relsPath])

            // Find linked slideLayout for placeholder inheritance
            val layoutTarget = slideRels.values.firstOrNull { it.contains("slideLayout") }
            val layoutPath = layoutTarget?.let { resolvePptPath("ppt/slides", it) }
            val layoutPlaceholders = parseLayoutPlaceholders(
                layoutPath?.let { entries[it] },
                slideWEmu,
                slideHEmu,
            )

            parsePptxSlideDom(
                xmlBytes = xml,
                rels = slideRels,
                entries = entries,
                themeColors = themeColors,
                placeholders = layoutPlaceholders,
                slideWEmu = slideWEmu,
                slideHEmu = slideHEmu,
            )
        }

        val widthPt = (slideWEmu / EMU_PER_PT).coerceAtLeast(360f)
        val heightPt = (slideHEmu / EMU_PER_PT).coerceAtLeast(240f)
        return PresentationDocument(
            title = title,
            slides = slides.ifEmpty { listOf(SlidePage()) },
            widthPt = widthPt,
            heightPt = heightPt,
        )
    }

    private fun resolvePptPath(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList<String>()
        baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts.add(it) }
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun parseRels(bytes: ByteArray?): Map<String, String> {
        if (bytes == null) return emptyMap()
        val root = parseXmlTree(bytes) ?: return emptyMap()
        val map = HashMap<String, String>()
        for (rel in root.children("Relationship")) {
            val id = rel.attr("Id") ?: continue
            val target = rel.attr("Target") ?: continue
            map[id] = target
        }
        return map
    }

    private fun parseThemeColors(bytes: ByteArray?): Map<String, Int> {
        val map = hashMapOf(
            "dk1" to 0xFF0F172A.toInt(),
            "tx1" to 0xFF0F172A.toInt(),
            "lt1" to 0xFFFFFFFF.toInt(),
            "bg1" to 0xFFFFFFFF.toInt(),
            "dk2" to 0xFF1E293B.toInt(),
            "tx2" to 0xFF334155.toInt(),
            "lt2" to 0xFFF1F5F9.toInt(),
            "bg2" to 0xFFF8FAFC.toInt(),
            "accent1" to 0xFF2563EB.toInt(),
            "accent2" to 0xFF0F766E.toInt(),
            "accent3" to 0xFF7C3AED.toInt(),
            "accent4" to 0xFFD97706.toInt(),
            "accent5" to 0xFFDC2626.toInt(),
            "accent6" to 0xFF059669.toInt(),
        )
        if (bytes == null) return map
        val root = parseXmlTree(bytes) ?: return map
        val clrScheme = root.child("themeElements")?.child("clrScheme") ?: return map
        for (c in clrScheme.children) {
            val srgb = c.child("srgbClr")?.attr("val") ?: c.child("sysClr")?.attr("lastClr")
            if (srgb != null && srgb.length == 6) {
                srgb.toIntOrNull(16)?.let { rgb ->
                    map[c.name] = 0xFF000000.toInt() or rgb
                }
            }
        }
        map["tx1"] = map["dk1"] ?: 0xFF0F172A.toInt()
        map["bg1"] = map["lt1"] ?: 0xFFFFFFFF.toInt()
        map["tx2"] = map["dk2"] ?: 0xFF334155.toInt()
        map["bg2"] = map["lt2"] ?: 0xFFF8FAFC.toInt()
        return map
    }

    private fun resolveColorNode(fillContainer: XmlNode?, themeColors: Map<String, Int>): Int? {
        if (fillContainer == null) return null
        val solid = if (fillContainer.name == "solidFill") fillContainer else fillContainer.child("solidFill")
        if (solid != null) {
            solid.child("srgbClr")?.let { srgb ->
                val hex = srgb.attr("val")
                if (hex != null && hex.length == 6) {
                    hex.toIntOrNull(16)?.let { rgb ->
                        return applyColorTransforms(0xFF000000.toInt() or rgb, srgb)
                    }
                }
            }
            solid.child("schemeClr")?.let { sch ->
                val key = sch.attr("val")
                val base = key?.let { themeColors[it] } ?: 0xFF1E293B.toInt()
                return applyColorTransforms(base, sch)
            }
            solid.child("prstClr")?.let { prst ->
                val color = when (prst.attr("val")?.lowercase()) {
                    "white" -> 0xFFFFFFFF.toInt()
                    "black" -> 0xFF000000.toInt()
                    "red" -> 0xFFDC2626.toInt()
                    "blue" -> 0xFF2563EB.toInt()
                    "green" -> 0xFF16A34A.toInt()
                    "yellow" -> 0xFFEAB308.toInt()
                    "gray", "grey" -> 0xFF64748B.toInt()
                    else -> null
                }
                if (color != null) return color
            }
        }
        // Gradient fill fallback: use first gradient stop color
        val grad = if (fillContainer.name == "gradFill") fillContainer else fillContainer.child("gradFill")
        val firstGs = grad?.child("gsLst")?.children("gs")?.firstOrNull()
        if (firstGs != null) {
            firstGs.child("srgbClr")?.attr("val")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let {
                return 0xFF000000.toInt() or it
            }
            firstGs.child("schemeClr")?.attr("val")?.let { key ->
                themeColors[key]?.let { return it }
            }
        }
        return null
    }

    private fun applyColorTransforms(baseArgb: Int, clrNode: XmlNode): Int {
        var r = (baseArgb shr 16) and 0xFF
        var g = (baseArgb shr 8) and 0xFF
        var b = baseArgb and 0xFF
        val lumMod = clrNode.child("lumMod")?.attr("val")?.toFloatOrNull()?.div(100000f)
        val lumOff = clrNode.child("lumOff")?.attr("val")?.toFloatOrNull()?.div(100000f)
        val tint = clrNode.child("tint")?.attr("val")?.toFloatOrNull()?.div(100000f)
        val shade = clrNode.child("shade")?.attr("val")?.toFloatOrNull()?.div(100000f)

        if (lumMod != null || lumOff != null) {
            val m = lumMod ?: 1f
            val o = lumOff ?: 0f
            r = ((r * m) + (255f * o)).roundToInt().coerceIn(0, 255)
            g = ((g * m) + (255f * o)).roundToInt().coerceIn(0, 255)
            b = ((b * m) + (255f * o)).roundToInt().coerceIn(0, 255)
        }
        if (tint != null) {
            r = (r + (255 - r) * (1f - tint)).roundToInt().coerceIn(0, 255)
            g = (g + (255 - g) * (1f - tint)).roundToInt().coerceIn(0, 255)
            b = (b + (255 - b) * (1f - tint)).roundToInt().coerceIn(0, 255)
        }
        if (shade != null) {
            r = (r * shade).roundToInt().coerceIn(0, 255)
            g = (g * shade).roundToInt().coerceIn(0, 255)
            b = (b * shade).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun parseLayoutPlaceholders(
        layoutBytes: ByteArray?,
        slideWEmu: Float,
        slideHEmu: Float,
    ): Map<String, PlaceholderSpec> {
        val map = HashMap<String, PlaceholderSpec>()
        // Standard PowerPoint default placeholder positions
        map["title"] = PlaceholderSpec(0.06f, 0.07f, 0.88f, 0.18f, 30f, true)
        map["ctrTitle"] = PlaceholderSpec(0.08f, 0.26f, 0.84f, 0.24f, 36f, true)
        map["subTitle"] = PlaceholderSpec(0.10f, 0.52f, 0.80f, 0.20f, 20f, false)
        map["body"] = PlaceholderSpec(0.06f, 0.26f, 0.88f, 0.64f, 18f, false)
        map["obj"] = PlaceholderSpec(0.06f, 0.26f, 0.88f, 0.64f, 18f, false)
        map["ftr"] = PlaceholderSpec(0.06f, 0.91f, 0.30f, 0.05f, 10f, false)
        map["dt"] = PlaceholderSpec(0.38f, 0.91f, 0.24f, 0.05f, 10f, false)
        map["sldNum"] = PlaceholderSpec(0.76f, 0.91f, 0.18f, 0.05f, 10f, false)

        if (layoutBytes == null) return map
        val root = parseXmlTree(layoutBytes) ?: return map
        val spTree = root.child("cSld")?.child("spTree") ?: return map
        for (sp in spTree.children("sp")) {
            val ph = sp.child("nvSpPr")?.child("nvPr")?.child("ph") ?: continue
            val type = ph.attr("type") ?: "body"
            val idx = ph.attr("idx")
            val xfrm = sp.child("spPr")?.child("xfrm")
            val off = xfrm?.child("off")
            val ext = xfrm?.child("ext")
            val ox = off?.attr("x")?.toFloatOrNull()
            val oy = off?.attr("y")?.toFloatOrNull()
            val cx = ext?.attr("cx")?.toFloatOrNull()
            val cy = ext?.attr("cy")?.toFloatOrNull()
            if (ox != null && oy != null && cx != null && cy != null && cx > 0 && cy > 0) {
                val base = map[type] ?: map["body"]!!
                val spec = PlaceholderSpec(
                    x = (ox / slideWEmu).coerceIn(0f, 0.95f),
                    y = (oy / slideHEmu).coerceIn(0f, 0.95f),
                    w = (cx / slideWEmu).coerceIn(0.04f, 1f),
                    h = (cy / slideHEmu).coerceIn(0.03f, 1f),
                    defaultFontPt = base.defaultFontPt,
                    defaultBold = base.defaultBold,
                )
                map[type] = spec
                if (idx != null) map["idx:$idx"] = spec
            }
        }
        return map
    }

    private fun parsePptxSlideDom(
        xmlBytes: ByteArray,
        rels: Map<String, String>,
        entries: Map<String, ByteArray>,
        themeColors: Map<String, Int>,
        placeholders: Map<String, PlaceholderSpec>,
        slideWEmu: Float,
        slideHEmu: Float,
    ): SlidePage {
        val root = parseXmlTree(xmlBytes) ?: return SlidePage()
        val cSld = root.child("cSld") ?: return SlidePage()

        // 1. Slide Background (<p:bg>)
        var bgArgb = 0xFFFFFFFF.toInt()
        val bgNode = cSld.child("bg")
        if (bgNode != null) {
            resolveColorNode(bgNode.child("bgPr"), themeColors)?.let { bgArgb = it }
            if (bgNode.child("bgPr") == null) {
                resolveColorNode(bgNode.child("bgRef"), themeColors)?.let { bgArgb = it }
            }
        }

        val items = ArrayList<SlideItem>()
        val spTree = cSld.child("spTree")

        fun parseTxBody(
            txBody: XmlNode?,
            defaultFontPt: Float,
            defaultBold: Boolean,
            defaultColorArgb: Int,
        ): List<SlideParagraph> {
            if (txBody == null) return emptyList()
            val list = ArrayList<SlideParagraph>()
            var autoNumSeq = 1
            for (pNode in txBody.children("p")) {
                val pPr = pNode.child("pPr")
                val align = when (pPr?.attr("algn")) {
                    "ctr" -> SlideTextAlign.CENTER
                    "r" -> SlideTextAlign.RIGHT
                    else -> SlideTextAlign.LEFT
                }
                val indentLvl = pPr?.attr("lvl")?.toIntOrNull() ?: 0
                val bullet = when {
                    pPr?.child("buNone") != null -> ""
                    pPr?.child("buChar") != null -> pPr.child("buChar")?.attr("char") ?: "•"
                    pPr?.child("buAutoNum") != null -> "${autoNumSeq++}."
                    else -> ""
                }
                val spcBefPt = pPr?.child("spcBef")?.child("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f) ?: 0f
                val spcAftPt = pPr?.child("spcAft")?.child("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f) ?: 0f

                // Default Run Properties (<a:defRPr>) on <a:pPr>
                val defRPr = pPr?.child("defRPr")
                val defSizePt = defRPr?.attr("sz")?.toFloatOrNull()?.div(100f) ?: defaultFontPt
                val defBold = defRPr?.attr("b")?.let { it == "1" || it == "true" } ?: defaultBold
                val defItalic = defRPr?.attr("i")?.let { it == "1" || it == "true" } ?: false
                val defUnderline = defRPr?.attr("u")?.let { it != "none" } ?: false
                val defColor = resolveColorNode(defRPr, themeColors) ?: defaultColorArgb

                val runs = ArrayList<SlideRun>()
                for (child in pNode.children) {
                    when (child.name) {
                        "r", "fld" -> {
                            val rPr = child.child("rPr")
                            val rSizePt = rPr?.attr("sz")?.toFloatOrNull()?.div(100f) ?: defSizePt
                            val rBold = rPr?.attr("b")?.let { it == "1" || it == "true" } ?: defBold
                            val rItalic = rPr?.attr("i")?.let { it == "1" || it == "true" } ?: defItalic
                            val rUnderline = rPr?.attr("u")?.let { it != "none" } ?: defUnderline
                            val rColor = resolveColorNode(rPr, themeColors) ?: defColor
                            val t = child.child("t")?.text.orEmpty()
                            if (t.isNotEmpty()) {
                                runs.add(
                                    SlideRun(
                                        text = t,
                                        bold = rBold,
                                        italic = rItalic,
                                        underline = rUnderline,
                                        fontSizePt = rSizePt.coerceIn(7f, 96f),
                                        colorArgb = rColor,
                                    )
                                )
                            }
                        }
                        "br" -> {
                            runs.add(
                                SlideRun(
                                    text = "\n",
                                    bold = defBold,
                                    italic = defItalic,
                                    fontSizePt = defSizePt,
                                    colorArgb = defColor,
                                )
                            )
                        }
                    }
                }
                if (runs.isNotEmpty()) {
                    list.add(
                        SlideParagraph(
                            runs = runs,
                            align = align,
                            bullet = bullet,
                            indentLevel = indentLvl,
                            spaceBeforePt = spcBefPt,
                            spaceAfterPt = spcAftPt,
                        )
                    )
                }
            }
            return list
        }

        // Recursive walker for spTree and nested grpSp
        fun walkContainer(
            container: XmlNode,
            mapX: (Float) -> Float,
            mapY: (Float) -> Float,
            scaleW: Float,
            scaleH: Float,
        ) {
            var autoBodyIndex = 0
            for (node in container.children) {
                when (node.name) {
                    "sp", "cxnSp" -> {
                        val nvPr = node.child("nvSpPr")?.child("nvPr") ?: node.child("nvCxnSpPr")?.child("nvPr")
                        val ph = nvPr?.child("ph")
                        val phType = ph?.attr("type")
                        val phIdx = ph?.attr("idx")
                        val phSpec = when {
                            phType != null && placeholders.containsKey(phType) -> placeholders[phType]
                            phIdx != null && placeholders.containsKey("idx:$phIdx") -> placeholders["idx:$phIdx"]
                            ph != null -> {
                                val fallback = if (items.isEmpty()) placeholders["title"] else placeholders["body"]
                                fallback?.copy(y = (fallback.y + (autoBodyIndex++) * 0.22f).coerceAtMost(0.78f))
                            }
                            else -> null
                        }

                        val spPr = node.child("spPr")
                        val xfrm = spPr?.child("xfrm")
                        val off = xfrm?.child("off")
                        val ext = xfrm?.child("ext")
                        val rawX = off?.attr("x")?.toFloatOrNull()
                        val rawY = off?.attr("y")?.toFloatOrNull()
                        val rawW = ext?.attr("cx")?.toFloatOrNull()
                        val rawH = ext?.attr("cy")?.toFloatOrNull()

                        val normX = if (rawX != null) mapX(rawX / slideWEmu) else (phSpec?.x ?: 0.08f)
                        val normY = if (rawY != null) mapY(rawY / slideHEmu) else (phSpec?.y ?: (0.12f + items.size * 0.20f).coerceAtMost(0.80f))
                        val normW = if (rawW != null) (rawW / slideWEmu) * scaleW else (phSpec?.w ?: 0.84f)
                        val normH = if (rawH != null) (rawH / slideHEmu) * scaleH else (phSpec?.h ?: 0.18f)

                        val prst = spPr?.child("prstGeom")?.attr("prst")
                        val isTxBox = node.child("nvSpPr")?.child("cNvSpPr")?.attr("txBox") == "1"

                        val kind = when {
                            node.name == "cxnSp" || prst == "line" || prst == "straightConnector1" -> ShapeKind.LINE
                            prst == "ellipse" -> ShapeKind.ELLIPSE
                            prst == "roundRect" || prst == "snipRoundRect" || prst?.contains("round", ignoreCase = true) == true -> ShapeKind.ROUND_RECT
                            prst?.contains("callout", ignoreCase = true) == true -> ShapeKind.CALLOUT
                            isTxBox || ph != null -> ShapeKind.TEXT_BOX
                            else -> ShapeKind.RECTANGLE
                        }

                        // Shape background fill (only from spPr or style/fillRef, NEVER from text runs!)
                        val hasNoFill = spPr?.child("noFill") != null
                        val fillArgb = if (hasNoFill) {
                            null
                        } else {
                            resolveColorNode(spPr, themeColors)
                                ?: node.child("style")?.child("fillRef")?.let { fillRef ->
                                    if (fillRef.attr("idx") != "0") resolveColorNode(fillRef, themeColors) else null
                                }
                        }

                        // Shape border / line (<a:ln>)
                        val lnNode = spPr?.child("ln")
                        val hasNoLine = lnNode?.child("noFill") != null
                        val strokeColorArgb = if (hasNoLine) null else resolveColorNode(lnNode, themeColors)
                        val strokeWidthPt = (lnNode?.attr("w")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 1f).coerceIn(0.5f, 8f)

                        // Text body properties & insets
                        val txBody = node.child("txBody")
                        val bodyPr = txBody?.child("bodyPr")
                        val vertAlign = when (bodyPr?.attr("anchor")) {
                            "ctr" -> SlideVerticalAlign.MIDDLE
                            "b" -> SlideVerticalAlign.BOTTOM
                            else -> SlideVerticalAlign.TOP
                        }
                        val padL = bodyPr?.attr("lIns")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 2.5f
                        val padT = bodyPr?.attr("tIns")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 1.5f
                        val padR = bodyPr?.attr("rIns")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 2.5f
                        val padB = bodyPr?.attr("bIns")?.toFloatOrNull()?.div(EMU_PER_PT) ?: 1.5f

                        val defaultTextColor = if (fillArgb != null && isDarkColor(fillArgb)) {
                            0xFFFFFFFF.toInt()
                        } else {
                            0xFF0F172A.toInt()
                        }

                        val paragraphs = parseTxBody(
                            txBody = txBody,
                            defaultFontPt = phSpec?.defaultFontPt ?: 18f,
                            defaultBold = phSpec?.defaultBold ?: false,
                            defaultColorArgb = defaultTextColor,
                        )

                        val plainText = paragraphs.joinToString("\n") { p ->
                            if (p.bullet.isNotEmpty()) "${p.bullet} ${p.plainText}" else p.plainText
                        }

                        // Keep shape if it has text, fill, or border/line
                        if (plainText.isNotEmpty() || fillArgb != null || strokeColorArgb != null) {
                            val allRuns = paragraphs.flatMap { it.runs }
                            val primaryRun = allRuns.maxByOrNull { it.fontSizePt } ?: allRuns.firstOrNull()
                            val firstPara = paragraphs.firstOrNull()
                            items.add(
                                SlideItem(
                                    kind = kind,
                                    x = normX.coerceIn(0f, 0.99f),
                                    y = normY.coerceIn(0f, 0.99f),
                                    w = normW.coerceIn(0.002f, 1f),
                                    h = normH.coerceIn(0.002f, 1f),
                                    text = plainText,
                                    bold = primaryRun?.bold ?: (phSpec?.defaultBold ?: false),
                                    italic = primaryRun?.italic ?: false,
                                    fontSizeSp = (primaryRun?.fontSizePt ?: phSpec?.defaultFontPt ?: 18f).roundToInt(),
                                    fillArgb = fillArgb,
                                    strokeColorArgb = strokeColorArgb,
                                    strokeWidthPt = strokeWidthPt,
                                    textColorArgb = primaryRun?.colorArgb ?: defaultTextColor,
                                    textAlign = firstPara?.align ?: SlideTextAlign.LEFT,
                                    verticalAlign = vertAlign,
                                    padLeftPt = padL,
                                    padTopPt = padT,
                                    padRightPt = padR,
                                    padBottomPt = padB,
                                    paragraphs = paragraphs,
                                )
                            )
                        }
                    }

                    "pic" -> {
                        val spPr = node.child("spPr")
                        val xfrm = spPr?.child("xfrm")
                        val off = xfrm?.child("off")
                        val ext = xfrm?.child("ext")
                        val rawX = off?.attr("x")?.toFloatOrNull() ?: (slideWEmu * 0.1f)
                        val rawY = off?.attr("y")?.toFloatOrNull() ?: (slideHEmu * 0.15f)
                        val rawW = ext?.attr("cx")?.toFloatOrNull() ?: (slideWEmu * 0.5f)
                        val rawH = ext?.attr("cy")?.toFloatOrNull() ?: (slideHEmu * 0.5f)

                        val blip = node.child("blipFill")?.child("blip")
                        val rId = blip?.attr("embed") ?: blip?.attr("r:embed")
                        val target = rId?.let { rels[it] }
                        val mediaPath = target?.let { resolvePptPath("ppt/slides", it) }
                        val imgBytes = mediaPath?.let { entries[it] }
                        if (imgBytes != null) {
                            items.add(
                                SlideItem(
                                    kind = ShapeKind.RECTANGLE,
                                    x = mapX(rawX / slideWEmu).coerceIn(0f, 0.98f),
                                    y = mapY(rawY / slideHEmu).coerceIn(0f, 0.98f),
                                    w = ((rawW / slideWEmu) * scaleW).coerceIn(0.02f, 1f),
                                    h = ((rawH / slideHEmu) * scaleH).coerceIn(0.02f, 1f),
                                    imageBytes = imgBytes,
                                )
                            )
                        }
                    }

                    "graphicFrame" -> {
                        val xfrm = node.child("xfrm")
                        val off = xfrm?.child("off")
                        val ext = xfrm?.child("ext")
                        val rawX = off?.attr("x")?.toFloatOrNull() ?: (slideWEmu * 0.06f)
                        val rawY = off?.attr("y")?.toFloatOrNull() ?: (slideHEmu * 0.20f)
                        val rawW = ext?.attr("cx")?.toFloatOrNull() ?: (slideWEmu * 0.88f)
                        val rawH = ext?.attr("cy")?.toFloatOrNull() ?: (slideHEmu * 0.65f)
                        val normX = mapX(rawX / slideWEmu).coerceIn(0f, 0.96f)
                        val normY = mapY(rawY / slideHEmu).coerceIn(0f, 0.96f)
                        val normW = ((rawW / slideWEmu) * scaleW).coerceIn(0.05f, 1f)
                        val normH = ((rawH / slideHEmu) * scaleH).coerceIn(0.05f, 1f)

                        val graphicData = node.child("graphic")?.child("graphicData")
                        val tblNode = graphicData?.child("tbl")
                        if (tblNode != null) {
                            val gridCols = tblNode.child("tblGrid")?.children("gridCol")
                                ?.mapNotNull { it.attr("w")?.toFloatOrNull() }
                                .orEmpty()
                            val totalColW = gridCols.sum().takeIf { it > 0f } ?: 1f
                            val trNodes = tblNode.children("tr")
                            val rowHeights = trNodes.map { it.attr("h")?.toFloatOrNull() ?: 500000f }
                            val totalRowH = rowHeights.sum().takeIf { it > 0f } ?: 1f

                            val tableRows = trNodes.mapIndexed { rIdx, tr ->
                                val tcNodes = tr.children("tc")
                                val cells = tcNodes.map { tc ->
                                    val tcPr = tc.child("tcPr")
                                    val cellFill = resolveColorNode(tcPr, themeColors)
                                        ?: if (rIdx == 0) 0xFF0F172A.toInt()
                                        else if (rIdx % 2 == 1) 0xFFFFFFFF.toInt()
                                        else 0xFFF8FAFC.toInt()
                                    val defCellTextColor = if (isDarkColor(cellFill)) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt()
                                    val cellParas = parseTxBody(
                                        txBody = tc.child("txBody"),
                                        defaultFontPt = 10.5f,
                                        defaultBold = rIdx == 0,
                                        defaultColorArgb = defCellTextColor,
                                    )
                                    val vAlign = when (tcPr?.attr("anchor")) {
                                        "t" -> SlideVerticalAlign.TOP
                                        "b" -> SlideVerticalAlign.BOTTOM
                                        else -> SlideVerticalAlign.MIDDLE
                                    }
                                    SlideTableCell(
                                        paragraphs = cellParas,
                                        fillArgb = cellFill,
                                        verticalAlign = vAlign,
                                    )
                                }
                                SlideTableRow(
                                    cells = cells,
                                    heightFraction = (rowHeights[rIdx] / totalRowH).coerceAtLeast(0.05f),
                                )
                            }

                            val colCount = tableRows.maxOfOrNull { it.cells.size } ?: 1
                            val colFracs = if (gridCols.size == colCount) {
                                gridCols.map { it / totalColW }
                            } else {
                                List(colCount) { 1f / colCount }
                            }

                            if (tableRows.isNotEmpty()) {
                                val summary = tableRows.joinToString("\n") { r -> r.cells.joinToString(" | ") { it.plainText } }
                                items.add(
                                    SlideItem(
                                        kind = ShapeKind.TABLE,
                                        x = normX,
                                        y = normY,
                                        w = normW,
                                        h = normH,
                                        text = summary,
                                        fontSizeSp = 11,
                                        table = SlideTable(colFractions = colFracs, rows = tableRows),
                                    )
                                )
                            }
                        } else {
                            // Check for chart reference (<c:chart r:id="..."/>)
                            val chartNode = graphicData?.child("chart")
                            val rId = chartNode?.attr("id") ?: chartNode?.attr("r:id")
                            val chartTarget = rId?.let { rels[it] }
                            val chartBytes = chartTarget?.let { entries[resolvePptPath("ppt/slides", it)] }
                            if (chartBytes != null) {
                                val chartParas = parseChartSummary(chartBytes)
                                if (chartParas.isNotEmpty()) {
                                    items.add(
                                        SlideItem(
                                            kind = ShapeKind.ROUND_RECT,
                                            x = normX,
                                            y = normY,
                                            w = normW,
                                            h = normH,
                                            text = chartParas.joinToString("\n") { it.plainText },
                                            fillArgb = 0xFFF8FAFC.toInt(),
                                            strokeColorArgb = 0xFFCBD5E1.toInt(),
                                            strokeWidthPt = 1.2f,
                                            paragraphs = chartParas,
                                        )
                                    )
                                }
                            }
                        }
                    }

                    "grpSp" -> {
                        val grpXfrm = node.child("grpSpPr")?.child("xfrm")
                        val off = grpXfrm?.child("off")
                        val ext = grpXfrm?.child("ext")
                        val chOff = grpXfrm?.child("chOff")
                        val chExt = grpXfrm?.child("chExt")

                        val gx = off?.attr("x")?.toFloatOrNull()
                        val gy = off?.attr("y")?.toFloatOrNull()
                        val gw = ext?.attr("cx")?.toFloatOrNull()
                        val gh = ext?.attr("cy")?.toFloatOrNull()
                        val cx = chOff?.attr("x")?.toFloatOrNull()
                        val cy = chOff?.attr("y")?.toFloatOrNull()
                        val cw = chExt?.attr("cx")?.toFloatOrNull()
                        val ch = chExt?.attr("cy")?.toFloatOrNull()

                        if (gx != null && gy != null && gw != null && gh != null &&
                            cx != null && cy != null && cw != null && ch != null && cw > 0f && ch > 0f
                        ) {
                            val sx = gw / cw
                            val sy = gh / ch
                            walkContainer(
                                container = node,
                                mapX = { childNormX ->
                                    val childEmuX = childNormX * slideWEmu
                                    val mappedEmuX = gx + (childEmuX - cx) * sx
                                    mapX(mappedEmuX / slideWEmu)
                                },
                                mapY = { childNormY ->
                                    val childEmuY = childNormY * slideHEmu
                                    val mappedEmuY = gy + (childEmuY - cy) * sy
                                    mapY(mappedEmuY / slideHEmu)
                                },
                                scaleW = scaleW * sx,
                                scaleH = scaleH * sy,
                            )
                        } else {
                            walkContainer(node, mapX, mapY, scaleW, scaleH)
                        }
                    }
                }
            }
        }

        if (spTree != null) {
            walkContainer(
                container = spTree,
                mapX = { it },
                mapY = { it },
                scaleW = 1f,
                scaleH = 1f,
            )
        }

        return SlidePage(
            items = items.ifEmpty { listOf(SlideItem(text = "")) },
            backgroundArgb = bgArgb,
        )
    }

    private fun parseChartSummary(chartBytes: ByteArray): List<SlideParagraph> {
        val root = parseXmlTree(chartBytes) ?: return emptyList()
        val paras = ArrayList<SlideParagraph>()
        paras.add(
            SlideParagraph(
                runs = listOf(SlideRun(text = "Chart Data", bold = true, fontSizePt = 14f, colorArgb = 0xFF0F172A.toInt()))
            )
        )
        // Collect text values (<c:v> or <a:t>) inside the chart
        val values = ArrayList<String>()
        fun collect(n: XmlNode) {
            if ((n.name == "t" || n.name == "v") && n.text.isNotBlank()) {
                values.add(n.text.trim())
            }
            n.children.forEach(::collect)
        }
        collect(root)
        values.distinct().take(12).chunked(3).forEach { chunk ->
            paras.add(
                SlideParagraph(
                    runs = listOf(SlideRun(text = chunk.joinToString("  •  "), fontSizePt = 11f, colorArgb = 0xFF334155.toInt()))
                )
            )
        }
        return paras
    }

    private fun isDarkColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b)
        return lum < 128.0
    }

    private fun writePptx(pres: PresentationDocument, out: OutputStream) {
        val slideWEmu = (pres.widthPt * EMU_PER_PT).toLong()
        val slideHEmu = (pres.heightPt * EMU_PER_PT).toLong()
        Zip.Writer(out).use { z ->
            val ct = StringBuilder()
            ct.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            ct.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            ct.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            ct.append("""<Default Extension="xml" ContentType="application/xml"/>""")
            ct.append("""<Default Extension="png" ContentType="image/png"/>""")
            ct.append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
            ct.append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
            ct.append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
            for (i in pres.slides.indices) {
                ct.append("""<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
            }
            ct.append("</Types>")
            z.put("[Content_Types].xml", ct.toString())

            z.put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                </Relationships>""".trimIndent()
            )

            val presXml = StringBuilder()
            presXml.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            presXml.append("""<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
            presXml.append("""<p:sldIdLst>""")
            val presRels = StringBuilder()
            presRels.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            presRels.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")

            var mediaSeq = 1
            pres.slides.forEachIndexed { idx, slide ->
                val slideNo = idx + 1
                val rId = "rId$slideNo"
                val sldId = 256 + idx
                presXml.append("""<p:sldId id="$sldId" r:id="$rId"/>""")
                presRels.append("""<Relationship Id="$rId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide$slideNo.xml"/>""")

                val slideRels = StringBuilder()
                slideRels.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                slideRels.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                val itemRelIds = HashMap<Long, String>()

                for (item in slide.items) {
                    val bytes = item.imageBytes
                    if (bytes != null) {
                        val ext = if (item.imageMime.contains("jpg") || item.imageMime.contains("jpeg")) "jpg" else "png"
                        val mediaName = "image${mediaSeq++}.$ext"
                        val imgRId = "rImg$mediaSeq"
                        z.put("ppt/media/$mediaName", bytes)
                        slideRels.append("""<Relationship Id="$imgRId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/$mediaName"/>""")
                        itemRelIds[item.id] = imgRId
                    }
                }
                slideRels.append("</Relationships>")
                z.put("ppt/slides/_rels/slide$slideNo.xml.rels", slideRels.toString())
                z.put("ppt/slides/slide$slideNo.xml", buildPptxSlideXml(slide, itemRelIds, slideWEmu, slideHEmu))
            }

            presXml.append("""</p:sldIdLst><p:sldSz cx="$slideWEmu" cy="$slideHEmu"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""")
            presRels.append("</Relationships>")
            z.put("ppt/presentation.xml", presXml.toString())
            z.put("ppt/_rels/presentation.xml.rels", presRels.toString())
        }
    }

    private fun buildPptxSlideXml(slide: SlidePage, imgRels: Map<Long, String>, slideWEmu: Long, slideHEmu: Long): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld>""")
        if (slide.backgroundArgb != 0xFFFFFFFF.toInt()) {
            val bgHex = "%06X".format(slide.backgroundArgb and 0xFFFFFF)
            sb.append("""<p:bg><p:bgPr><a:solidFill><a:srgbClr val="$bgHex"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>""")
        }
        sb.append("""<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")

        slide.items.forEachIndexed { idx, item ->
            val xEmu = (item.x * slideWEmu).toLong()
            val yEmu = (item.y * slideHEmu).toLong()
            val wEmu = (item.w * slideWEmu).toLong()
            val hEmu = (item.h * slideHEmu).toLong()
            val shapeId = idx + 2
            val relId = imgRels[item.id]
            val tbl = item.table
            if (relId != null) {
                sb.append(
                    """<p:pic><p:nvPicPr><p:cNvPr id="$shapeId" name="Picture $shapeId"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$xEmu" y="$yEmu"/><a:ext cx="$wEmu" cy="$hEmu"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
                )
            } else if (tbl != null) {
                sb.append("""<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$shapeId" name="Table $shapeId"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>""")
                sb.append("""<p:xfrm><a:off x="$xEmu" y="$yEmu"/><a:ext cx="$wEmu" cy="$hEmu"/></p:xfrm>""")
                sb.append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblPr firstRow="1" bandRow="1"/><a:tblGrid>""")
                for (cf in tbl.colFractions) {
                    sb.append("""<a:gridCol w="${(cf * wEmu).toLong()}"/>""")
                }
                sb.append("</a:tblGrid>")
                for (row in tbl.rows) {
                    val rh = (row.heightFraction * hEmu).toLong()
                    sb.append("""<a:tr h="$rh">""")
                    for (cell in row.cells) {
                        sb.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>")
                        val paras = cell.paragraphs.ifEmpty { listOf(SlideParagraph(listOf(SlideRun("")))) }
                        for (p in paras) {
                            sb.append("<a:p>")
                            for (r in p.runs) {
                                val bAttr = if (r.bold) """ b="1"""" else ""
                                val szAttr = """ sz="${(r.fontSizePt * 100).roundToInt().coerceIn(800, 4800)}""""
                                val cHex = "%06X".format(r.colorArgb and 0xFFFFFF)
                                sb.append("""<a:r><a:rPr lang="en-US"$bAttr$szAttr><a:solidFill><a:srgbClr val="$cHex"/></a:solidFill></a:rPr><a:t>${Xml.escape(r.text)}</a:t></a:r>""")
                            }
                            sb.append("</a:p>")
                        }
                        sb.append("</a:txBody><a:tcPr>")
                        if (cell.fillArgb != null) {
                            val fHex = "%06X".format(cell.fillArgb and 0xFFFFFF)
                            sb.append("""<a:solidFill><a:srgbClr val="$fHex"/></a:solidFill>""")
                        }
                        sb.append("</a:tcPr></a:tc>")
                    }
                    sb.append("</a:tr>")
                }
                sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
            } else {
                val prst = when (item.kind) {
                    ShapeKind.ELLIPSE -> "ellipse"
                    ShapeKind.ROUND_RECT -> "roundRect"
                    ShapeKind.CALLOUT -> "wedgeRoundRectCallout"
                    ShapeKind.LINE -> "line"
                    else -> "rect"
                }
                sb.append("""<p:sp><p:nvSpPr><p:cNvPr id="$shapeId" name="Shape $shapeId"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>""")
                sb.append("""<a:xfrm><a:off x="$xEmu" y="$yEmu"/><a:ext cx="$wEmu" cy="$hEmu"/></a:xfrm>""")
                sb.append("""<a:prstGeom prst="$prst"><a:avLst/></a:prstGeom>""")
                val fill = item.fillArgb
                if (fill != null) {
                    val hex = "%06X".format(fill and 0xFFFFFF)
                    sb.append("""<a:solidFill><a:srgbClr val="$hex"/></a:solidFill>""")
                } else {
                    sb.append("<a:noFill/>")
                }
                if (item.strokeColorArgb != null) {
                    val sHex = "%06X".format(item.strokeColorArgb and 0xFFFFFF)
                    val wLn = (item.strokeWidthPt * EMU_PER_PT).toLong()
                    sb.append("""<a:ln w="$wLn"><a:solidFill><a:srgbClr val="$sHex"/></a:solidFill></a:ln>""")
                }
                val anchorAttr = when (item.verticalAlign) {
                    SlideVerticalAlign.MIDDLE -> """ anchor="ctr""""
                    SlideVerticalAlign.BOTTOM -> """ anchor="b""""
                    SlideVerticalAlign.TOP -> ""
                }
                sb.append("""</p:spPr><p:txBody><a:bodyPr$anchorAttr/><a:lstStyle/>""")
                if (item.paragraphs.isNotEmpty()) {
                    for (p in item.paragraphs) {
                        val algnAttr = when (p.align) {
                            SlideTextAlign.CENTER -> """ algn="ctr""""
                            SlideTextAlign.RIGHT -> """ algn="r""""
                            SlideTextAlign.LEFT -> ""
                        }
                        sb.append("""<a:p><a:pPr$algnAttr/>""")
                        for (r in p.runs) {
                            val bAttr = if (r.bold) """ b="1"""" else ""
                            val iAttr = if (r.italic) """ i="1"""" else ""
                            val szAttr = """ sz="${(r.fontSizePt * 100).roundToInt().coerceIn(800, 7200)}""""
                            val cHex = "%06X".format(r.colorArgb and 0xFFFFFF)
                            sb.append("""<a:r><a:rPr lang="en-US"$bAttr$iAttr$szAttr><a:solidFill><a:srgbClr val="$cHex"/></a:solidFill></a:rPr><a:t>${Xml.escape(r.text)}</a:t></a:r>""")
                        }
                        sb.append("</a:p>")
                    }
                } else {
                    val bAttr = if (item.bold) """ b="1"""" else ""
                    val iAttr = if (item.italic) """ i="1"""" else ""
                    val szAttr = """ sz="${(item.fontSizeSp * 100).coerceIn(800, 7200)}""""
                    val cHex = "%06X".format(item.textColorArgb and 0xFFFFFF)
                    for (line in item.text.split('\n')) {
                        sb.append("""<a:p><a:r><a:rPr lang="en-US"$bAttr$iAttr$szAttr><a:solidFill><a:srgbClr val="$cHex"/></a:solidFill></a:rPr><a:t>${Xml.escape(line)}</a:t></a:r></a:p>""")
                    }
                }
                sb.append("</p:txBody></p:sp>")
            }
        }
        sb.append("</p:spTree></p:cSld></p:sld>")
        return sb.toString()
    }

    // --------------------------------------------------------- ODP READER & WRITER
    private fun readOdp(input: InputStream, title: String): PresentationDocument {
        val entries = Zip.readAll(input)
        val content = entries["content.xml"] ?: throw DocumentException("Invalid ODP: missing content.xml")
        val p = Xml.parser(content.inputStream())

        val slides = ArrayList<SlidePage>()
        var currentItems: ArrayList<SlideItem>? = null
        var inFrame = false
        var x = 0.08f; var y = 0.15f; var w = 0.84f; var h = 0.25f
        val textSb = StringBuilder()
        var imgBytes: ByteArray? = null

        fun parseCmRatio(attrVal: String?, totalCm: Float, fallback: Float): Float {
            if (attrVal == null) return fallback
            val num = attrVal.removeSuffix("cm").removeSuffix("in").removeSuffix("pt").toFloatOrNull() ?: return fallback
            val cm = when {
                attrVal.endsWith("in") -> num * 2.54f
                attrVal.endsWith("pt") -> num * 0.03527f
                else -> num
            }
            return (cm / totalCm).coerceIn(0.02f, 0.96f)
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "page" -> currentItems = ArrayList()
                    "frame", "custom-shape" -> if (currentItems != null) {
                        inFrame = true
                        x = parseCmRatio(p.attr("x"), 28f, 0.08f)
                        y = parseCmRatio(p.attr("y"), 15.75f, (0.14f + currentItems!!.size * 0.24f).coerceAtMost(0.75f))
                        w = parseCmRatio(p.attr("width"), 28f, 0.84f)
                        h = parseCmRatio(p.attr("height"), 15.75f, 0.24f)
                        textSb.setLength(0)
                        imgBytes = null
                    }
                    "p" -> if (inFrame && textSb.isNotEmpty()) textSb.append('\n')
                    "image" -> if (inFrame) {
                        val href = p.attr("href")?.removePrefix("/")
                        imgBytes = href?.let { entries[it] }
                    }
                }
                XmlPullParser.TEXT -> if (inFrame) textSb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "frame", "custom-shape" -> if (inFrame && currentItems != null) {
                        inFrame = false
                        val txt = textSb.toString().trim()
                        if (imgBytes != null) {
                            currentItems!!.add(SlideItem(kind = ShapeKind.RECTANGLE, x = x, y = y, w = w, h = h, imageBytes = imgBytes))
                        } else if (txt.isNotEmpty()) {
                            val isTitle = currentItems!!.isEmpty()
                            currentItems!!.add(
                                SlideItem(
                                    x = x, y = y, w = w, h = h,
                                    text = txt,
                                    bold = isTitle,
                                    fontSizeSp = if (isTitle) 26 else 18,
                                    paragraphs = txt.split('\n').map { line ->
                                        SlideParagraph(listOf(SlideRun(line, bold = isTitle, fontSizePt = if (isTitle) 26f else 18f)))
                                    },
                                )
                            )
                        }
                    }
                    "page" -> if (currentItems != null) {
                        slides.add(SlidePage(items = currentItems!!.ifEmpty { listOf(SlideItem(text = "")) }))
                        currentItems = null
                    }
                }
            }
        }
        return PresentationDocument(title = title, slides = slides.ifEmpty { listOf(SlidePage()) })
    }

    private fun writeOdp(pres: PresentationDocument, out: OutputStream) {
        val mime = "application/vnd.oasis.opendocument.presentation"
        Zip.Writer(out).use { z ->
            z.stored("mimetype", mime.toByteArray(Charsets.US_ASCII))
            val manifest = StringBuilder()
            manifest.append("""<?xml version="1.0" encoding="UTF-8"?>""")
            manifest.append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">""")
            manifest.append("""<manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/>""")
            manifest.append("""<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>""")

            val imgPaths = HashMap<Long, String>()
            var imgSeq = 1
            for (slide in pres.slides) for (item in slide.items) {
                val bytes = item.imageBytes
                if (bytes != null) {
                    val path = "Pictures/image${imgSeq++}.png"
                    imgPaths[item.id] = path
                    z.put(path, bytes)
                    manifest.append("""<manifest:file-entry manifest:full-path="$path" manifest:media-type="${item.imageMime}"/>""")
                }
            }
            manifest.append("</manifest:manifest>")
            z.put("META-INF/manifest.xml", manifest.toString())

            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.append("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" office:version="1.2">""")
            sb.append("<office:body><office:presentation>")
            pres.slides.forEachIndexed { idx, slide ->
                sb.append("""<draw:page draw:name="Slide${idx + 1}">""")
                for (item in slide.items) {
                    val xCm = "%.2fcm".format(java.util.Locale.US, item.x * 28f)
                    val yCm = "%.2fcm".format(java.util.Locale.US, item.y * 15.75f)
                    val wCm = "%.2fcm".format(java.util.Locale.US, item.w * 28f)
                    val hCm = "%.2fcm".format(java.util.Locale.US, item.h * 15.75f)
                    val imgPath = imgPaths[item.id]
                    if (imgPath != null) {
                        sb.append("""<draw:frame svg:x="$xCm" svg:y="$yCm" svg:width="$wCm" svg:height="$hCm"><draw:image xlink:href="$imgPath" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>""")
                    } else {
                        sb.append("""<draw:frame svg:x="$xCm" svg:y="$yCm" svg:width="$wCm" svg:height="$hCm"><draw:text-box>""")
                        for (line in item.fullText.split('\n')) {
                            sb.append("<text:p>${Xml.escape(line)}</text:p>")
                        }
                        sb.append("</draw:text-box></draw:frame>")
                    }
                }
                sb.append("</draw:page>")
            }
            sb.append("</office:presentation></office:body></office:document-content>")
            z.put("content.xml", sb.toString())
        }
    }

    // --------------------------------------------------------- LEGACY PPT (PowerPoint 97-2003 OLE2) READER
    private fun readLegacyPpt(input: InputStream, title: String): PresentationDocument {
        val raw = input.readBytes()
        if (!Cfb.isCfb(raw)) {
            throw DocumentException("Invalid PPT file: not an OLE2 container")
        }
        val cfb = Cfb(raw)
        val pptBytes = cfb.stream("PowerPoint Document")
            ?: throw DocumentException("Invalid PPT file: missing PowerPoint Document stream")
        val slides = parsePptRecords(pptBytes)
        return PresentationDocument(title = title, slides = slides.ifEmpty { listOf(SlidePage()) })
    }

    private fun parsePptRecords(data: ByteArray): List<SlidePage> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val slides = ArrayList<SlidePage>()
        var currentStrings = ArrayList<String>()

        fun flushSlide() {
            if (currentStrings.isNotEmpty()) {
                val items = currentStrings.mapIndexed { idx, txt ->
                    val isTitle = idx == 0
                    SlideItem(
                        x = 0.08f,
                        y = (0.12f + idx * 0.24f).coerceAtMost(0.76f),
                        w = 0.84f,
                        h = 0.22f,
                        text = txt,
                        bold = isTitle,
                        fontSizeSp = if (isTitle) 26 else 18,
                        paragraphs = txt.split('\n').map { line ->
                            SlideParagraph(listOf(SlideRun(line, bold = isTitle, fontSizePt = if (isTitle) 26f else 18f)))
                        },
                    )
                }
                slides.add(SlidePage(items = items))
                currentStrings = ArrayList()
            }
        }

        fun walk(start: Int, limit: Int) {
            var pos = start
            while (pos + 8 <= limit) {
                val verInst = bb.getShort(pos).toInt() and 0xFFFF
                val recVer = verInst and 0x0F
                val recType = bb.getShort(pos + 2).toInt() and 0xFFFF
                val recLen = bb.getInt(pos + 4)
                val body = pos + 8
                if (recLen < 0 || body + recLen > limit) break

                when {
                    recType == 0x03EE -> {
                        flushSlide()
                        walk(body, body + recLen)
                        flushSlide()
                    }
                    recType == 0x0FF0 -> walk(body, body + recLen)
                    recType == 0x03F3 -> flushSlide()
                    recType == 0x0FA0 -> {
                        val str = String(data, body, recLen, Charsets.UTF_16LE).replace('\r', '\n').trim()
                        if (str.isNotEmpty()) currentStrings.add(str)
                    }
                    recType == 0x0FA8 -> {
                        val str = String(data, body, recLen, Charsets.ISO_8859_1).replace('\r', '\n').trim()
                        if (str.isNotEmpty()) currentStrings.add(str)
                    }
                    recVer == 0x0F -> walk(body, body + recLen)
                }
                pos = body + recLen
            }
        }

        walk(0, data.size)
        flushSlide()
        return slides
    }

    // --------------------------------------------------------- HIGH-FIDELITY CANVAS & PDF RENDERING
    fun renderPdf(pres: PresentationDocument, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val pageW = pres.widthPt.roundToInt().coerceAtLeast(720)
        val pageH = pres.heightPt.roundToInt().coerceAtLeast(405)
        val pdf = PdfDocument()
        try {
            val total = pres.slides.size.coerceAtLeast(1)
            pres.slides.forEachIndexed { idx, slide ->
                onProgress((idx + 1).toFloat() / total)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
                drawSlideOnCanvas(page.canvas, slide, pageW.toFloat(), pageH.toFloat(), pres.widthPt)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun renderThumbnail(pres: PresentationDocument, widthPx: Int): Bitmap {
        val heightPx = (widthPx / pres.aspectRatio).toInt().coerceAtLeast(80)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val first = pres.slides.firstOrNull() ?: SlidePage()
        drawSlideOnCanvas(canvas, first, widthPx.toFloat(), heightPx.toFloat(), pres.widthPt)
        return bmp
    }

    fun renderSlideBitmap(
        slide: SlidePage,
        widthPx: Int,
        slideWidthPt: Float = 960f,
        slideHeightPt: Float = 540f,
        highlightQuery: String = "",
        selectedItemIdx: Int = -1,
    ): Bitmap {
        val aspect = (slideWidthPt / slideHeightPt.coerceAtLeast(1f)).coerceIn(0.5f, 3.0f)
        val heightPx = (widthPx / aspect).roundToInt().coerceAtLeast(90)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawSlideOnCanvas(
            canvas = canvas,
            slide = slide,
            w = widthPx.toFloat(),
            h = heightPx.toFloat(),
            slideWidthPt = slideWidthPt,
            highlightQuery = highlightQuery,
            selectedItemIdx = selectedItemIdx,
        )
        return bmp
    }

    /**
     * Renders an entire [SlidePage] onto [canvas] with exact proportional scaling (`w / slideWidthPt`),
     * preserving background colors, rounded cards, borders, images, multi-run styled paragraphs
     * with automatic word wrapping, vertical alignment, and tables.
     */
    fun drawSlideOnCanvas(
        canvas: Canvas,
        slide: SlidePage,
        w: Float,
        h: Float,
        slideWidthPt: Float = 960f,
        highlightQuery: String = "",
        selectedItemIdx: Int = -1,
    ) {
        canvas.drawColor(slide.backgroundArgb)
        val scale = w / slideWidthPt.coerceAtLeast(360f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        slide.items.forEachIndexed { itemIdx, item ->
            val rect = RectF(
                item.x * w,
                item.y * h,
                (item.x + item.w) * w,
                (item.y + item.h) * h,
            )

            // 1. Image item
            val bmp = item.decodeBitmap(1400)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                drawSelectionOrHighlightOverlay(canvas, rect, scale, itemIdx == selectedItemIdx, false)
                return@forEachIndexed
            }

            // 2. Table item (<a:tbl>)
            val tbl = item.table
            if (tbl != null) {
                drawSlideTable(canvas, rect, tbl, scale, highlightQuery)
                drawSelectionOrHighlightOverlay(
                    canvas = canvas,
                    rect = rect,
                    scale = scale,
                    isSelected = itemIdx == selectedItemIdx,
                    isSearchMatch = highlightQuery.isNotBlank() && item.fullText.contains(highlightQuery, ignoreCase = true),
                )
                return@forEachIndexed
            }

            // 3. Shape Fill
            val fill = item.fillArgb
            val cornerRadius = when (item.kind) {
                ShapeKind.ROUND_RECT, ShapeKind.CALLOUT -> min(rect.width(), rect.height()) * 0.08f
                else -> 0f
            }.coerceIn(4f * scale, 16f * scale)

            if (fill != null) {
                fillPaint.color = fill
                when (item.kind) {
                    ShapeKind.ELLIPSE -> canvas.drawOval(rect, fillPaint)
                    ShapeKind.ROUND_RECT, ShapeKind.CALLOUT -> canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
                    else -> canvas.drawRect(rect, fillPaint)
                }
            }

            // 4. Shape Stroke / Border / Line
            if (item.strokeColorArgb != null) {
                strokePaint.color = item.strokeColorArgb
                strokePaint.strokeWidth = max(1f, item.strokeWidthPt * scale)
                when (item.kind) {
                    ShapeKind.LINE -> {
                        if (rect.width() >= rect.height()) {
                            val cy = rect.centerY()
                            canvas.drawLine(rect.left, cy, rect.right, cy, strokePaint)
                        } else {
                            val cx = rect.centerX()
                            canvas.drawLine(cx, rect.top, cx, rect.bottom, strokePaint)
                        }
                    }
                    ShapeKind.ELLIPSE -> canvas.drawOval(rect, strokePaint)
                    ShapeKind.ROUND_RECT, ShapeKind.CALLOUT -> canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
                    else -> canvas.drawRect(rect, strokePaint)
                }
            }

            // 5. Rich Paragraphs & Text Layout
            val paras = if (item.paragraphs.isNotEmpty()) {
                item.paragraphs
            } else if (item.text.isNotEmpty()) {
                item.text.split('\n').map { line ->
                    SlideParagraph(
                        runs = listOf(
                            SlideRun(
                                text = line,
                                bold = item.bold,
                                italic = item.italic,
                                fontSizePt = item.fontSizeSp.toFloat(),
                                colorArgb = item.textColorArgb,
                            )
                        ),
                        align = item.textAlign,
                    )
                }
            } else emptyList()

            if (paras.isNotEmpty()) {
                val padL = item.padLeftPt * scale
                val padR = item.padRightPt * scale
                val padT = item.padTopPt * scale
                val padB = item.padBottomPt * scale
                val textBounds = RectF(
                    rect.left + padL,
                    rect.top + padT,
                    max(rect.left + padL + 10f, rect.right - padR),
                    max(rect.top + padT + 10f, rect.bottom - padB),
                )
                drawRichParagraphs(
                    canvas = canvas,
                    bounds = textBounds,
                    paragraphs = paras,
                    verticalAlign = item.verticalAlign,
                    scale = scale,
                    highlightQuery = highlightQuery,
                )
            }

            val matchesSearch = highlightQuery.isNotBlank() && item.fullText.contains(highlightQuery, ignoreCase = true)
            drawSelectionOrHighlightOverlay(canvas, rect, scale, itemIdx == selectedItemIdx, matchesSearch)
        }
    }

    private fun drawSlideTable(
        canvas: Canvas,
        tableRect: RectF,
        table: SlideTable,
        scale: Float,
        highlightQuery: String,
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFCBD5E1.toInt()
            strokeWidth = max(1f, 0.8f * scale)
        }
        var curY = tableRect.top
        val totalH = tableRect.height()
        val totalW = tableRect.width()

        for (row in table.rows) {
            val rowH = row.heightFraction * totalH
            var curX = tableRect.left
            row.cells.forEachIndexed { cIdx, cell ->
                val colFrac = table.colFractions.getOrElse(cIdx) { 1f / row.cells.size.coerceAtLeast(1) }
                val cellW = colFrac * totalW
                val cellRect = RectF(curX, curY, curX + cellW, curY + rowH)

                cell.fillArgb?.let {
                    fillPaint.color = it
                    canvas.drawRect(cellRect, fillPaint)
                }
                canvas.drawRect(cellRect, borderPaint)

                if (cell.paragraphs.isNotEmpty()) {
                    val padX = 3.5f * scale
                    val padY = 2.0f * scale
                    val inner = RectF(
                        cellRect.left + padX,
                        cellRect.top + padY,
                        max(cellRect.left + padX + 4f, cellRect.right - padX),
                        max(cellRect.top + padY + 4f, cellRect.bottom - padY),
                    )
                    drawRichParagraphs(
                        canvas = canvas,
                        bounds = inner,
                        paragraphs = cell.paragraphs,
                        verticalAlign = cell.verticalAlign,
                        scale = scale,
                        highlightQuery = highlightQuery,
                    )
                }
                curX += cellW
            }
            curY += rowH
        }
    }

    private data class StyledToken(
        val text: String,
        val paint: Paint,
        val widthPx: Float,
        val fontSizePx: Float,
        val isNewline: Boolean = false,
    )

    private data class VisualLine(
        val tokens: List<StyledToken>,
        val align: SlideTextAlign,
        val indentPx: Float,
        val maxFontPx: Float,
        val spaceBeforePx: Float,
        val spaceAfterPx: Float,
        val lineHeightMultiplier: Float = 1.18f,
    ) {
        val totalWidthPx: Float get() = tokens.sumOf { it.widthPx.toDouble() }.toFloat()
        val lineHeightPx: Float get() = maxFontPx * lineHeightMultiplier
    }

    private fun drawRichParagraphs(
        canvas: Canvas,
        bounds: RectF,
        paragraphs: List<SlideParagraph>,
        verticalAlign: SlideVerticalAlign,
        scale: Float,
        highlightQuery: String,
    ) {
        val availWidth = bounds.width().coerceAtLeast(16f)
        val availHeight = bounds.height().coerceAtLeast(12f)

        fun buildVisualLines(fontFactor: Float, spacingFactor: Float, lineMult: Float): List<VisualLine> {
            val visualLines = ArrayList<VisualLine>()
            for (para in paragraphs) {
                val indentPx = para.indentLevel * 12f * scale * fontFactor
                val lineMaxW = (availWidth - indentPx).coerceAtLeast(16f)
                val tokens = ArrayList<StyledToken>()

                // Optional bullet prefix
                if (para.bullet.isNotEmpty()) {
                    val firstRun = para.runs.firstOrNull() ?: SlideRun("")
                    val p = createRunPaint(firstRun, scale * fontFactor)
                    val bTxt = "${para.bullet} "
                    tokens.add(StyledToken(bTxt, p, p.measureText(bTxt), firstRun.fontSizePt * scale * fontFactor))
                }

                for (run in para.runs) {
                    val p = createRunPaint(run, scale * fontFactor)
                    val fPx = (run.fontSizePt * scale * fontFactor).coerceAtLeast(4.5f)
                    val segments = run.text.split('\n')
                    segments.forEachIndexed { sIdx, seg ->
                        if (sIdx > 0) {
                            tokens.add(StyledToken("", p, 0f, fPx, isNewline = true))
                        }
                        // Split into words while preserving spaces
                        val words = splitIntoWordsPreservingSpaces(seg)
                        for (w in words) {
                            tokens.add(StyledToken(w, p, p.measureText(w), fPx))
                        }
                    }
                }

                if (tokens.isEmpty()) continue

                var currentLine = ArrayList<StyledToken>()
                var currentW = 0f
                var isFirstLineOfPara = true

                fun flushLine(isLastOfPara: Boolean) {
                    if (currentLine.isEmpty()) return
                    // Trim trailing whitespace token on wrapped lines
                    while (currentLine.size > 1 && currentLine.last().text.isBlank()) {
                        currentLine.removeAt(currentLine.lastIndex)
                    }
                    val maxFont = currentLine.maxOfOrNull { it.fontSizePx } ?: (14f * scale * fontFactor)
                    visualLines.add(
                        VisualLine(
                            tokens = ArrayList(currentLine),
                            align = para.align,
                            indentPx = indentPx,
                            maxFontPx = maxFont,
                            spaceBeforePx = if (isFirstLineOfPara) para.spaceBeforePt * scale * spacingFactor else 0f,
                            spaceAfterPx = if (isLastOfPara) para.spaceAfterPt * scale * spacingFactor else 0f,
                            lineHeightMultiplier = lineMult,
                        )
                    )
                    currentLine = ArrayList()
                    currentW = 0f
                    isFirstLineOfPara = false
                }

                for (tok in tokens) {
                    if (tok.isNewline) {
                        flushLine(isLastOfPara = false)
                        continue
                    }
                    if (currentLine.isEmpty() && tok.text.isBlank()) {
                        continue
                    }
                    if (currentLine.isNotEmpty() && currentW + tok.widthPx > lineMaxW * 1.01f) {
                        flushLine(isLastOfPara = false)
                        if (tok.text.isBlank()) continue
                    }
                    currentLine.add(tok)
                    currentW += tok.widthPx
                }
                flushLine(isLastOfPara = true)
            }
            return visualLines
        }

        // Iteratively auto-fit font size and paragraph spacing so all paragraphs fit comfortably inside the box
        var fontFactor = 1.0f
        var spacingFactor = 1.0f
        var lineMult = 1.18f
        var lines = buildVisualLines(fontFactor, spacingFactor, lineMult)
        var totalTextH = lines.sumOf { (it.spaceBeforePx + it.lineHeightPx + it.spaceAfterPx).toDouble() }.toFloat()

        var step = 0
        while (totalTextH > availHeight * 1.02f && step < 10) {
            step++
            fontFactor = (fontFactor * 0.91f).coerceAtLeast(0.45f)
            spacingFactor = (spacingFactor * 0.80f).coerceAtLeast(0.25f)
            lineMult = (lineMult - 0.01f).coerceAtLeast(1.10f)
            lines = buildVisualLines(fontFactor, spacingFactor, lineMult)
            totalTextH = lines.sumOf { (it.spaceBeforePx + it.lineHeightPx + it.spaceAfterPx).toDouble() }.toFloat()
            if (fontFactor <= 0.45f) break
        }

        var curY = when (verticalAlign) {
            SlideVerticalAlign.MIDDLE -> bounds.top + max(0f, (availHeight - totalTextH) / 2f)
            SlideVerticalAlign.BOTTOM -> bounds.bottom - min(availHeight, totalTextH)
            SlideVerticalAlign.TOP -> bounds.top
        }

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x99FFEC99.toInt()
        }

        // Render every single line without breaking early so zero slide text is ever truncated
        for (line in lines) {
            curY += line.spaceBeforePx
            val baselineY = curY + line.maxFontPx * 0.86f

            var curX = when (line.align) {
                SlideTextAlign.CENTER -> bounds.left + max(0f, (availWidth - line.totalWidthPx) / 2f)
                SlideTextAlign.RIGHT -> bounds.right - line.totalWidthPx
                SlideTextAlign.LEFT -> bounds.left + line.indentPx
            }

            for (tok in line.tokens) {
                if (tok.text.isNotEmpty()) {
                    if (highlightQuery.isNotBlank() && tok.text.contains(highlightQuery, ignoreCase = true)) {
                        canvas.drawRect(
                            curX,
                            baselineY - tok.fontSizePx * 0.86f,
                            curX + tok.widthPx,
                            baselineY + tok.fontSizePx * 0.22f,
                            highlightPaint,
                        )
                    }
                    canvas.drawText(tok.text, curX, baselineY, tok.paint)
                    curX += tok.widthPx
                }
            }
            curY += line.lineHeightPx + line.spaceAfterPx
        }
    }

    private fun createRunPaint(run: SlideRun, scale: Float): Paint {
        val tfStyle = when {
            run.bold && run.italic -> Typeface.BOLD_ITALIC
            run.bold -> Typeface.BOLD
            run.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = run.colorArgb
            textSize = (run.fontSizePt * scale).coerceAtLeast(4.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, tfStyle)
            isUnderlineText = run.underline
        }
    }

    private fun splitIntoWordsPreservingSpaces(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var lastWasSpace = text[0].isWhitespace()
        for (ch in text) {
            val isSpace = ch.isWhitespace()
            if (isSpace != lastWasSpace && sb.isNotEmpty()) {
                result.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(ch)
            lastWasSpace = isSpace
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    private fun drawSelectionOrHighlightOverlay(
        canvas: Canvas,
        rect: RectF,
        scale: Float,
        isSelected: Boolean,
        isSearchMatch: Boolean,
    ) {
        if (!isSelected && !isSearchMatch) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, 2.2f * scale)
            color = if (isSearchMatch) 0xFFF59E0B.toInt() else 0xFF2563EB.toInt()
        }
        canvas.drawRoundRect(rect, 6f * scale, 6f * scale, p)
    }
}
