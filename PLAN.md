# Document Scanner implementation plan

## Capability

Deliver an Android app that turns camera captures or gallery images into readable, correctly cropped, locally saved multi-page documents, then exports or shares PDF and selected-page JPEG files. The release must implement SC-01 through SC-07 in [PRD.md](PRD.md), using [DESIGN.md](DESIGN.md) for app-owned UI and [ARCHITECTURE.md](ARCHITECTURE.md) for engine and storage boundaries.

Planning baseline: three specification files, before application code existed. The application is now implemented locally; build instructions and observed verification are recorded in [ARCHITECTURE.md](ARCHITECTURE.md). Physical scanner acceptance and CamScanner parity remain unverified.

## Constraints

### Fixed requirements

- One primary scanning flow; manual capture and correction remain available. Do not ship competing SDK and custom scanner experiences.
- On-device document processing and local persistence. No accounts, backend, analytics containing document content, or cloud document storage.
- Preserve the last saved document when editing is canceled or fails. Preserve recoverable draft files after interruptions.
- PDF and JPEG output must match the final page selection, order, rotation, and rendering. Export failure must not modify the document.
- Copy scanner/import URIs into managed storage before temporary access expires. Never persist a third-party URI as the only saved page source.
- No claims of CamScanner equivalence without matched-fixture evidence. Observe public behavior using synthetic or non-sensitive documents; independently implement code and assets.
- Use Android controls, insets, scalable text, screen-reader semantics, and at least 48 dp touch targets. SDK-owned screens are not assumed to accept the app's styling.

### Initial engineering choices

- One Gradle `app` module: Kotlin, Compose, Material 3, ViewModel, StateFlow, Room, internal files, Storage Access Framework, and FileProvider.
- Keep UI, scanner integration, document persistence, and export in separate packages, not separate Gradle modules. Start with explicit dependency wiring; no generic framework or per-operation abstraction unless needed.
- Evaluate ML Kit Full mode first. Add CameraX/OpenCV only for demonstrated gaps; remove rejected spike code before the release.
- Persist processed pages for an SDK-only flow. Original images and crop/filter recipes become required only if the selected implementation needs them for the promised editing workflow.

## Implementation contract

### Actors and surfaces

- **User:** library, new-scan entry, scanner/crop/filter UI, page review, document detail, rename/delete dialogs, export controls.
- **Android/Google Play services:** scanner launch/results, gallery access, lifecycle interruption, destination picker, and sharesheet.
- **Other apps:** consume exported files using temporary read grants; cannot browse the internal library.

### State and ownership

- New scan: create a stable draft → launch scanner/import → ingest pages → review → name and save → library/detail.
- Existing document edit: create an isolated working draft → apply page edits → commit explicitly, or discard without touching the saved version.
- Scanner launch: preparing → active → completed, canceled, or failed. Cancellation is not an error; return to the prior app state.
- Export: take a stable document snapshot → render privately → save or share → report success, cancellation, or failure without changing source data.
- Restore persisted draft identity and staged page references after recreation/process death where possible. An interrupted external scanner session may need relaunching; do not promise recovery of pages the SDK has not returned.

### Boundaries and data

- **Scanner boundary:** launch a scan/import session and return ordered page results, optional PDF, and typed cancellation/failure. Keep Google provider objects out of ViewModels and the document repository. Map unsupported/setup errors to actionable UI.
- **Document repository:** own draft creation, page ingestion, replacement, rotation, ordering, removal, save, rename, and deletion. Expose app models rather than Room entities.
- **Document:** stable ID, title, created/updated timestamps, and lifecycle status. **Page:** stable ID, owner, position, relative managed-file reference, rotation/render metadata, and optional original reference. **Draft:** stable ID, optional source document, staged page references, and current edit state.
- **File lifecycle:** stage and validate files before committing references. Commit page-order changes in a Room transaction. Roll back metadata on failure; keep previously committed content valid. Reconcile unreferenced files without racing active drafts or exports.
- **Deletion:** remove document metadata transactionally, then reclaim only unreferenced files through retryable cleanup. Shared exports must remain readable for the receiving app during the defined cache-retention window.
- **Rendering/export:** use the same orientation and page geometry for previews and exported output. Reuse an SDK PDF only when it exactly matches the final document; otherwise regenerate from the stable page snapshot. Return JPEG files only for selected pages, in document order.
- **Resource policy:** display-sized thumbnails, bounded full-resolution processing off the main thread, and one page rendered at a time during PDF generation. Avoid retaining a document's full bitmap set in memory.
- **Privacy:** no document contents or private paths in logs. Restrict FileProvider to export files. Explicitly exclude document files and metadata from automatic cloud backup for this local-only release; revisit backup only with a product policy.

## Execution sequence

### 1. Bootstrap and device baseline

1. Inspect JDK, Android SDK, Gradle availability, and attached ADB devices. Record phone model, Android version, RAM, and Google Play services availability.
2. Select compatible pinned Android Gradle Plugin, Kotlin, Compose, Room, and scanner versions. Choose minSdk from target devices and dependency constraints; do not infer it from ML Kit alone.
3. Create the Gradle Wrapper and single-module app with a launchable Compose screen. Keep machine-local SDK paths and signing material out of source control.
4. Create non-sensitive fixtures covering every PRD benchmark case, plus unreadable images and a proposed 20-page stress document. Record fixture provenance and reference app version/mode.

**Gate:** debug APK builds, installs, and opens on a physical target phone. Missing hardware blocks physical acceptance, not repository setup.

### 2. Scanner spike and engine decision

1. Implement the smallest ML Kit Full-mode launch using Activity Result APIs and immediate managed-file ingestion. Request JPEG; request PDF only when evaluating SDK PDF output.
2. Observe CamScanner capture/import, auto/manual capture, corners, rotation, filters, multi-page actions, and export. Use the installed REA workflow if available; record inaccessible behavior as Unverified rather than inventing it.
3. Exercise ML Kit on the same fixtures. Record SDK-owned versus app-owned capabilities, first-use download behavior, offline use after setup, cancellation, unsupported-device behavior, output quality, latency, file size, and manual corrections.
4. Prove that Scan with camera and Import photo have truthful entry behavior. Gallery import being enabled inside the scanner does not prove a direct gallery-first launch is available.
5. Prove all four required filter outcomes, including retention of stamps/signatures. SDK filter names alone are not evidence of equivalence.
6. Probe append/replace, reorder, manual crop, and rotation across scanner sessions and app review. Do not assume the SDK can reopen arbitrary saved pages or return editable originals.
7. Prototype only unmet requirements with CameraX/OpenCV or app-owned processing, then choose one coherent primary flow that satisfies all release requirements. If a custom flow is required, include its camera permissions, lifecycle, detection, manual corner correction, perspective transform, and filters in the implementation scope.

**Gate:** an engine decision backed by a behavior matrix and matched-input examples. Any unmet SC requirement must be resolved before proceeding; scope reduction requires explicit approval. Agree numerical performance budgets from measured target-device baselines before release optimization.

### 3. Durable document pipeline

1. Implement Room schema, managed directories, stable IDs, staged ingestion, draft restoration, and repository operations.
2. Keep edits to existing documents isolated until save. Validate nonempty documents and maintain unique, deterministic page order.
3. Implement interrupted-save recovery, orphan reconciliation, and deletion cleanup with protection for active operations.
4. Exercise failure paths with isolated tests for order changes, canceled replacement/edit, interruption between file staging and metadata commit, missing files, and failed writes.

**Gate:** a saved multi-page document survives restart; failed or canceled editing leaves it unchanged; recoverable staged work is not silently deleted.

### 4. Complete scan-to-save experience

1. Before UI implementation, inspect the Mobbin references in DESIGN.md and search Mobbin for relevant Android library, scanner, and review patterns. Preserve the specified identity rather than copying CamScanner branding.
2. Build the library's empty/populated states and New scan choices. Integrate the selected scanner and recovery states.
3. Deliver crop recovery, rotation, and the four filter outcomes in the engine-appropriate surface. Do not show controls the selected engine cannot execute.
4. Build page review with add, replace, rotate, remove, reorder, page position, and naming/save. Provide accessible move actions alongside drag reordering.
5. Build saved document detail, rename, and confirmed deletion. Support cancel/back without losing saved content and explain recoverable draft/discard choices.
6. Apply design tokens, system insets, progress feedback, TalkBack descriptions, enlarged-font layouts, and non-color-only selected states.

**Gate:** SC-01 through SC-05 work through normal app entry points on a phone, including manual recovery from poor edge detection and canceled edits. Inspect actual rendered screens and TalkBack behavior.

### 5. Export and Android integration

1. Generate PDF from the final ordered/rotated page snapshot unless a verified matching SDK PDF is reusable. Make page sizing and image scaling explicit and consistent with preview.
2. Export selected-page JPEG files without including unselected pages. Save one file per selected page; use a system directory destination for a batch rather than promising several files through a single-file picker.
3. Implement distinct Save to device and Share actions, destination cancellation, progress, temporary content URIs, and read grants. Clean up temporary output without invalidating an active share.
4. Inject generation/destination-write failure and low-storage cases. Preserve the source document and explain retry or choosing another destination; do not claim a partially written external file is complete.

**Gate:** SC-06 and export-related SC-07 pass. A separate PDF viewer opens single- and multi-page exports with correct count, order, orientation, and legibility; receiving apps open shared JPEG/PDF content.

### 6. Recovery, benchmark, and release evidence

1. Complete SC-07 across capture cancellation, first-use setup failure, unsupported device, invalid/unreadable imports, failed writes, low storage, activity recreation, and process interruption.
2. Run matched-fixture comparisons, including angled A4/receipts, light backgrounds, shadow/tiny text, stamps/signatures, blank/no-paper scenes, and the larger multi-page case. Report equivalence only per observed fixture/mode.
3. Check first-use setup and post-setup offline use separately. Verify large-font layouts, TalkBack actions, permission denial where applicable, and app restart persistence on physical phones.
4. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`; run `./gradlew connectedDebugAndroidTest` when device-backed tests exist. Check `adb devices`, then `./gradlew installDebug` and repeat the complete capture/import-to-share path.
5. Update project documentation with the selected engine, actual supported-device floor, storage/export behavior, build instructions, measurements, and known limitations. Add migration tests when changing a shipped Room schema.

**Gate:** all SC requirements have evidence; no unresolved data-loss failures or unsupported promises. Deliver the APK path, device/build versions, behavior matrix, matched-input outputs, measurements, and explicit Unverified cases. Unverified required release behavior prevents a full acceptance claim.

## Requirement traceability

| Requirement | Implementation phase | Required proof |
| --- | --- | --- |
| SC-01 Capture/import | 2, 4 | Camera and gallery entry, manual/automatic behavior, cancellation |
| SC-02 Crop/straighten | 2, 4 | Four-corner correction, perspective, rotation, missed-edge recovery |
| SC-03 Filters | 2, 4 | Four actual output modes; tiny text and color-stamp fixtures |
| SC-04 Multi-page review | 3, 4 | Add/replace/rotate/remove/reorder; canceled edit preserves save |
| SC-05 Library | 3, 4 | Save/reopen/rename/confirmed delete; restart persistence |
| SC-06 Export/share | 5 | External PDF viewer and receiving app; selected-page JPEG output |
| SC-07 Recovery | 2, 3, 5, 6 | Setup/import/storage/export/interruption failures without source loss |

## Non-goals

OCR, text search, searchable PDFs, accounts, subscriptions, sync, cloud storage, and a general post-save crop editor. Do not advertise re-cropping or re-filtering saved pages unless the selected pipeline retains the necessary source and edit metadata. Final product naming does not block the spike.

## Open questions and decision owners

- **Engineering, phase 1:** actual target phones, compatible dependency versions, minSdk, and development environment. Resolve by inspection, not by guessing versions here.
- **Engineering, phase 2:** required capability coverage, gallery-entry limitations, SDK output/editability, and first-use/unsupported-device behavior. Official SDK documentation is not proof of output quality or full product coverage.
- **Product + engineering, after baseline:** supported page limit, output-size/quality tradeoff, and numerical preview/export budgets. The 20-page fixture is a proposed stress test, not a newly imposed product limit.
- **Release owner, phase 6:** availability of a physical phone and reference app for final acceptance. Record unavailable evidence explicitly; do not substitute emulator success for physical-camera results.

## Handoff

Ready to execute phase 1 and the phase 2 spike. Production scanner implementation is gated on the engine evidence, not on a speculative commitment to ML Kit or a custom camera. Follow phases in order; export work can proceed alongside UI work once the document snapshot/render contract is stable.

External evidence checked while planning: [ML Kit Android scanner setup](https://developers.google.com/ml-kit/vision/doc-scanner/android). It documents SDK-owned UI, dynamically downloaded components, configurable gallery import/page limit/mode, JPEG/PDF results, API 21+, and at least 1.7 GB total device RAM. Recheck compatibility when pinning the complete application toolchain.
