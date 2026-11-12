package app.slate.sheet

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

enum class CellAlign { LEFT, CENTER, RIGHT }

data class CellFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: CellAlign = CellAlign.LEFT,
    val bgArgb: Int? = null,
)

data class CellData(
    val raw: String = "",
    val format: CellFormat = CellFormat(),
) {
    val isFormula: Boolean get() = raw.startsWith("=") && raw.length > 1
}

data class SheetTab(
    val name: String,
    val rows: Int = 30,
    val cols: Int = 10,
    val cells: Map<Long, CellData> = emptyMap(), // key = packKey(r, c)
    val colWidthsDp: Map<Int, Int> = emptyMap(),
    val rowHeightsDp: Map<Int, Int> = emptyMap(),
) {
    fun cell(r: Int, c: Int): CellData = cells[packKey(r, c)] ?: CellData()

    fun withCell(r: Int, c: Int, data: CellData): SheetTab {
        val next = HashMap(cells)
        if (data.raw.isEmpty() && data.format == CellFormat()) next.remove(packKey(r, c))
        else next[packKey(r, c)] = data
        return copy(
            rows = maxOf(rows, r + 1),
            cols = maxOf(cols, c + 1),
            cells = next,
        )
    }

    fun withCellRaw(r: Int, c: Int, raw: String): SheetTab {
        val cur = cell(r, c)
        return withCell(r, c, cur.copy(raw = raw))
    }

    fun withCellFormat(r: Int, c: Int, transform: (CellFormat) -> CellFormat): SheetTab {
        val cur = cell(r, c)
        return withCell(r, c, cur.copy(format = transform(cur.format)))
    }

    fun insertRowAt(atRow: Int): SheetTab {
        val moved = HashMap<Long, CellData>()
        for ((k, v) in cells) {
            val r = unpackRow(k)
            val c = unpackCol(k)
            moved[packKey(if (r >= atRow) r + 1 else r, c)] = v
        }
        return copy(rows = rows + 1, cells = moved)
    }

    fun deleteRowAt(atRow: Int): SheetTab {
        if (rows <= 1) return this
        val moved = HashMap<Long, CellData>()
        for ((k, v) in cells) {
            val r = unpackRow(k)
            val c = unpackCol(k)
            when {
                r < atRow -> moved[k] = v
                r > atRow -> moved[packKey(r - 1, c)] = v
            }
        }
        return copy(rows = (rows - 1).coerceAtLeast(1), cells = moved)
    }

    fun insertColAt(atCol: Int): SheetTab {
        val moved = HashMap<Long, CellData>()
        for ((k, v) in cells) {
            val r = unpackRow(k)
            val c = unpackCol(k)
            moved[packKey(r, if (c >= atCol) c + 1 else c)] = v
        }
        return copy(cols = cols + 1, cells = moved)
    }

    fun deleteColAt(atCol: Int): SheetTab {
        if (cols <= 1) return this
        val moved = HashMap<Long, CellData>()
        for ((k, v) in cells) {
            val r = unpackRow(k)
            val c = unpackCol(k)
            when {
                c < atCol -> moved[k] = v
                c > atCol -> moved[packKey(r, c - 1)] = v
            }
        }
        return copy(cols = (cols - 1).coerceAtLeast(1), cells = moved)
    }

    fun sortByColumn(col: Int, ascending: Boolean, keepHeaderRow: Boolean = true): SheetTab {
        val startRow = if (keepHeaderRow && rows > 1) 1 else 0
        val maxUsedRow = (cells.keys.maxOfOrNull { unpackRow(it) } ?: 0).coerceAtLeast(startRow)
        if (maxUsedRow <= startRow) return this

        val rowIndices = (startRow..maxUsedRow).toList()
        val sortedIndices = rowIndices.sortedWith { r1, r2 ->
            val v1 = displayValue(r1, col)
            val v2 = displayValue(r2, col)
            val n1 = v1.toDoubleOrNull()
            val n2 = v2.toDoubleOrNull()
            val cmp = if (n1 != null && n2 != null) n1.compareTo(n2)
            else v1.lowercase(Locale.ROOT).compareTo(v2.lowercase(Locale.ROOT))
            if (ascending) cmp else -cmp
        }

        val next = HashMap<Long, CellData>()
        for ((k, v) in cells) {
            val r = unpackRow(k)
            if (r < startRow || r > maxUsedRow) next[k] = v
        }
        sortedIndices.forEachIndexed { idx, oldRow ->
            val targetRow = startRow + idx
            for (c in 0 until cols) {
                val cellData = cells[packKey(oldRow, c)]
                if (cellData != null) next[packKey(targetRow, c)] = cellData
            }
        }
        return copy(cells = next)
    }

    fun displayValue(r: Int, c: Int, visited: Set<Long> = emptySet()): String {
        val cd = cell(r, c)
        if (!cd.isFormula) return cd.raw
        val key = packKey(r, c)
        if (key in visited) return "#CYCLE!"
        return FormulaEvaluator.evaluate(cd.raw.substring(1).trim(), this, visited + key)
    }

    fun numericSelectionSummary(): SelectionStats {
        var count = 0
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for ((k, _) in cells) {
            val r = unpackRow(k)
            val c = unpackCol(k)
            val v = displayValue(r, c).toDoubleOrNull()
            if (v != null) {
                count++
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        return SelectionStats(
            count = count,
            sum = sum,
            avg = if (count > 0) sum / count else 0.0,
            min = if (count > 0) min else 0.0,
            max = if (count > 0) max else 0.0,
        )
    }

    fun columnStats(col: Int): SelectionStats {
        var count = 0
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (r in 0 until rows) {
            val v = displayValue(r, col).toDoubleOrNull()
            if (v != null) {
                count++
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        return if (count > 0) {
            SelectionStats(count, sum, sum / count, min, max)
        } else {
            numericSelectionSummary()
        }
    }

    companion object {
        fun packKey(r: Int, c: Int): Long = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
        fun unpackRow(key: Long): Int = (key ushr 32).toInt()
        fun unpackCol(key: Long): Int = key.toInt()

        fun cellRef(r: Int, c: Int): String = "${colName(c)}${r + 1}"

        fun colName(col: Int): String {
            var n = col
            val sb = StringBuilder()
            while (n >= 0) {
                sb.insert(0, ('A' + (n % 26)))
                n = n / 26 - 1
            }
            return sb.toString()
        }

        fun parseCellRef(ref: String): Pair<Int, Int>? {
            val clean = ref.trim().replace("$", "").uppercase(Locale.ROOT)
            var i = 0
            var col = 0
            while (i < clean.length && clean[i] in 'A'..'Z') {
                col = col * 26 + (clean[i] - 'A' + 1)
                i++
            }
            if (i == 0 || i == clean.length) return null
            val row = clean.substring(i).toIntOrNull() ?: return null
            if (row < 1) return null
            return (row - 1) to (col - 1)
        }
    }
}

data class SelectionStats(
    val count: Int,
    val sum: Double,
    val avg: Double,
    val min: Double,
    val max: Double,
)

data class WorkbookDocument(
    val sheets: List<SheetTab> = listOf(SheetTab("Sheet1")),
    val activeSheet: Int = 0,
) {
    val current: SheetTab get() = sheets.getOrElse(activeSheet.coerceIn(0, sheets.lastIndex)) { SheetTab("Sheet1") }

    fun updateActive(transform: (SheetTab) -> SheetTab): WorkbookDocument {
        val idx = activeSheet.coerceIn(0, sheets.lastIndex)
        val next = sheets.toMutableList().apply { set(idx, transform(get(idx))) }
        return copy(sheets = next)
    }
}

/**
 * Recursive-descent spreadsheet formula evaluator supporting:
 *  - Functions: SUM, AVERAGE, AVG, MIN, MAX, COUNT, COUNTA, ABS, ROUND, IF
 *  - Cell ranges: A1:C10
 *  - Cell references: A1, $B$2
 *  - Operators: +, -, *, /, ^, parentheses, comparisons (=, <>, <, <=, >, >=)
 */
object FormulaEvaluator {

    fun evaluate(expr: String, sheet: SheetTab, visited: Set<Long>): String = runCatching {
        val v = Parser(expr, sheet, visited).parseComparison()
        formatNumber(v)
    }.getOrElse { "#ERR" }

    fun formatNumber(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "#NUM!"
        val rounded = (v * 1e8).roundToLong() / 1e8
        val asLong = rounded.toLong()
        return if (abs(rounded - asLong) < 1e-9) asLong.toString()
        else rounded.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    private class Parser(
        private val s: String,
        private val sheet: SheetTab,
        private val visited: Set<Long>,
    ) {
        private var pos = 0

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseComparison(): Double {
            var left = parseAddSub()
            skipWs()
            if (pos < s.length) {
                val op = when {
                    s.startsWith("<=", pos) -> { pos += 2; "<=" }
                    s.startsWith(">=", pos) -> { pos += 2; ">=" }
                    s.startsWith("<>", pos) -> { pos += 2; "<>" }
                    s[pos] == '=' -> { pos++; "=" }
                    s[pos] == '<' -> { pos++; "<" }
                    s[pos] == '>' -> { pos++; ">" }
                    else -> null
                }
                if (op != null) {
                    val right = parseAddSub()
                    val res = when (op) {
                        "=" -> abs(left - right) < 1e-9
                        "<>" -> abs(left - right) >= 1e-9
                        "<" -> left < right
                        "<=" -> left <= right
                        ">" -> left > right
                        ">=" -> left >= right
                        else -> false
                    }
                    left = if (res) 1.0 else 0.0
                }
            }
            return left
        }

        private fun parseAddSub(): Double {
            var v = parseMulDiv()
            while (true) {
                skipWs()
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                    val op = s[pos++]
                    val r = parseMulDiv()
                    v = if (op == '+') v + r else v - r
                } else break
            }
            return v
        }

        private fun parseMulDiv(): Double {
            var v = parsePower()
            while (true) {
                skipWs()
                if (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                    val op = s[pos++]
                    val r = parsePower()
                    v = if (op == '*') v * r else if (r == 0.0) Double.NaN else v / r
                } else break
            }
            return v
        }

        private fun parsePower(): Double {
            var v = parseUnary()
            skipWs()
            if (pos < s.length && s[pos] == '^') {
                pos++
                val exp = parsePower()
                v = v.pow(exp)
            }
            return v
        }

        private fun parseUnary(): Double {
            skipWs()
            if (pos < s.length && s[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (pos < s.length && s[pos] == '+') {
                pos++
                return parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (pos >= s.length) return 0.0
            if (s[pos] == '(') {
                pos++
                val v = parseComparison()
                skipWs()
                if (pos < s.length && s[pos] == ')') pos++
                return v
            }
            if (s[pos].isDigit() || s[pos] == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDoubleOrNull() ?: 0.0
            }
            // Identifier: function call or cell reference
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '$')) pos++
            val token = s.substring(start, pos).uppercase(Locale.ROOT)
            skipWs()
            if (pos < s.length && s[pos] == '(') {
                pos++ // consume '('
                return evalFunction(token)
            }
            val rc = SheetTab.parseCellRef(token) ?: return 0.0
            val str = sheet.displayValue(rc.first, rc.second, visited).trim()
            return str.toDoubleOrNull() ?: 0.0
        }

        private fun evalFunction(fn: String): Double {
            if (fn == "IF") {
                val cond = parseComparison()
                skipWs()
                if (pos < s.length && (s[pos] == ',' || s[pos] == ';')) pos++
                val whenTrue = parseComparison()
                skipWs()
                val whenFalse = if (pos < s.length && (s[pos] == ',' || s[pos] == ';')) {
                    pos++
                    parseComparison()
                } else 0.0
                skipWs()
                if (pos < s.length && s[pos] == ')') pos++
                return if (cond != 0.0) whenTrue else whenFalse
            }

            val nums = ArrayList<Double>()
            var nonEmptyCount = 0
            while (pos < s.length && s[pos] != ')') {
                skipWs()
                val save = pos
                val range = tryParseRange()
                if (range != null) {
                    for (r in range.first.first..range.second.first) {
                        for (c in range.first.second..range.second.second) {
                            val txt = sheet.displayValue(r, c, visited).trim()
                            if (txt.isNotEmpty()) nonEmptyCount++
                            txt.toDoubleOrNull()?.let { nums.add(it) }
                        }
                    }
                } else {
                    pos = save
                    val v = parseComparison()
                    nums.add(v)
                    nonEmptyCount++
                }
                skipWs()
                if (pos < s.length && (s[pos] == ',' || s[pos] == ';')) pos++
            }
            if (pos < s.length && s[pos] == ')') pos++

            return when (fn) {
                "SUM" -> nums.sum()
                "AVERAGE", "AVG" -> if (nums.isEmpty()) 0.0 else nums.average()
                "MIN" -> nums.minOrNull() ?: 0.0
                "MAX" -> nums.maxOrNull() ?: 0.0
                "COUNT" -> nums.size.toDouble()
                "COUNTA" -> nonEmptyCount.toDouble()
                "ABS" -> abs(nums.firstOrNull() ?: 0.0)
                "ROUND" -> {
                    val x = nums.getOrElse(0) { 0.0 }
                    val digits = nums.getOrElse(1) { 0.0 }.toInt()
                    val factor = 10.0.pow(digits)
                    (x * factor).roundToLong() / factor
                }
                else -> 0.0
            }
        }

        private fun tryParseRange(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
            skipWs()
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '$')) pos++
            val firstTok = s.substring(start, pos)
            skipWs()
            if (pos >= s.length || s[pos] != ':') return null
            pos++
            skipWs()
            val start2 = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '$')) pos++
            val secondTok = s.substring(start2, pos)
            val a = SheetTab.parseCellRef(firstTok) ?: return null
            val b = SheetTab.parseCellRef(secondTok) ?: return null
            val r1 = minOf(a.first, b.first)
            val r2 = maxOf(a.first, b.first)
            val c1 = minOf(a.second, b.second)
            val c2 = maxOf(a.second, b.second)
            return (r1 to c1) to (r2 to c2)
        }
    }
}
