package app.slate.convert

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.slate.DocumentActivity
import app.slate.SlateApp
import app.slate.core.DocFormat
import app.slate.core.FormatSniffer
import app.slate.core.io.DocIO
import app.slate.core.withoutExtension
import app.slate.engine.Engines
import app.slate.files.formatBytes
import app.slate.pdf.PdfToolbox
import app.slate.share.ShareAndPrint
import app.slate.ui.components.FileBadge
import app.slate.ui.components.Pill
import app.slate.ui.components.SlateCard
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ConvertScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var sourceSize by remember { mutableLongStateOf(0L) }
    var sourceFormat by remember { mutableStateOf<DocFormat?>(null) }
    var targetFormat by remember { mutableStateOf<DocFormat?>(null) }

    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var convertedFile by remember { mutableStateOf<File?>(null) }
    var convertedFormat by remember { mutableStateOf<DocFormat?>(null) }

    val availableTargets = remember(sourceFormat) {
        sourceFormat?.let { Engines.outputsFor(it) } ?: emptyList()
    }

    LaunchedEffect(availableTargets) {
        if (targetFormat !in availableTargets) {
            targetFormat = availableTargets.firstOrNull()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            convertedFile = null
            errorMessage = null
            val info = DocIO.info(context, uri)
            sourceUri = uri
            sourceName = info.name
            sourceSize = info.size
            val detected = DocFormat.detect(info.name, info.mime)
                ?: FormatSniffer.sniff { DocIO.open(context, uri) }
            sourceFormat = detected
            if (detected == null) {
                errorMessage = "Unsupported source file format for \"${info.name}\"."
            }
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dstUri ->
        val f = convertedFile
        if (dstUri != null && f != null) {
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        DocIO.writeSafely(context, dstUri) { out -> f.inputStream().use { it.copyTo(out) } }
                    }
                }
                res.onSuccess {
                    Toast.makeText(context, "Saved converted file", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Multi-file pickers for PDF Toolbox shortcuts
    val mergePdfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size >= 2) {
            scope.launch {
                converting = true
                progress = 0.3f
                errorMessage = null
                val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), "Merged_${System.currentTimeMillis() % 10000}.pdf")
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val streams = uris.map { DocIO.open(context, it) }
                        try {
                            outFile.outputStream().use { out -> PdfToolbox.mergePdfs(context, streams, out) }
                        } finally {
                            streams.forEach { runCatching { it.close() } }
                        }
                    }
                }
                converting = false
                res.onSuccess {
                    convertedFile = outFile
                    convertedFormat = DocFormat.PDF
                    SlateApp.get(context).recents.touch(Uri.fromFile(outFile), null)
                }.onFailure {
                    errorMessage = "PDF Merge failed: ${it.message}"
                }
            }
        } else if (uris.isNotEmpty()) {
            Toast.makeText(context, "Select at least 2 PDF files to merge", Toast.LENGTH_SHORT).show()
        }
    }

    val imagesToPdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                converting = true
                progress = 0.1f
                errorMessage = null
                val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), "Images_${System.currentTimeMillis() % 10000}.pdf")
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val streams = uris.map { DocIO.open(context, it) }
                        try {
                            outFile.outputStream().use { out ->
                                PdfToolbox.imagesToPdf(streams, out) { p -> progress = p }
                            }
                        } finally {
                            streams.forEach { runCatching { it.close() } }
                        }
                    }
                }
                converting = false
                res.onSuccess {
                    convertedFile = outFile
                    convertedFormat = DocFormat.PDF
                    SlateApp.get(context).recents.touch(Uri.fromFile(outFile), null)
                }.onFailure {
                    errorMessage = "Images to PDF failed: ${it.message}"
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Convert", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Local, offline document conversion engine. Preserves your original file untouched.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate.colors.textSecondary,
        )

        // Step 1: Select Source Document
        SlateCard(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(format = sourceFormat, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sourceName ?: "1. Select source document",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (sourceFormat != null) "${sourceFormat!!.label} • ${formatBytes(sourceSize)}"
                        else "PDF, DOCX, DOC, ODT, RTF, TXT, XLSX, XLS, ODS, CSV, PPTX, PPT, ODP, PNG, JPG",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate.colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(12.dp)) {
                    Text(if (sourceUri == null) "Browse" else "Change")
                }
            }
        }

        // Step 2 & 3: Source Format & Output Format Selection
        if (sourceFormat != null) {
            SlateCard {
                Text("2. Source format", style = MaterialTheme.typography.labelLarge, color = Slate.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(sourceFormat!!.label, selected = true, onClick = {})
                }
                Spacer(Modifier.height(14.dp))
                Text("3. Choose output format", style = MaterialTheme.typography.labelLarge, color = Slate.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                if (availableTargets.isEmpty()) {
                    Text(
                        "No direct conversions available for ${sourceFormat!!.label}.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableTargets.forEach { fmt ->
                            Pill(
                                text = fmt.label,
                                selected = targetFormat == fmt,
                                onClick = { targetFormat = fmt },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !converting && sourceUri != null && targetFormat != null,
                    onClick = {
                        val sUri = sourceUri ?: return@Button
                        val sFmt = sourceFormat ?: return@Button
                        val tFmt = targetFormat ?: return@Button
                        val engine = Engines.forFormat(sFmt) ?: return@Button
                        scope.launch {
                            converting = true
                            progress = 0.05f
                            errorMessage = null
                            convertedFile = null
                            val base = (sourceName ?: "Document").withoutExtension()
                            val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), "$base.${tFmt.ext}")
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    outFile.outputStream().buffered().use { out ->
                                        engine.convert(
                                            context = context,
                                            input = { DocIO.open(context, sUri) },
                                            from = sFmt,
                                            to = tFmt,
                                            out = out,
                                            progress = { p -> progress = p.coerceIn(0f, 1f) },
                                        )
                                    }
                                }
                            }
                            converting = false
                            res.onSuccess {
                                convertedFile = outFile
                                convertedFormat = tFmt
                                SlateApp.get(context).recents.touch(Uri.fromFile(outFile), null)
                            }.onFailure {
                                outFile.delete()
                                errorMessage = it.message ?: "Conversion failed"
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Convert ${sourceFormat!!.ext.uppercase()} → ${targetFormat?.ext?.uppercase() ?: ""}")
                }
            }
        }

        // Progress Indicator
        if (converting) {
            SlateCard {
                Text("Converting document…", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Slate.colors.textSecondary)
            }
        }

        // Error Banner
        if (errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Conversion Result Preview / Save / Share Card
        convertedFile?.let { outFile ->
            SlateCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileBadge(convertedFormat, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Conversion complete", style = MaterialTheme.typography.labelMedium, color = Slate.colors.sheet)
                        Text(outFile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Saved in SLATE Library • ${formatBytes(outFile.length())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate.colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (convertedFormat?.kind != app.slate.core.DocKind.IMAGE) {
                        Button(
                            onClick = { DocumentActivity.open(context, Uri.fromFile(outFile)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Visibility, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Preview")
                        }
                    }
                    OutlinedButton(
                        onClick = { saveCopyLauncher.launch(outFile.name) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save As")
                    }
                    OutlinedButton(
                        onClick = { ShareAndPrint.shareDocument(context, Uri.fromFile(outFile), outFile.name) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
        }

        // Multi-file PDF Toolbox Utilities
        Text("PDF & Image Batch Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SlateCard(
                onClick = { mergePdfsPicker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.MergeType, null, tint = Slate.colors.pdf)
                Spacer(Modifier.height(8.dp))
                Text("Merge PDFs", style = MaterialTheme.typography.titleSmall)
                Text("Combine 2+ PDFs into one file", style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
            }
            SlateCard(
                onClick = { imagesToPdfPicker.launch("image/*") },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.PictureAsPdf, null, tint = Slate.colors.pdf)
                Spacer(Modifier.height(8.dp))
                Text("Images → PDF", style = MaterialTheme.typography.titleSmall)
                Text("Turn PNG/JPG photos into a PDF", style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
            }
        }

        // Supported Conversions Reference Matrix
        SlateCard {
            Text("Supported Local Conversions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ConversionMatrixRow("Word", "DOCX ↔ ODT ↔ RTF ↔ TXT, DOC → DOCX/PDF, DOCX/ODT → PDF")
            ConversionMatrixRow("Spreadsheets", "XLSX ↔ ODS ↔ CSV, XLS → XLSX/PDF, XLSX/ODS/CSV → PDF")
            ConversionMatrixRow("Presentations", "PPTX ↔ ODP, PPT → PPTX/PDF, PPTX/ODP → PDF")
            ConversionMatrixRow("PDF & Images", "PDF → DOCX / ODT / RTF / TXT / PNG / JPG, Images → PDF")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConversionMatrixRow(category: String, pairs: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(category, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(pairs, style = MaterialTheme.typography.bodySmall, color = Slate.colors.textSecondary)
    }
}
