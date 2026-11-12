# SLATE — Development Checklist & Feature Matrix

**Product:** SLATE — *Every document. One place.*
**Package:** `app.slate`
**Architecture:** Kotlin + Jetpack Compose + Material 3 (SLATE custom palette) + Room + DataStore + Android `PdfRenderer` + `PdfBox-Android` + Streaming OOXML/ODF/RTF/CSV/OLE2 engines + ML Kit Document Scanner.

---

## 1. Completed Features

### Brand Identity & Custom Launcher Icon
- [x] Custom procedural high-resolution slate-and-chalk icon (`SLATE/branding/make_icon.py`) with dark charcoal slate board, bevelled frame, diagonal realistic chalk stick, chalk texture, and soft shadows.
- [x] Strictly adheres to icon rules (no letters `S`/`SLATE`, no PDF/document symbols, no clutter).
- [x] Adaptive icon foreground (`108dp` canvas across `mdpi`–`xxxhdpi`), adaptive background, legacy rounded square (`ic_launcher.png`), legacy circle (`ic_launcher_round.png`), in-app brand mark (`drawable-nodpi/slate_mark.png`), and `512×512` Play Store icon (`SLATE/branding/out/playstore_512.png`).
- [x] Custom splash screen (`Theme.Slate.Splash`), SLATE Light (`#F7F7F5` / `#FFFFFF` / `#171717` / `#777777` / `#454545` / `#E8E8E8`) and Dark (`#111111` / `#1C1C1C` / `#FFFFFF` / `#A0A0A0` / `#D0D0D0` / `#303030`) themes, plus System theme mode.

### Privacy & Offline-First Architecture
- [x] `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` explicitly removed (`tools:node="remove"`) in `AndroidManifest.xml`.
- [x] Zero advertisements, zero subscriptions, zero account walls, zero trackers/analytics.
- [x] Secure temporary files directory (`cache/tmp`) automatically cleaned up and user-clearable in Settings.

### Home Screen & 5-Tab Navigation
- [x] 5 bottom navigation sections (`Home`, `Files`, `Search`, `Convert`, `Settings`) with smooth crossfade transitions.
- [x] Home header with custom SLATE icon, tagline, Search shortcut, and Settings shortcut.
- [x] Quick Access category cards (`PDFs`, `Documents`, `Spreadsheets`, `Presentations`, `All Files`).
- [x] Continue Reading card that remembers the exact page, slide, or row index (`RecentsRepository`) and reopens directly at that position.
- [x] Quick Actions (`Open File`, `New Document`, `Scan Document`, `Convert`, `Import Document`).
- [x] Recent Documents list with file type badge, file size, last-opened timestamp, and generated WEBP page thumbnail previews (`Thumbnails`).

### Complete Local File Manager (`app.slate.files`)
- [x] Browse SLATE Library, Downloads, Documents, Internal Storage, Favorites, and user-picked SAF folders (`OpenDocumentTree`).
- [x] Import files, create folders, create new blank documents (`DOCX`, `XLSX`, `PPTX`, `TXT`, `ODT`, `ODS`, `ODP`, `CSV`, `RTF`).
- [x] Rename, Copy, Move, and Delete files and folders.
- [x] Sort by Name, Date, File Size, or File Type (ascending/descending).
- [x] Grid view and List view toggle.
- [x] Favorites star toggle persisted in Room (`FavoriteDao`).
- [x] Multi-file selection for batch sharing (`ACTION_SEND_MULTIPLE`) and batch deletion.
- [x] Registered as system handler for Android `Open With` (`ACTION_VIEW` / `ACTION_EDIT`) and `Share` (`ACTION_SEND`) across all 13 document formats.

### Universal Document Search (`app.slate.search`)
- [x] **File Search**: instant search by filename, filter by category, filter by extension, filter by folder, and sort results.
- [x] **Content Search**: local full-text search inside PDF pages, Word paragraphs/tables, Spreadsheet cells, Presentation slides, and Text files with highlighted snippets, location badges (`Page N`, `Slide N`, `Sheet!Cell`, `Paragraph N`), and deep-link jump straight to the matching page/slide/cell.

### PDF Viewer, Editor & Toolbox (`app.slate.pdf`)
- [x] Fast hardware-accelerated page rendering via `PdfRenderer` with LRU bitmap caching and pinch-to-zoom.
- [x] Search text inside PDF with match counter and jump-to-next match.
- [x] Select and copy text from any PDF page.
- [x] Highlight text, freehand pen drawing (multiple colors), sticky comment notes, and drawn signature pad burned into real PDF streams via `PdfBox-Android`.
- [x] Page management: rotate pages (90° increments), reorder pages up/down, delete pages, and extract page ranges.
- [x] PDF Toolbox: Merge multiple PDFs, Split/Extract pages, Compress PDFs, Export pages as PNG/JPG, Print PDF via `PrintManager`, and Convert PDF to `DOCX`, `ODT`, `RTF`, `TXT`, `PNG`, `JPG`.

### Word Document Viewer & Editor (`app.slate.word`)
- [x] Formats supported: `DOCX`, `ODT`, `RTF`, `TXT` (full read & write), `DOC` (OLE2 Word 97–2003 FIB/CLX piece-table reader), and export to `PDF`.
- [x] Rich editing: Bold, Italic, Underline, Font Size (`10pt`–`36pt`), Headings (`Body`, `H1`, `H2`, `H3`), Paragraph Alignment (`Left`, `Center`, `Right`, `Justify`), Bulleted & Numbered lists.
- [x] Insert & edit tables (`+ Row`, `+ Column`, `- Row`, `- Column`).
- [x] Insert embedded images from device gallery.
- [x] Find & Replace across all paragraphs and table cells.
- [x] Copy, Cut, Paste, Undo, Redo, Save, Save As, and auto-save crash-recovery drafts.

### Spreadsheet Viewer & Editor (`app.slate.sheet`)
- [x] Formats supported: `XLSX`, `ODS`, `CSV` (full read & write), `XLS` (OLE2 BIFF8 reader), and export to `PDF`.
- [x] Interactive grid with formula bar (`fx`), cell editing, Bold/Italic/Alignment formatting.
- [x] Built-in recursive-descent formula evaluator supporting `=SUM(A1:B10)`, `=AVERAGE(...)`, `=MIN(...)`, `=MAX(...)`, `=COUNT(...)`, `=COUNTA(...)`, `=IF(...)`, `=ROUND(...)`, `=ABS(...)`, cell references, and arithmetic (`+ - * / ^`).
- [x] Live numeric calculation bar (`Count`, `Sum`, `Avg`, `Min`, `Max`).
- [x] Add/delete rows, add/delete columns, adjust column widths & row heights, sort column (`A→Z` / `Z→A`), filter rows, and multi-sheet tabs (add, rename, delete sheets).

### Presentation Viewer & Editor (`app.slate.slides`)
- [x] Formats supported: `PPTX`, `ODP` (full read & write), `PPT` (OLE2 PowerPoint 97–2003 record stream reader), and export to `PDF`.
- [x] 16:9 interactive slide stage with pinch zoom and full-screen presentation mode.
- [x] Add, delete, duplicate, and reorder slides.
- [x] Edit text boxes, insert images, insert shapes (`Rectangle`, `Rounded Card`, `Circle/Oval`, `Callout Banner`), and format text (`Bold`, `Italic`, font size).

### Document Scanner (`app.slate.scanner`)
- [x] Integrates Google Play Services ML Kit Document Scanner (`GmsDocumentScanning`) for automatic edge detection, perspective correction, cropping, filters, and multi-page PDF generation.
- [x] Includes a built-in 100% offline fallback Scan Studio (Camera capture + Gallery import + 90° rotation + auto-margin crop + high-contrast document filter + multi-page PDF export) for devices without Play Services.

---

## 2. Known Limitations & Honest Engineering Trade-offs

1. **Legacy Binary Office Formats (`DOC`, `XLS`, `PPT`)**:
   - Microsoft's legacy 1997–2003 OLE2 binary formats are read using our custom `Cfb` compound-file reader and stream parsers (`CLX` piece table for `DOC`, `BIFF8` records for `XLS`, atom records for `PPT`).
   - Editing and saving a legacy `.doc`, `.xls`, or `.ppt` file prompts the user to save into its modern open standard (`DOCX`, `XLSX`, `PPTX`, `ODT`, `ODS`, `ODP`, or `PDF`) rather than writing proprietary legacy binary streams. The UI clearly states this in the editor subtitle.
2. **Complex Desktop Office Layout Fidelity**:
   - Native mobile rendering focuses on clean typography, headings, lists, tables, formulas, images, and shapes. Advanced desktop-only features (VBA macros, pivot tables, 3D SmartArt, custom embedded PostScript fonts) are not executed.
3. **PDF → DOCX Conversion**:
   - Extracts structured text paragraphs per page from the PDF text layer and writes a clean, editable `DOCX` / `ODT` / `RTF` / `TXT` document. Scanned image-only PDFs without an OCR text layer produce a notice paragraph rather than fabricating text.
