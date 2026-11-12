package app.slate.ui.editor

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.core.DocFormat
import app.slate.core.OpenDoc
import app.slate.core.io.DocIO
import app.slate.core.io.RecoveryStore
import app.slate.core.withoutExtension
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Writes the editor's current model to [format]. Called off the main thread. */
typealias DocWriter = suspend (format: DocFormat, out: OutputStream) -> Unit

/**
 * Central save logic shared by all editors:
 *  - Save: overwrites the original only after the user explicitly confirms (once per session).
 *  - Save As / Export: Storage Access Framework "create document" picker, never clobbers.
 *  - Auto-save: debounced recovery drafts in private storage, cleared after a real save.
 */
class SaveController internal constructor(
    private val context: Context,
    val doc: OpenDoc,
    /** Formats the editor can write natively, preferred first. */
    val writable: List<DocFormat>,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    internal lateinit var writer: DocWriter
    var dirty by mutableStateOf(doc.recoveryFile != null)
        private set
    var busy by mutableStateOf(false)
        private set
    internal var showOverwrite by mutableStateOf(false)
    internal var showSaveAsPicker by mutableStateOf(false)
    internal var pendingSaveAs: DocFormat? = null
    internal var launchCreate: ((String, DocFormat) -> Unit)? = null
    private var overwriteConfirmed = false
    private var draftJob: Job? = null
    var onSaved: (() -> Unit)? = null

    /** Whether "Save" can write back into the original file. */
    val canOverwrite: Boolean get() = doc.writable && doc.format in writable

    /** Editors call this after every change. Schedules a recovery draft. */
    fun markDirty() {
        dirty = true
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(2500)
            val fmt = if (doc.format in writable) doc.format else writable.first()
            runCatching {
                withContext(Dispatchers.IO) {
                    val buf = java.io.ByteArrayOutputStream()
                    writer(fmt, buf)
                    RecoveryStore.clearAll(context, doc.uri)
                    RecoveryStore.saveDraft(context, doc.uri, fmt) { it.write(buf.toByteArray()) }
                }
            }
        }
    }

    fun save() {
        if (canOverwrite) {
            if (overwriteConfirmed) overwrite() else showOverwrite = true
        } else {
            saveAs(if (doc.format in writable) doc.format else writable.first())
        }
    }

    internal fun overwrite() {
        overwriteConfirmed = true
        scope.launch {
            busy = true
            val r = runCatching {
                withContext(Dispatchers.IO) { DocIO.writeSafely(context, doc.uri) { out -> kotlinx.coroutines.runBlocking { writer(doc.format, out) } } }
            }
            busy = false
            r.onSuccess { afterSave("Saved") }
                .onFailure { toast("Couldn't save: ${it.message ?: "write failed"}. Try Save As.") }
        }
    }

    /** Opens the system "Save as" picker for [format]. */
    fun saveAs(format: DocFormat) {
        launchCreate?.invoke(doc.name.withoutExtension() + "." + format.ext, format)
    }

    /** Let the user pick the format first (Save As…). */
    fun chooseSaveAs() { showSaveAsPicker = true }

    internal fun writeTo(uri: Uri, format: DocFormat) {
        scope.launch {
            busy = true
            val r = runCatching { withContext(Dispatchers.IO) { DocIO.writeSafely(context, uri) { out -> kotlinx.coroutines.runBlocking { writer(format, out) } } } }
            busy = false
            r.onSuccess {
                app.slate.SlateApp.get(context).recents.touch(uri, null)
                if (format in writable) afterSave("Saved as ${DocIO.info(context, uri).name}") else toast("Exported ${DocIO.info(context, uri).name}")
            }.onFailure { toast("Couldn't save: ${it.message ?: "write failed"}") }
        }
    }

    private fun afterSave(msg: String) {
        dirty = false
        draftJob?.cancel()
        RecoveryStore.clearAll(context, doc.uri)
        toast(msg)
        onSaved?.invoke()
    }

    /** Discard: drop drafts (user chose not to keep changes). */
    fun discard() { draftJob?.cancel(); RecoveryStore.clearAll(context, doc.uri); dirty = false }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
}

@Composable
fun rememberSaveController(doc: OpenDoc, writable: List<DocFormat>, writer: DocWriter): SaveController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctl = remember(doc.uri) { SaveController(context, doc, writable, scope) }
    ctl.writer = writer
    var pendingFormat by remember { mutableStateOf<DocFormat?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingFormat
        if (uri != null && f != null) ctl.writeTo(uri, f)
    }
    ctl.launchCreate = { name, format -> pendingFormat = format; launcher.launch(name) }
    return ctl
}

/**
 * Standard SLATE Viewer & Editor Chrome:
 *  - Opens in Read-Only VIEW MODE by default (`isEditMode = false`).
 *  - Provides consistent top bar: Back, Document Title + Subtitle, Search, Zoom controls,
 *    explicit Edit toggle button (or Undo/Redo/Save/Done in Edit Mode), and More overflow menu.
 *  - Formatting/editing `toolbar` row is only shown when `isEditMode == true`.
 */
@Composable
fun EditorScaffold(
    title: String,
    controller: SaveController,
    onClose: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    exportFormats: List<DocFormat> = emptyList(),
    subtitle: String? = null,
    isEditMode: Boolean = false,
    onToggleEditMode: (() -> Unit)? = null,
    onCancelEditMode: (() -> Unit)? = null,
    onToggleSearch: (() -> Unit)? = null,
    isSearchActive: Boolean = false,
    zoomPercent: Int = 100,
    onZoomIn: (() -> Unit)? = null,
    onZoomOut: (() -> Unit)? = null,
    onResetZoom: (() -> Unit)? = null,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    toolbar: (@Composable RowScope.() -> Unit)? = null,
    searchBar: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Whenever we switch to View Mode and search is not active, ensure keyboard is hidden and focus is cleared.
    LaunchedEffect(isEditMode, isSearchActive) {
        if (!isEditMode && !isSearchActive) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    val tryClose = { if (controller.dirty) confirmExit = true else onClose() }
    BackHandler(onBack = {
        if (isSearchActive && onToggleSearch != null) {
            onToggleSearch()
        } else if (isEditMode && onToggleEditMode != null && !controller.dirty) {
            onToggleEditMode()
        } else {
            tryClose()
        }
    })

    Scaffold(
        containerColor = Slate.colors.background,
        topBar = {
            Column(Modifier.statusBarsPadding().background(Slate.colors.background)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = tryClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Column(Modifier.weight(1f).padding(end = 4.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val modePrefix = if (onToggleEditMode != null) {
                            if (isEditMode) "Editing" else "Viewing"
                        } else null
                        val rawSub = subtitle ?: if (controller.dirty) "Unsaved changes" else null
                        val fullSub = listOfNotNull(modePrefix, rawSub).joinToString(" • ")
                        if (fullSub.isNotEmpty()) {
                            Text(
                                text = fullSub,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isEditMode) Slate.colors.accent else Slate.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Search button (available in both View and Edit modes)
                    if (onToggleSearch != null) {
                        IconButton(onClick = onToggleSearch) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search document",
                                tint = if (isSearchActive) Slate.colors.text else Slate.colors.textSecondary,
                            )
                        }
                    }

                    // Zoom indicator / reset chip when zoomed or zoom buttons
                    if (zoomPercent != 100 && onResetZoom != null) {
                        Surface(
                            onClick = onResetZoom,
                            shape = RoundedCornerShape(8.dp),
                            color = Slate.colors.surfaceAlt,
                            border = BorderStroke(1.dp, Slate.colors.border),
                            modifier = Modifier.padding(horizontal = 2.dp),
                        ) {
                            Text(
                                text = "$zoomPercent%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            )
                        }
                    } else if (!isEditMode && onZoomIn != null) {
                        IconButton(onClick = onZoomIn, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Outlined.ZoomIn, "Zoom in", modifier = Modifier.size(20.dp))
                        }
                    }

                    if (isEditMode) {
                        IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, "Redo", modifier = Modifier.size(20.dp))
                        }
                    }

                    actions()

                    // Save button (shown in Edit mode or whenever document has unsaved changes)
                    if (isEditMode || controller.dirty) {
                        if (controller.busy) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = { controller.save() }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.Save, "Save")
                            }
                        }
                    }

                    // Explicit View / Edit mode toggle button
                    if (onToggleEditMode != null) {
                        if (!isEditMode) {
                            Surface(
                                onClick = onToggleEditMode,
                                shape = RoundedCornerShape(10.dp),
                                color = Slate.colors.text,
                                modifier = Modifier.padding(horizontal = 4.dp).height(34.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = "Edit document",
                                        tint = Slate.colors.background,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        "Edit",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Slate.colors.background,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        } else {
                            Surface(
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    onToggleEditMode()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Slate.colors.surfaceAlt,
                                border = BorderStroke(1.dp, Slate.colors.border),
                                modifier = Modifier.padding(horizontal = 4.dp).height(34.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Visibility,
                                        contentDescription = "Switch to View mode",
                                        tint = Slate.colors.text,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        "View",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Slate.colors.text,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.MoreVert, "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onToggleEditMode != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isEditMode) "Switch to Viewing Mode" else "Enter Editing Mode") },
                                    leadingIcon = {
                                        Icon(if (isEditMode) Icons.Outlined.Visibility else Icons.Outlined.Edit, null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onToggleEditMode()
                                    },
                                )
                            }
                            if (isEditMode && onCancelEditMode != null) {
                                DropdownMenuItem(
                                    text = { Text("Cancel edits & revert", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onCancelEditMode()
                                    },
                                )
                            }
                            if (onZoomIn != null || onZoomOut != null || onResetZoom != null) {
                                if (onZoomIn != null) {
                                    DropdownMenuItem(
                                        text = { Text("Zoom in ($zoomPercent%)") },
                                        leadingIcon = { Icon(Icons.Outlined.ZoomIn, null) },
                                        onClick = { menuOpen = false; onZoomIn() },
                                    )
                                }
                                if (onZoomOut != null) {
                                    DropdownMenuItem(
                                        text = { Text("Zoom out ($zoomPercent%)") },
                                        leadingIcon = { Icon(Icons.Outlined.ZoomOut, null) },
                                        onClick = { menuOpen = false; onZoomOut() },
                                    )
                                }
                                if (zoomPercent != 100 && onResetZoom != null) {
                                    DropdownMenuItem(
                                        text = { Text("Reset zoom (100%)") },
                                        leadingIcon = { Icon(Icons.Outlined.CenterFocusStrong, null) },
                                        onClick = { menuOpen = false; onResetZoom() },
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text("Save as / Export…") },
                                leadingIcon = { Icon(Icons.Outlined.SaveAs, null) },
                                onClick = { menuOpen = false; controller.chooseSaveAs() },
                            )
                            menu { menuOpen = false }
                        }
                    }
                }

                // Collapsible Search Bar (shown when user activates search)
                if (isSearchActive && searchBar != null) {
                    searchBar()
                }

                // Formatting / Editing toolbar row — ONLY displayed in Edit Mode
                if (isEditMode && toolbar != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Slate.colors.surface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = toolbar,
                    )
                }
                HorizontalDivider(color = Slate.colors.border)
            }
        },
        bottomBar = bottomBar,
        content = content,
    )

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save your changes before closing? A recovery copy is kept until you decide.", color = Slate.colors.textSecondary) },
            confirmButton = { TextButton(onClick = { confirmExit = false; controller.onSaved = onClose; controller.save() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false; controller.discard(); onClose() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (controller.showOverwrite) {
        AlertDialog(
            onDismissRequest = { controller.showOverwrite = false },
            title = { Text("Replace the original?") },
            text = {
                Text(
                    "SLATE will write your changes into \"${controller.doc.name}\". Formatting that SLATE doesn't support may be simplified. " +
                        "Choose \"Save a copy\" to keep the original untouched.",
                    color = Slate.colors.textSecondary,
                )
            },
            confirmButton = { TextButton(onClick = { controller.showOverwrite = false; controller.overwrite() }) { Text("Replace") } },
            dismissButton = {
                TextButton(onClick = { controller.showOverwrite = false; controller.saveAs(controller.doc.format) }) { Text("Save a copy") }
            },
        )
    }
    if (controller.showSaveAsPicker) {
        val options = (controller.writable + exportFormats).distinct()
        AlertDialog(
            onDismissRequest = { controller.showSaveAsPicker = false },
            title = { Text("Save as") },
            text = {
                Column {
                    options.forEach { f ->
                        Surface(
                            onClick = { controller.showSaveAsPicker = false; controller.saveAs(f) },
                            shape = RoundedCornerShape(12.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                app.slate.ui.components.FileBadge(f, 32.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(f.label)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { controller.showSaveAsPicker = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Unified Search Bar used across Spreadsheet, PowerPoint, Word, and PDF viewers.
 * Automatically requests focus ONLY when explicitly opened by the user.
 */
@Composable
fun DocumentSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    matchCount: Int,
    currentMatchIndex: Int,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit,
    extraInfo: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Surface(color = Slate.colors.surfaceAlt, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = Slate.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Slate.colors.surface)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(placeholder, color = Slate.colors.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Slate.colors.text),
                    cursorBrush = SolidColor(Slate.colors.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        if (matchCount > 0) onNextMatch()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                val badgeText = if (matchCount > 0) {
                    val idx = (currentMatchIndex.coerceIn(0, matchCount - 1) + 1)
                    if (extraInfo != null) "$idx/$matchCount ($extraInfo)" else "$idx / $matchCount"
                } else {
                    "0 results"
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (matchCount > 0) Slate.colors.text else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onPrevMatch,
                    enabled = matchCount > 0,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Previous match", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = onNextMatch,
                    enabled = matchCount > 0,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Next match", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Outlined.Backspace, "Clear query", modifier = Modifier.size(16.dp))
                }
            }
            IconButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    onClose()
                },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.Outlined.Close, "Close search", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Compact toggle/icon button for formatting toolbars. */
@Composable
fun ToolButton(icon: ImageVector, description: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Slate.colors
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = c.surfaceAlt, checkedContentColor = c.text),
        modifier = Modifier.size(40.dp),
    ) { Icon(icon, description, modifier = Modifier.size(20.dp)) }
}
