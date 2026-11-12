package app.slate.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.slate.DocumentActivity
import app.slate.SlateApp
import app.slate.core.DocKind
import app.slate.core.io.DocIO
import app.slate.data.RecentDoc
import app.slate.data.Thumbnails
import app.slate.files.FileManager
import app.slate.files.NewDocumentDialog
import app.slate.files.formatBytes
import app.slate.scanner.rememberDocumentScanner
import app.slate.ui.components.EmptyState
import app.slate.ui.components.FileBadge
import app.slate.ui.components.SectionHeader
import app.slate.ui.components.SlateCard
import app.slate.ui.components.SlateMark
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    onOpenFilesWithFilter: (DocKind?) -> Unit,
    onNavigateSearch: () -> Unit,
    onNavigateConvert: () -> Unit,
    onNavigateSettings: () -> Unit,
    onOpenDeveloper: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember { SlateApp.get(context) }
    val scope = rememberCoroutineScope()

    val recents by app.recents.observe(25).collectAsStateWithLifecycle(emptyList())
    var showNewDocDialog by remember { mutableStateOf(false) }

    val scanner = rememberDocumentScanner()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            FileManager.ensureStarterSamples(context)
        }
    }

    val openDocPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            DocumentActivity.open(context, uri)
        }
    }

    val importDocPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    FileManager.importExternalUriToDir(context, uri, DocIO.libraryDir(context))
                }
                Toast.makeText(context, "Imported ${imported.name} to SLATE Library", Toast.LENGTH_SHORT).show()
                DocumentActivity.open(context, Uri.fromFile(imported))
            }
        }
    }

    val continueReadingDoc = remember(recents) {
        recents.firstOrNull { it.position > 0 || it.total > 1 } ?: recents.firstOrNull()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // Header: SLATE branding, Custom Icon, Search button, Settings button
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SlateMark(size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Slate",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = "Every document. One place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate.colors.textSecondary,
                    )
                }
                IconButton(onClick = onNavigateSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = "Search")
                }
                IconButton(onClick = onOpenDeveloper) {
                    Icon(Icons.Outlined.Person, contentDescription = "Developer")
                }
                IconButton(onClick = onNavigateSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            }
        }

        // Reading Insights Strip (Aesthetic Minimalist Stats)
        item {
            val totalDocs = recents.size
            val activeReadingDocs = recents.count { it.position > 0 }
            val completedDocs = recents.count { it.total > 0 && it.position >= it.total }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReadingStatPill(
                    icon = Icons.Outlined.MenuBook,
                    label = "Library",
                    value = "$totalDocs docs",
                    modifier = Modifier.weight(1f),
                )
                ReadingStatPill(
                    icon = Icons.Outlined.AutoStories,
                    label = "In Progress",
                    value = "$activeReadingDocs read",
                    modifier = Modifier.weight(1f),
                )
                ReadingStatPill(
                    icon = Icons.Outlined.CheckCircleOutline,
                    label = "Finished",
                    value = "$completedDocs done",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // Quick Access Categories
        item {
            SectionHeader("Quick Access")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickAccessTile("PDFs", "Read & sign", Icons.Outlined.PictureAsPdf, Slate.colors.pdf) {
                    onOpenFilesWithFilter(DocKind.PDF)
                }
                QuickAccessTile("Documents", "DOCX, ODT, TXT", Icons.Outlined.Description, Slate.colors.word) {
                    onOpenFilesWithFilter(DocKind.WORD)
                }
                QuickAccessTile("Spreadsheets", "XLSX, ODS, CSV", Icons.Outlined.TableChart, Slate.colors.sheet) {
                    onOpenFilesWithFilter(DocKind.SHEET)
                }
                QuickAccessTile("Presentations", "PPTX, ODP, PPT", Icons.Outlined.Slideshow, Slate.colors.slides) {
                    onOpenFilesWithFilter(DocKind.SLIDES)
                }
                QuickAccessTile("All Files", "Browse storage", Icons.Outlined.Folder, Slate.colors.accent) {
                    onOpenFilesWithFilter(null)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Continue Reading Banner
        if (continueReadingDoc != null) {
            item {
                SectionHeader("Continue Reading")
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    ContinueReadingCard(
                        doc = continueReadingDoc,
                        onClick = { DocumentActivity.open(context, Uri.parse(continueReadingDoc.uri)) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Quick Actions
        item {
            SectionHeader("Quick Actions")
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickActionCard(
                        icon = Icons.Outlined.FolderOpen,
                        title = "Open File",
                        subtitle = "From device",
                        modifier = Modifier.weight(1f),
                        onClick = { openDocPicker.launch(arrayOf("*/*")) },
                    )
                    QuickActionCard(
                        icon = Icons.Outlined.NoteAdd,
                        title = "New Document",
                        subtitle = "Word, Sheet, Slide",
                        modifier = Modifier.weight(1f),
                        onClick = { showNewDocDialog = true },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickActionCard(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "Scan Document",
                        subtitle = "Camera to PDF",
                        modifier = Modifier.weight(1f),
                        onClick = { scanner.startScan() },
                    )
                    QuickActionCard(
                        icon = Icons.Outlined.SwapHoriz,
                        title = "Convert",
                        subtitle = "Any format",
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateConvert,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Slate.colors.surface,
                    border = BorderStroke(1.dp, Slate.colors.border),
                    onClick = { importDocPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.FileDownload, null, tint = Slate.colors.accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Import Document to SLATE Library", style = MaterialTheme.typography.titleSmall)
                            Text("Copy a file into private offline workspace", style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Recent Documents
        item {
            SectionHeader(
                title = "Recent Documents",
                action = if (recents.isNotEmpty()) "Browse All" else null,
                onAction = { onOpenFilesWithFilter(null) },
            )
        }

        if (recents.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = "No recent documents yet",
                    message = "Documents you open, create, scan, or convert will appear here with their reading progress.",
                    actionLabel = "Open a File",
                    onAction = { openDocPicker.launch(arrayOf("*/*")) },
                )
            }
        } else {
            items(recents, key = { it.uri }) { rec ->
                Box(Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
                    RecentDocumentRow(
                        doc = rec,
                        onClick = { DocumentActivity.open(context, Uri.parse(rec.uri)) },
                    )
                }
            }
        }
    }

    if (showNewDocDialog) {
        NewDocumentDialog(
            onDismiss = { showNewDocDialog = false },
            onCreate = { title, fmt ->
                showNewDocDialog = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        FileManager.createNewBlankDocument(DocIO.libraryDir(context), title, fmt)
                    }
                    DocumentActivity.open(context, Uri.fromFile(created))
                }
            },
        )
    }
}

@Composable
private fun QuickAccessTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Slate.colors.surface,
        border = BorderStroke(1.dp, Slate.colors.border),
        onClick = onClick,
        modifier = Modifier.width(142.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = if (Slate.colors.isDark) 0.18f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Slate.colors.surface,
        border = BorderStroke(1.dp, Slate.colors.border),
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Slate.colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    doc: RecentDoc,
    onClick: () -> Unit,
) {
    val fmt = doc.format
    val unitLabel = when (fmt?.kind) {
        DocKind.PDF -> "Page"
        DocKind.SLIDES -> "Slide"
        DocKind.SHEET -> "Row"
        else -> "Section"
    }
    val progressLabel = if (doc.total > 0) {
        "$unitLabel ${(doc.position + 1).coerceAtMost(doc.total)} of ${doc.total}"
    } else {
        "Resume where you left off"
    }

    SlateCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThumbnailOrBadge(doc, 54.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(progressLabel, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
                if (doc.total > 1) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ((doc.position + 1).toFloat() / doc.total.toFloat()).coerceIn(0.05f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentDocumentRow(
    doc: RecentDoc,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Slate.colors.surface,
        border = BorderStroke(1.dp, Slate.colors.border),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThumbnailOrBadge(doc, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildString {
                    append(doc.ext.uppercase().ifEmpty { "DOC" })
                    if (doc.size > 0) {
                        append(" • ")
                        append(formatBytes(doc.size))
                    }
                    append(" • ")
                    append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(doc.lastOpened)))
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReadingStatPill(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Slate.colors.surface,
        border = BorderStroke(1.dp, Slate.colors.border),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Slate.colors.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Slate.colors.text, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Slate.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ThumbnailOrBadge(doc: RecentDoc, size: androidx.compose.ui.unit.Dp) {
    val bmp = remember(doc.thumbPath) { doc.thumbPath?.let { Thumbnails.load(it) } }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = doc.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.22f))
                .background(Color.White),
        )
    } else {
        FileBadge(format = doc.format, size = size)
    }
}
