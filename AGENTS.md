# Repository Guidelines

## Project Overview
FreeScanner is a local-first Android document scanner: capture/import → crop/filter/annotate → editable multi-page document → export/share. Preserve saved documents and recoverable drafts; processing is on-device, and the manifest deliberately removes Internet permission.

## Architecture & Data Flow
- One Gradle `:app` module. `MainActivity` owns Activity Result launchers, ML Kit scanner setup, Android pickers, sharing and printing; Compose screens route through `AppViewModel`.
- `ScannerApplication` wires app-scoped dependencies explicitly. `AppViewModel` exposes `StateFlow` state and buffered `Channel` events; `SavedStateHandle` retains document/draft IDs across recreation.
- `DocumentRepository` coordinates Room metadata and immutable JPEG sources. Edits are metadata applied at render/export time. Mutations share one IO `Mutex`; use `withDocumentSources` for export/OCR snapshots. Preserve staged-file rename and cancellation-safe metadata commits.
- Preview, OCR and export share `imaging/ImageProcessor.kt` (`renderPage`: crop → rotate → filter → annotations); extend that pipeline rather than adding a separate renderer.
- Exports are staged under `cache/exports/`, then delivered through SAF, MediaStore or FileProvider. Keep temporary export ownership separate from persistent document ownership.

## Key Directories
Under `app/src/main/java/com/freescanner/`:
- `ui/`: Compose screens, document navigation and gesture surfaces; `model/`: document/edit models and pure layout/query logic.
- `data/`: Room records/DAO, migrations, metadata codec and repository/file lifecycle.
- `imaging/`, `annotations/`: rendering, edge detection and non-destructive page edits.
- `export/`, `pdf/`, `printing/`, `ocr/`, `tools/`: output formats, PDF import, Android printing and local recognition/media operations.
`app/src/test/` contains JVM tests; `app/src/androidTest/` contains Android/Compose integration tests. `app/schemas/` stores exported Room schema history.

## Development Commands
Run from the repository root using the checked-in wrapper:
```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest  # requires an emulator/device
adb devices -l
./gradlew installDebug
adb shell am start -n com.freescanner/.MainActivity
```
Set `ANDROID_SERIAL` when multiple devices are connected. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Use fewer Gradle workers and a smaller JVM heap on resource-constrained hosts.

## Code Conventions & Common Patterns
- Follow official Kotlin style: four-space indentation, PascalCase types/files, camelCase members/test methods and `*Test.kt` test filenames. Reuse manual dependency wiring and existing feature packages.
- Collect state with lifecycle-aware Compose APIs; keep durable IDs in `SavedStateHandle` and screen-local state in `remember`/`rememberSaveable`. Pending scanner targets stay separate from visible drafts; open review only after input returns.
- Use structured coroutines, IO dispatching for file/database work, and interruptible rendering. Preserve `CancellationException`; report actionable Indonesian UI errors through existing ViewModel operation/state patterns.
- UI copy is Indonesian literals. Preserve persisted enum/codec keys, stable page IDs and test tags; update label-based tests deliberately when changing copy.
- Schema changes require an explicit migration, repository registration, exported schema and migration regression using frozen old-schema SQL; retain user data rather than destructive fallback.
- Recycle owned working bitmaps in `finally`, but retain inputs until native/ML Kit tasks finish. Invalidate OCR text after visual edits and keep snapshot checks so late results cannot overwrite newer corrections.
- External PDFs enter through OS `PdfRenderer`; PDFBox generates only app-rendered output. Keep PDF passwords ephemeral and document/OCR contents out of logs.

## Important Files
- `MainActivity.kt`, `ScannerApplication.kt`, `AppViewModel.kt` under the source root: platform boundary, dependency wiring and app state.
- `app/src/main/java/com/freescanner/ui/ScannerApp.kt`: root UI routing/theme and shared page previews; `app/src/main/java/com/freescanner/data/DocumentRepository.kt`: document/file ownership.
- `app/build.gradle.kts`, root Gradle configuration and `app/src/main/AndroidManifest.xml`: dependencies, SDK requirements, permissions and export provider.
- Read `PRD.md` for acceptance criteria, `DESIGN.md` for UI behavior, and `ARCHITECTURE.md` for storage/lifecycle constraints. `PLAN.md` and `REA_ANALYSIS.md` contain planning and benchmark history, not current test guarantees.

## Runtime/Tooling Preferences
Use JDK 17, Android SDK (compile/target 35; minimum device API 26), ADB and Gradle wrapper 8.9. Dependency/plugin versions are pinned in Gradle files; preserve them unless an upgrade is requested. No Node/Bun package workflow or custom script runner is present. Set SDK paths through `ANDROID_HOME` or untracked `local.properties`; build caches, outputs and signing keys stay untracked. ML Kit scanner availability depends on Google Play services and may require a first-use download.

## Testing & QA
JUnit4 is used throughout. Keep pure geometry/query/serialization checks on the JVM; Bitmap, Room, rendering, ML Kit, Compose and platform integrations belong in instrumentation tests. No numeric coverage threshold is configured.
Use synthetic images and isolated test storage, close activities/ViewModel stores and clean fixtures in `finally`/`@After`. For gesture bugs, inject real touch gestures (`PageImageScrollTest.kt`), not only semantic scroll actions. Keep the tested app foregrounded on devices that freeze background processes. Verify changed UI on an actual device/emulator and PDFs in an independent viewer; distinguish synthetic fixtures from physical-camera quality. Report checks actually run, not historical passing counts in documentation.
