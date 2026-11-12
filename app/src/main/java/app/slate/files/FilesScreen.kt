package app.slate.files

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.slate.DocumentActivity
import app.slate.SlateApp
import app.slate.core.DocFormat
import app.slate.core.DocKind
import app.slate.core.io.DocIO
import app.slate.data.Settings
import app.slate.data.SortBy
import app.slate.share.ShareAndPrint
import app.slate.ui.components.ConfirmDialog
import app.slate.ui.components.EmptyState
import app.slate.ui.components.FileBadge
import app.slate.ui.components.Pill
import app.slate.ui.components.TextPromptDialog
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    settings: Settings,
    initialKindFilter: DocKind? = null,
) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()

    var activeRoot by remember { mutableStateOf(StorageRoot.LIBRARY) }
    var currentDir by remember { mutableStateOf(DocIO.libraryDir(context)) }
    var safTreeUri by remember { mutableStateOf<Uri?>(null) }
    var kindFilter by remember(initialKindFilter) { mutableStateOf(initialKindFilter) }
    var onlyFavorites by remember { mutableStateOf(false) }

    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val selectedIds = remember { mutableStateListOf<String>() }
    var clipboardEntry by remember { mutableStateOf<Pair<FileEntry, Boolean>?>(null) } // entry to isMove

    var showSortMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewDocDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var actionMenuTarget by remember { mutableStateOf<FileEntry?>(null) }

    val favorites by app.favorites.observe().collectAsStateWithLifecycle(emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    val rootBaseDir = remember(activeRoot) { FileManager.defaultRootDir(context, activeRoot) }

    BackHandler(enabled = selectedIds.isNotEmpty() || (activeRoot != StorageRoot.SAF && currentDir != rootBaseDir)) {
        if (selectedIds.isNotEmpty()) {
            selectedIds.clear()
        } else {
            currentDir.parentFile?.let { currentDir = it }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            safTreeUri = uri
            activeRoot = StorageRoot.SAF
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val targetFolder = if (activeRoot == StorageRoot.SAF) DocIO.libraryDir(context) else currentDir
                val imported = withContext(Dispatchers.IO) {
                    FileManager.importExternalUriToDir(context, uri, targetFolder)
                }
                Toast.makeText(context, "Imported ${imported.name}", Toast.LENGTH_SHORT).show()
                refreshTick++
            }
        }
    }

    LaunchedEffect(activeRoot, currentDir, safTreeUri, kindFilter, onlyFavorites, settings.sortBy, settings.sortDescending, settings.showHidden, refreshTick, favoriteIds) {
        entries = withContext(Dispatchers.IO) {
            FileManager.ensureStarterSamples(context)
            val raw = when {
                onlyFavorites -> {
                    favorites.mapNotNull { fav ->
                        val u = Uri.parse(fav.id)
                        val f = if (u.scheme == "file") File(u.path!!) else null
                        if (f != null && f.exists()) {
                            FileEntry(
                                id = fav.id,
                                name = f.name,
                                uri = u,
                                file = f,
                                isDirectory = f.isDirectory,
                                size = if (f.isDirectory) 0L else f.length(),
                                lastModified = f.lastModified(),
                                format = if (f.isDirectory) null else DocFormat.fromName(f.name),
                            )
                        } else null
                    }.let { FileManager.sortEntries(it, settings.sortBy, settings.sortDescending) }
                }
                activeRoot == StorageRoot.SAF && safTreeUri != null -> {
                    FileManager.listSafTree(context, safTreeUri!!, settings.showHidden, kindFilter, settings.sortBy, settings.sortDescending)
                }
                else -> {
                    FileManager.listDirectory(currentDir, settings.showHidden, kindFilter, settings.sortBy, settings.sortDescending)
                }
            }
            raw
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Top Header Bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedIds.isNotEmpty()) {
                IconButton(onClick = { selectedIds.clear() }) { Icon(Icons.Outlined.Close, "Clear selection") }
                Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val chosen = entries.filter { it.id in selectedIds && !it.isDirectory }.map { it.uri }
                        ShareAndPrint.shareMultiple(context, chosen)
                        selectedIds.clear()
                    }
                ) { Icon(Icons.Outlined.Share, "Share selected") }
                IconButton(
                    onClick = {
                        deleteTargets = entries.filter { it.id in selectedIds }
                    }
                ) { Icon(Icons.Outlined.DeleteOutline, "Delete selected", tint = MaterialTheme.colorScheme.error) }
            } else {
                Text("Files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (clipboardEntry != null) {
                    FilledTonalButton(
                        onClick = {
                            val (srcEntry, isMove) = clipboardEntry ?: return@FilledTonalButton
                            val srcFile = srcEntry.file
                            if (srcFile != null && activeRoot != StorageRoot.SAF) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val dst = DocIO.uniqueFile(currentDir, srcFile.name)
                                        srcFile.copyTo(dst, overwrite = false)
                                        if (isMove) srcFile.delete()
                                    }
                                    clipboardEntry = null
                                    refreshTick++
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp).padding(end = 6.dp),
                    ) { Text(if (clipboardEntry?.second == true) "Move Here" else "Paste Here") }
                }
                IconButton(onClick = { showNewDocDialog = true }) { Icon(Icons.Outlined.NoteAdd, "New document") }
                IconButton(onClick = { showNewFolderDialog = true }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") }
                IconButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.FileOpen, "Import file") }
                IconButton(onClick = { app.settings.setGrid(!settings.gridView) }) {
                    Icon(if (settings.gridView) Icons.Outlined.ViewList else Icons.Outlined.GridView, "Toggle view")
                }
                Box {
                    IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    val arrow = if (settings.sortBy == s) (if (settings.sortDescending) " ↓" else " ↑") else ""
                                    Text("${s.label}$arrow")
                                },
                                onClick = {
                                    val desc = if (settings.sortBy == s) !settings.sortDescending else false
                                    app.settings.setSort(s, desc)
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Storage Root Selector Pills
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(StorageRoot.LIBRARY, StorageRoot.DOWNLOADS, StorageRoot.DOCUMENTS, StorageRoot.INTERNAL).forEach { root ->
                Pill(
                    text = root.label,
                    selected = !onlyFavorites && activeRoot == root,
                    onClick = {
                        onlyFavorites = false
                        activeRoot = root
                        currentDir = FileManager.defaultRootDir(context, root)
                    },
                )
            }
            Pill(
                text = "Favorites",
                selected = onlyFavorites,
                onClick = { onlyFavorites = !onlyFavorites },
            )
            Pill(
                text = if (activeRoot == StorageRoot.SAF) "Picked Folder" else "Browse Folder…",
                selected = !onlyFavorites && activeRoot == StorageRoot.SAF,
                onClick = {
                    onlyFavorites = false
                    folderPicker.launch(null)
                },
            )
        }

        // Category Filter Pills
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("All Types", kindFilter == null, onClick = { kindFilter = null })
            Pill("PDFs", kindFilter == DocKind.PDF, onClick = { kindFilter = DocKind.PDF })
            Pill("Documents", kindFilter == DocKind.WORD, onClick = { kindFilter = DocKind.WORD })
            Pill("Spreadsheets", kindFilter == DocKind.SHEET, onClick = { kindFilter = DocKind.SHEET })
            Pill("Presentations", kindFilter == DocKind.SLIDES, onClick = { kindFilter = DocKind.SLIDES })
        }

        // Breadcrumb bar when inside subdirectories
        if (!onlyFavorites && activeRoot != StorageRoot.SAF && currentDir != rootBaseDir) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { currentDir.parentFile?.let { currentDir = it } },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Outlined.ArrowUpward, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(currentDir.name.ifBlank { currentDir.absolutePath }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Permission banner when browsing shared storage without All-Files access on Android 11+
        if (!onlyFavorites &&
            (activeRoot == StorageRoot.DOWNLOADS || activeRoot == StorageRoot.DOCUMENTS || activeRoot == StorageRoot.INTERNAL) &&
            !FileManager.hasAllFilesPermission()
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Slate.colors.surfaceAlt,
                border = BorderStroke(1.dp, Slate.colors.border),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Storage access needed", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Grant All Files Access to browse device folders directly, or use 'Browse Folder…' for scoped access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate.colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val i = Intent(
                                    AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                runCatching { context.startActivity(i) }.onFailure {
                                    context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }
                        }
                    ) { Text("Grant") }
                }
            }
        }

        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = "No documents here",
                message = "Create a new document, import an existing file, or switch folders.",
                actionLabel = "Create Document",
                onAction = { showNewDocDialog = true },
            )
        } else if (settings.gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isSelected = entry.id in selectedIds
                    val isFav = entry.id in favoriteIds
                    FileGridCard(
                        entry = entry,
                        selected = isSelected,
                        favorite = isFav,
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                if (isSelected) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
                            } else if (entry.isDirectory) {
                                entry.file?.let { currentDir = it }
                            } else {
                                DocumentActivity.open(context, entry.uri)
                            }
                        },
                        onLongClick = {
                            if (entry.id in selectedIds) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
                        },
                        onMore = { actionMenuTarget = entry },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isSelected = entry.id in selectedIds
                    val isFav = entry.id in favoriteIds
                    FileListRow(
                        entry = entry,
                        selected = isSelected,
                        favorite = isFav,
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                if (isSelected) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
                            } else if (entry.isDirectory) {
                                entry.file?.let { currentDir = it }
                            } else {
                                DocumentActivity.open(context, entry.uri)
                            }
                        },
                        onLongClick = {
                            if (entry.id in selectedIds) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
                        },
                        onToggleFavorite = {
                            app.favorites.toggle(entry.id, entry.name, entry.isDirectory, isFav)
                        },
                        onMore = { actionMenuTarget = entry },
                    )
                }
            }
        }
    }

    // Per-file action sheet
    actionMenuTarget?.let { target ->
        val isFav = target.id in favoriteIds
        AlertDialog(
            onDismissRequest = { actionMenuTarget = null },
            title = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (!target.isDirectory) {
                        TextButton(
                            onClick = {
                                actionMenuTarget = null
                                DocumentActivity.open(context, target.uri)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(12.dp)); Text("Open", Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = {
                                actionMenuTarget = null
                                ShareAndPrint.shareDocument(context, target.uri, target.name)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(12.dp)); Text("Share", Modifier.weight(1f))
                        }
                    }
                    TextButton(
                        onClick = {
                            actionMenuTarget = null
                            app.favorites.toggle(target.id, target.name, target.isDirectory, isFav)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (isFav) Icons.Outlined.StarBorder else Icons.Outlined.Star, null)
                        Spacer(Modifier.width(12.dp))
                        Text(if (isFav) "Remove from Favorites" else "Add to Favorites", Modifier.weight(1f))
                    }
                    if (target.file != null) {
                        TextButton(
                            onClick = {
                                actionMenuTarget = null
                                renameTarget = target
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(12.dp)); Text("Rename", Modifier.weight(1f))
                        }
                        if (!target.isDirectory) {
                            TextButton(
                                onClick = {
                                    clipboardEntry = target to false
                                    actionMenuTarget = null
                                    Toast.makeText(context, "Navigate to destination folder and tap 'Paste Here'", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(12.dp)); Text("Copy", Modifier.weight(1f))
                            }
                            TextButton(
                                onClick = {
                                    clipboardEntry = target to true
                                    actionMenuTarget = null
                                    Toast.makeText(context, "Navigate to destination folder and tap 'Move Here'", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null); Spacer(Modifier.width(12.dp)); Text("Move", Modifier.weight(1f))
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            actionMenuTarget = null
                            deleteTargets = listOf(target)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionMenuTarget = null }) { Text("Close") } },
        )
    }

    // Create Folder dialog
    if (showNewFolderDialog) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            confirm = "Create",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                val parent = if (activeRoot == StorageRoot.SAF) DocIO.libraryDir(context) else currentDir
                File(parent, name).mkdirs()
                showNewFolderDialog = false
                refreshTick++
            },
        )
    }

    // Create New Blank Document dialog
    if (showNewDocDialog) {
        NewDocumentDialog(
            onDismiss = { showNewDocDialog = false },
            onCreate = { title, format ->
                showNewDocDialog = false
                val targetDir = if (activeRoot == StorageRoot.SAF) DocIO.libraryDir(context) else currentDir
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        FileManager.createNewBlankDocument(targetDir, title, format)
                    }
                    refreshTick++
                    DocumentActivity.open(context, Uri.fromFile(f))
                }
            },
        )
    }

    // Rename dialog
    renameTarget?.let { target ->
        TextPromptDialog(
            title = "Rename",
            initial = target.name,
            label = "New name",
            confirm = "Rename",
            selectBaseName = !target.isDirectory,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                val f = target.file
                if (f != null) {
                    val dst = File(f.parentFile, newName)
                    if (f.renameTo(dst)) {
                        scope.launch {
                            val newUri = Uri.fromFile(dst).toString()
                            app.recents.rename(target.id, newUri, newName)
                            app.favorites.rename(target.id, newUri, newName)
                            refreshTick++
                        }
                    } else {
                        Toast.makeText(context, "Could not rename file", Toast.LENGTH_SHORT).show()
                    }
                }
                renameTarget = null
            },
        )
    }

    // Delete confirmation
    if (deleteTargets.isNotEmpty()) {
        val count = deleteTargets.size
        ConfirmDialog(
            title = if (count == 1) "Delete \"${deleteTargets.first().name}\"?" else "Delete $count items?",
            message = "This will permanently remove the selected item(s) from storage.",
            confirm = "Delete",
            destructive = true,
            onDismiss = { deleteTargets = emptyList() },
            onConfirm = {
                val toDelete = deleteTargets
                deleteTargets = emptyList()
                selectedIds.clear()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        toDelete.forEach { item ->
                            item.file?.deleteRecursively()
                            app.recents.remove(item.id)
                            app.favorites.remove(item.id)
                        }
                    }
                    refreshTick++
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    entry: FileEntry,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Slate.colors.surfaceAlt else Slate.colors.surface,
        border = BorderStroke(1.dp, if (selected) Slate.colors.accent else Slate.colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileBadge(format = entry.format, size = 42.dp, isFolder = entry.isDirectory)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildString {
                    if (entry.isDirectory) append("Folder")
                    else append(formatBytes(entry.size))
                    if (entry.lastModified > 0) {
                        append(" • ")
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.lastModified)))
                    }
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary, maxLines = 1)
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (favorite) Slate.colors.slides else Slate.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.MoreVert, "More actions", tint = Slate.colors.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCard(
    entry: FileEntry,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Slate.colors.surfaceAlt else Slate.colors.surface,
        border = BorderStroke(1.dp, if (selected) Slate.colors.accent else Slate.colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                FileBadge(format = entry.format, size = 44.dp, isFolder = entry.isDirectory)
                IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.MoreVert, "More", modifier = Modifier.size(18.dp), tint = Slate.colors.textSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (entry.isDirectory) "Folder" else formatBytes(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = Slate.colors.textSecondary,
            )
        }
    }
}

@Composable
fun NewDocumentDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, format: DocFormat) -> Unit,
) {
    var name by remember { mutableStateOf("Untitled") }
    val creatable = remember {
        listOf(
            DocFormat.DOCX,
            DocFormat.XLSX,
            DocFormat.PPTX,
            DocFormat.TXT,
            DocFormat.ODT,
            DocFormat.ODS,
            DocFormat.ODP,
            DocFormat.CSV,
            DocFormat.RTF,
        )
    }
    var selectedFormat by remember { mutableStateOf(DocFormat.DOCX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Document name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Format", style = MaterialTheme.typography.labelLarge, color = Slate.colors.textSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    creatable.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { fmt ->
                                Pill(
                                    text = fmt.ext.uppercase(),
                                    selected = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim().ifEmpty { "Untitled" }, selectedFormat) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.2f MB".format(mb)
}
