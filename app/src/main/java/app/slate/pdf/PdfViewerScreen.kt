package app.slate.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.DocumentActivity
import app.slate.SlateApp
import app.slate.core.DocFormat
import app.slate.core.OpenDoc
import app.slate.core.UndoStack
import app.slate.core.io.DocIO
import app.slate.core.io.openSource
import app.slate.core.parseTarget
import app.slate.core.withoutExtension
import app.slate.share.ShareAndPrint
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class PdfToolMode { VIEW, HIGHLIGHT, DRAW, COMMENT, SIGNATURE }

@Composable
fun PdfViewerScreen(doc: OpenDoc, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var workingPdfFile by remember { mutableStateOf<File?>(null) }
    var pageTexts by remember { mutableStateOf<List<String>>(emptyList()) }

    val history = remember { UndoStack(PdfEditState(emptyList())) }
    var editState by remember { mutableStateOf(history.current) }

    var toolMode by remember { mutableStateOf(PdfToolMode.VIEW) }
    var activePenColor by remember { mutableIntStateOf(0xFFE03131.toInt()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }

    var searchQuery by remember { mutableStateOf(doc.query.orEmpty()) }
    var showSearchBar by remember { mutableStateOf(!doc.query.isNullOrBlank()) }
    var showPageTextDialog by remember { mutableStateOf<Int?>(null) }
    var showPageManagerSheet by remember { mutableStateOf(false) }
    var showSignaturePadForPage by remember { mutableStateOf<Int?>(null) }
    var pendingCommentTarget by remember { mutableStateOf<Pair<Int, NormalizedPoint>?>(null) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showGotoPageDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val currentPageIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val writable = remember { listOf(DocFormat.PDF) }
    val saveController = rememberSaveController(doc, writable) { fmt, out ->
        val src = workingPdfFile ?: return@rememberSaveController
        if (fmt == DocFormat.PDF) {
            PdfToolbox.writeEditedPdf(context, src, editState, out)
        } else {
            // Convert via PdfEngine after applying edits to a temp file
            val editedTmp = File.createTempFile("edited_", ".pdf", DocIO.secureTemp(context))
            try {
                editedTmp.outputStream().use { PdfToolbox.writeEditedPdf(context, src, editState, it) }
                PdfEngine.convert(context, { editedTmp.inputStream() }, DocFormat.PDF, fmt, out)
            } finally {
                editedTmp.delete()
            }
        }
    }

    fun commit(next: PdfEditState) {
        history.push(next)
        editState = history.current
        saveController.markDirty()
    }

    // Bitmap cache for rendered PDF pages
    val bitmapCache = remember {
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
    val renderMutex = remember { Mutex() }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            workingPdfFile?.delete()
        }
    }

    LaunchedEffect(doc.uri, doc.recoveryFile) {
        loading = true
        loadError = null
        val res = runCatching {
            withContext(Dispatchers.IO) {
                val f = doc.openSource(context).use { PdfToolbox.copyToTempPdf(context, it) }
                val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(fd)
                val count = r.pageCount
                val initialPages = (0 until count).map { PdfPageTransform(it, 0) }
                Triple(f, fd to r, initialPages)
            }
        }
        res.onSuccess { (file, pair, pages) ->
            workingPdfFile = file
            pfd = pair.first
            renderer = pair.second
            val initial = PdfEditState(pages = pages)
            history.reset(initial)
            editState = initial
            loading = false

            val targetPage = parseTarget(doc.target)["page"]?.toIntOrNull() ?: doc.lastPosition
            if (targetPage in pages.indices) {
                runCatching { listState.scrollToItem(targetPage) }
            }
            // Extract text asynchronously for search & copy
            withContext(Dispatchers.IO) {
                runCatching { PdfToolbox.extractPageTexts(context, file) }.onSuccess { pageTexts = it }
            }
        }.onFailure {
            loadError = it.message ?: "Unable to open PDF"
            loading = false
        }
    }

    LaunchedEffect(currentPageIndex, editState.pages.size) {
        if (!loading && editState.pages.isNotEmpty()) {
            app.recents.setPosition(doc.uri, currentPageIndex, editState.pages.size)
        }
    }

    // Merge another PDF launcher
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { otherUri ->
        val src = workingPdfFile
        if (otherUri != null && src != null) {
            scope.launch {
                val outName = "${doc.name.withoutExtension()}_merged.pdf"
                val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), outName)
                val r = runCatching {
                    withContext(Dispatchers.IO) {
                        val firstTmp = File.createTempFile("m1_", ".pdf", DocIO.secureTemp(context))
                        try {
                            firstTmp.outputStream().use { PdfToolbox.writeEditedPdf(context, src, editState, it) }
                            firstTmp.inputStream().use { s1 ->
                                DocIO.open(context, otherUri).use { s2 ->
                                    outFile.outputStream().use { out ->
                                        PdfToolbox.mergePdfs(context, listOf(s1, s2), out)
                                    }
                                }
                            }
                        } finally {
                            firstTmp.delete()
                        }
                    }
                }
                r.onSuccess {
                    Toast.makeText(context, "Merged saved to SLATE Library: ${outFile.name}", Toast.LENGTH_LONG).show()
                    DocumentActivity.open(context, android.net.Uri.fromFile(outFile))
                }.onFailure {
                    Toast.makeText(context, "Merge failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (loading) {
        LoadingState("Opening ${doc.name}…")
        return
    }
    if (loadError != null) {
        ErrorState("Couldn't open PDF", loadError!!, onClose = onClose)
        return
    }

    val matchingPages = remember(searchQuery, pageTexts, editState.pages) {
        if (searchQuery.isBlank()) emptyList()
        else editState.pages.mapIndexedNotNull { visIdx, pt ->
            val txt = pageTexts.getOrNull(pt.originalPageIndex).orEmpty()
            if (txt.contains(searchQuery, ignoreCase = true)) visIdx else null
        }
    }
    var activeMatchIdx by remember { mutableIntStateOf(0) }
    var isEditMode by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, matchingPages.size) {
        if (searchQuery.isNotBlank() && matchingPages.isNotEmpty()) {
            activeMatchIdx = 0
            runCatching { listState.animateScrollToItem(matchingPages[0]) }
        } else {
            activeMatchIdx = 0
        }
    }

    val totalPages = editState.pages.size
    val zoomPct = (zoom * 100).roundToInt()
    val subtitle = buildString {
        append("Page ${(currentPageIndex + 1).coerceAtMost(totalPages)} of $totalPages")
        if (matchingPages.isNotEmpty()) append(" • ${matchingPages.size} matching page(s)")
        else if (saveController.dirty) append(" • Unsaved")
    }

    EditorScaffold(
        title = doc.name,
        controller = saveController,
        onClose = onClose,
        canUndo = history.canUndo,
        canRedo = history.canRedo,
        onUndo = { editState = history.undo(); saveController.markDirty() },
        onRedo = { editState = history.redo(); saveController.markDirty() },
        exportFormats = listOf(DocFormat.DOCX, DocFormat.ODT, DocFormat.RTF, DocFormat.TXT, DocFormat.PNG, DocFormat.JPG),
        subtitle = subtitle,
        isEditMode = isEditMode,
        onToggleEditMode = {
            isEditMode = !isEditMode
            if (!isEditMode) toolMode = PdfToolMode.VIEW
        },
        onToggleSearch = {
            showSearchBar = !showSearchBar
            if (!showSearchBar) searchQuery = ""
        },
        isSearchActive = showSearchBar,
        zoomPercent = zoomPct,
        onZoomIn = { zoom = (zoom + 0.25f).coerceAtMost(3f) },
        onZoomOut = {
            zoom = (zoom - 0.25f).coerceAtLeast(1f)
            if (zoom <= 1.01f) panX = 0f
        },
        onResetZoom = { zoom = 1f; panX = 0f },
        searchBar = {
            val matchPageNum = matchingPages.getOrNull(activeMatchIdx)?.let { "Page ${it + 1}" }
            DocumentSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search text across all $totalPages pages…",
                matchCount = matchingPages.size,
                currentMatchIndex = activeMatchIdx,
                extraInfo = matchPageNum,
                onPrevMatch = {
                    if (matchingPages.isNotEmpty()) {
                        activeMatchIdx = if (activeMatchIdx - 1 < 0) matchingPages.lastIndex else activeMatchIdx - 1
                        scope.launch { runCatching { listState.animateScrollToItem(matchingPages[activeMatchIdx]) } }
                    }
                },
                onNextMatch = {
                    if (matchingPages.isNotEmpty()) {
                        activeMatchIdx = (activeMatchIdx + 1) % matchingPages.size
                        scope.launch { runCatching { listState.animateScrollToItem(matchingPages[activeMatchIdx]) } }
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
                text = { Text("Go to page…") },
                onClick = { dismiss(); showGotoPageDialog = true },
            )
            DropdownMenuItem(
                text = { Text("Select & copy page text…") },
                onClick = { dismiss(); showPageTextDialog = currentPageIndex },
            )
            DropdownMenuItem(
                text = { Text("Manage pages (reorder, rotate, delete)…") },
                onClick = { dismiss(); showPageManagerSheet = true },
            )
            DropdownMenuItem(
                text = { Text("Merge with another PDF…") },
                onClick = { dismiss(); mergePicker.launch("application/pdf") },
            )
            DropdownMenuItem(
                text = { Text("Split / Extract pages…") },
                onClick = { dismiss(); showSplitDialog = true },
            )
            DropdownMenuItem(
                text = { Text("Compress PDF…") },
                onClick = {
                    dismiss()
                    val src = workingPdfFile ?: return@DropdownMenuItem
                    scope.launch {
                        val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), "${doc.name.withoutExtension()}_compressed.pdf")
                        Toast.makeText(context, "Compressing PDF…", Toast.LENGTH_SHORT).show()
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                outFile.outputStream().use { out -> PdfToolbox.compressPdf(context, src, out) }
                            }
                        }
                        res.onSuccess {
                            val kb = outFile.length() / 1024
                            Toast.makeText(context, "Saved ${outFile.name} ($kb KB)", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, "Compress failed: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Export current page as PNG") },
                onClick = {
                    dismiss()
                    val src = workingPdfFile ?: return@DropdownMenuItem
                    val origIdx = editState.pages.getOrNull(currentPageIndex)?.originalPageIndex ?: 0
                    scope.launch {
                        val outFile = DocIO.uniqueFile(
                            DocIO.libraryDir(context),
                            "${doc.name.withoutExtension()}_page${currentPageIndex + 1}.png"
                        )
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                val bmp = PdfToolbox.renderPageBitmap(src, origIdx, 1600)
                                    ?: error("Cannot render page")
                                outFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
                                bmp.recycle()
                            }
                        }
                        res.onSuccess {
                            Toast.makeText(context, "Exported ${outFile.name}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Print PDF…") },
                onClick = {
                    dismiss()
                    val src = workingPdfFile ?: return@DropdownMenuItem
                    scope.launch {
                        val printFile = withContext(Dispatchers.IO) {
                            val tmp = File(DocIO.secureTemp(context), "print_${doc.name}")
                            tmp.outputStream().use { PdfToolbox.writeEditedPdf(context, src, editState, it) }
                            tmp
                        }
                        ShareAndPrint.printPdf(context, printFile, doc.name)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Share PDF…") },
                onClick = {
                    dismiss()
                    ShareAndPrint.shareDocument(context, doc.uri, doc.name)
                },
            )
        },
        toolbar = {
            ToolButton(
                icon = Icons.Outlined.PanTool,
                description = "Pan / Scroll mode",
                selected = toolMode == PdfToolMode.VIEW,
                onClick = { toolMode = PdfToolMode.VIEW },
            )
            ToolButton(
                icon = Icons.Outlined.Highlight,
                description = "Highlight text",
                selected = toolMode == PdfToolMode.HIGHLIGHT,
                onClick = { toolMode = if (toolMode == PdfToolMode.HIGHLIGHT) PdfToolMode.VIEW else PdfToolMode.HIGHLIGHT },
            )
            ToolButton(
                icon = Icons.Outlined.Draw,
                description = "Freehand draw",
                selected = toolMode == PdfToolMode.DRAW,
                onClick = { toolMode = if (toolMode == PdfToolMode.DRAW) PdfToolMode.VIEW else PdfToolMode.DRAW },
            )
            ToolButton(
                icon = Icons.Outlined.AddComment,
                description = "Add comment note",
                selected = toolMode == PdfToolMode.COMMENT,
                onClick = { toolMode = if (toolMode == PdfToolMode.COMMENT) PdfToolMode.VIEW else PdfToolMode.COMMENT },
            )
            ToolButton(
                icon = Icons.Outlined.Gesture,
                description = "Add signature",
                selected = toolMode == PdfToolMode.SIGNATURE,
                onClick = { showSignaturePadForPage = currentPageIndex },
            )
            if (toolMode == PdfToolMode.DRAW) {
                val colors = listOf(0xFFE03131.toInt(), 0xFF1971C2.toInt(), 0xFF2F9E44.toInt(), 0xFF171717.toInt())
                colors.forEach { col ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(col))
                            .border(if (activePenColor == col) 2.dp else 0.dp, Slate.colors.text, CircleShape)
                            .clickable { activePenColor = col }
                    )
                }
            }
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            ToolButton(
                icon = Icons.Outlined.RotateRight,
                description = "Rotate current page 90°",
                onClick = {
                    val idx = currentPageIndex
                    if (idx in editState.pages.indices) {
                        val updatedPages = editState.pages.toMutableList().apply {
                            val old = get(idx)
                            set(idx, old.copy(extraRotationDeg = (old.extraRotationDeg + 90) % 360))
                        }
                        commit(editState.copy(pages = updatedPages))
                    }
                },
            )
            ToolButton(
                icon = Icons.Outlined.ContentCopy,
                description = "Copy page text",
                onClick = { showPageTextDialog = currentPageIndex },
            )
            ToolButton(
                icon = Icons.Outlined.DashboardCustomize,
                description = "Manage pages",
                onClick = { showPageManagerSheet = true },
            )
            ToolButton(
                icon = Icons.Outlined.ZoomIn,
                description = "Zoom in",
                onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) },
            )
            if (zoom > 1.01f) {
                ToolButton(
                    icon = Icons.Outlined.ZoomOut,
                    description = "Reset zoom",
                    onClick = { zoom = 1f; panX = 0f },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (toolMode != PdfToolMode.VIEW) {
                Surface(color = Slate.colors.surfaceAlt, modifier = Modifier.fillMaxWidth()) {
                    val hint = when (toolMode) {
                        PdfToolMode.HIGHLIGHT -> "Drag across text on any page to highlight"
                        PdfToolMode.DRAW -> "Draw directly on any page with your finger"
                        PdfToolMode.COMMENT -> "Tap anywhere on a page to place a comment note"
                        else -> ""
                    }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(hint, style = MaterialTheme.typography.labelMedium, color = Slate.colors.textSecondary)
                        Text(
                            "Done",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { toolMode = PdfToolMode.VIEW },
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                userScrollEnabled = toolMode == PdfToolMode.VIEW,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(toolMode) {
                        if (toolMode == PdfToolMode.VIEW) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoom > 1.1f) {
                                        zoom = 1f; panX = 0f
                                    } else {
                                        zoom = 2.0f
                                    }
                                }
                            )
                        }
                    }
                    .pointerInput(toolMode) {
                        if (toolMode == PdfToolMode.VIEW) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val z = event.calculateZoom()
                                        val p = event.calculatePan()
                                        if (abs(z - 1f) > 0.005f || zoom > 1.01f) {
                                            zoom = (zoom * z).coerceIn(1f, 3f)
                                            panX = if (zoom > 1.01f) (panX + p.x).coerceIn(-600f, 600f) else 0f
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else if (event.changes.size == 1 && zoom > 1.02f) {
                                        val p = event.calculatePan()
                                        if (abs(p.x) > abs(p.y)) {
                                            panX = (panX + p.x).coerceIn(-600f, 600f)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    }
                    .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(editState.pages, key = { idx, p -> "$idx-${p.originalPageIndex}-${p.extraRotationDeg}" }) { visIdx, pageTransform ->
                    val r = renderer
                    if (r != null) {
                        PdfPageCard(
                            renderer = r,
                            renderMutex = renderMutex,
                            bitmapCache = bitmapCache,
                            visualPageIndex = visIdx,
                            transform = pageTransform,
                            strokes = editState.strokes.filter { it.pageIndex == visIdx },
                            comments = editState.comments.filter { it.pageIndex == visIdx },
                            hasSearchMatch = visIdx in matchingPages,
                            searchQuery = searchQuery,
                            toolMode = toolMode,
                            penColor = activePenColor,
                            onAddStroke = { stroke -> commit(editState.copy(strokes = editState.strokes + stroke)) },
                            onTapForComment = { pt -> pendingCommentTarget = visIdx to pt },
                        )
                    }
                }
            }
        }
    }

    // Comment prompt dialog
    pendingCommentTarget?.let { (pageIdx, pt) ->
        TextPromptDialog(
            title = "Add comment on Page ${pageIdx + 1}",
            label = "Comment note",
            confirm = "Add",
            onDismiss = { pendingCommentTarget = null },
            onConfirm = { text ->
                val note = PdfCommentNote(pageIndex = pageIdx, x = pt.x, y = pt.y, text = text)
                commit(editState.copy(comments = editState.comments + note))
                pendingCommentTarget = null
                toolMode = PdfToolMode.VIEW
            },
        )
    }

    // Signature pad dialog
    showSignaturePadForPage?.let { targetPage ->
        SignaturePadDialog(
            onDismiss = { showSignaturePadForPage = null },
            onConfirmSignature = { sigPoints ->
                if (sigPoints.isNotEmpty()) {
                    // Place signature in lower-right area of targetPage
                    val placed = sigPoints.map { stroke ->
                        PdfStroke(
                            pageIndex = targetPage,
                            kind = PdfStrokeKind.SIGNATURE,
                            points = stroke.map { p -> NormalizedPoint(0.55f + p.x * 0.35f, 0.78f + p.y * 0.14f) },
                            colorArgb = 0xFF112244.toInt(),
                            strokeWidthNorm = 0.0045f,
                        )
                    }
                    commit(editState.copy(strokes = editState.strokes + placed))
                }
                showSignaturePadForPage = null
            },
        )
    }

    // Page text selection & copy dialog
    showPageTextDialog?.let { visIdx ->
        val origIdx = editState.pages.getOrNull(visIdx)?.originalPageIndex ?: 0
        val txt = pageTexts.getOrNull(origIdx).orEmpty().ifBlank { "No selectable text found on Page ${visIdx + 1}." }
        AlertDialog(
            onDismissRequest = { showPageTextDialog = null },
            title = { Text("Page ${visIdx + 1} Text") },
            text = {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(txt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PDF Page ${visIdx + 1}", txt))
                        Toast.makeText(context, "Page text copied", Toast.LENGTH_SHORT).show()
                        showPageTextDialog = null
                    }
                ) { Text("Copy all") }
            },
            dismissButton = { TextButton(onClick = { showPageTextDialog = null }) { Text("Close") } },
        )
    }

    // Page Manager Dialog (Reorder, Rotate, Delete)
    if (showPageManagerSheet) {
        AlertDialog(
            onDismissRequest = { showPageManagerSheet = false },
            title = { Text("Manage Pages (${editState.pages.size})") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editState.pages.forEachIndexed { idx, p ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Slate.colors.surfaceAlt,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Page ${idx + 1} (src #${p.originalPageIndex + 1}, ${p.extraRotationDeg}°)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        if (idx > 0) {
                                            val list = editState.pages.toMutableList()
                                            val item = list.removeAt(idx)
                                            list.add(idx - 1, item)
                                            commit(editState.copy(pages = list))
                                        }
                                    },
                                    enabled = idx > 0,
                                    modifier = Modifier.size(32.dp),
                                ) { Icon(Icons.Outlined.ArrowUpward, "Move up", modifier = Modifier.size(18.dp)) }

                                IconButton(
                                    onClick = {
                                        if (idx < editState.pages.lastIndex) {
                                            val list = editState.pages.toMutableList()
                                            val item = list.removeAt(idx)
                                            list.add(idx + 1, item)
                                            commit(editState.copy(pages = list))
                                        }
                                    },
                                    enabled = idx < editState.pages.lastIndex,
                                    modifier = Modifier.size(32.dp),
                                ) { Icon(Icons.Outlined.ArrowDownward, "Move down", modifier = Modifier.size(18.dp)) }

                                IconButton(
                                    onClick = {
                                        val list = editState.pages.toMutableList()
                                        list[idx] = p.copy(extraRotationDeg = (p.extraRotationDeg + 90) % 360)
                                        commit(editState.copy(pages = list))
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) { Icon(Icons.Outlined.RotateRight, "Rotate", modifier = Modifier.size(18.dp)) }

                                if (editState.pages.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val list = editState.pages.filterIndexed { i, _ -> i != idx }
                                            commit(editState.copy(pages = list))
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) { Icon(Icons.Outlined.DeleteOutline, "Delete page", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPageManagerSheet = false }) { Text("Done") } },
        )
    }

    // Split / Extract pages dialog
    if (showSplitDialog) {
        var rangeText by remember { mutableStateOf("1-${editState.pages.size}") }
        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text("Split / Extract Pages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter pages or range to extract into a new PDF (e.g. 1-3 or 2,4,5):", color = Slate.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = rangeText,
                        onValueChange = { rangeText = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val src = workingPdfFile ?: return@TextButton
                        val indices = parsePageSelection(rangeText, editState.pages.size)
                            .mapNotNull { visIdx -> editState.pages.getOrNull(visIdx)?.originalPageIndex }
                        if (indices.isEmpty()) {
                            Toast.makeText(context, "Invalid page range", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        showSplitDialog = false
                        scope.launch {
                            val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), "${doc.name.withoutExtension()}_extracted.pdf")
                            val r = runCatching {
                                withContext(Dispatchers.IO) {
                                    outFile.outputStream().use { out -> PdfToolbox.extractPages(context, src, indices, out) }
                                }
                            }
                            r.onSuccess {
                                Toast.makeText(context, "Extracted ${indices.size} page(s) to ${outFile.name}", Toast.LENGTH_LONG).show()
                                DocumentActivity.open(context, android.net.Uri.fromFile(outFile))
                            }.onFailure {
                                Toast.makeText(context, "Extract failed: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text("Extract") }
            },
            dismissButton = { TextButton(onClick = { showSplitDialog = false }) { Text("Cancel") } },
        )
    }

    if (showGotoPageDialog) {
        TextPromptDialog(
            title = "Go to page (1–$totalPages)",
            initial = "${currentPageIndex + 1}",
            label = "Page number",
            confirm = "Go",
            onDismiss = { showGotoPageDialog = false },
            onConfirm = { input ->
                val p = input.toIntOrNull()
                if (p != null && p in 1..totalPages) {
                    scope.launch { listState.scrollToItem(p - 1) }
                }
                showGotoPageDialog = false
            },
        )
    }
}

@Composable
private fun PdfPageCard(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    bitmapCache: LruCache<String, Bitmap>,
    visualPageIndex: Int,
    transform: PdfPageTransform,
    strokes: List<PdfStroke>,
    comments: List<PdfCommentNote>,
    hasSearchMatch: Boolean,
    searchQuery: String,
    toolMode: PdfToolMode,
    penColor: Int,
    onAddStroke: (PdfStroke) -> Unit,
    onTapForComment: (NormalizedPoint) -> Unit,
) {
    val cacheKey = "${transform.originalPageIndex}@${transform.extraRotationDeg}"
    var bitmap by remember(cacheKey) { mutableStateOf(bitmapCache.get(cacheKey)) }
    var aspect by remember(cacheKey) { mutableFloatStateOf(1f / 1.414f) }

    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                renderMutex.withLock {
                    bitmapCache.get(cacheKey)?.let {
                        bitmap = it
                        aspect = it.width.toFloat() / it.height.coerceAtLeast(1)
                        return@withLock
                    }
                    if (transform.originalPageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(transform.originalPageIndex).use { page ->
                            val targetW = 1280
                            val targetH = (targetW.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(100)
                            val rawBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            AndroidCanvas(rawBmp).drawColor(AndroidColor.WHITE)
                            page.render(rawBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val finalBmp = if (transform.extraRotationDeg % 360 != 0) {
                                val m = Matrix().apply { postRotate(transform.extraRotationDeg.toFloat()) }
                                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                                if (rotated != rawBmp) rawBmp.recycle()
                                rotated
                            } else rawBmp
                            bitmapCache.put(cacheKey, finalBmp)
                            bitmap = finalBmp
                            aspect = finalBmp.width.toFloat() / finalBmp.height.coerceAtLeast(1)
                        }
                    }
                }
            }
        } else {
            bitmap?.let { aspect = it.width.toFloat() / it.height.coerceAtLeast(1) }
        }
    }

    val livePoints = remember { mutableStateListOf<NormalizedPoint>() }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        border = BorderStroke(if (hasSearchMatch) 2.dp else 1.dp, if (hasSearchMatch) Slate.colors.pdf else Slate.colors.border),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .pointerInput(toolMode, penColor, visualPageIndex) {
                        when (toolMode) {
                            PdfToolMode.DRAW, PdfToolMode.HIGHLIGHT -> {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        livePoints.clear()
                                        if (size.width > 0 && size.height > 0) {
                                            livePoints.add(
                                                NormalizedPoint(
                                                    (offset.x / size.width).coerceIn(0f, 1f),
                                                    (offset.y / size.height).coerceIn(0f, 1f),
                                                )
                                            )
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (size.width > 0 && size.height > 0) {
                                            livePoints.add(
                                                NormalizedPoint(
                                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                                    (change.position.y / size.height).coerceIn(0f, 1f),
                                                )
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        if (livePoints.size >= 2) {
                                            val isHl = toolMode == PdfToolMode.HIGHLIGHT
                                            onAddStroke(
                                                PdfStroke(
                                                    pageIndex = visualPageIndex,
                                                    kind = if (isHl) PdfStrokeKind.HIGHLIGHT else PdfStrokeKind.PEN,
                                                    points = livePoints.toList(),
                                                    colorArgb = if (isHl) 0xFFFFE066.toInt() else penColor,
                                                    strokeWidthNorm = if (isHl) 0.022f else 0.005f,
                                                )
                                            )
                                        }
                                        livePoints.clear()
                                    },
                                    onDragCancel = { livePoints.clear() },
                                )
                            }
                            PdfToolMode.COMMENT -> {
                                detectTapGestures { offset ->
                                    if (size.width > 0 && size.height > 0) {
                                        onTapForComment(
                                            NormalizedPoint(
                                                (offset.x / size.width).coerceIn(0.05f, 0.85f),
                                                (offset.y / size.height).coerceIn(0.05f, 0.92f),
                                            )
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Page ${visualPageIndex + 1}",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }

                // Overlay canvas for strokes and comments
                Canvas(Modifier.fillMaxSize()) {
                    fun drawStrokeList(pts: List<NormalizedPoint>, color: Color, widthNorm: Float) {
                        if (pts.size < 2) return
                        val path = Path()
                        path.moveTo(pts[0].x * size.width, pts[0].y * size.height)
                        for (pt in pts.drop(1)) {
                            path.lineTo(pt.x * size.width, pt.y * size.height)
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = widthNorm * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }

                    for (s in strokes) {
                        val col = Color(s.colorArgb).let { if (s.kind == PdfStrokeKind.HIGHLIGHT) it.copy(alpha = 0.42f) else it }
                        drawStrokeList(s.points, col, s.strokeWidthNorm)
                    }
                    if (livePoints.size >= 2) {
                        val isHl = toolMode == PdfToolMode.HIGHLIGHT
                        val col = if (isHl) Color(0xFFFFE066).copy(alpha = 0.42f) else Color(penColor)
                        drawStrokeList(livePoints, col, if (isHl) 0.022f else 0.005f)
                    }
                    for (note in comments) {
                        val nx = note.x * size.width
                        val ny = note.y * size.height
                        drawRoundRect(
                            color = Color(0xFFFFF3BF),
                            topLeft = Offset(nx, ny),
                            size = Size(28.dp.toPx(), 22.dp.toPx()),
                        )
                    }
                }

                // Comment badges
                for (note in comments) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFFF3BF),
                            border = BorderStroke(1.dp, Color(0xFFE0B400)),
                            modifier = Modifier
                                .offset(x = maxWidth * note.x, y = maxHeight * note.y)
                                .widthIn(max = 180.dp),
                        ) {
                            Text(
                                text = note.text,
                                color = Color(0xFF171717),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Slate.colors.surfaceAlt)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Page ${visualPageIndex + 1}", style = MaterialTheme.typography.labelSmall, color = Slate.colors.textSecondary)
                if (hasSearchMatch && searchQuery.isNotBlank()) {
                    Text("Contains \"$searchQuery\"", style = MaterialTheme.typography.labelSmall, color = Slate.colors.pdf, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onConfirmSignature: (List<List<NormalizedPoint>>) -> Unit,
) {
    val strokes = remember { mutableStateListOf<List<NormalizedPoint>>() }
    val current = remember { mutableStateListOf<NormalizedPoint>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw Signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sign inside the box below:", style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, Slate.colors.border, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    current.clear()
                                    current.add(
                                        NormalizedPoint(
                                            (offset.x / size.width).coerceIn(0f, 1f),
                                            (offset.y / size.height).coerceIn(0f, 1f),
                                        )
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current.add(
                                        NormalizedPoint(
                                            (change.position.x / size.width).coerceIn(0f, 1f),
                                            (change.position.y / size.height).coerceIn(0f, 1f),
                                        )
                                    )
                                },
                                onDragEnd = {
                                    if (current.size >= 2) strokes.add(current.toList())
                                    current.clear()
                                },
                            )
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        fun drawSig(pts: List<NormalizedPoint>) {
                            if (pts.size < 2) return
                            val path = Path()
                            path.moveTo(pts[0].x * size.width, pts[0].y * size.height)
                            for (p in pts.drop(1)) path.lineTo(p.x * size.width, p.y * size.height)
                            drawPath(path, Color(0xFF112244), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        strokes.forEach { drawSig(it) }
                        drawSig(current)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirmSignature(strokes.toList()) }, enabled = strokes.isNotEmpty()) {
                Text("Place Signature")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear(); current.clear() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun parsePageSelection(spec: String, maxPages: Int): List<Int> {
    val result = LinkedHashSet<Int>()
    for (part in spec.split(',')) {
        val t = part.trim()
        if (t.isEmpty()) continue
        if ('-' in t) {
            val a = t.substringBefore('-').trim().toIntOrNull()
            val b = t.substringAfter('-').trim().toIntOrNull()
            if (a != null && b != null) {
                val start = minOf(a, b).coerceAtLeast(1)
                val end = maxOf(a, b).coerceAtMost(maxPages)
                for (p in start..end) result.add(p - 1)
            }
        } else {
            val p = t.toIntOrNull()
            if (p != null && p in 1..maxPages) result.add(p - 1)
        }
    }
    return result.toList()
}
