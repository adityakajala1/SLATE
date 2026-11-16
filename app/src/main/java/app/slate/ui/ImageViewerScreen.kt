package app.slate.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.slate.DocumentActivity
import app.slate.core.OpenDoc
import app.slate.core.io.DocIO
import app.slate.core.withoutExtension
import app.slate.pdf.PdfToolbox
import app.slate.share.ShareAndPrint
import app.slate.ui.theme.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerScreen(doc: OpenDoc, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(doc.uri) {
        withContext(Dispatchers.IO) {
            val bytes = runCatching { DocIO.readBytes(context, doc.uri) }.getOrNull()
            bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            loading = false
        }
    }

    BackHandler(onBack = onClose)

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
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = doc.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val dims = bitmap?.let { "${it.width} × ${it.height} px" } ?: "Image"
                        Text(
                            text = dims,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate.colors.textSecondary,
                        )
                    }

                    // Convert Image to PDF button
                    IconButton(onClick = {
                        scope.launch {
                            val outFile = DocIO.uniqueFile(DocIO.libraryDir(context), "${doc.name.withoutExtension()}.pdf")
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    val stream = DocIO.open(context, doc.uri)
                                    outFile.outputStream().use { out ->
                                        PdfToolbox.imagesToPdf(listOf(stream), out)
                                    }
                                }
                            }
                            if (res.isSuccess && outFile.length() > 0L) {
                                Toast.makeText(context, "Converted to PDF", Toast.LENGTH_SHORT).show()
                                DocumentActivity.open(context, Uri.fromFile(outFile))
                            } else {
                                Toast.makeText(context, "Conversion failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.PictureAsPdf, "Convert to PDF")
                    }

                    // Download button
                    IconButton(onClick = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) { DocIO.saveToDownloads(context, doc.uri, doc.name) }
                            if (uri != null) {
                                Toast.makeText(context, "Downloaded to Downloads folder: ${doc.name}", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.FileDownload, "Download to device")
                    }

                    // Share button
                    IconButton(onClick = { ShareAndPrint.shareDocument(context, doc.uri, doc.name) }) {
                        Icon(Icons.Outlined.Share, "Share")
                    }
                }
                HorizontalDivider(color = Slate.colors.border)
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Slate.colors.surfaceAlt)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = doc.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            } else if (loading) {
                CircularProgressIndicator()
            } else {
                Text("Unable to load image", color = Slate.colors.textSecondary)
            }
        }
    }
}
