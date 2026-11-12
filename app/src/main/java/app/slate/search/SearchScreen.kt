package app.slate.search

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.slate.DocumentActivity
import app.slate.core.DocFormat
import app.slate.core.DocKind
import app.slate.core.io.DocIO
import app.slate.data.SortBy
import app.slate.engine.Engines
import app.slate.files.FileEntry
import app.slate.files.FileManager
import app.slate.files.formatBytes
import app.slate.ui.components.EmptyState
import app.slate.ui.components.FileBadge
import app.slate.ui.components.Pill
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class SearchMode { FILENAME, CONTENT }

data class ContentMatchHit(
    val entry: FileEntry,
    val locationLabel: String, // "Page 4", "Slide 2", "Budget!B4", "Paragraph 3"
    val target: String,        // "page=3", "slide=1", "sheet=0;row=3;col=1", "para=2"
    val snippet: String,
)

@Composable
fun SearchScreen() {
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.FILENAME) }
    var kindFilter by remember { mutableStateOf<DocKind?>(null) }
    var extFilter by remember { mutableStateOf<DocFormat?>(null) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var sortDesc by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    var allDocs by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var contentHits by remember { mutableStateOf<List<ContentMatchHit>>(emptyList()) }
    var searchingContent by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allDocs = withContext(Dispatchers.IO) {
            FileManager.ensureStarterSamples(context)
            FileManager.collectAllDocuments(context)
        }
    }

    val folderNames = remember(allDocs) {
        allDocs.mapNotNull { it.file?.parentFile?.name }.distinct().sorted()
    }

    val filteredFiles = remember(allDocs, query, kindFilter, extFilter, folderFilter, sortBy, sortDesc) {
        val base = allDocs.filter { entry ->
            (query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true)) &&
                (kindFilter == null || entry.format?.kind == kindFilter) &&
                (extFilter == null || entry.format == extFilter) &&
                (folderFilter == null || entry.file?.parentFile?.name == folderFilter)
        }
        FileManager.sortEntries(base, sortBy, sortDesc)
    }

    LaunchedEffect(query, mode, kindFilter, extFilter, folderFilter, allDocs) {
        if (mode != SearchMode.CONTENT || query.trim().length < 2) {
            contentHits = emptyList()
            searchingContent = false
            return@LaunchedEffect
        }
        searchingContent = true
        delay(220) // debounce typing
        val q = query.trim()
        val candidates = allDocs.filter { entry ->
            (kindFilter == null || entry.format?.kind == kindFilter) &&
                (extFilter == null || entry.format == extFilter) &&
                (folderFilter == null || entry.file?.parentFile?.name == folderFilter)
        }
        val hits = withContext(Dispatchers.IO) {
            val found = ArrayList<ContentMatchHit>()
            for (entry in candidates) {
                val fmt = entry.format ?: continue
                val engine = Engines.forFormat(fmt) ?: continue
                val blocks = runCatching {
                    engine.extractText(context, { DocIO.open(context, entry.uri) }, fmt)
                }.getOrDefault(emptyList())
                for (b in blocks) {
                    val idx = b.text.indexOf(q, ignoreCase = true)
                    if (idx >= 0) {
                        val start = (idx - 42).coerceAtLeast(0)
                        val end = (idx + q.length + 64).coerceAtMost(b.text.length)
                        val rawSnippet = b.text.substring(start, end).replace('\n', ' ').trim()
                        val snippet = buildString {
                            if (start > 0) append("…")
                            append(rawSnippet)
                            if (end < b.text.length) append("…")
                        }
                        found.add(
                            ContentMatchHit(
                                entry = entry,
                                locationLabel = b.label,
                                target = b.target,
                                snippet = snippet,
                            )
                        )
                        if (found.size >= 150) break
                    }
                }
                if (found.size >= 150) break
            }
            found
        }
        contentHits = hits
        searchingContent = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Header & Search Input
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (mode == SearchMode.FILENAME) {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort results") }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        val dir = if (sortBy == s) (if (sortDesc) " ↓" else " ↑") else ""
                                        Text("${s.label}$dir")
                                    },
                                    onClick = {
                                        if (sortBy == s) sortDesc = !sortDesc else { sortBy = s; sortDesc = false }
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(if (mode == SearchMode.FILENAME) "Search by filename…" else "Search inside PDF, Word, Sheets, Slides…")
                },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Mode Switch: File Search vs Content Search
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(
                text = "File Search (${filteredFiles.size})",
                selected = mode == SearchMode.FILENAME,
                onClick = { mode = SearchMode.FILENAME },
            )
            Pill(
                text = if (mode == SearchMode.CONTENT && query.length >= 2) "Content Search (${contentHits.size})" else "Content Search",
                selected = mode == SearchMode.CONTENT,
                onClick = { mode = SearchMode.CONTENT },
            )
        }

        // Category & Extension Filters
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("All Categories", kindFilter == null && extFilter == null, onClick = { kindFilter = null; extFilter = null })
            Pill("PDF", kindFilter == DocKind.PDF, onClick = { kindFilter = if (kindFilter == DocKind.PDF) null else DocKind.PDF; extFilter = null })
            Pill("Word", kindFilter == DocKind.WORD, onClick = { kindFilter = if (kindFilter == DocKind.WORD) null else DocKind.WORD; extFilter = null })
            Pill("Spreadsheets", kindFilter == DocKind.SHEET, onClick = { kindFilter = if (kindFilter == DocKind.SHEET) null else DocKind.SHEET; extFilter = null })
            Pill("Presentations", kindFilter == DocKind.SLIDES, onClick = { kindFilter = if (kindFilter == DocKind.SLIDES) null else DocKind.SLIDES; extFilter = null })
            DocFormat.documents.forEach { fmt ->
                Pill(
                    text = ".${fmt.ext}",
                    selected = extFilter == fmt,
                    onClick = { extFilter = if (extFilter == fmt) null else fmt },
                )
            }
        }

        // Folder Filter
        if (folderNames.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Pill("All Folders", folderFilter == null, onClick = { folderFilter = null })
                folderNames.forEach { folder ->
                    Pill(folder, folderFilter == folder, onClick = { folderFilter = if (folderFilter == folder) null else folder })
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when (mode) {
            SearchMode.FILENAME -> {
                if (filteredFiles.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "No matching files",
                        message = "Try a different filename or clear the active extension/category filters.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filteredFiles, key = { it.id }) { entry ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Slate.colors.surface,
                                border = BorderStroke(1.dp, Slate.colors.border),
                                onClick = { DocumentActivity.open(context, entry.uri) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FileBadge(entry.format, 42.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        HighlightedText(text = entry.name, highlight = query.trim())
                                        val folder = entry.file?.parentFile?.name ?: "Storage"
                                        Text(
                                            "$folder • ${formatBytes(entry.size)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate.colors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SearchMode.CONTENT -> {
                if (searchingContent) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("Searching inside local documents…", color = Slate.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (query.trim().length < 2) {
                    EmptyState(
                        icon = Icons.Outlined.FindInPage,
                        title = "Search inside documents",
                        message = "Type at least 2 characters to search text inside PDFs, Word documents, spreadsheet cells, and presentation slides.",
                    )
                } else if (contentHits.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.FindInPage,
                        title = "No text matches found",
                        message = "No document contained \"${query.trim()}\". All content indexing runs 100% locally on your device.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(contentHits, key = { "${it.entry.id}_${it.target}_${it.snippet.hashCode()}" }) { hit ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Slate.colors.surface,
                                border = BorderStroke(1.dp, Slate.colors.border),
                                onClick = {
                                    DocumentActivity.open(context, hit.entry.uri, target = hit.target, query = query.trim())
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FileBadge(hit.entry.format, 32.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            hit.entry.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Slate.colors.surfaceAlt,
                                        ) {
                                            Text(
                                                hit.locationLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Slate.colors.textSecondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                    HighlightedText(text = hit.snippet, highlight = query.trim())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedText(text: String, highlight: String) {
    if (highlight.isBlank()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    val annotated = buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val idx = text.indexOf(highlight, start, ignoreCase = true)
            if (idx < 0) {
                append(text.substring(start))
                break
            }
            if (idx > start) append(text.substring(start, idx))
            withStyle(SpanStyle(background = Slate.colors.pdf.copy(alpha = 0.28f), fontWeight = FontWeight.SemiBold)) {
                append(text.substring(idx, idx + highlight.length))
            }
            start = idx + highlight.length
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
}
