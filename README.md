# FreeScanner

A local-first Android document scanner. Capture or import pages, clean them up, keep them as editable multi-page documents, then export to PDF, Word, plain text, or the photo gallery — all on the device.

There is no account, no cloud storage, and no upload: the manifest actively removes the `INTERNET` permission.

**Status:** version `1.4.1` (versionCode 11). Application ID `com.freescanner`. UI language is Indonesian.

**In one line:** a CamScanner-style scanner that never leaves your phone — capture, straighten, clean up, annotate, read the text, and export, with the documents stored in Room plus private app files.

## Install

Releases are published at [github.com/baniputrabangsawan/freescanner/releases](https://github.com/baniputrabangsawan/freescanner/releases). Download `freescanner-<version>.apk` and open it on the phone; Android asks once for permission to install apps from the browser or file manager that downloaded it.

Every release is signed with the same key, so a newer APK installs over the old one and keeps the library, folders, drafts and settings. Uninstalling the app removes them.

**Automatic updates.** The app has no `INTERNET` permission and cannot check for updates itself. Use [Obtainium](https://github.com/ImranR98/Obtainium): add `https://github.com/baniputrabangsawan/freescanner` as a source and it follows the GitHub releases, downloads a new APK when one appears, and hands it to Android's installer for confirmation. The update channel stays outside the app, so the app itself remains offline.

**Building a release yourself.** Release signing reads `keystore.properties` in the repository root, which is untracked:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=freescanner
keyPassword=…
```

`./gradlew assembleRelease` then writes `app/build/outputs/apk/release/app-release.apk`. Without that file the release build fails instead of producing an APK signed with the debug key, which could not update an installed copy.

## Features

| Area | What it does |
| --- | --- |
| Capture & import | ML Kit Document Scanner (auto/manual capture, edge detection), gallery images, external PDFs |
| Page editing | Corner crop with perspective correction, rotation, four filters, comparison against the managed original |
| Annotations | Signature/handwriting, pen, highlight, text overlay, tiled watermark — stored as metadata, not baked into the source |
| Text | On-device Latin OCR (English/Indonesian), editable recognized text, TXT and DOCX export |
| PDF | Generated PDFs with optional page numbers and invisible searchable text, AES-256 password protection, page extraction, split into single-page PDFs, compression presets, long-image output |
| Utilities | QR/barcode reading, ID-card layout on A4, Android printing |
| Library | Folders, search, six sort orders, list/grid, bulk select for merge/duplicate/move/delete, recoverable drafts |

Every entry in the tool catalog is wired to real behavior; features that would require a server or a paid third-party service are deliberately absent.

## Requirements

- JDK 17
- Android SDK with compile/target SDK 35; device or emulator on **API 26+**
- Gradle **8.9** via the checked-in wrapper (`./gradlew`) — no separate Gradle install
- `ANDROID_HOME` set, or an untracked `local.properties` with `sdk.dir=...`
- Google Play services on the device for the ML Kit scanner (its model may download on first use; OCR and barcode models are bundled and work offline)

## Build and run

```sh
# From the repository root
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install to the connected device
adb shell am start -n com.freescanner/.MainActivity
```

With several devices attached, set the target explicitly:

```sh
ANDROID_SERIAL=<serial> ./gradlew installDebug
adb devices -l                 # list serials first
```

## Test and check

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest   # requires a device/emulator
```

- **Unit tests** (`app/src/test`): pure JVM only — crop geometry, PDF layout math, library sorting, TXT/DOCX writing.
- **Instrumented tests** (`app/src/androidTest`): Compose journeys, Room migrations, repository/file lifecycle, imaging, annotations, OCR, PDF, gestures. Launch `MainActivity` via `ActivityScenario`, drive real touch input for gesture bugs, and use synthetic bitmaps with cleanup in `finally`.
- No numeric coverage threshold is configured. Report checks actually run — not historical pass counts from the docs.

A single instrumented class:

```sh
adb shell am instrument -w -r \
  -e class com.freescanner.PageImageScrollTest \
  com.freescanner.test/androidx.test.runner.AndroidJUnitRunner
```

## Architecture

Single Gradle module (`:app`), manual dependency wiring, Compose UI, Room persistence. No DI framework, no multi-module split.

```
MainActivity  (Activity Result launchers, ML Kit scanner, SAF pickers,
   │           share sheet, printing)
   ▼
AppViewModel  (StateFlow<AppState> + buffered Channel<AppEvent>,
   │           SavedStateHandle for durable IDs)
   ▼
ScannerApplication  →  app-scoped singletons:
   │                   ImageProcessor, DocumentRepository, DocumentExporter,
   │                   OcrProcessor, PdfImporter, MediaTools
   ▼
DocumentRepository  (single IO Mutex, Room + managed source files)
   ▼
documents.db  +  filesDir/documents/sources/<id>.jpg
```

**Data flow.** A URI is copied by `ImageProcessor.ingest` (≤50 MiB, sampled to ≤3000 px, EXIF applied, metadata-free JPEG) into a staged `.part` file, renamed, then registered as a page inside a draft under `NonCancellable` so a late cancellation cannot lose a normalized page. `saveDraft` commits the draft's ordered metadata atomically. Exports lease a snapshot through `withDocumentSources`, render at ≤2400 px, stage into `cache/exports/export-<uuid>/`, then hand off to SAF, MediaStore, or FileProvider.

### Invariants worth preserving

1. **One mutation lock.** All repository writes go through `DocumentRepository.locked`. Do not add a second lock or write Room/sources from elsewhere.
2. **Render under a lease.** `withDocumentSources` holds the snapshot while `ImageProcessor.renderPage` reads source files; cleanup deletes any source no longer referenced.
3. **One renderer.** Preview, OCR and export all use `renderPage` (crop → rotate → filter → annotations). Extend that pipeline instead of adding another.
4. **Snapshot checks.** `setPageText` requires the expected page to be unchanged, so a late OCR result cannot overwrite newer edits. Visual edits invalidate stored text.
5. **Native bitmap ownership.** Recycle bitmaps you own in `finally`, but never recycle an input still in use by ML Kit/`PdfRenderer`.
6. **PDF trust boundary.** External PDFs are decoded only by the OS `PdfRenderer`; PDFBox writes app-rendered output only. Passwords are memory-only and redacted from `toString`.
7. **No private data in logs.** There is no logging of document text or paths anywhere in the app.

### Project layout

```
app/src/main/java/com/freescanner/
├── MainActivity.kt, ScannerApplication.kt, AppViewModel.kt, IndonesianLocale.kt
├── ui/          Compose screens, theme/tokens, root navigation
├── model/       documents, edits, PDF options/layout, tool catalog, queries
├── data/        Room entities/DAO/migrations, repository, metadata codec
├── imaging/     bounded decode, EXIF, perspective render, paper detection
├── annotations/ non-destructive overlay model and renderer
├── export/      PDF/JPEG/text writers, SAF + MediaStore delivery
├── pdf/         generated-PDF writer, PDF importer, long-image layout
├── ocr/         bundled Latin text recognition
├── tools/       barcode reading, ID-card composition
└── printing/    PrintDocumentAdapter
app/schemas/     exported Room schema history (must stay in sync with migrations)
```

## Storage and data handling

- Room schema version 3; migrations are additive and registered explicitly. Schema changes need a new `Migration`, a new exported JSON schema, and a migration test using frozen old-schema SQL.
- Saved documents, drafts and folders live in `documents.db`; page images are immutable JPEGs in internal storage.
- Drafts are metadata-only rows sharing the source files of the document they were opened from.
- Exports are retained in the app cache for at least 24 hours. Android may evict them; re-export if a share link is stale.
- Backup and device transfer are disabled for app data.
- On Android 8–9 only, gallery saving asks for `WRITE_EXTERNAL_STORAGE` (capped at API 28); Android 10+ uses scoped MediaStore writes.

## Documentation map

| File | Use it for |
| --- | --- |
| `PRD.md` | Product scope and acceptance criteria (SC-01…SC-07 and later feature contracts) |
| `DESIGN.md` | Screen behavior, color/typography decisions, Indonesian copy rules |
| `ARCHITECTURE.md` | Storage, lifecycle, export, engine boundaries, verification logs |
| `AGENTS.md` | Conventions and guardrails for coding agents |
| `REA_ANALYSIS.md` | Reference-app behavior analysis and evidence limits |
| `PLAN.md` | Original delivery plan and risk notes (historical) |

## Licensing

Bundled third-party notices are under `app/src/main/assets/notices/` (Apache 2.0, PDFBox NOTICE, Liberation Sans under SIL OFL 1.1). The PDF font is embedded from the pinned PdfBox-Android artifact.

## Known limits

- ML Kit returns processed JPEGs, not guaranteed original camera frames; app edits cannot reverse a crop or filter already applied by the SDK.
- Import is bounded to 50 MiB per image and normalizes to ≤3000 px, so original resolution is not preserved.
- Physical camera quality, first-use scanner download behavior, and full screen-reader compliance are device-dependent and are not asserted by the build.
