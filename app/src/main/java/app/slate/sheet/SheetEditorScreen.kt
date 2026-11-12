package app.slate.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.SlateApp
import app.slate.core.DocFormat
import app.slate.core.OpenDoc
import app.slate.core.UndoStack
import app.slate.core.io.openSource
import app.slate.core.io.sourceFormat
import app.slate.core.parseTarget
import app.slate.ui.components.ErrorState
import app.slate.ui.components.LoadingState
import app.slate.ui.components.TextPromptDialog
import app.slate.ui.editor.DocumentSearchBar
import app.slate.ui.editor.EditorScaffold
import app.slate.ui.editor.ToolButton
import app.slate.ui.editor.rememberSaveController
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class SheetSearchMatch(
    val sheetIndex: Int,
    val row: Int,
    val col: Int,
    val preview: String,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetEditorScreen(doc: OpenDoc, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val history = remember { UndoStack(WorkbookDocument()) }
    var wb by remember { mutableStateOf(history.current) }

    // CRITICAL: Spreadsheets ALWAYS open in Read-Only VIEW MODE by default.
    var isEditMode by remember { mutableStateOf(false) }
    var editSnapshot by remember { mutableStateOf<WorkbookDocument?>(null) }

    var selRow by remember { mutableIntStateOf(0) }
    var selCol by remember { mutableIntStateOf(0) }

    // Zoom state (0.5f = 50% to 2.5f = 250%)
    var zoom by remember { mutableFloatStateOf(1f) }
    var freezeTopRow by remember { mutableStateOf(false) }

    // Cross-sheet Search state
    var showSearchBar by remember { mutableStateOf(!doc.query.isNullOrBlank()) }
    var searchQuery by remember { mutableStateOf(doc.query.orEmpty()) }
    var activeMatchIdx by remember { mutableIntStateOf(0) }

    // Optional row filter (separate from search)
    var filterColumnOnly by remember { mutableStateOf(false) }

    var showRenameSheetDialog by remember { mutableStateOf(false) }
    var showSizeDialog by remember { mutableStateOf(false) }
    var showFormulaSheet by remember { mutableStateOf(false) }

    val formulaFocusRequester = remember { FocusRequester() }

    val writable = remember { listOf(DocFormat.XLSX, DocFormat.ODS, DocFormat.CSV) }
    val saveController = rememberSaveController(doc, writable) { fmt, out ->
        SheetFormatCodecs.write(wb, fmt, out)
    }

    val horizScroll = rememberScrollState()
    val vertScroll = rememberLazyListState()

    fun commit(next: WorkbookDocument) {
        history.push(next)
        wb = history.current
        saveController.markDirty()
    }

    LaunchedEffect(doc.uri, doc.recoveryFile) {
        loading = true
        loadError = null
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                doc.openSource(context).use { SheetFormatCodecs.read(it, doc.sourceFormat()) }
            }
        }
        loaded.onSuccess { book ->
            history.reset(book)
            wb = book
            val targetMap = parseTarget(doc.target)
            val sheetIdx = targetMap["sheet"]?.toIntOrNull() ?: doc.lastPosition
            if (sheetIdx in book.sheets.indices) {
                wb = book.copy(activeSheet = sheetIdx)
            }
            targetMap["row"]?.toIntOrNull()?.let { r -> selRow = r.coerceAtLeast(0) }
            targetMap["col"]?.toIntOrNull()?.let { c -> selCol = c.coerceAtLeast(0) }
            loading = false
        }.onFailure {
            loadError = it.message ?: "Unable to read spreadsheet"
            loading = false
        }
    }

    LaunchedEffect(wb.activeSheet, wb.sheets.size) {
        if (!loading && wb.sheets.isNotEmpty()) {
            app.recents.setPosition(doc.uri, wb.activeSheet, wb.sheets.size)
        }
    }

    // Compute all matches across ALL sheets in the workbook
    val searchMatches = remember(wb, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList()
        else buildList {
            wb.sheets.forEachIndexed { sIdx, tab ->
                for (r in 0 until tab.rows) {
                    for (c in 0 until tab.cols) {
                        val disp = tab.displayValue(r, c)
                        val raw = tab.cell(r, c).raw
                        if (disp.contains(q, ignoreCase = true) || raw.contains(q, ignoreCase = true)) {
                            add(SheetSearchMatch(sIdx, r, c, disp.ifEmpty { raw }))
                        }
                    }
                }
            }
        }
    }

    // Scroll to a specific cell (switching sheet if needed)
    fun navigateToMatch(match: SheetSearchMatch) {
        if (wb.activeSheet != match.sheetIndex) {
            wb = wb.copy(activeSheet = match.sheetIndex)
        }
        selRow = match.row
        selCol = match.col
        scope.launch {
            val targetListRow = if (freezeTopRow && match.row > 0) (match.row - 1) else match.row
            runCatching { vertScroll.animateScrollToItem(targetListRow.coerceAtLeast(0)) }
            val sheetTab = wb.sheets.getOrNull(match.sheetIndex)
            if (sheetTab != null) {
                var pxOffset = 0f
                for (c in 0 until match.col) {
                    val colW = (sheetTab.colWidthsDp[c] ?: 104) * zoom
                    pxOffset += with(density) { colW.dp.toPx() }
                }
                val targetScroll = (pxOffset - with(density) { 80.dp.toPx() }).roundToInt().coerceAtLeast(0)
                runCatching { horizScroll.animateScrollTo(targetScroll) }
            }
        }
    }

    // Automatically jump to first match when search query changes
    LaunchedEffect(searchQuery, searchMatches.size) {
        if (searchQuery.isNotBlank() && searchMatches.isNotEmpty()) {
            activeMatchIdx = 0
            navigateToMatch(searchMatches[0])
        } else {
            activeMatchIdx = 0
        }
    }

    if (loading) {
        LoadingState("Opening ${doc.name}…")
        return
    }
    if (loadError != null) {
        ErrorState("Couldn't open spreadsheet", loadError!!, onClose = onClose)
        return
    }

    val sheet = wb.current
    selRow = selRow.coerceIn(0, (sheet.rows - 1).coerceAtLeast(0))
    selCol = selCol.coerceIn(0, (sheet.cols - 1).coerceAtLeast(0))
    val activeCell = sheet.cell(selRow, selCol)
    val activeCellDisplay = sheet.displayValue(selRow, selCol)
    val cellRefLabel = SheetTab.cellRef(selRow, selCol)
    val stats = remember(sheet, selCol) { sheet.columnStats(selCol) }

    fun updateActiveFormat(transform: (CellFormat) -> CellFormat) {
        commit(wb.updateActive { it.withCellFormat(selRow, selCol, transform) })
    }

    val subtitle = buildString {
        if (doc.format == DocFormat.XLS) append("XLS • ")
        append("${sheet.name} (${sheet.rows}×${sheet.cols})")
        if (saveController.dirty) append(" • Unsaved")
    }

    val zoomPct = (zoom * 100).roundToInt()
    val currentMatch = searchMatches.getOrNull(activeMatchIdx)

    EditorScaffold(
        title = doc.name,
        controller = saveController,
        onClose = onClose,
        canUndo = history.canUndo,
        canRedo = history.canRedo,
        onUndo = { wb = history.undo(); saveController.markDirty() },
        onRedo = { wb = history.redo(); saveController.markDirty() },
        exportFormats = listOf(DocFormat.PDF),
        subtitle = subtitle,
        isEditMode = isEditMode,
        onToggleEditMode = {
            if (!isEditMode) {
                editSnapshot = wb
                isEditMode = true
            } else {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                isEditMode = false
            }
        },
        onCancelEditMode = {
            editSnapshot?.let { snap ->
                history.reset(snap)
                wb = snap
                saveController.discard()
            }
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            isEditMode = false
        },
        onToggleSearch = {
            showSearchBar = !showSearchBar
            if (!showSearchBar) {
                searchQuery = ""
            }
        },
        isSearchActive = showSearchBar,
        zoomPercent = zoomPct,
        onZoomIn = { zoom = (zoom + 0.25f).coerceAtMost(2.5f) },
        onZoomOut = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) },
        onResetZoom = { zoom = 1f },
        searchBar = {
            val matchSheetName = currentMatch?.let { wb.sheets.getOrNull(it.sheetIndex)?.name }
            DocumentSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search text or numbers across all sheets…",
                matchCount = searchMatches.size,
                currentMatchIndex = activeMatchIdx,
                extraInfo = matchSheetName,
                onPrevMatch = {
                    if (searchMatches.isNotEmpty()) {
                        activeMatchIdx = if (activeMatchIdx - 1 < 0) searchMatches.lastIndex else activeMatchIdx - 1
                        navigateToMatch(searchMatches[activeMatchIdx])
                    }
                },
                onNextMatch = {
                    if (searchMatches.isNotEmpty()) {
                        activeMatchIdx = (activeMatchIdx + 1) % searchMatches.size
                        navigateToMatch(searchMatches[activeMatchIdx])
                    }
                },
                onClose = {
                    showSearchBar = false
                    searchQuery = ""
                },
            )
        },
        menu = { dismiss ->
            DropdownMenuItem(
                text = { Text(if (freezeTopRow) "Unfreeze top row" else "Freeze top header row") },
                leadingIcon = { Icon(Icons.Outlined.ViewHeadline, null) },
                onClick = { dismiss(); freezeTopRow = !freezeTopRow },
            )
            DropdownMenuItem(
                text = { Text("Adjust column width / row height…") },
                leadingIcon = { Icon(Icons.Outlined.Straighten, null) },
                onClick = { dismiss(); showSizeDialog = true },
            )
            DropdownMenuItem(
                text = { Text("Export as PDF…") },
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                onClick = { dismiss(); saveController.saveAs(DocFormat.PDF) },
            )
            if (isEditMode) {
                DropdownMenuItem(
                    text = { Text("Insert formula…") },
                    leadingIcon = { Icon(Icons.Outlined.Functions, null) },
                    onClick = { dismiss(); showFormulaSheet = true },
                )
                DropdownMenuItem(
                    text = { Text("Sort by Column ${SheetTab.colName(selCol)} (A → Z)") },
                    onClick = { dismiss(); commit(wb.updateActive { it.sortByColumn(selCol, ascending = true) }) },
                )
                DropdownMenuItem(
                    text = { Text("Sort by Column ${SheetTab.colName(selCol)} (Z → A)") },
                    onClick = { dismiss(); commit(wb.updateActive { it.sortByColumn(selCol, ascending = false) }) },
                )
                DropdownMenuItem(
                    text = { Text("Add new sheet") },
                    onClick = {
                        dismiss()
                        val nextName = "Sheet${wb.sheets.size + 1}"
                        val nextList = wb.sheets + SheetTab(nextName)
                        commit(wb.copy(sheets = nextList, activeSheet = nextList.lastIndex))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename current sheet…") },
                    onClick = { dismiss(); showRenameSheetDialog = true },
                )
                if (wb.sheets.size > 1) {
                    DropdownMenuItem(
                        text = { Text("Delete current sheet", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            dismiss()
                            val idx = wb.activeSheet
                            val nextList = wb.sheets.filterIndexed { i, _ -> i != idx }
                            commit(wb.copy(sheets = nextList, activeSheet = (idx - 1).coerceAtLeast(0)))
                        },
                    )
                }
            }
        },
        toolbar = {
            ToolButton(
                icon = Icons.Outlined.FormatBold,
                description = "Bold",
                selected = activeCell.format.bold,
                onClick = { updateActiveFormat { it.copy(bold = !it.bold) } },
            )
            ToolButton(
                icon = Icons.Outlined.FormatItalic,
                description = "Italic",
                selected = activeCell.format.italic,
                onClick = { updateActiveFormat { it.copy(italic = !it.italic) } },
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.FormatAlignLeft,
                description = "Align left",
                selected = activeCell.format.align == CellAlign.LEFT,
                onClick = { updateActiveFormat { it.copy(align = CellAlign.LEFT) } },
            )
            ToolButton(
                icon = Icons.Outlined.FormatAlignCenter,
                description = "Align center",
                selected = activeCell.format.align == CellAlign.CENTER,
                onClick = { updateActiveFormat { it.copy(align = CellAlign.CENTER) } },
            )
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.FormatAlignRight,
                description = "Align right",
                selected = activeCell.format.align == CellAlign.RIGHT,
                onClick = { updateActiveFormat { it.copy(align = CellAlign.RIGHT) } },
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            ToolButton(
                icon = Icons.Outlined.Functions,
                description = "Insert formula",
                onClick = { showFormulaSheet = true },
            )
            AssistChip(
                onClick = {
                    commit(wb.updateActive { it.insertRowAt(selRow + 1) })
                    selRow++
                },
                label = { Text("+ Row") },
                modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
            )
            AssistChip(
                onClick = {
                    commit(wb.updateActive { it.deleteRowAt(selRow) })
                    selRow = selRow.coerceAtMost((wb.current.rows - 1).coerceAtLeast(0))
                },
                label = { Text("- Row") },
                modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
            )
            AssistChip(
                onClick = {
                    commit(wb.updateActive { it.insertColAt(selCol + 1) })
                    selCol++
                },
                label = { Text("+ Col") },
                modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
            )
            AssistChip(
                onClick = {
                    commit(wb.updateActive { it.deleteColAt(selCol) })
                    selCol = selCol.coerceAtMost((wb.current.cols - 1).coerceAtLeast(0))
                },
                label = { Text("- Col") },
                modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
            )
            ToolButton(
                icon = Icons.Outlined.SortByAlpha,
                description = "Sort column ascending",
                onClick = { commit(wb.updateActive { it.sortByColumn(selCol, ascending = true) }) },
            )
            ToolButton(
                icon = Icons.Outlined.Straighten,
                description = "Adjust width/height",
                onClick = { showSizeDialog = true },
            )
        },
        bottomBar = {
            Surface(color = Slate.colors.surface, border = BorderStroke(1.dp, Slate.colors.border)) {
                Column(Modifier.navigationBarsPadding()) {
                    if (stats.count > 0) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Slate.colors.surfaceAlt)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Col ${SheetTab.colName(selCol)}:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text("Count: ${stats.count}", style = MaterialTheme.typography.labelMedium, color = Slate.colors.textSecondary)
                            Text("Sum: ${FormulaEvaluator.formatNumber(stats.sum)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text("Avg: ${FormulaEvaluator.formatNumber(stats.avg)}", style = MaterialTheme.typography.labelMedium, color = Slate.colors.textSecondary)
                            Text("Min: ${FormulaEvaluator.formatNumber(stats.min)}", style = MaterialTheme.typography.labelMedium, color = Slate.colors.textSecondary)
                            Text("Max: ${FormulaEvaluator.formatNumber(stats.max)}", style = MaterialTheme.typography.labelMedium, color = Slate.colors.textSecondary)
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        wb.sheets.forEachIndexed { idx, tab ->
                            val selected = idx == wb.activeSheet
                            val sheetMatchCount = if (searchQuery.isNotBlank()) {
                                searchMatches.count { it.sheetIndex == idx }
                            } else 0
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) Slate.colors.text else Slate.colors.surfaceAlt,
                                border = BorderStroke(1.dp, if (selected) Slate.colors.text else Slate.colors.border),
                                onClick = { wb = wb.copy(activeSheet = idx) },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = tab.name,
                                        color = if (selected) Slate.colors.background else Slate.colors.text,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    if (sheetMatchCount > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (selected) Slate.colors.background.copy(alpha = 0.25f) else Slate.colors.sheet.copy(alpha = 0.2f),
                                        ) {
                                            Text(
                                                text = "$sheetMatchCount",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (selected) Slate.colors.background else Slate.colors.sheet,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (isEditMode) {
                            OutlinedButton(
                                onClick = {
                                    val nextName = "Sheet${wb.sheets.size + 1}"
                                    val nextList = wb.sheets + SheetTab(nextName)
                                    commit(wb.copy(sheets = nextList, activeSheet = nextList.lastIndex))
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Sheet", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // Compact zoom controls in bottom sheet bar for effortless access
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Slate.colors.surfaceAlt)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            IconButton(
                                onClick = { zoom = (zoom - 0.2f).coerceAtLeast(0.5f) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Outlined.Remove, "Zoom out", modifier = Modifier.size(15.dp))
                            }
                            Text(
                                text = "$zoomPct%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { zoom = 1f }
                                    .padding(horizontal = 6.dp),
                            )
                            IconButton(
                                onClick = { zoom = (zoom + 0.2f).coerceAtMost(2.5f) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Outlined.Add, "Zoom in", modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (!isEditMode) {
                // VIEW MODE: Clean Read-Only Cell Inspector Bar (No BasicTextField, no automatic keyboard!)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Slate.colors.surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Slate.colors.surfaceAlt)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = cellRefLabel,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val inspectText = buildString {
                        if (activeCellDisplay.isNotEmpty()) {
                            append(activeCellDisplay)
                            if (activeCell.raw.startsWith("=") && activeCell.raw != activeCellDisplay) {
                                append("   (${activeCell.raw})")
                            }
                        } else {
                            append("Tap any cell to inspect • Pinch or double-tap to zoom")
                        }
                    }
                    Text(
                        text = inspectText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeCellDisplay.isNotEmpty()) Slate.colors.text else Slate.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (activeCellDisplay.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Cell $cellRefLabel", activeCellDisplay))
                                Toast.makeText(context, "Copied $cellRefLabel to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(
                        onClick = { freezeTopRow = !freezeTopRow },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ViewHeadline,
                            contentDescription = if (freezeTopRow) "Unfreeze header row" else "Freeze header row",
                            tint = if (freezeTopRow) Slate.colors.sheet else Slate.colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                // EDIT MODE: Editable Formula & Value Bar (Only shown when user explicitly entered Edit Mode)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Slate.colors.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate.colors.surfaceAlt)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(cellRefLabel, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "fx",
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        color = Slate.colors.textSecondary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Slate.colors.border, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (activeCell.raw.isEmpty()) {
                            Text(
                                "Tap to edit $cellRefLabel or enter =SUM(A1:A5)…",
                                color = Slate.colors.textSecondary.copy(alpha = 0.5f),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = activeCell.raw,
                            onValueChange = { newRaw ->
                                val next = wb.updateActive { it.withCellRaw(selRow, selCol, newRaw) }
                                history.replace(next)
                                wb = history.current
                                saveController.markDirty()
                            },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = Slate.colors.text, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(Slate.colors.text),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboardController?.hide()
                                if (selRow + 1 < sheet.rows) selRow++
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(formulaFocusRequester),
                        )
                    }
                }
            }
            HorizontalDivider(color = Slate.colors.border)

            // Scaled dimensions for smooth Spreadsheet Zoom
            val rowNumWidth = (46f * zoom).coerceIn(32f, 92f).dp
            val colHeaderHeight = (30f * zoom).coerceIn(22f, 56f).dp
            val cellFontSize = (13f * zoom).coerceIn(8f, 26f).sp
            val headerFontSize = (11f * zoom).coerceIn(8f, 20f).sp

            //Spreadsheet Grid Container with Two-Finger Pinch-to-Zoom (does not block 1-finger scroll!)
            Column(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (abs(zoomChange - 1f) > 0.005f) {
                                        zoom = (zoom * zoomChange).coerceIn(0.5f, 2.5f)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                // Frozen Column Letter Header Row (A, B, C...)
                Row(Modifier.fillMaxWidth().background(Slate.colors.surfaceAlt)) {
                    Box(
                        Modifier
                            .width(rowNumWidth)
                            .height(colHeaderHeight)
                            .border(0.5.dp, Slate.colors.border)
                            .clickable { zoom = 1f },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("#", fontSize = headerFontSize, color = Slate.colors.textSecondary)
                    }

                    Row(Modifier.horizontalScroll(horizScroll)) {
                        for (c in 0 until sheet.cols) {
                            val w = ((sheet.colWidthsDp[c] ?: 104) * zoom).dp
                            val isColSelected = c == selCol
                            Box(
                                Modifier
                                    .width(w)
                                    .height(colHeaderHeight)
                                    .background(if (isColSelected) Slate.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .border(0.5.dp, Slate.colors.border)
                                    .combinedClickable(
                                        onClick = { selCol = c },
                                        onLongClick = { selCol = c; showSizeDialog = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = SheetTab.colName(c),
                                    fontSize = headerFontSize,
                                    fontWeight = if (isColSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = Slate.colors.text,
                                )
                            }
                        }
                    }
                }

                // Optional Frozen Row 1 (Table Header Row)
                if (freezeTopRow && sheet.rows > 0) {
                    SpreadsheetRowView(
                        r = 0,
                        sheet = sheet,
                        zoom = zoom,
                        selRow = selRow,
                        selCol = selCol,
                        horizScroll = horizScroll,
                        searchQuery = searchQuery,
                        activeMatch = currentMatch?.takeIf { it.sheetIndex == wb.activeSheet },
                        isPinnedHeader = true,
                        onSelectCell = { r, c -> selRow = r; selCol = c },
                        onDoubleTapCell = { r, c ->
                            selRow = r; selCol = c
                            if (isEditMode) {
                                runCatching { formulaFocusRequester.requestFocus() }
                                keyboardController?.show()
                            } else {
                                zoom = if (zoom > 1.2f) 1f else 1.6f
                            }
                        },
                        onLongPressRow = { r -> selRow = r; showSizeDialog = true },
                    )
                    HorizontalDivider(thickness = 1.5.dp, color = Slate.colors.sheet.copy(alpha = 0.5f))
                }

                val bodyRows = remember(sheet.rows, freezeTopRow) {
                    val start = if (freezeTopRow && sheet.rows > 1) 1 else 0
                    (start until sheet.rows).toList()
                }

                LazyColumn(
                    state = vertScroll,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(bodyRows, key = { it }) { r ->
                        SpreadsheetRowView(
                            r = r,
                            sheet = sheet,
                            zoom = zoom,
                            selRow = selRow,
                            selCol = selCol,
                            horizScroll = horizScroll,
                            searchQuery = searchQuery,
                            activeMatch = currentMatch?.takeIf { it.sheetIndex == wb.activeSheet },
                            isPinnedHeader = false,
                            onSelectCell = { row, col -> selRow = row; selCol = col },
                            onDoubleTapCell = { row, col ->
                                selRow = row; selCol = col
                                if (isEditMode) {
                                    runCatching { formulaFocusRequester.requestFocus() }
                                    keyboardController?.show()
                                } else {
                                    zoom = if (zoom > 1.2f) 1f else 1.6f
                                }
                            },
                            onLongPressRow = { row -> selRow = row; showSizeDialog = true },
                        )
                    }
                }
            }
        }
    }

    if (showRenameSheetDialog) {
        TextPromptDialog(
            title = "Rename sheet",
            initial = sheet.name,
            label = "Sheet name",
            confirm = "Rename",
            onDismiss = { showRenameSheetDialog = false },
            onConfirm = { newName ->
                commit(wb.updateActive { it.copy(name = newName) })
                showRenameSheetDialog = false
            },
        )
    }

    if (showSizeDialog) {
        val curColW = sheet.colWidthsDp[selCol] ?: 104
        val curRowH = sheet.rowHeightsDp[selRow] ?: 36
        var colW by remember { mutableIntStateOf(curColW) }
        var rowH by remember { mutableIntStateOf(curRowH) }
        AlertDialog(
            onDismissRequest = { showSizeDialog = false },
            title = { Text("Adjust Column ${SheetTab.colName(selCol)} & Row ${selRow + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Column ${SheetTab.colName(selCol)} width: ${colW}dp")
                    Slider(value = colW.toFloat(), onValueChange = { colW = it.toInt() }, valueRange = 60f..320f)
                    Text("Row ${selRow + 1} height: ${rowH}dp")
                    Slider(value = rowH.toFloat(), onValueChange = { rowH = it.toInt() }, valueRange = 28f..140f)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    commit(
                        wb.updateActive {
                            it.copy(
                                colWidthsDp = it.colWidthsDp + (selCol to colW),
                                rowHeightsDp = it.rowHeightsDp + (selRow to rowH),
                            )
                        }
                    )
                    showSizeDialog = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showSizeDialog = false }) { Text("Cancel") } },
        )
    }

    if (showFormulaSheet) {
        val colName = SheetTab.colName(selCol)
        val endRow = (selRow).coerceAtLeast(1)
        val rangeAbove = "${colName}1:${colName}$endRow"
        val presets = listOf(
            "SUM" to "=SUM($rangeAbove)",
            "AVERAGE" to "=AVERAGE($rangeAbove)",
            "MIN" to "=MIN($rangeAbove)",
            "MAX" to "=MAX($rangeAbove)",
            "COUNT" to "=COUNT($rangeAbove)",
            "IF" to "=IF(${colName}1>0, 1, 0)",
            "ROUND" to "=ROUND(${colName}1, 2)",
        )
        AlertDialog(
            onDismissRequest = { showFormulaSheet = false },
            title = { Text("Insert Formula into $cellRefLabel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (title, expr) ->
                        Surface(
                            onClick = {
                                commit(wb.updateActive { it.withCellRaw(selRow, selCol, expr) })
                                showFormulaSheet = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = Slate.colors.surfaceAlt,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(title, fontWeight = FontWeight.SemiBold)
                                Text(expr, fontFamily = FontFamily.Monospace, color = Slate.colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFormulaSheet = false }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpreadsheetRowView(
    r: Int,
    sheet: SheetTab,
    zoom: Float,
    selRow: Int,
    selCol: Int,
    horizScroll: androidx.compose.foundation.ScrollState,
    searchQuery: String,
    activeMatch: SheetSearchMatch?,
    isPinnedHeader: Boolean,
    onSelectCell: (Int, Int) -> Unit,
    onDoubleTapCell: (Int, Int) -> Unit,
    onLongPressRow: (Int) -> Unit,
) {
    val rowNumWidth = (46f * zoom).coerceIn(32f, 92f).dp
    val cellFontSize = (13f * zoom).coerceIn(8f, 26f).sp
    val headerFontSize = (11f * zoom).coerceIn(8f, 20f).sp
    val h = ((sheet.rowHeightsDp[r] ?: 36) * zoom).coerceAtLeast(22f).dp
    val isRowSelected = r == selRow
    Row(Modifier.fillMaxWidth()) {
        // Frozen Row Number Cell
        Box(
            Modifier
                .width(rowNumWidth)
                .height(h)
                .background(if (isRowSelected) Slate.colors.accent.copy(alpha = 0.16f) else Slate.colors.surfaceAlt)
                .border(0.5.dp, Slate.colors.border)
                .combinedClickable(
                    onClick = { onSelectCell(r, selCol) },
                    onLongClick = { onLongPressRow(r) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${r + 1}",
                fontSize = headerFontSize,
                fontWeight = if (isRowSelected || isPinnedHeader) FontWeight.Bold else FontWeight.Normal,
                color = Slate.colors.textSecondary,
            )
        }
        // Horizontally Scrollable Data Cells
        Row(Modifier.horizontalScroll(horizScroll)) {
            for (c in 0 until sheet.cols) {
                val w = ((sheet.colWidthsDp[c] ?: 104) * zoom).dp
                val isSelected = r == selRow && c == selCol
                val isActiveSearchMatch = activeMatch != null && activeMatch.row == r && activeMatch.col == c
                val cell = sheet.cell(r, c)
                val disp = sheet.displayValue(r, c)
                val isSearchMatch = searchQuery.isNotBlank() &&
                    (disp.contains(searchQuery, ignoreCase = true) || cell.raw.contains(searchQuery, ignoreCase = true))

                Box(
                    Modifier
                        .width(w)
                        .height(h)
                        .background(
                            when {
                                isActiveSearchMatch -> Color(0xFFFFEC99)
                                isSearchMatch -> Color(0xFFFFF3BF)
                                isSelected -> Slate.colors.sheet.copy(alpha = 0.15f)
                                isPinnedHeader -> Slate.colors.surfaceAlt.copy(alpha = 0.7f)
                                else -> Slate.colors.surface
                            }
                        )
                        .border(
                            width = when {
                                isActiveSearchMatch -> 2.dp
                                isSelected -> 1.5.dp
                                else -> 0.5.dp
                            },
                            color = when {
                                isActiveSearchMatch -> Slate.colors.pdf
                                isSelected -> Slate.colors.sheet
                                else -> Slate.colors.border
                            },
                        )
                        .combinedClickable(
                            onClick = { onSelectCell(r, c) },
                            onDoubleClick = { onDoubleTapCell(r, c) },
                        )
                        .padding(horizontal = (6f * zoom).coerceAtLeast(3f).dp),
                    contentAlignment = when (cell.format.align) {
                        CellAlign.LEFT -> Alignment.CenterStart
                        CellAlign.CENTER -> Alignment.Center
                        CellAlign.RIGHT -> Alignment.CenterEnd
                    },
                ) {
                    val cellTextColor: Color = if (isActiveSearchMatch || isSearchMatch) Color(0xFF171717) else Slate.colors.text
                    Text(
                        text = disp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = cellFontSize,
                        fontWeight = if (cell.format.bold || isPinnedHeader || isSearchMatch) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (cell.format.italic) FontStyle.Italic else FontStyle.Normal,
                        color = cellTextColor,
                    )
                }
            }
        }
    }
}
