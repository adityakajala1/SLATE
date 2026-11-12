package app.slate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.slate.core.DocFormat
import app.slate.core.DocKind
import app.slate.core.FormatSniffer
import app.slate.core.OpenDoc
import app.slate.core.io.DocIO
import app.slate.core.io.RecoveryStore
import app.slate.data.Settings
import app.slate.pdf.PdfViewerScreen
import app.slate.sheet.SheetEditorScreen
import app.slate.slides.SlidesEditorScreen
import app.slate.ui.FollowDeveloperDialog
import app.slate.ui.components.ErrorState
import app.slate.ui.components.LoadingState
import app.slate.ui.theme.Slate
import app.slate.ui.theme.SlateTheme
import app.slate.word.WordEditorScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Opens one document in the right viewer/editor. Entry point for in-app navigation as well as
 * Android "Open with" (ACTION_VIEW / ACTION_EDIT) and "Share" (ACTION_SEND) from other apps.
 */
class DocumentActivity : ComponentActivity() {

    private sealed interface State {
        data object Loading : State
        data class Error(val title: String, val message: String) : State
        data class Recover(val doc: OpenDoc, val draft: java.io.File) : State
        data class Ready(val doc: OpenDoc) : State
    }

    private var state by mutableStateOf<State>(State.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = SlateApp.get(this)
        val uri = extractUri(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            SlateTheme(settings.theme) {
                Box(Modifier.fillMaxSize().background(Slate.colors.background)) {
                    LaunchedEffect(uri) {
                        state = if (uri == null) State.Error("Nothing to open", "No document was provided.") else prepare(uri)
                    }
                    when (val s = state) {
                        State.Loading -> LoadingState()
                        is State.Error -> ErrorState(s.title, s.message, onClose = { finish() })
                        is State.Recover -> {
                            LoadingState()
                            AlertDialog(
                                onDismissRequest = {},
                                title = { Text("Restore unsaved changes?") },
                                text = {
                                    Text("SLATE kept a recovery copy of \"${s.doc.name}\" from " +
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.draft.lastModified())) + ".")
                                },
                                confirmButton = { TextButton(onClick = { state = State.Ready(s.doc.copy(recoveryFile = s.draft)) }) { Text("Restore") } },
                                dismissButton = {
                                    TextButton(onClick = { RecoveryStore.clearAll(this@DocumentActivity, s.doc.uri); state = State.Ready(s.doc) }) { Text("Discard") }
                                },
                            )
                        }
                        is State.Ready -> {
                            var showFollowDialog by rememberSaveable(uri) { mutableStateOf(true) }
                            Route(s.doc)
                            if (showFollowDialog) {
                                FollowDeveloperDialog(onDismiss = { showFollowDialog = false })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Route(doc: OpenDoc) {
        val close = { finish() }
        when (doc.format.kind) {
            DocKind.PDF -> PdfViewerScreen(doc, close)
            DocKind.WORD -> WordEditorScreen(doc, close)
            DocKind.SHEET -> SheetEditorScreen(doc, close)
            DocKind.SLIDES -> SlidesEditorScreen(doc, close)
            else -> ErrorState("Unsupported file", "SLATE can't open ${doc.name}. Try the Convert tab for images.", onClose = close)
        }
    }

    private suspend fun prepare(uri: Uri): State = withContext(Dispatchers.IO) {
        val app = SlateApp.get(this@DocumentActivity)
        // Keep access for "Recent documents" when the grant allows it.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        val info = runCatching { DocIO.info(this@DocumentActivity, uri) }.getOrElse {
            return@withContext State.Error("Can't access file", "SLATE no longer has permission to open this file. Open it again from its folder.")
        }
        val format = DocFormat.detect(info.name, info.mime)?.takeIf { it.kind != DocKind.IMAGE }
            ?: FormatSniffer.sniff { DocIO.open(this@DocumentActivity, uri) }
            ?: return@withContext State.Error("Unsupported file", "\"${info.name}\" isn't a document format SLATE supports.")
        // Verify we can actually read it before showing an editor.
        runCatching { DocIO.open(this@DocumentActivity, uri).close() }.onFailure {
            return@withContext State.Error("Can't open file", "The file is unavailable or access was revoked. (${it.message})")
        }
        app.recents.touch(uri, info)
        val settings = app.settings.settings.first()
        val last = if (settings.rememberPosition) app.recents.get(uri)?.position ?: 0 else 0
        val doc = OpenDoc(
            uri = uri,
            name = info.name,
            format = format,
            writable = format.canWrite && DocIO.canWrite(this@DocumentActivity, uri),
            target = intent.getStringExtra(EXTRA_TARGET),
            query = intent.getStringExtra(EXTRA_QUERY),
            lastPosition = last,
        )
        val draft = RecoveryStore.findDraft(this@DocumentActivity, uri)
        if (draft != null) State.Recover(doc, draft) else State.Ready(doc)
    }

    private fun extractUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> intent.data
    }

    companion object {
        const val EXTRA_TARGET = "slate.target"
        const val EXTRA_QUERY = "slate.query"

        fun open(context: Context, uri: Uri, target: String? = null, query: String? = null) {
            val i = Intent(context, DocumentActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                target?.let { putExtra(EXTRA_TARGET, it) }
                query?.let { putExtra(EXTRA_QUERY, it) }
            }
            context.startActivity(i)
        }
    }
}
