package app.slate.scanner

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.slate.DocumentActivity
import app.slate.SlateApp
import app.slate.core.io.DocIO
import app.slate.pdf.PdfToolbox
import app.slate.ui.theme.Slate
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScannerLauncher(
    val startScan: () -> Unit,
)

@Composable
fun rememberDocumentScanner(
    onPdfSaved: (File) -> Unit = {},
): ScannerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showStudioFallback by remember { mutableStateOf(false) }

    val mlKitLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdf = scanResult?.pdf
            if (pdf != null) {
                scope.launch {
                    val outFile = withContext(Dispatchers.IO) {
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val dst = DocIO.uniqueFile(DocIO.libraryDir(context), "Scan_$stamp.pdf")
                        DocIO.open(context, pdf.uri).use { input ->
                            dst.outputStream().use { out -> input.copyTo(out) }
                        }
                        dst
                    }
                    val fileUri = Uri.fromFile(outFile)
                    SlateApp.get(context).recents.touch(fileUri, null)
                    Toast.makeText(context, "Saved scan: ${outFile.name}", Toast.LENGTH_SHORT).show()
                    onPdfSaved(outFile)
                    DocumentActivity.open(context, fileUri)
                }
            }
        }
    }

    if (showStudioFallback) {
        OnDeviceScanStudioDialog(
            onDismiss = { showStudioFallback = false },
            onExportPdf = { pdfFile ->
                showStudioFallback = false
                val fileUri = Uri.fromFile(pdfFile)
                SlateApp.get(context).recents.touch(fileUri, null)
                onPdfSaved(pdfFile)
                DocumentActivity.open(context, fileUri)
            },
        )
    }

    return remember {
        ScannerLauncher(
            startScan = {
                val activity = context as? Activity
                if (activity == null) {
                    showStudioFallback = true
                } else {
                    val options = GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setResultFormats(RESULT_FORMAT_PDF, RESULT_FORMAT_JPEG)
                        .setScannerMode(SCANNER_MODE_FULL)
                        .build()
                    GmsDocumentScanning.getClient(options)
                        .getStartScanIntent(activity)
                        .addOnSuccessListener { intentSender ->
                            mlKitLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        }
                        .addOnFailureListener {
                            // Play Services scanner unavailable -> launch built-in on-device scan studio
                            showStudioFallback = true
                        }
                }
            }
        )
    }
}

/**
 * Built-in on-device multi-page scan studio (camera capture + gallery import + auto-crop + rotation +
 * high-contrast document filter + PDF export). Works 100% offline even on devices without Play Services.
 */
@Composable
private fun OnDeviceScanStudioDialog(
    onDismiss: () -> Unit,
    onExportPdf: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<Bitmap>() }
    var activeIdx by remember { mutableIntStateOf(0) }
    var highContrast by remember { mutableStateOf(true) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) {
            pages.add(if (highContrast) applyDocumentContrast(bmp) else bmp)
            activeIdx = pages.lastIndex
        }
    }

    val imageImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u ->
                        runCatching {
                            val bytes = DocIO.readBytes(context, u)
                            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
                            if (highContrast) applyDocumentContrast(raw) else raw
                        }.getOrNull()
                    }
                }
                pages.addAll(decoded)
                if (pages.isNotEmpty()) activeIdx = pages.lastIndex
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document Scanner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { cameraLauncher.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.PhotoCamera, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Camera")
                    }
                    OutlinedButton(onClick = { imageImporter.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }

                if (pages.isNotEmpty()) {
                    val current = pages[activeIdx.coerceIn(0, pages.lastIndex)]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription = "Scanned page ${activeIdx + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            val idx = activeIdx.coerceIn(0, pages.lastIndex)
                            pages[idx] = rotateBitmap(pages[idx], 90f)
                        }) { Icon(Icons.Outlined.RotateRight, "Rotate 90°") }

                        IconButton(onClick = {
                            val idx = activeIdx.coerceIn(0, pages.lastIndex)
                            pages[idx] = autoCropMargins(pages[idx])
                        }) { Icon(Icons.Outlined.Crop, "Auto-crop edges") }

                        IconButton(onClick = {
                            val idx = activeIdx.coerceIn(0, pages.lastIndex)
                            pages[idx] = applyDocumentContrast(pages[idx])
                        }) { Icon(Icons.Outlined.Contrast, "Enhance contrast") }

                        IconButton(onClick = {
                            val idx = activeIdx.coerceIn(0, pages.lastIndex)
                            pages.removeAt(idx)
                            activeIdx = (idx - 1).coerceAtLeast(0)
                        }) { Icon(Icons.Outlined.DeleteOutline, "Remove page", tint = MaterialTheme.colorScheme.error) }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(pages) { idx, bmp ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(if (idx == activeIdx) 2.dp else 1.dp, if (idx == activeIdx) Slate.colors.accent else Slate.colors.border),
                                onClick = { activeIdx = idx },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop)
                            }
                        }
                    }
                } else {
                    Text(
                        "Capture one or more pages with your camera or import document photos to auto-enhance and export as a multi-page PDF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate.colors.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pages.isNotEmpty(),
                onClick = {
                    scope.launch {
                        val outFile = withContext(Dispatchers.IO) {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val dst = DocIO.uniqueFile(DocIO.libraryDir(context), "Scan_$stamp.pdf")
                            val streams = pages.map { bmp ->
                                val bos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                                ByteArrayInputStream(bos.toByteArray())
                            }
                            dst.outputStream().use { out -> PdfToolbox.imagesToPdf(streams, out) }
                            dst
                        }
                        Toast.makeText(context, "Saved ${outFile.name}", Toast.LENGTH_SHORT).show()
                        onExportPdf(outFile)
                    }
                }
            ) { Text("Export PDF (${pages.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun rotateBitmap(src: Bitmap, deg: Float): Bitmap {
    val m = Matrix().apply { postRotate(deg) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun autoCropMargins(src: Bitmap): Bitmap {
    val mx = (src.width * 0.05f).toInt()
    val my = (src.height * 0.05f).toInt()
    val w = (src.width - mx * 2).coerceAtLeast(32)
    val h = (src.height - my * 2).coerceAtLeast(32)
    return Bitmap.createBitmap(src, mx, my, w, h)
}

private fun applyDocumentContrast(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val contrast = 1.35f
    val translate = (-0.5f * contrast + 0.5f) * 255f + 12f
    val cm = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return out
}
