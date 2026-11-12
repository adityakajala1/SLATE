package app.slate.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import app.slate.core.DocFormat
import app.slate.core.io.DocIO
import java.io.File
import java.io.FileOutputStream

object ShareAndPrint {

    fun shareDocument(context: Context, uri: Uri, name: String? = null) {
        val shareUri = ensureShareableUri(context, uri)
        val mime = DocFormat.fromName(name ?: DocIO.info(context, uri).name)?.mime ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ${name ?: "document"}").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareMultiple(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            shareDocument(context, uris.first())
            return
        }
        val shareUris = ArrayList(uris.map { ensureShareableUri(context, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ${uris.size} documents").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun printPdf(context: Context, pdfFile: File, jobName: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (pm == null) {
            Toast.makeText(context, "Printing is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        pm.print(
            jobName,
            object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?,
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build()
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback,
                ) {
                    runCatching {
                        pdfFile.inputStream().use { input ->
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }.onSuccess {
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    }.onFailure {
                        callback.onWriteFailed(it.message)
                    }
                }
            },
            null,
        )
    }

    private fun ensureShareableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme == "file") {
            val f = File(uri.path!!)
            return runCatching { DocIO.shareUri(context, f) }.getOrElse {
                val copy = File(DocIO.secureTemp(context), f.name)
                f.copyTo(copy, overwrite = true)
                DocIO.shareUri(context, copy)
            }
        }
        return uri
    }
}
