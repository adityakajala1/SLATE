# SLATE — Architecture

SLATE is a single-module Android app (Kotlin, Jetpack Compose, Material 3 re-skinned with the SLATE
design system). Components are separated by **package**, each owning one responsibility. There is
no DI framework (a tiny service locator in `SlateApp` keeps cold start fast).

```
app/src/main/java/app/slate/
  SlateApp.kt            service locator (Room, DataStore, repositories), lazy PDFBox init
  MainActivity.kt        launcher activity: splash + 5-tab shell (Home, Files, Search, Convert, Settings)
  DocumentActivity.kt    opens ONE document; handles ACTION_VIEW / ACTION_EDIT / ACTION_SEND; recovery prompt
  core/                  DocFormat, DocumentEngine contract, OpenDoc, UndoStack, FormatSniffer
  core/io/               DocIO (content URIs, safe writes), RecoveryStore, Xml/Zip helpers, Cfb (OLE2 reader)
  data/                  Room (recents, favorites, reading positions), Settings (DataStore), Thumbnails cache
  engine/Engines.kt      registry: format -> engine
  pdf/                   PdfEngine + PDF viewer/annotator + PDF toolbox (merge/split/compress/...)
  word/                  WordEngine (DOCX/ODT/RTF/TXT read+write, DOC read) + Word editor
  sheet/                 SheetEngine (XLSX/ODS/CSV read+write, XLS read) + formula engine + grid editor
  slides/                SlidesEngine (PPTX/ODP read+write, PPT read) + slide editor + presenter
  files/                 file manager (internal storage, Downloads, Documents, SAF folders, library)
  search/                filename + content search
  convert/               conversion UI + WorkManager worker
  scanner/               ML Kit on-device document scanner
  share/                 share / print helpers
  ui/                    theme, shared components, editor kit, navigation, screens
```

## Library choices

| Need | Choice | Why | License |
|---|---|---|---|
| PDF rendering | `android.graphics.pdf.PdfRenderer` (platform) | Zero dependency, hardware fast, page-at-a-time memory | Platform |
| PDF text/annotations/page ops | PdfBox-Android 2.0.27 | Mature, pure Java, offline; text extraction, annotations, merge/split, image re-encode | Apache-2.0 |
| PDF writing from documents | `android.graphics.pdf.PdfDocument` (platform) | Renders our own layout via Canvas/StaticLayout | Platform |
| OOXML / ODF | **Own streaming XML engines** (XmlPullParser + ZIP) | Apache POI is ~15 MB, relies on java.awt / StAX not on Android; LibreOffice for Android is ~200 MB+ native and impractical to embed | Own |
| DOC / XLS / PPT (binary) | Own OLE2 (`Cfb`) + record readers, **read-only** | Honest read support; saving goes to modern formats | Own |
| Scanner | ML Kit Document Scanner (Play services) | On-device edge detection, crop, perspective, filters, multipage; needs no camera permission | Google APIs ToS (free) |
| Storage | Storage Access Framework + optional All-files access | Works without broad permission; full browsing when the user opts in | Platform |
| Metadata | Room; settings in DataStore; conversions in WorkManager | Jetpack standard | Apache-2.0 |

Evaluated and rejected: Apache POI (size, AWT), docx4j (JAXB), LibreOffice/Collabora SDK (size, native
build), MuPDF (AGPL — incompatible with closed distribution), PdfiumAndroid (adds native libs; platform renderer suffices).

## Engine contract (`core/Engine.kt`)

```kotlin
interface DocumentEngine {
    val reads: Set<DocFormat>
    fun outputsFor(from: DocFormat): Set<DocFormat>
    suspend fun convert(context, input: InputProvider, from, to, out: OutputStream, progress: (Float)->Unit)
    suspend fun extractText(context, input, from): List<TextBlock>   // label + deep-link target + text
    suspend fun thumbnail(context, input, from, widthPx): Bitmap?
}
```

* Conversions always go through a real document model (parse → model → write/render). Never renames.
* `TextBlock.target` deep links: `page=N` (0-based, PDF), `para=N` (Word), `sheet=S;row=R;col=C` (Sheets), `slide=N` (Slides).

## Editor contract

Each family exposes `@Composable fun XxxEditorScreen(doc: OpenDoc, onClose: () -> Unit)` (PDF: `PdfViewerScreen`).
Editors use `ui/editor/EditorKit.kt`:

* `rememberSaveController(doc, writableFormats) { format, out -> write model }`
* `EditorScaffold(...)` — back with unsaved-changes guard, undo/redo, Save, Save As menu, toolbar slot.
* Save never overwrites the original without an explicit "Replace" confirmation (once per session).
* `controller.markDirty()` after each edit → debounced recovery draft (`RecoveryStore`); restored by
  `DocumentActivity` after a crash. Load from `doc.openSource(context)` / `doc.sourceFormat()` so drafts work.
* `UndoStack<T>` for immutable snapshot undo/redo.
* Positions: `SlateApp.get(ctx).recents.setPosition(doc.uri, index, total)`; start at `doc.lastPosition`
  unless `doc.target` says otherwise.

## Privacy

The manifest **removes** `INTERNET` and `ACCESS_NETWORK_STATE`: SLATE cannot upload anything.
No analytics, no ads, no accounts. Temp files live in `cache/tmp` and are purged; Settings can clear caches.
