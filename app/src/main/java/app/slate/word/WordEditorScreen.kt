package app.slate.word

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle as ComposeSpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.SlateApp
import app.slate.core.DocFormat
import app.slate.core.OpenDoc
import app.slate.core.UndoStack
import app.slate.core.io.DocIO
import app.slate.core.io.openSource
import app.slate.core.io.sourceFormat
import app.slate.core.parseTarget
import app.slate.ui.components.ErrorState
import app.slate.ui.components.LoadingState
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

private data class IndexedDocElement(
    val globalIndex: Int,
    val element: DocElement,
    val fallbackNumberIndex: Int,
)

private data class WordVisualPage(
    val pageNumber: Int,
    val items: List<IndexedDocElement>,
)

@Composable
fun WordEditorScreen(doc: OpenDoc, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val history = remember { UndoStack(WordDocument(title = doc.name)) }
    var model by remember { mutableStateOf(history.current) }

    // CRITICAL: Word documents ALWAYS open in Read-Only VIEW MODE (Print Page Layout) by default.
    var isEditMode by remember { mutableStateOf(false) }
    var editSnapshot by remember { mutableStateOf<WordDocument?>(null) }

    var focusedIndex by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }

    // Search state
    var showSearchBar by remember { mutableStateOf(!doc.query.isNullOrBlank()) }
    var searchQuery by remember { mutableStateOf(doc.query.orEmpty()) }
    var activeMatchIdx by remember { mutableIntStateOf(0) }

    var showFindDialog by remember { mutableStateOf(false) }
    var showInsertTableDialog by remember { mutableStateOf(false) }

    val writableFormats = remember { listOf(DocFormat.DOCX, DocFormat.ODT, DocFormat.RTF, DocFormat.TXT) }
    val saveController = rememberSaveController(doc, writableFormats) { fmt, out ->
        WordFormatCodecs.write(model, fmt, out)
    }

    val viewPageListState = rememberLazyListState()
    val editListState = rememberLazyListState()

    fun commit(next: WordDocument) {
        history.push(next)
        model = history.current
        saveController.markDirty()
    }

    LaunchedEffect(doc.uri, doc.recoveryFile) {
        loading = true
        loadError = null
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                doc.openSource(context).use { WordFormatCodecs.read(it, doc.sourceFormat(), doc.name) }
            }
        }
        loaded.onSuccess { w ->
            history.reset(w)
            model = w
            loading = false
            val targetPara = parseTarget(doc.target)["para"]?.toIntOrNull() ?: doc.lastPosition
            if (targetPara in w.elements.indices) {
                focusedIndex = targetPara
            }
        }.onFailure {
            loadError = it.message ?: "Unable to read document"
            loading = false
        }
    }

    LaunchedEffect(zoom) {
        if (zoom <= 1.01f) panX = 0f
    }

    // Group document elements into visual Print Layout pages (splitting on explicit page breaks or page height budget)
    val visualPages = remember(model.elements) {
        val pages = ArrayList<WordVisualPage>()
        var currentItems = ArrayList<IndexedDocElement>()
        var currentUnits = 0f
        var seq = 0

        fun flushPage() {
            if (currentItems.isNotEmpty()) {
                pages.add(WordVisualPage(pageNumber = pages.size + 1, items = ArrayList(currentItems)))
                currentItems = ArrayList()
                currentUnits = 0f
            }
        }

        model.elements.forEachIndexed { idx, el ->
            when (el) {
                is ParagraphNode -> {
                    if (el.isPageBreakOnly) {
                        flushPage()
                        return@forEachIndexed
                    }
                    if (el.pageBreakBefore && currentItems.isNotEmpty()) {
                        flushPage()
                    }
                    if (el.listKind == ListKind.NUMBERED) seq++ else seq = 0
                    val linesEst = (el.plainText.length / 75f).coerceAtLeast(1f) + el.plainText.count { it == '\n' }
                    val unitCost = linesEst * 18f + el.spaceBeforePt + el.spaceAfterPt
                    if (currentUnits + unitCost > 760f && currentItems.size >= 8) {
                        flushPage()
                    }
                    currentItems.add(IndexedDocElement(idx, el, seq))
                    currentUnits += unitCost
                }
                is TableNode -> {
                    seq = 0
                    val unitCost = (el.rowCount * 24f).coerceAtLeast(48f)
                    if (currentUnits + unitCost > 820f && currentItems.isNotEmpty()) {
                        flushPage()
                    }
                    currentItems.add(IndexedDocElement(idx, el, 0))
                    currentUnits += unitCost
                }
                is ImageNode -> {
                    seq = 0
                    val unitCost = el.heightPt.toFloat().coerceIn(80f, 320f)
                    if (currentUnits + unitCost > 780f && currentItems.isNotEmpty()) {
                        flushPage()
                    }
                    currentItems.add(IndexedDocElement(idx, el, 0))
                    currentUnits += unitCost
                }
            }
        }
        flushPage()
        if (pages.isEmpty()) {
            pages.add(WordVisualPage(1, listOf(IndexedDocElement(0, ParagraphNode(), 0))))
        }
        pages
    }

    // Compute element indices matching search query
    val searchMatchingIndices = remember(model, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList()
        else model.elements.mapIndexedNotNull { idx, el ->
            val matches = when (el) {
                is ParagraphNode -> !el.isPageBreakOnly && el.plainText.contains(q, ignoreCase = true)
                is TableNode -> el.rows.any { r -> r.any { c -> c.contains(q, ignoreCase = true) } }
                is ImageNode -> el.caption.contains(q, ignoreCase = true)
            }
            if (matches) idx else null
        }
    }

    fun navigateToMatchIndex(matchListIdx: Int) {
        val elIdx = searchMatchingIndices.getOrNull(matchListIdx) ?: return
        focusedIndex = elIdx
        scope.launch {
            if (isEditMode) {
                runCatching { editListState.animateScrollToItem(elIdx) }
            } else {
                val pageIdx = visualPages.indexOfFirst { pg -> pg.items.any { it.globalIndex == elIdx } }
                if (pageIdx >= 0) {
                    runCatching { viewPageListState.animateScrollToItem(pageIdx) }
                }
            }
        }
    }

    LaunchedEffect(searchQuery, searchMatchingIndices.size) {
        if (searchQuery.isNotBlank() && searchMatchingIndices.isNotEmpty()) {
            activeMatchIdx = 0
            navigateToMatchIndex(0)
        } else {
            activeMatchIdx = 0
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching { DocIO.readBytes(context, uri) }.getOrNull()
            if (bytes != null) {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val imgNode = ImageNode(bytes = bytes, mimeType = mime)
                val insertAt = (focusedIndex + 1).coerceIn(0, model.elements.size)
                val next = model.elements.toMutableList().apply { add(insertAt, imgNode) }
                commit(model.copy(elements = next))
            }
        }
    }

    if (loading) {
        LoadingState("Opening ${doc.name}…")
        return
    }
    if (loadError != null) {
        ErrorState("Couldn't open document", loadError!!, onClose = onClose)
        return
    }

    val activePara = model.elements.getOrNull(focusedIndex) as? ParagraphNode
    val activeStyle = activePara?.runs?.firstOrNull()?.style ?: SpanStyle()

    fun updateActiveParagraph(transform: (ParagraphNode) -> ParagraphNode) {
        val idx = focusedIndex.coerceIn(0, model.elements.lastIndex)
        val el = model.elements.getOrNull(idx) as? ParagraphNode ?: return
        val updated = model.elements.toMutableList().apply { set(idx, transform(el)) }
        commit(model.copy(elements = updated))
    }

    val isLegacyReadOnly = doc.format == DocFormat.DOC
    val currentVisiblePage = (viewPageListState.firstVisibleItemIndex + 1).coerceIn(1, visualPages.size)
    val subtitle = buildString {
        if (isLegacyReadOnly) append("DOC • ")
        append("Page $currentVisiblePage of ${visualPages.size} • ")
        val words = model.elements.sumOf {
            when (it) {
                is ParagraphNode -> if (it.isPageBreakOnly) 0 else it.plainText.trim().split(Regex("\\s+")).count { w -> w.isNotEmpty() }
                is TableNode -> it.rows.flatten().sumOf { c -> c.trim().split(Regex("\\s+")).count { w -> w.isNotEmpty() } }
                is ImageNode -> 0
            }
        }
        append("$words words")
        if (saveController.dirty) append(" • Unsaved")
    }

    val zoomPct = (zoom * 100).roundToInt()
    val activeMatchedElementIdx = searchMatchingIndices.getOrNull(activeMatchIdx) ?: -1

    EditorScaffold(
        title = doc.name,
        controller = saveController,
        onClose = onClose,
        canUndo = history.canUndo,
        canRedo = history.canRedo,
        onUndo = { model = history.undo(); saveController.markDirty() },
        onRedo = { model = history.redo(); saveController.markDirty() },
        exportFormats = listOf(DocFormat.PDF),
        subtitle = subtitle,
        isEditMode = isEditMode,
        onToggleEditMode = {
            if (!isEditMode) {
                editSnapshot = model
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
                model = snap
                saveController.discard()
            }
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            isEditMode = false
        },
        onToggleSearch = {
            showSearchBar = !showSearchBar
            if (!showSearchBar) searchQuery = ""
        },
        isSearchActive = showSearchBar,
        zoomPercent = zoomPct,
        onZoomIn = { zoom = (zoom + 0.2f).coerceAtMost(3.0f) },
        onZoomOut = {
            zoom = (zoom - 0.2f).coerceAtLeast(0.6f)
            if (zoom <= 1.01f) panX = 0f
        },
        onResetZoom = { zoom = 1f; panX = 0f },
        searchBar = {
            DocumentSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search text in document…",
                matchCount = searchMatchingIndices.size,
                currentMatchIndex = activeMatchIdx,
                onPrevMatch = {
                    if (searchMatchingIndices.isNotEmpty()) {
                        activeMatchIdx = if (activeMatchIdx - 1 < 0) searchMatchingIndices.lastIndex else activeMatchIdx - 1
                        navigateToMatchIndex(activeMatchIdx)
                    }
                },
                onNextMatch = {
                    if (searchMatchingIndices.isNotEmpty()) {
                        activeMatchIdx = (activeMatchIdx + 1) % searchMatchingIndices.size
                        navigateToMatchIndex(activeMatchIdx)
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
                text = { Text("Export as PDF…") },
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                onClick = { dismiss(); saveController.saveAs(DocFormat.PDF) },
            )
            if (isEditMode) {
                DropdownMenuItem(
                    text = { Text("Find & Replace…") },
                    leadingIcon = { Icon(Icons.Outlined.FindReplace, null) },
                    onClick = { dismiss(); showFindDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("Insert table…") },
                    leadingIcon = { Icon(Icons.Outlined.TableChart, null) },
                    onClick = { dismiss(); showInsertTableDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("Insert image…") },
                    leadingIcon = { Icon(Icons.Outlined.Image, null) },
                    onClick = { dismiss(); imagePicker.launch("image/*") },
                )
            }
            DropdownMenuItem(
                text = { Text("Copy full document text") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = {
                    dismiss()
                    copyToClipboard(context, model.fullPlainText())
                },
            )
        },
        toolbar = {
            ToolButton(
                icon = Icons.Outlined.FormatBold,
                description = "Bold",
                selected = activeStyle.bold,
                onClick = { updateActiveParagraph { p -> p.applyStyle { it.copy(bold = !it.bold) } } },
            )
            ToolButton(
                icon = Icons.Outlined.FormatItalic,
                description = "Italic",
                selected = activeStyle.italic,
                onClick = { updateActiveParagraph { p -> p.applyStyle { it.copy(italic = !it.italic) } } },
            )
            ToolButton(
                icon = Icons.Outlined.FormatUnderlined,
                description = "Underline",
                selected = activeStyle.underline,
                onClick = { updateActiveParagraph { p -> p.applyStyle { it.copy(underline = !it.underline) } } },
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            ToolButton(
                icon = Icons.Outlined.TextDecrease,
                description = "Decrease font size",
                onClick = {
                    updateActiveParagraph { p ->
                        p.applyStyle {
                            val next = (it.fontSizeSp - 2).coerceAtLeast(8)
                            it.copy(fontSizeSp = next, fontSizePt = next.toFloat())
                        }
                    }
                },
            )
            Text("${activeStyle.fontSizeSp}pt", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
            ToolButton(
                icon = Icons.Outlined.TextIncrease,
                description = "Increase font size",
                onClick = {
                    updateActiveParagraph { p ->
                        p.applyStyle {
                            val next = (it.fontSizeSp + 2).coerceAtMost(48)
                            it.copy(fontSizeSp = next, fontSizePt = next.toFloat())
                        }
                    }
                },
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            AssistChip(
                onClick = {
                    updateActiveParagraph { p ->
                        val nextH = (p.headingLevel + 1) % 4
                        val sz = when (nextH) { 1 -> 20; 2 -> 15; 3 -> 13; else -> 12 }
                        p.copy(headingLevel = nextH).applyStyle { it.copy(bold = nextH > 0, fontSizeSp = sz, fontSizePt = sz.toFloat()) }
                    }
                },
                label = { Text(if ((activePara?.headingLevel ?: 0) == 0) "Body" else "H${activePara?.headingLevel}") },
                modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.FormatAlignLeft,
                description = "Align left",
                selected = activePara?.align == ParaAlign.LEFT,
                onClick = { updateActiveParagraph { it.copy(align = ParaAlign.LEFT) } },
            )
            ToolButton(
                icon = Icons.Outlined.FormatAlignCenter,
                description = "Align center",
                selected = activePara?.align == ParaAlign.CENTER,
                onClick = { updateActiveParagraph { it.copy(align = ParaAlign.CENTER) } },
            )
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.FormatAlignRight,
                description = "Align right",
                selected = activePara?.align == ParaAlign.RIGHT,
                onClick = { updateActiveParagraph { it.copy(align = ParaAlign.RIGHT) } },
            )
            ToolButton(
                icon = Icons.Outlined.FormatAlignJustify,
                description = "Justify",
                selected = activePara?.align == ParaAlign.JUSTIFY,
                onClick = { updateActiveParagraph { it.copy(align = ParaAlign.JUSTIFY) } },
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            ToolButton(
                icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                description = "Bulleted list",
                selected = activePara?.listKind == ListKind.BULLET,
                onClick = {
                    updateActiveParagraph {
                        val next = if (it.listKind == ListKind.BULLET) ListKind.NONE else ListKind.BULLET
                        it.copy(listKind = next, listPrefix = if (next == ListKind.BULLET) "•  " else "")
                    }
                },
            )
            ToolButton(
                icon = Icons.Outlined.FormatListNumbered,
                description = "Numbered list",
                selected = activePara?.listKind == ListKind.NUMBERED,
                onClick = {
                    updateActiveParagraph {
                        val next = if (it.listKind == ListKind.NUMBERED) ListKind.NONE else ListKind.NUMBERED
                        it.copy(listKind = next, listPrefix = "")
                    }
                },
            )
            ToolButton(
                icon = Icons.Outlined.FindReplace,
                description = "Find & replace",
                onClick = { showFindDialog = true },
            )
            ToolButton(
                icon = Icons.Outlined.TableChart,
                description = "Insert table",
                onClick = { showInsertTableDialog = true },
            )
            ToolButton(
                icon = Icons.Outlined.Image,
                description = "Insert image",
                onClick = { imagePicker.launch("image/*") },
            )
            ToolButton(
                icon = Icons.Outlined.Add,
                description = "Add paragraph below",
                onClick = {
                    val insertAt = (focusedIndex + 1).coerceIn(0, model.elements.size)
                    val next = model.elements.toMutableList().apply { add(insertAt, ParagraphNode()) }
                    commit(model.copy(elements = next))
                    focusedIndex = insertAt
                },
            )
            if (model.elements.size > 1) {
                ToolButton(
                    icon = Icons.Outlined.DeleteOutline,
                    description = "Delete block",
                    onClick = {
                        val idx = focusedIndex.coerceIn(0, model.elements.lastIndex)
                        val next = model.elements.toMutableList().apply { removeAt(idx) }
                        commit(model.copy(elements = next))
                        focusedIndex = (idx - 1).coerceAtLeast(0)
                    },
                )
            }
        },
    ) { padding ->
        if (!isEditMode) {
            // VIEW MODE (Default True Word Page Layout):
            // Renders actual white document paper pages with exact fonts, colors, spacing, indents, tables, and images,
            // plus smooth two-finger pinch-to-zoom, double-tap zoom, and horizontal panning when zoomed in.
            val pagePaperColor = Color(model.pageBackgroundArgb)
            val isDarkPaper = isDarkArgb(model.pageBackgroundArgb)
            val defaultInkColor = if (isDarkPaper) Color(0xFFF8FAFC) else Color(0xFF0F172A)

            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(Slate.colors.background)
                        .clipToBounds()
                        .pointerInput(zoom) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoom > 1.1f) {
                                        zoom = 1f
                                        panX = 0f
                                    } else {
                                        zoom = 1.6f
                                    }
                                }
                            )
                        }
                        .pointerInput(zoom) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val z = event.calculateZoom()
                                        val p = event.calculatePan()
                                        if (abs(z - 1f) > 0.004f || zoom > 1.01f) {
                                            zoom = (zoom * z).coerceIn(0.6f, 3.0f)
                                            if (zoom > 1.01f) {
                                                val maxPanX = (size.width * (zoom - 1f)) / 2f
                                                panX = (panX + p.x).coerceIn(-maxPanX, maxPanX)
                                            } else {
                                                panX = 0f
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else if (event.changes.size == 1 && zoom > 1.02f) {
                                        val p = event.calculatePan()
                                        if (abs(p.x) > abs(p.y)) {
                                            val maxPanX = (size.width * (zoom - 1f)) / 2f
                                            panX = (panX + p.x).coerceIn(-maxPanX, maxPanX)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    LazyColumn(
                        state = viewPageListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = panX,
                            ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(visualPages, key = { _, pg -> pg.pageNumber }) { _, page ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (visualPages.size > 1) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Page ${page.pageNumber} of ${visualPages.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Slate.colors.textSecondary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                // Actual Document Paper Sheet
                                Surface(
                                    color = pagePaperColor,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 520.dp),
                                ) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 24.dp),
                                    ) {
                                        // Optional Document Header
                                        if (model.headerParagraphs.isNotEmpty()) {
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 12.dp),
                                            ) {
                                                model.headerParagraphs.forEach { hp ->
                                                    WordPageParagraphView(
                                                        node = hp,
                                                        fallbackNumberIndex = 0,
                                                        defaultTextColor = defaultInkColor.copy(alpha = 0.65f),
                                                        highlightQuery = searchQuery,
                                                        isMatchedBlock = false,
                                                    )
                                                }
                                                HorizontalDivider(
                                                    Modifier.padding(top = 6.dp),
                                                    thickness = 0.5.dp,
                                                    color = Color(0xFFCBD5E1),
                                                )
                                            }
                                        }

                                        // Page Body Elements (flowing continuously like a real Word page)
                                        page.items.forEach { item ->
                                            val isMatched = item.globalIndex == activeMatchedElementIdx
                                            when (val el = item.element) {
                                                is ParagraphNode -> {
                                                    WordPageParagraphView(
                                                        node = el,
                                                        fallbackNumberIndex = item.fallbackNumberIndex,
                                                        defaultTextColor = defaultInkColor,
                                                        highlightQuery = searchQuery,
                                                        isMatchedBlock = isMatched,
                                                    )
                                                }
                                                is TableNode -> {
                                                    Spacer(Modifier.height(6.dp))
                                                    WordPageTableView(
                                                        table = el,
                                                        defaultTextColor = defaultInkColor,
                                                        highlightQuery = searchQuery,
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                }
                                                is ImageNode -> {
                                                    Spacer(Modifier.height(6.dp))
                                                    WordPageImageView(image = el)
                                                    Spacer(Modifier.height(8.dp))
                                                }
                                            }
                                        }

                                        // Optional Document Footer
                                        if (model.footerParagraphs.isNotEmpty()) {
                                            Spacer(Modifier.height(16.dp))
                                            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFCBD5E1))
                                            Spacer(Modifier.height(6.dp))
                                            model.footerParagraphs.forEach { fp ->
                                                WordPageParagraphView(
                                                    node = fp,
                                                    fallbackNumberIndex = 0,
                                                    defaultTextColor = defaultInkColor.copy(alpha = 0.65f),
                                                    highlightQuery = searchQuery,
                                                    isMatchedBlock = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // EDIT MODE: Only displayed when the user explicitly tapped the Edit button!
            val numberedPrefixes = remember(model.elements) {
                var seq = 0
                model.elements.map { el ->
                    if (el is ParagraphNode && el.listKind == ListKind.NUMBERED) ++seq
                    else { seq = 0; 0 }
                }
            }

            LazyColumn(
                state = editListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = model.elements.size,
                    key = { idx -> model.elements[idx].id },
                ) { index ->
                    val element = model.elements[index]
                    if (element is ParagraphNode && element.isPageBreakOnly) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HorizontalDivider(Modifier.weight(1f), color = Slate.colors.border)
                            Text(
                                " Page Break ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate.colors.textSecondary,
                            )
                            HorizontalDivider(Modifier.weight(1f), color = Slate.colors.border)
                        }
                        return@items
                    }

                    val isMatched = index == activeMatchedElementIdx
                    val isFocused = focusedIndex == index
                    Surface(
                        color = if (isMatched) Color(0xFFFFF3BF) else Color.White,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            width = if (isFocused || isMatched) 1.5.dp else 1.dp,
                            color = when {
                                isMatched -> Slate.colors.pdf
                                isFocused -> Slate.colors.word
                                else -> Color(0xFFCBD5E1)
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            when (element) {
                                is ParagraphNode -> {
                                    EditableParagraphRowView(
                                        node = element,
                                        numberIndex = numberedPrefixes.getOrElse(index) { 0 },
                                        isFocused = isFocused,
                                        onFocus = { focusedIndex = index },
                                        onTextChange = { newText ->
                                            val updatedNode = element.withPlainText(newText)
                                            val updatedList = model.elements.toMutableList().apply { set(index, updatedNode) }
                                            history.replace(model.copy(elements = updatedList))
                                            model = history.current
                                            saveController.markDirty()
                                        },
                                    )
                                }
                                is TableNode -> {
                                    EditableTableBlockView(
                                        table = element,
                                        onFocus = { focusedIndex = index },
                                        onChange = { updatedTable ->
                                            val updatedList = model.elements.toMutableList().apply { set(index, updatedTable) }
                                            commit(model.copy(elements = updatedList))
                                        },
                                    )
                                }
                                is ImageNode -> {
                                    WordPageImageView(
                                        image = element,
                                        isSelected = isFocused,
                                        onSelect = { focusedIndex = index },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFindDialog) {
        FindReplaceDialog(
            initialFind = searchQuery,
            onDismiss = { showFindDialog = false },
            onHighlight = { q -> searchQuery = q },
            onReplaceAll = { find, rep ->
                val (updated, count) = model.replaceAll(find, rep, ignoreCase = true)
                if (count > 0) commit(updated)
                Toast.makeText(context, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
                showFindDialog = false
            },
        )
    }

    if (showInsertTableDialog) {
        InsertTableDialog(
            onDismiss = { showInsertTableDialog = false },
            onInsert = { rows, cols ->
                val grid = List(rows) { r -> List(cols) { c -> if (r == 0) "Header ${c + 1}" else "" } }
                val insertAt = (focusedIndex + 1).coerceIn(0, model.elements.size)
                val next = model.elements.toMutableList().apply { add(insertAt, TableNode(rows = grid)) }
                commit(model.copy(elements = next))
                focusedIndex = insertAt
                showInsertTableDialog = false
            },
        )
    }
}

private fun WordFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    WordFontFamily.SERIF -> FontFamily.Serif
    WordFontFamily.MONOSPACE -> FontFamily.Monospace
    WordFontFamily.SANS_SERIF -> FontFamily.SansSerif
}

private fun isDarkArgb(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) < 128.0
}

/**
 * Renders a single [ParagraphNode] directly on the white Word page in View Mode,
 * preserving multi-run styling, font family (Serif / Monospace / Sans-Serif),
 * colors, highlight/shading, paragraph borders, indentation, bullets/numbers,
 * and exact before/after paragraph spacing.
 */
@Composable
private fun WordPageParagraphView(
    node: ParagraphNode,
    fallbackNumberIndex: Int,
    defaultTextColor: Color,
    highlightQuery: String,
    isMatchedBlock: Boolean,
) {
    val baseRunStyle = node.runs.firstOrNull()?.style ?: SpanStyle()
    val baseFontPt = baseRunStyle.fontSizePt.coerceIn(7f, 56f)
    val align = when (node.align) {
        ParaAlign.LEFT -> TextAlign.Start
        ParaAlign.CENTER -> TextAlign.Center
        ParaAlign.RIGHT -> TextAlign.End
        ParaAlign.JUSTIFY -> TextAlign.Justify
    }

    val prefix = if (node.listPrefix.isNotEmpty()) {
        node.listPrefix
    } else when (node.listKind) {
        ListKind.BULLET -> "•  "
        ListKind.NUMBERED -> "${fallbackNumberIndex.coerceAtLeast(1)}.  "
        ListKind.NONE -> ""
    }

    val spaceBeforeDp = node.spaceBeforePt.coerceIn(0f, 36f).dp
    val spaceAfterDp = node.spaceAfterPt.coerceIn(0f, 36f).dp
    val leftIndentDp = (node.leftIndentPt * 0.7f).coerceIn(0f, 120f).dp
    val rightIndentDp = (node.rightIndentPt * 0.7f).coerceIn(0f, 80f).dp
    val firstLineSp = if (node.firstLineIndentPt > 0f && node.listKind == ListKind.NONE) {
        (node.firstLineIndentPt * 0.75f).coerceIn(0f, 48f).sp
    } else 0.sp

    val bgColor = when {
        isMatchedBlock -> Color(0xFFFFF3BF)
        node.backgroundColorArgb != null -> Color(node.backgroundColorArgb)
        else -> Color.Transparent
    }

    val borders = node.borders
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = spaceBeforeDp,
                bottom = spaceAfterDp,
                start = leftIndentDp,
                end = rightIndentDp,
            )
            .background(bgColor, RoundedCornerShape(2.dp))
            .then(
                if (borders.hasAny) {
                    Modifier.drawBehind {
                        if (borders.topWidthPt > 0f) {
                            drawLine(
                                color = Color(borders.topColorArgb ?: 0xFF94A3B8.toInt()),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = borders.topWidthPt.dp.toPx(),
                            )
                        }
                        if (borders.bottomWidthPt > 0f) {
                            drawLine(
                                color = Color(borders.bottomColorArgb ?: 0xFF94A3B8.toInt()),
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = borders.bottomWidthPt.dp.toPx(),
                            )
                        }
                        if (borders.leftWidthPt > 0f) {
                            drawLine(
                                color = Color(borders.leftColorArgb ?: 0xFF94A3B8.toInt()),
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = borders.leftWidthPt.dp.toPx(),
                            )
                        }
                        if (borders.rightWidthPt > 0f) {
                            drawLine(
                                color = Color(borders.rightColorArgb ?: 0xFF94A3B8.toInt()),
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = borders.rightWidthPt.dp.toPx(),
                            )
                        }
                    }.padding(
                        top = if (borders.topWidthPt > 0f) 4.dp else 0.dp,
                        bottom = if (borders.bottomWidthPt > 0f) 4.dp else 0.dp,
                        start = if (borders.leftWidthPt > 0f) 8.dp else 0.dp,
                        end = if (borders.rightWidthPt > 0f) 8.dp else 0.dp,
                    )
                } else if (node.backgroundColorArgb != null) {
                    Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                } else {
                    Modifier
                }
            )
    ) {
        if (node.plainText.isEmpty() && prefix.isEmpty()) {
            // Blank paragraph spacer line in the Word document
            Spacer(Modifier.height((baseFontPt * 0.75f).dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (prefix.isNotEmpty()) {
                    Text(
                        text = prefix,
                        style = TextStyle(
                            fontSize = baseFontPt.sp,
                            fontWeight = if (baseRunStyle.bold || node.headingLevel > 0) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = baseRunStyle.fontFamily.toComposeFontFamily(),
                            color = baseRunStyle.colorArgb?.let { Color(it) } ?: defaultTextColor,
                            lineHeight = (baseFontPt * node.lineSpacingMultiplier.coerceIn(1.1f, 2.2f)).sp,
                        ),
                    )
                }

                val annotated = buildAnnotatedString {
                    for (run in node.runs) {
                        val rStyle = run.style
                        val rFontPt = rStyle.fontSizePt.coerceIn(7f, 56f)
                        val effectiveSize = if (rStyle.superscript || rStyle.subscript) (rFontPt * 0.75f).sp else rFontPt.sp
                        val deco = when {
                            rStyle.underline && rStyle.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            rStyle.underline -> TextDecoration.Underline
                            rStyle.strikethrough -> TextDecoration.LineThrough
                            else -> TextDecoration.None
                        }
                        val shift = when {
                            rStyle.superscript -> BaselineShift.Superscript
                            rStyle.subscript -> BaselineShift.Subscript
                            else -> BaselineShift.None
                        }
                        val runSpan = ComposeSpanStyle(
                            fontSize = effectiveSize,
                            fontWeight = if (rStyle.bold || node.headingLevel in 1..3) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (rStyle.italic) FontStyle.Italic else FontStyle.Normal,
                            fontFamily = rStyle.fontFamily.toComposeFontFamily(),
                            textDecoration = deco,
                            baselineShift = shift,
                            color = rStyle.colorArgb?.let { Color(it) } ?: defaultTextColor,
                            background = rStyle.backgroundColorArgb?.let { Color(it) } ?: Color.Unspecified,
                        )
                        val txt = run.text
                        if (highlightQuery.isNotBlank() && txt.contains(highlightQuery, ignoreCase = true)) {
                            var start = 0
                            while (start < txt.length) {
                                val idx = txt.indexOf(highlightQuery, start, ignoreCase = true)
                                if (idx < 0) {
                                    withStyle(runSpan) { append(txt.substring(start)) }
                                    break
                                }
                                if (idx > start) {
                                    withStyle(runSpan) { append(txt.substring(start, idx)) }
                                }
                                withStyle(
                                    runSpan.copy(
                                        background = Color(0xFFFFEC99),
                                        color = Color(0xFF0F172A),
                                        fontWeight = FontWeight.Bold,
                                    )
                                ) {
                                    append(txt.substring(idx, idx + highlightQuery.length))
                                }
                                start = idx + highlightQuery.length
                            }
                        } else {
                            withStyle(runSpan) { append(txt) }
                        }
                    }
                }

                val maxRunPt = node.runs.maxOfOrNull { it.style.fontSizePt }?.coerceIn(7f, 56f) ?: baseFontPt
                Text(
                    text = annotated,
                    style = TextStyle(
                        fontSize = baseFontPt.sp,
                        fontFamily = baseRunStyle.fontFamily.toComposeFontFamily(),
                        textAlign = align,
                        textIndent = TextIndent(firstLine = firstLineSp),
                        lineHeight = (maxRunPt * node.lineSpacingMultiplier.coerceIn(1.1f, 2.2f)).sp,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Renders a [TableNode] on the Word page in View Mode with exact proportional column widths,
 * merged columns (`gridSpan`), cell background shading, border colors, and rich styled cell paragraphs.
 */
@Composable
private fun WordPageTableView(
    table: TableNode,
    defaultTextColor: Color,
    highlightQuery: String,
) {
    val totalCols = table.colCount.coerceAtLeast(1)
    val colFractions = if (table.colWidthFractions.size == totalCols) {
        table.colWidthFractions
    } else {
        List(totalCols) { 1f / totalCols }
    }
    val borderColor = Color(table.borderColorArgb ?: 0xFFCBD5E1.toInt())
    val borderWidth = if (table.hasBorders) 0.75.dp else 0.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (table.hasBorders) Modifier.border(borderWidth, borderColor) else Modifier
            )
    ) {
        table.rows.indices.forEach { rIdx ->
            val richRow = table.richRows.getOrNull(rIdx)
            val plainRow = table.rows[rIdx]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                if (richRow != null && richRow.isNotEmpty()) {
                    var colCursor = 0
                    richRow.forEach { cell ->
                        val span = cell.gridSpan.coerceIn(1, (totalCols - colCursor).coerceAtLeast(1))
                        val weightFrac = colFractions.subList(
                            colCursor.coerceIn(0, colFractions.size),
                            (colCursor + span).coerceIn(0, colFractions.size),
                        ).sum().takeIf { it > 0f } ?: (span.toFloat() / totalCols)
                        colCursor += span

                        val isMatch = highlightQuery.isNotBlank() && cell.plainText.contains(highlightQuery, ignoreCase = true)
                        val cellBg = when {
                            isMatch -> Color(0xFFFFF3BF)
                            cell.fillArgb != null -> Color(cell.fillArgb)
                            else -> Color.Transparent
                        }
                        val vAlign = when (cell.verticalAlign) {
                            "center" -> Alignment.CenterStart
                            "bottom" -> Alignment.BottomStart
                            else -> Alignment.TopStart
                        }

                        Box(
                            modifier = Modifier
                                .weight(weightFrac.coerceAtLeast(0.05f))
                                .fillMaxHeight()
                                .background(cellBg)
                                .then(
                                    if (table.hasBorders) {
                                        Modifier.border(0.5.dp, cell.borderColorArgb?.let { Color(it) } ?: borderColor)
                                    } else Modifier
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = vAlign,
                        ) {
                            if (!cell.vMergeContinue) {
                                Column(Modifier.fillMaxWidth()) {
                                    cell.paragraphs.forEachIndexed { pIdx, pNode ->
                                        WordPageParagraphView(
                                            node = pNode.copy(
                                                spaceBeforePt = if (pIdx == 0) 0f else pNode.spaceBeforePt.coerceAtMost(4f),
                                                spaceAfterPt = if (pIdx == cell.paragraphs.lastIndex) 0f else pNode.spaceAfterPt.coerceAtMost(4f),
                                            ),
                                            fallbackNumberIndex = pIdx + 1,
                                            defaultTextColor = defaultTextColor,
                                            highlightQuery = highlightQuery,
                                            isMatchedBlock = false,
                                        )
                                    }
                                    cell.images.forEach { img ->
                                        WordPageImageView(image = img)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    for (cIdx in 0 until totalCols) {
                        val cell = table.cellAt(rIdx, cIdx)
                        val weightFrac = colFractions.getOrElse(cIdx) { 1f / totalCols }.coerceAtLeast(0.05f)
                        val isMatch = highlightQuery.isNotBlank() && cell.plainText.contains(highlightQuery, ignoreCase = true)
                        val cellBg = when {
                            isMatch -> Color(0xFFFFF3BF)
                            cell.fillArgb != null -> Color(cell.fillArgb)
                            else -> Color.Transparent
                        }
                        Box(
                            modifier = Modifier
                                .weight(weightFrac)
                                .fillMaxHeight()
                                .background(cellBg)
                                .then(if (table.hasBorders) Modifier.border(0.5.dp, borderColor) else Modifier)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            cell.paragraphs.forEach { pNode ->
                                WordPageParagraphView(
                                    node = pNode.copy(spaceBeforePt = 0f, spaceAfterPt = 0f),
                                    fallbackNumberIndex = 1,
                                    defaultTextColor = defaultTextColor,
                                    highlightQuery = highlightQuery,
                                    isMatchedBlock = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordPageImageView(
    image: ImageNode,
    isSelected: Boolean = false,
    onSelect: (() -> Unit)? = null,
) {
    val bmp = remember(image.id, image.bytes.size) { image.decodeBitmap(1400) }
    val boxAlign = when (image.align) {
        ParaAlign.LEFT -> Alignment.CenterStart
        ParaAlign.RIGHT -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    // Proportional width relative to standard 487pt content width
    val widthFraction = (image.widthPt / 487f).coerceIn(0.25f, 1.0f)
    val aspect = if (image.widthPt > 0 && image.heightPt > 0) {
        (image.widthPt.toFloat() / image.heightPt.toFloat()).coerceIn(0.25f, 5.0f)
    } else if (bmp != null && bmp.height > 0) {
        (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.25f, 5.0f)
    } else 1.5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onSelect != null) Modifier.clickable { onSelect() } else Modifier),
        contentAlignment = boxAlign,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = image.caption.ifBlank { "Document image" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .aspectRatio(aspect)
                    .then(
                        if (isSelected) Modifier.border(2.dp, Slate.colors.word, RoundedCornerShape(4.dp))
                        else Modifier
                    ),
            )
        } else {
            Text("Image (${image.bytes.size / 1024} KB)", color = Color(0xFF64748B), fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditableParagraphRowView(
    node: ParagraphNode,
    numberIndex: Int,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    val style = node.runs.firstOrNull()?.style ?: SpanStyle()
    val fontSize = style.fontSizeSp.coerceIn(9, 48).sp
    val fontWeight = if (style.bold || node.headingLevel > 0) FontWeight.Bold else FontWeight.Normal
    val fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal
    val textDeco = if (style.underline) TextDecoration.Underline else TextDecoration.None
    val align = when (node.align) {
        ParaAlign.LEFT -> TextAlign.Start
        ParaAlign.CENTER -> TextAlign.Center
        ParaAlign.RIGHT -> TextAlign.End
        ParaAlign.JUSTIFY -> TextAlign.Justify
    }

    val prefix = if (node.listPrefix.isNotEmpty()) node.listPrefix else when (node.listKind) {
        ListKind.BULLET -> "•  "
        ListKind.NUMBERED -> "$numberIndex.  "
        ListKind.NONE -> ""
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        if (prefix.isNotEmpty()) {
            Text(
                text = prefix,
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    fontFamily = style.fontFamily.toComposeFontFamily(),
                    color = style.colorArgb?.let { Color(it) } ?: Color(0xFF0F172A),
                ),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            if (node.plainText.isEmpty() && !isFocused) {
                Text(
                    text = if (node.headingLevel > 0) "Heading ${node.headingLevel}" else "Tap to type…",
                    style = TextStyle(fontSize = fontSize, color = Color(0xFF94A3B8), textAlign = align),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicTextField(
                value = node.plainText,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    fontFamily = style.fontFamily.toComposeFontFamily(),
                    textDecoration = textDeco,
                    color = style.colorArgb?.let { Color(it) } ?: Color(0xFF0F172A),
                    textAlign = align,
                    lineHeight = (style.fontSizeSp * 1.35f).sp,
                ),
                cursorBrush = SolidColor(Color(0xFF0F172A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) onFocus() },
            )
        }
    }
}

@Composable
private fun EditableTableBlockView(
    table: TableNode,
    onFocus: () -> Unit,
    onChange: (TableNode) -> Unit,
) {
    val cols = table.colCount.coerceAtLeast(1)
    val cellW = 132.dp

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                table.rows.forEachIndexed { rIdx, row ->
                    Row {
                        for (cIdx in 0 until cols) {
                            val cellVal = row.getOrNull(cIdx).orEmpty()
                            val cell = table.cellAt(rIdx, cIdx)
                            Box(
                                Modifier
                                    .width(cellW)
                                    .border(0.5.dp, Color(0xFFCBD5E1))
                                    .background(cell.fillArgb?.let { Color(it) } ?: if (rIdx == 0 && table.headerRow) Color(0xFFF1F5F9) else Color.White)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                BasicTextField(
                                    value = cellVal,
                                    onValueChange = { onChange(table.withCell(rIdx, cIdx, it)) },
                                    textStyle = TextStyle(
                                        fontSize = 13.sp,
                                        fontWeight = if (rIdx == 0 && table.headerRow) FontWeight.SemiBold else FontWeight.Normal,
                                        color = Color(0xFF0F172A),
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF0F172A)),
                                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(table.addRow()) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("+ Row", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { onChange(table.addCol()) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("+ Column", style = MaterialTheme.typography.labelMedium)
            }
            if (table.rowCount > 1) {
                TextButton(onClick = { onChange(table.removeRow(table.rowCount - 1)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("- Row", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (table.colCount > 1) {
                TextButton(onClick = { onChange(table.removeCol(table.colCount - 1)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("- Column", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun FindReplaceDialog(
    initialFind: String,
    onDismiss: () -> Unit,
    onHighlight: (String) -> Unit,
    onReplaceAll: (String, String) -> Unit,
) {
    var find by remember { mutableStateOf(initialFind) }
    var replace by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find & Replace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it; onHighlight(it) },
                    label = { Text("Find text") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    label = { Text("Replace with") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReplaceAll(find, replace) }, enabled = find.isNotEmpty()) {
                Text("Replace all")
            }
        },
        dismissButton = {
            Row {
                if (find.isNotEmpty()) {
                    TextButton(onClick = { find = ""; onHighlight(""); onDismiss() }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}

@Composable
private fun InsertTableDialog(
    onDismiss: () -> Unit,
    onInsert: (rows: Int, cols: Int) -> Unit,
) {
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert table") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Rows: $rows")
                    Row {
                        IconButton(onClick = { rows = (rows - 1).coerceAtLeast(1) }) { Icon(Icons.Outlined.Remove, null) }
                        IconButton(onClick = { rows = (rows + 1).coerceAtMost(20) }) { Icon(Icons.Outlined.Add, null) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Columns: $cols")
                    Row {
                        IconButton(onClick = { cols = (cols - 1).coerceAtLeast(1) }) { Icon(Icons.Outlined.Remove, null) }
                        IconButton(onClick = { cols = (cols + 1).coerceAtMost(10) }) { Icon(Icons.Outlined.Add, null) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onInsert(rows, cols) }) { Text("Insert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("SLATE", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
