package app.slate.slides

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import app.slate.ui.components.TextPromptDialog
import app.slate.ui.editor.DocumentSearchBar
import app.slate.ui.editor.EditorScaffold
import app.slate.ui.editor.ToolButton
import app.slate.ui.editor.rememberSaveController
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

data class SlideSearchMatch(
    val slideIndex: Int,
    val itemIndex: Int,
    val preview: String,
)

@Composable
fun SlidesEditorScreen(doc: OpenDoc, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val history = remember { UndoStack(PresentationDocument(title = doc.name)) }
    var pres by remember { mutableStateOf(history.current) }

    // CRITICAL: Presentations ALWAYS open in Read-Only VIEW MODE with Vertical Scroll ("Up and Down") by default.
    var isEditMode by remember { mutableStateOf(false) }
    var editSnapshot by remember { mutableStateOf<PresentationDocument?>(null) }

    var currentSlideIdx by remember { mutableIntStateOf(0) }
    var selectedItemIdx by remember { mutableIntStateOf(0) }
    var presenting by remember { mutableStateOf(false) }
    var verticalDeckMode by remember { mutableStateOf(true) }

    // Zoom & pan state
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // Search state across all slides
    var showSearchBar by remember { mutableStateOf(!doc.query.isNullOrBlank()) }
    var searchQuery by remember { mutableStateOf(doc.query.orEmpty()) }
    var activeMatchIdx by remember { mutableIntStateOf(0) }

    var showInsertShapeMenu by remember { mutableStateOf(false) }
    var showGotoSlideDialog by remember { mutableStateOf(false) }
    var showSlideTextSheet by remember { mutableStateOf(false) }

    val thumbRowState = rememberLazyListState()
    val deckListState = rememberLazyListState()

    val writable = remember { listOf(DocFormat.PPTX, DocFormat.ODP) }
    val saveController = rememberSaveController(doc, writable) { fmt, out ->
        SlidesFormatCodecs.write(pres, fmt, out)
    }

    fun commit(next: PresentationDocument) {
        history.push(next)
        pres = history.current
        saveController.markDirty()
    }

    fun jumpToSlide(idx: Int) {
        val target = idx.coerceIn(0, (pres.slides.size - 1).coerceAtLeast(0))
        currentSlideIdx = target
        selectedItemIdx = 0
        if (verticalDeckMode && !isEditMode) {
            scope.launch { runCatching { deckListState.animateScrollToItem(target) } }
        }
    }

    LaunchedEffect(doc.uri, doc.recoveryFile) {
        loading = true
        loadError = null
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                doc.openSource(context).use { SlidesFormatCodecs.read(it, doc.sourceFormat(), doc.name) }
            }
        }
        loaded.onSuccess { p ->
            history.reset(p)
            pres = p
            val slideFromTarget = parseTarget(doc.target)["slide"]?.toIntOrNull() ?: doc.lastPosition
            val initialSlide = slideFromTarget.coerceIn(0, p.slides.lastIndex)
            currentSlideIdx = initialSlide
            selectedItemIdx = 0
            loading = false
            if (initialSlide > 0) {
                runCatching { deckListState.scrollToItem(initialSlide) }
            }
        }.onFailure {
            loadError = it.message ?: "Unable to read presentation"
            loading = false
        }
    }

    // Track current slide index automatically as the user scrolls up and down in verticalDeckMode
    LaunchedEffect(deckListState, verticalDeckMode, isEditMode, loading) {
        if (!loading && verticalDeckMode && !isEditMode) {
            snapshotFlow { deckListState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { visibleIdx ->
                    if (visibleIdx in pres.slides.indices) {
                        currentSlideIdx = visibleIdx
                    }
                }
        }
    }

    LaunchedEffect(currentSlideIdx, pres.slides.size) {
        if (!loading && pres.slides.isNotEmpty()) {
            app.recents.setPosition(doc.uri, currentSlideIdx, pres.slides.size)
            if (!verticalDeckMode || isEditMode) {
                runCatching { thumbRowState.animateScrollToItem(currentSlideIdx) }
            }
        }
    }

    // Reset pan when returning to 100% zoom
    LaunchedEffect(zoom) {
        if (zoom <= 1.01f) {
            panX = 0f
            panY = 0f
        }
    }

    // Compute search matches across all slides
    val searchMatches = remember(pres, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList()
        else buildList {
            pres.slides.forEachIndexed { sIdx, slide ->
                slide.items.forEachIndexed { iIdx, item ->
                    val txt = item.fullText
                    if (txt.contains(q, ignoreCase = true)) {
                        add(SlideSearchMatch(sIdx, iIdx, txt.lineSequence().firstOrNull().orEmpty()))
                    }
                }
            }
        }
    }

    fun navigateToMatch(match: SlideSearchMatch) {
        currentSlideIdx = match.slideIndex.coerceIn(0, pres.slides.lastIndex)
        selectedItemIdx = match.itemIndex
        if (verticalDeckMode) {
            scope.launch { runCatching { deckListState.animateScrollToItem(currentSlideIdx) } }
        }
    }

    LaunchedEffect(searchQuery, searchMatches.size) {
        if (searchQuery.isNotBlank() && searchMatches.isNotEmpty()) {
            activeMatchIdx = 0
            navigateToMatch(searchMatches[0])
        } else {
            activeMatchIdx = 0
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching { DocIO.readBytes(context, uri) }.getOrNull()
            if (bytes != null) {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val newItem = SlideItem(
                    kind = ShapeKind.RECTANGLE,
                    x = 0.16f, y = 0.22f, w = 0.68f, h = 0.56f,
                    imageBytes = bytes,
                    imageMime = mime,
                )
                commit(
                    pres.updateSlide(currentSlideIdx) { s ->
                        s.copy(items = s.items + newItem)
                    }
                )
                selectedItemIdx = pres.slides[currentSlideIdx].items.lastIndex
            }
        }
    }

    if (loading) {
        LoadingState("Opening ${doc.name}…")
        return
    }
    if (loadError != null) {
        ErrorState("Couldn't open presentation", loadError!!, onClose = onClose)
        return
    }

    val totalSlides = pres.slides.size.coerceAtLeast(1)
    currentSlideIdx = currentSlideIdx.coerceIn(0, pres.slides.lastIndex)
    val activeSlide = pres.slides.getOrElse(currentSlideIdx) { SlidePage() }
    val activeItem = activeSlide.items.getOrNull(selectedItemIdx)

    if (presenting) {
        FullScreenPresentationMode(
            pres = pres,
            startIndex = currentSlideIdx,
            onExit = { idx ->
                currentSlideIdx = idx
                presenting = false
            },
        )
        return
    }

    fun updateActiveItem(transform: (SlideItem) -> SlideItem) {
        val idx = selectedItemIdx.coerceIn(0, activeSlide.items.lastIndex)
        if (idx !in activeSlide.items.indices) return
        commit(
            pres.updateSlide(currentSlideIdx) { s ->
                val list = s.items.toMutableList().apply { set(idx, transform(get(idx))) }
                s.copy(items = list)
            }
        )
    }

    val subtitle = buildString {
        if (doc.format == DocFormat.PPT) append("PPT • ")
        append("Slide ${currentSlideIdx + 1} of $totalSlides")
        if (searchMatches.isNotEmpty()) {
            val matchingSlides = searchMatches.map { it.slideIndex }.distinct().size
            append(" • $matchingSlides matching slide(s)")
        } else if (saveController.dirty) {
            append(" • Unsaved")
        }
    }

    val zoomPct = (zoom * 100).roundToInt()
    val currentMatch = searchMatches.getOrNull(activeMatchIdx)

    EditorScaffold(
        title = doc.name,
        controller = saveController,
        onClose = onClose,
        canUndo = history.canUndo,
        canRedo = history.canRedo,
        onUndo = { pres = history.undo(); saveController.markDirty() },
        onRedo = { pres = history.redo(); saveController.markDirty() },
        exportFormats = listOf(DocFormat.PDF),
        subtitle = subtitle,
        isEditMode = isEditMode,
        onToggleEditMode = {
            if (!isEditMode) {
                editSnapshot = pres
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
                pres = snap
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
        onZoomIn = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
        onZoomOut = {
            zoom = (zoom - 0.25f).coerceAtLeast(1f)
            if (zoom <= 1.01f) { panX = 0f; panY = 0f }
        },
        onResetZoom = { zoom = 1f; panX = 0f; panY = 0f },
        actions = {
            Surface(
                onClick = { presenting = true },
                shape = RoundedCornerShape(10.dp),
                color = Slate.colors.slides,
                contentColor = Color.White,
                modifier = Modifier.height(34.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Outlined.Slideshow, "Present full screen", modifier = Modifier.size(17.dp))
                    Text("Present", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        searchBar = {
            val matchSlideLabel = currentMatch?.let { "Slide ${it.slideIndex + 1}" }
            DocumentSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search text across all $totalSlides slides…",
                matchCount = searchMatches.size,
                currentMatchIndex = activeMatchIdx,
                extraInfo = matchSlideLabel,
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
                text = { Text("Present full screen (Slide Show)") },
                leadingIcon = { Icon(Icons.Outlined.Slideshow, null) },
                onClick = { dismiss(); presenting = true },
            )
            if (!isEditMode) {
                DropdownMenuItem(
                    text = { Text(if (verticalDeckMode) "Single slide view" else "Vertical scroll view (Up & Down)") },
                    leadingIcon = { Icon(if (verticalDeckMode) Icons.Outlined.FitScreen else Icons.Outlined.ViewStream, null) },
                    onClick = { dismiss(); verticalDeckMode = !verticalDeckMode },
                )
            }
            DropdownMenuItem(
                text = { Text("Jump to slide…") },
                leadingIcon = { Icon(Icons.Outlined.Numbers, null) },
                onClick = { dismiss(); showGotoSlideDialog = true },
            )
            DropdownMenuItem(
                text = { Text("Select & copy slide text…") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { dismiss(); showSlideTextSheet = true },
            )
            DropdownMenuItem(
                text = { Text("Export as PDF…") },
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                onClick = { dismiss(); saveController.saveAs(DocFormat.PDF) },
            )
            if (isEditMode) {
                DropdownMenuItem(
                    text = { Text("Duplicate current slide") },
                    onClick = {
                        dismiss()
                        commit(pres.duplicateSlide(currentSlideIdx))
                        currentSlideIdx = (currentSlideIdx + 1).coerceAtMost(pres.slides.lastIndex)
                    },
                )
                if (currentSlideIdx > 0) {
                    DropdownMenuItem(
                        text = { Text("Move slide earlier") },
                        onClick = {
                            dismiss()
                            commit(pres.moveSlide(currentSlideIdx, currentSlideIdx - 1))
                            currentSlideIdx--
                        },
                    )
                }
                if (currentSlideIdx < pres.slides.lastIndex) {
                    DropdownMenuItem(
                        text = { Text("Move slide later") },
                        onClick = {
                            dismiss()
                            commit(pres.moveSlide(currentSlideIdx, currentSlideIdx + 1))
                            currentSlideIdx++
                        },
                    )
                }
                if (pres.slides.size > 1) {
                    DropdownMenuItem(
                        text = { Text("Delete current slide", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            dismiss()
                            commit(pres.deleteSlide(currentSlideIdx))
                            currentSlideIdx = currentSlideIdx.coerceAtMost(pres.slides.lastIndex)
                        },
                    )
                }
            }
        },
        toolbar = {
            ToolButton(
                icon = Icons.Outlined.FormatBold,
                description = "Bold",
                selected = activeItem?.bold == true,
                onClick = {
                    updateActiveItem { item ->
                        val nextBold = !item.bold
                        item.copy(
                            bold = nextBold,
                            paragraphs = item.paragraphs.map { p -> p.copy(runs = p.runs.map { r -> r.copy(bold = nextBold) }) }
                        )
                    }
                },
            )
            ToolButton(
                icon = Icons.Outlined.FormatItalic,
                description = "Italic",
                selected = activeItem?.italic == true,
                onClick = {
                    updateActiveItem { item ->
                        val nextItalic = !item.italic
                        item.copy(
                            italic = nextItalic,
                            paragraphs = item.paragraphs.map { p -> p.copy(runs = p.runs.map { r -> r.copy(italic = nextItalic) }) }
                        )
                    }
                },
            )
            ToolButton(
                icon = Icons.Outlined.TextDecrease,
                description = "Smaller text",
                onClick = {
                    updateActiveItem { item ->
                        val nextSz = (item.fontSizeSp - 2).coerceAtLeast(8)
                        item.copy(
                            fontSizeSp = nextSz,
                            paragraphs = item.paragraphs.map { p ->
                                p.copy(runs = p.runs.map { r -> r.copy(fontSizePt = (r.fontSizePt - 2f).coerceAtLeast(8f)) })
                            },
                        )
                    }
                },
            )
            Text("${activeItem?.fontSizeSp ?: 20}pt", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
            ToolButton(
                icon = Icons.Outlined.TextIncrease,
                description = "Larger text",
                onClick = {
                    updateActiveItem { item ->
                        val nextSz = (item.fontSizeSp + 2).coerceAtMost(64)
                        item.copy(
                            fontSizeSp = nextSz,
                            paragraphs = item.paragraphs.map { p ->
                                p.copy(runs = p.runs.map { r -> r.copy(fontSizePt = (r.fontSizePt + 2f).coerceAtMost(64f)) })
                            },
                        )
                    }
                },
            )
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = Slate.colors.border)
            AssistChip(
                onClick = {
                    val newItem = SlideItem(
                        kind = ShapeKind.TEXT_BOX,
                        x = 0.10f, y = 0.35f, w = 0.80f, h = 0.25f,
                        text = "New text box",
                        fontSizeSp = 20,
                        paragraphs = listOf(SlideParagraph(listOf(SlideRun("New text box", fontSizePt = 20f)))),
                    )
                    commit(pres.updateSlide(currentSlideIdx) { it.copy(items = it.items + newItem) })
                    selectedItemIdx = pres.slides[currentSlideIdx].items.lastIndex
                },
                label = { Text("+ Text") },
                modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
            )
            Box {
                AssistChip(
                    onClick = { showInsertShapeMenu = true },
                    label = { Text("+ Shape") },
                    modifier = Modifier.height(32.dp).padding(horizontal = 2.dp),
                )
                DropdownMenu(expanded = showInsertShapeMenu, onDismissRequest = { showInsertShapeMenu = false }) {
                    listOf(ShapeKind.RECTANGLE, ShapeKind.ROUND_RECT, ShapeKind.ELLIPSE, ShapeKind.CALLOUT).forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                showInsertShapeMenu = false
                                val shapeItem = SlideItem(
                                    kind = kind,
                                    x = 0.22f, y = 0.28f, w = 0.56f, h = 0.32f,
                                    text = kind.label,
                                    bold = true,
                                    fontSizeSp = 18,
                                    fillArgb = 0xFFE2E8F0.toInt(),
                                    strokeColorArgb = 0xFF94A3B8.toInt(),
                                    verticalAlign = SlideVerticalAlign.MIDDLE,
                                    paragraphs = listOf(
                                        SlideParagraph(
                                            runs = listOf(SlideRun(kind.label, bold = true, fontSizePt = 18f)),
                                            align = SlideTextAlign.CENTER,
                                        )
                                    ),
                                )
                                commit(pres.updateSlide(currentSlideIdx) { it.copy(items = it.items + shapeItem) })
                                selectedItemIdx = pres.slides[currentSlideIdx].items.lastIndex
                            },
                        )
                    }
                }
            }
            ToolButton(
                icon = Icons.Outlined.Image,
                description = "Insert image",
                onClick = { imagePicker.launch("image/*") },
            )
            if (activeSlide.items.size > 1 && selectedItemIdx in activeSlide.items.indices) {
                ToolButton(
                    icon = Icons.Outlined.DeleteOutline,
                    description = "Delete element",
                    onClick = {
                        val idx = selectedItemIdx
                        commit(pres.updateSlide(currentSlideIdx) { s -> s.copy(items = s.items.filterIndexed { i, _ -> i != idx }) })
                        selectedItemIdx = (idx - 1).coerceAtLeast(0)
                    },
                )
            }
        },
        bottomBar = {
            Surface(color = Slate.colors.surface, border = BorderStroke(1.dp, Slate.colors.border)) {
                Column(Modifier.navigationBarsPadding()) {
                    // Slide Navigation Controls Bar (Previous / Slide X of Y / Next / Zoom / Fullscreen)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Slate.colors.surfaceAlt)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (currentSlideIdx > 0) jumpToSlide(currentSlideIdx - 1) },
                                enabled = currentSlideIdx > 0,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous slide")
                            }
                            Surface(
                                onClick = { showGotoSlideDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                color = Slate.colors.surface,
                                border = BorderStroke(1.dp, Slate.colors.border),
                            ) {
                                Text(
                                    text = "Slide ${currentSlideIdx + 1} / $totalSlides",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            IconButton(
                                onClick = { if (currentSlideIdx < pres.slides.lastIndex) jumpToSlide(currentSlideIdx + 1) },
                                enabled = currentSlideIdx < pres.slides.lastIndex,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next slide")
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(
                                onClick = {
                                    zoom = (zoom - 0.25f).coerceAtLeast(1f)
                                    if (zoom <= 1.01f) { panX = 0f; panY = 0f }
                                },
                                enabled = zoom > 1.01f,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Outlined.Remove, "Zoom out", modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "$zoomPct%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { zoom = 1f; panX = 0f; panY = 0f }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            IconButton(
                                onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Outlined.Add, "Zoom in", modifier = Modifier.size(16.dp))
                            }
                            if (!isEditMode) {
                                IconButton(
                                    onClick = { verticalDeckMode = !verticalDeckMode },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = if (verticalDeckMode) Icons.Outlined.FitScreen else Icons.Outlined.ViewStream,
                                        contentDescription = if (verticalDeckMode) "Single slide view" else "Scroll slides up and down",
                                        tint = if (verticalDeckMode) Slate.colors.slides else Slate.colors.textSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Show thumbnail strip only in Edit mode or Single-Slide mode so Vertical Scroll mode is never cluttered by side-by-side cards
                    if (isEditMode || !verticalDeckMode) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LazyRow(
                                state = thumbRowState,
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(pres.slides, key = { _, s -> s.id }) { idx, slide ->
                                    val selected = idx == currentSlideIdx
                                    val hasMatch = searchQuery.isNotBlank() && searchMatches.any { it.slideIndex == idx }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(slide.backgroundArgb),
                                        border = BorderStroke(
                                            width = if (selected || hasMatch) 2.dp else 1.dp,
                                            color = when {
                                                hasMatch -> Color(0xFFF59E0B)
                                                selected -> Slate.colors.slides
                                                else -> Slate.colors.border
                                            },
                                        ),
                                        onClick = { jumpToSlide(idx) },
                                        modifier = Modifier.width(92.dp).height(52.dp),
                                    ) {
                                        Box(Modifier.fillMaxSize()) {
                                            SlideCanvasView(
                                                slide = slide,
                                                slideWidthPt = pres.widthPt,
                                                highlightQuery = if (hasMatch) searchQuery else "",
                                                selectedItemIdx = -1,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            Surface(
                                                color = if (selected) Slate.colors.slides else Color(0xAA0F172A),
                                                shape = RoundedCornerShape(bottomEnd = 6.dp),
                                                modifier = Modifier.align(Alignment.TopStart),
                                            ) {
                                                Text(
                                                    text = "${idx + 1}",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (isEditMode) {
                                Spacer(Modifier.width(8.dp))
                                FilledTonalIconButton(
                                    onClick = {
                                        commit(pres.addSlide(currentSlideIdx))
                                        currentSlideIdx = (currentSlideIdx + 1).coerceAtMost(pres.slides.lastIndex)
                                        selectedItemIdx = 0
                                    },
                                ) {
                                    Icon(Icons.Outlined.Add, "Add slide")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!isEditMode && verticalDeckMode) {
            // VIEW MODE (Default Vertical Continuous Slide Deck Reader):
            // Stacks all slides vertically ("up and down") so the user scrolls naturally through all pages,
            // with two-finger pinch-to-zoom, horizontal pan when zoomed in, and double-tap zoom.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clipToBounds()
                    .pointerInput(zoom) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    val zChange = event.calculateZoom()
                                    val pChange = event.calculatePan()
                                    if (abs(zChange - 1f) > 0.004f || zoom > 1.01f) {
                                        zoom = (zoom * zChange).coerceIn(1f, 4f)
                                        if (zoom > 1.01f) {
                                            val maxPanX = (size.width * (zoom - 1f)) / 2f
                                            val maxPanY = (size.height * (zoom - 1f)) / 2f
                                            panX = (panX + pChange.x).coerceIn(-maxPanX, maxPanX)
                                            panY = (panY + pChange.y).coerceIn(-maxPanY, maxPanY)
                                        } else {
                                            panX = 0f; panY = 0f
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } else if (event.changes.size == 1 && zoom > 1.02f) {
                                    val pChange = event.calculatePan()
                                    if (abs(pChange.x) > abs(pChange.y)) {
                                        val maxPanX = (size.width * (zoom - 1f)) / 2f
                                        panX = (panX + pChange.x).coerceIn(-maxPanX, maxPanX)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                LazyColumn(
                    state = deckListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(pres.slides, key = { _, s -> s.id }) { idx, slide ->
                        val isCurrent = idx == currentSlideIdx
                        val hasMatchOnSlide = searchQuery.isNotBlank() && searchMatches.any { it.slideIndex == idx }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isCurrent) Slate.colors.slides else Slate.colors.surfaceAlt,
                                    ) {
                                        Text(
                                            text = "Slide ${idx + 1} of $totalSlides",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isCurrent) Color.White else Slate.colors.textSecondary,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                    if (hasMatchOnSlide) {
                                        Text(
                                            text = "Match found",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFD97706),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "Read / Copy Text",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Slate.colors.accent,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                currentSlideIdx = idx
                                                showSlideTextSheet = true
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                    Text(
                                        text = "Full Screen",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Slate.colors.slides,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                currentSlideIdx = idx
                                                presenting = true
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(slide.backgroundArgb),
                                border = BorderStroke(
                                    width = if (isCurrent || hasMatchOnSlide) 2.dp else 1.dp,
                                    color = when {
                                        hasMatchOnSlide -> Color(0xFFF59E0B)
                                        isCurrent -> Slate.colors.slides
                                        else -> Slate.colors.border
                                    },
                                ),
                                shadowElevation = 3.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(pres.aspectRatio)
                                    .pointerInput(idx, zoom) {
                                        detectTapGestures(
                                            onTap = { currentSlideIdx = idx },
                                            onDoubleTap = {
                                                currentSlideIdx = idx
                                                if (zoom > 1.1f) {
                                                    zoom = 1f; panX = 0f; panY = 0f
                                                } else {
                                                    zoom = 2.0f
                                                }
                                            },
                                        )
                                    },
                            ) {
                                SlideCanvasView(
                                    slide = slide,
                                    slideWidthPt = pres.widthPt,
                                    highlightQuery = searchQuery,
                                    selectedItemIdx = if (currentMatch?.slideIndex == idx) currentMatch.itemIndex else -1,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        } else if (!isEditMode) {
            // VIEW MODE (Single Slide Fit-to-Screen Reader):
            // Fits the entire slide completely inside the available screen while preserving exact aspect ratio,
            // with pinch-to-zoom, double-tap zoom, pan, and vertical/horizontal swipe navigation.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .padding(12.dp)
                        .pointerInput(currentSlideIdx, zoom) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoom > 1.1f) {
                                        zoom = 1f
                                        panX = 0f
                                        panY = 0f
                                    } else {
                                        zoom = 2.2f
                                    }
                                },
                            )
                        }
                        .pointerInput(currentSlideIdx) {
                            var swipeAccumX = 0f
                            var swipeAccumY = 0f
                            awaitEachGesture {
                                swipeAccumX = 0f
                                swipeAccumY = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val zChange = event.calculateZoom()
                                        val pChange = event.calculatePan()
                                        zoom = (zoom * zChange).coerceIn(1f, 4f)
                                        if (zoom > 1.01f) {
                                            val maxPanX = (size.width * (zoom - 1f)) / 1.8f
                                            val maxPanY = (size.height * (zoom - 1f)) / 1.8f
                                            panX = (panX + pChange.x).coerceIn(-maxPanX, maxPanX)
                                            panY = (panY + pChange.y).coerceIn(-maxPanY, maxPanY)
                                        } else {
                                            panX = 0f; panY = 0f
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (event.changes.size == 1) {
                                        val pChange = event.calculatePan()
                                        if (zoom > 1.02f) {
                                            val maxPanX = (size.width * (zoom - 1f)) / 1.8f
                                            val maxPanY = (size.height * (zoom - 1f)) / 1.8f
                                            panX = (panX + pChange.x).coerceIn(-maxPanX, maxPanX)
                                            panY = (panY + pChange.y).coerceIn(-maxPanY, maxPanY)
                                            event.changes.forEach { it.consume() }
                                        } else {
                                            swipeAccumX += pChange.x
                                            swipeAccumY += pChange.y
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                val mainDelta = if (abs(swipeAccumY) > abs(swipeAccumX)) swipeAccumY else swipeAccumX
                                if (zoom <= 1.02f && abs(mainDelta) > 85f) {
                                    if (mainDelta < 0 && currentSlideIdx < pres.slides.lastIndex) {
                                        currentSlideIdx++
                                    } else if (mainDelta > 0 && currentSlideIdx > 0) {
                                        currentSlideIdx--
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val availW = maxWidth
                    val availH = maxHeight
                    val targetAspect = pres.aspectRatio
                    val boxAspect = availW / availH.coerceAtLeast(1.dp)

                    val fitW = if (boxAspect > targetAspect) availH * targetAspect else availW
                    val fitH = if (boxAspect > targetAspect) availH else availW / targetAspect

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(activeSlide.backgroundArgb),
                        border = BorderStroke(1.dp, Slate.colors.border),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .width(fitW)
                            .height(fitH)
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = panX,
                                translationY = panY,
                            ),
                    ) {
                        SlideCanvasView(
                            slide = activeSlide,
                            slideWidthPt = pres.widthPt,
                            highlightQuery = searchQuery,
                            selectedItemIdx = if (currentMatch?.slideIndex == currentSlideIdx) currentMatch.itemIndex else -1,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            // EDIT MODE: Only displayed when the user explicitly tapped the Edit button!
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(activeSlide.backgroundArgb),
                    border = BorderStroke(1.5.dp, Slate.colors.slides),
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(pres.aspectRatio)
                        .pointerInput(activeSlide) {
                            detectTapGestures { offset ->
                                val normX = offset.x / size.width.coerceAtLeast(1)
                                val normY = offset.y / size.height.coerceAtLeast(1)
                                val hitIdx = activeSlide.items.indexOfLast { item ->
                                    normX in item.x..(item.x + item.w) && normY in item.y..(item.y + item.h)
                                }
                                if (hitIdx >= 0) selectedItemIdx = hitIdx
                            }
                        },
                ) {
                    SlideCanvasView(
                        slide = activeSlide,
                        slideWidthPt = pres.widthPt,
                        highlightQuery = searchQuery,
                        selectedItemIdx = selectedItemIdx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Text(
                    "Edit Slide Elements (Tap element on slide or below to edit)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate.colors.textSecondary,
                )
                activeSlide.items.forEachIndexed { idx, item ->
                    val isSelected = idx == selectedItemIdx
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Slate.colors.surface,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) Slate.colors.slides else Slate.colors.border,
                        ),
                        modifier = Modifier.fillMaxWidth().clickable { selectedItemIdx = idx },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = when {
                                        item.isImage -> "Image #${idx + 1}"
                                        item.isTable -> "Table #${idx + 1} (${item.table?.rows?.size ?: 0} rows)"
                                        else -> "${item.kind.label} #${idx + 1}"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Slate.colors.textSecondary,
                                )
                                if (!item.isImage && !item.isTable) {
                                    Text("${item.fontSizeSp} pt", style = MaterialTheme.typography.labelSmall, color = Slate.colors.textSecondary)
                                }
                            }
                            if (item.isTable && item.table != null) {
                                val tbl = item.table
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    tbl.rows.forEachIndexed { rIdx, row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                            row.cells.forEachIndexed { cIdx, cell ->
                                                Box(
                                                    Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .border(1.dp, Slate.colors.border, RoundedCornerShape(6.dp))
                                                        .padding(6.dp),
                                                ) {
                                                    BasicTextField(
                                                        value = cell.plainText,
                                                        onValueChange = { newCellTxt ->
                                                            selectedItemIdx = idx
                                                            val updatedRows = tbl.rows.toMutableList().apply {
                                                                val updatedCells = row.cells.toMutableList().apply {
                                                                    val oldRun = cell.paragraphs.firstOrNull()?.runs?.firstOrNull()
                                                                    val updatedCell = cell.copy(
                                                                        paragraphs = listOf(
                                                                            SlideParagraph(
                                                                                listOf(
                                                                                    SlideRun(
                                                                                        text = newCellTxt,
                                                                                        bold = oldRun?.bold ?: (rIdx == 0),
                                                                                        fontSizePt = oldRun?.fontSizePt ?: 10.5f,
                                                                                        colorArgb = oldRun?.colorArgb ?: 0xFF0F172A.toInt(),
                                                                                    )
                                                                                )
                                                                            )
                                                                        )
                                                                    )
                                                                    set(cIdx, updatedCell)
                                                                }
                                                                set(rIdx, row.copy(cells = updatedCells))
                                                            }
                                                            val updatedItem = item.copy(table = tbl.copy(rows = updatedRows))
                                                            val updatedPres = pres.updateSlide(currentSlideIdx) { s ->
                                                                val list = s.items.toMutableList().apply { set(idx, updatedItem) }
                                                                s.copy(items = list)
                                                            }
                                                            history.replace(updatedPres)
                                                            pres = history.current
                                                            saveController.markDirty()
                                                        },
                                                        textStyle = TextStyle(fontSize = 12.sp, color = Slate.colors.text),
                                                        cursorBrush = SolidColor(Slate.colors.text),
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (!item.isImage) {
                                BasicTextField(
                                    value = item.fullText,
                                    onValueChange = { newText ->
                                        selectedItemIdx = idx
                                        val updated = pres.updateSlide(currentSlideIdx) { s ->
                                            val list = s.items.toMutableList().apply { set(idx, item.withEditedText(newText)) }
                                            s.copy(items = list)
                                        }
                                        history.replace(updated)
                                        pres = history.current
                                        saveController.markDirty()
                                    },
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = if (item.bold) FontWeight.Bold else FontWeight.Normal,
                                        fontStyle = if (item.italic) FontStyle.Italic else FontStyle.Normal,
                                        color = Slate.colors.text,
                                    ),
                                    cursorBrush = SolidColor(Slate.colors.text),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        if (item.fullText.isEmpty()) Text("Enter slide text…", color = Slate.colors.textSecondary.copy(alpha = 0.5f))
                                        inner()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Jump to Slide Dialog
    if (showGotoSlideDialog) {
        TextPromptDialog(
            title = "Jump to slide (1–$totalSlides)",
            initial = "${currentSlideIdx + 1}",
            label = "Slide number",
            confirm = "Go",
            onDismiss = { showGotoSlideDialog = false },
            onConfirm = { input ->
                val n = input.trim().toIntOrNull()
                if (n != null && n in 1..totalSlides) {
                    jumpToSlide(n - 1)
                }
                showGotoSlideDialog = false
            },
        )
    }

    // Select & Copy Slide Text Dialog
    if (showSlideTextSheet) {
        val txt = activeSlide.summaryText.ifBlank { "No text on Slide ${currentSlideIdx + 1}." }
        AlertDialog(
            onDismissRequest = { showSlideTextSheet = false },
            title = { Text("Slide ${currentSlideIdx + 1} Text") },
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
                        cm.setPrimaryClip(ClipData.newPlainText("Slide ${currentSlideIdx + 1}", txt))
                        Toast.makeText(context, "Slide text copied to clipboard", Toast.LENGTH_SHORT).show()
                        showSlideTextSheet = false
                    }
                ) { Text("Copy all") }
            },
            dismissButton = { TextButton(onClick = { showSlideTextSheet = false }) { Text("Close") } },
        )
    }
}

/**
 * Resolution-independent native Canvas view that renders a [SlidePage] at full screen DPI.
 */
@Composable
fun SlideCanvasView(
    slide: SlidePage,
    slideWidthPt: Float,
    highlightQuery: String,
    selectedItemIdx: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w > 1f && h > 1f) {
            SlidesFormatCodecs.drawSlideOnCanvas(
                canvas = drawContext.canvas.nativeCanvas,
                slide = slide,
                w = w,
                h = h,
                slideWidthPt = slideWidthPt,
                highlightQuery = highlightQuery,
                selectedItemIdx = selectedItemIdx,
            )
        }
    }
}

@Composable
private fun FullScreenPresentationMode(
    pres: PresentationDocument,
    startIndex: Int,
    onExit: (Int) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val slides = pres.slides
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, slides.lastIndex)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var showHud by remember { mutableStateOf(true) }
    var landscapeMode by remember { mutableStateOf(true) }
    var verticalScrollInPresent by remember { mutableStateOf(false) }
    val fullScreenListState = rememberLazyListState(initialFirstVisibleItemIndex = index)

    // Immersive full-screen + automatic landscape orientation for 16:9 presentation slides
    DisposableEffect(landscapeMode) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = if (landscapeMode) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(fullScreenListState, verticalScrollInPresent) {
        if (verticalScrollInPresent) {
            snapshotFlow { fullScreenListState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { vis ->
                    if (vis in slides.indices) index = vis
                }
        }
    }

    BackHandler { onExit(index) }
    val slide = slides.getOrElse(index) { SlidePage() }

    LaunchedEffect(index) {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (verticalScrollInPresent) {
            // Full-Screen Vertical Scroll through all slides
            LazyColumn(
                state = fullScreenListState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(zoom) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    val z = event.calculateZoom()
                                    val p = event.calculatePan()
                                    zoom = (zoom * z).coerceIn(1f, 4f)
                                    if (zoom > 1.01f) {
                                        panX = (panX + p.x).coerceIn(-1200f, 1200f)
                                        panY = (panY + p.y).coerceIn(-800f, 800f)
                                    } else {
                                        panX = 0f; panY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(slides, key = { _, s -> s.id }) { idx, s ->
                    Surface(
                        color = Color(s.backgroundArgb),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pres.aspectRatio)
                            .pointerInput(idx, zoom) {
                                detectTapGestures(
                                    onTap = { showHud = !showHud },
                                    onDoubleTap = {
                                        if (zoom > 1.1f) {
                                            zoom = 1f; panX = 0f; panY = 0f
                                        } else {
                                            zoom = 2.0f
                                        }
                                    },
                                )
                            },
                    ) {
                        SlideCanvasView(
                            slide = s,
                            slideWidthPt = pres.widthPt,
                            highlightQuery = "",
                            selectedItemIdx = -1,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            // Full-Screen Projector Mode: Fits the entire screen edge-to-edge
            val availW = maxWidth
            val availH = maxHeight
            val targetAspect = pres.aspectRatio
            val boxAspect = availW / availH.coerceAtLeast(1.dp)
            val fitW = if (boxAspect > targetAspect) availH * targetAspect else availW
            val fitH = if (boxAspect > targetAspect) availH else availW / targetAspect

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(index, zoom) {
                        detectTapGestures(
                            onTap = { showHud = !showHud },
                            onDoubleTap = {
                                if (zoom > 1.1f) {
                                    zoom = 1f; panX = 0f; panY = 0f
                                } else {
                                    zoom = 2.2f
                                }
                            },
                        )
                    }
                    .pointerInput(index) {
                        var swipeX = 0f
                        var swipeY = 0f
                        awaitEachGesture {
                            swipeX = 0f
                            swipeY = 0f
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    val z = event.calculateZoom()
                                    val p = event.calculatePan()
                                    zoom = (zoom * z).coerceIn(1f, 4f)
                                    if (zoom > 1.01f) {
                                        panX = (panX + p.x).coerceIn(-1200f, 1200f)
                                        panY = (panY + p.y).coerceIn(-800f, 800f)
                                    } else {
                                        panX = 0f; panY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (event.changes.size == 1) {
                                    val p = event.calculatePan()
                                    if (zoom > 1.02f) {
                                        panX = (panX + p.x).coerceIn(-1200f, 1200f)
                                        panY = (panY + p.y).coerceIn(-800f, 800f)
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        swipeX += p.x
                                        swipeY += p.y
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            val primarySwipe = if (abs(swipeY) > abs(swipeX)) swipeY else swipeX
                            if (zoom <= 1.02f && abs(primarySwipe) > 75f) {
                                if (primarySwipe < 0 && index < slides.lastIndex) index++
                                else if (primarySwipe > 0 && index > 0) index--
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color(slide.backgroundArgb),
                    modifier = Modifier
                        .width(fitW)
                        .height(fitH)
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = panX,
                            translationY = panY,
                        ),
                ) {
                    SlideCanvasView(
                        slide = slide,
                        slideWidthPt = pres.widthPt,
                        highlightQuery = "",
                        selectedItemIdx = -1,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (showHud) {
            // Top right controls: Rotate Orientation, Toggle Scroll/Projector, Exit
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xBB0F172A))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        verticalScrollInPresent = !verticalScrollInPresent
                        if (verticalScrollInPresent) {
                            scope.launch { runCatching { fullScreenListState.scrollToItem(index) } }
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (verticalScrollInPresent) Icons.Outlined.FitScreen else Icons.Outlined.ViewStream,
                        contentDescription = if (verticalScrollInPresent) "Projector single slide" else "Vertical scroll slides",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { landscapeMode = !landscapeMode },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ScreenRotation,
                        contentDescription = "Rotate screen",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { onExit(index) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Outlined.Close, "Exit presentation", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // Bottom slide navigation pill
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC0F172A))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    onClick = {
                        if (index > 0) {
                            index--
                            if (verticalScrollInPresent) {
                                scope.launch { runCatching { fullScreenListState.animateScrollToItem(index) } }
                            }
                        }
                    },
                    enabled = index > 0,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous slide", tint = Color.White)
                }
                Text(
                    text = "Slide ${index + 1} / ${slides.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = {
                        if (index < slides.lastIndex) {
                            index++
                            if (verticalScrollInPresent) {
                                scope.launch { runCatching { fullScreenListState.animateScrollToItem(index) } }
                            }
                        }
                    },
                    enabled = index < slides.lastIndex,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next slide", tint = Color.White)
                }
            }
        }
    }
}
