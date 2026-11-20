# CamScanner behavior analysis and implementation contract

## Scope and identity

Analyze the observable Android behavior of official installed CamScanner `com.intsig.camscanner`, version `7.28.5.2611020000`, on the connected Infinix X6885 (Android 16). Use only normal UI actions and synthetic page-1/page-2 images. Do not open, export, modify, or reproduce the user's existing private documents. FreeScanner retains its own identity, source code, assets, and local-only data model.

## REA installation and evidence

- Official source: https://github.com/morluto/rea.
- Acquired `rea-agents@6.0.0` through npm from outside the target project. Version 6.9.0 requires newer Node patch versions than the installed runtimes; 6.0.0 supports the existing Node 24.14.0. No Node upgrade or agent/MCP configuration change was made.
- Installed matching `reverse-engineer-anything` instructions under `~/.agents/skills/reverse-engineer-anything`; the CLI is cached under `~/.npm/_npx/aefcdf12b4fd0d75/node_modules/rea-agents`. This is CLI access, not a connected MCP server.
- `rea inspect-artifact` inspected the device's base APK. SHA-256: `504f3d4316214a7bde7085d0cc7a8de0ef0ae174388cd2fe1c79e4afc2b04860`.
- REA Evidence: `ev_c3e564229bbcc93121cb30259bea1fcbd39fccae1026316f1bb09c8787d98fa2`; nested inventory `ev_ef856eb16ebd34a31b25616b22acd62553b29596a0460dde2b75410c579a1893`. Inventory: 12777 graph nodes, 13295 occurrences, 13294 edges. Full local evidence: `app/build/reports/rea/camscanner-artifact.json`.
- Coverage: base APK only; installed feature/configuration splits were not analyzed. Artifact inspection provides static inventory, not proof of runtime behavior or internal algorithms. Android declaration analysis returned `capability_unavailable`; the required separately supplied JADX MCP JAR is not installed. No decompilation, account access, protection bypass, or proprietary code/model reuse occurred.
- Runtime observations below are **ADB/UI observations**, not REA runtime Evidence. Saved screenshots contain synthetic documents or cropped toolbars without private filenames. Existing private library entries were not opened.

## Observed feature matrix

| ID | Exact reference route and observation | FreeScanner implementation target | Evidence boundary |
| --- | --- | --- | --- |
| RE-01 | Home → Search; Files → Search. Home exposes scan/import shortcuts and recent document rows with date/page count. | Search local document titles; prominent scan/import entry; document count and date metadata. | Controls observed; content/OCR search semantics not tested. |
| RE-02 | Files → sort icon. Date edited/created ascending/descending and Title A–Z/Z–A appear. Adjacent grid and selection icons are visible. | Six sort orders, list/grid layout, accessible document selection. | Sort choices observed; title matching/locale behavior is independently chosen. |
| RE-03 | Files → Create Folder entry. | Create/rename empty-delete folders and move local saved documents; preserve existing schema through migration. | Folder entry observed; FreeScanner folder lifecycle is a design choice, not claimed parity. |
| RE-04 | Home → Import Images → select synthetic page 1 then page 2 → Import(2). Selection shows order badges, Enhance, and Auto Crop. | Preserve explicit import order; managed originals, auto detection with manual recovery; date-based default titles. | Two synthetic imports observed. Camera quality and ordering behavior on other providers remain unverified. |
| RE-05 | Imported document opens two-column page thumbnails with Add, Edit, Share. Edit shows page position and Crop/Filter/Retake. | Saved page overview grid/full pages, direct page edit, add/replace/reorder/delete, reliable position. | Synthetic reference overview captured in `reference-review.png`. |
| RE-06 | Edit → Filter shows Compare, Pages to Apply, All, Pages; Pages opens selectable thumbnails and Apply Filter to Pages. | Compare normalized source to edited preview; current/all/selected page filtering in one atomic draft operation. | `reference-filters.png`; proprietary Magic/No Shadow/erase modes are not replicated or advertised. |
| RE-07 | Share displays 2 selected and Share as PDF/Images/Long Image/Word/separate PDFs. | PDF/JPEG page selection; distinct save/share; no watermark. | Share choices observed; only existing release formats PDF/JPEG are implemented. Word/long-image/separate PDFs remain outside this change. |
| RE-08 | Share as PDF → PDF Preferences → page number, direction, A4 page size, margin. | A4/Letter/Fit-image sizing, optional margins and page numbers; selected pages in saved order. | `reference-pdf-options.png`. Password locking and cloud links are not part of this release. |
| RE-09 | Paid conversion, markup, sign, ID-card, QR, AI, and cloud entries visible. | Record as separate capability candidates; do not show nonfunctional buttons. | Entry presence only; underlying behavior, pricing, network processing, and algorithms unverified. |

## Chosen independent contracts

- Blue action color and Android-native controls from DESIGN.md; quiet document workspace, not copied branding or ads. Mobbin references for library structure: [Gusto document list](https://mobbin.com/screens/965f5cd0-b65e-4dbf-acc0-63cc48fc8643), [Manus file list](https://mobbin.com/screens/94b5bdc9-653b-4c47-82da-20284e74c329).
- Document bulk actions: explicit selection, duplicate, merge into a new editable draft, move to a folder, and confirmed deletion. Originals remain unchanged by merge/duplicate. These are independent product decisions, not observations of executed reference actions.
- Selected-document merge order is the explicit selection order; each source's page order and edit recipe are preserved. The new draft uses fresh page IDs and shared immutable managed sources.
- Folder deletion is allowed only when no saved document or draft references it; otherwise move items first. Root means no folder. No destructive migration fallback.
- Batch filter changes are atomic and affect only selected draft page IDs. Canceling the draft leaves saved sources/recipes intact. Compare means normalized managed input; an SDK-preprocessed image cannot recover an earlier SDK crop/filter.
- PDF selection never includes unchecked pages, even after rotation. A4/Letter automatically orient to each page, margins inset content, and optional numbers count exported pages. Preview and PDF/JPEG reuse the same renderer.
- No new login, cloud storage, OCR/Word conversion, payments, external content processing, or fake premium controls.

## Acceptance gates

1. Room v1 data migrates without loss; folder ownership, bulk deletion, duplicate and merge retain immutable file references correctly.
2. Search/sort/folder/grid/selection behavior works through app UI, including no-result and empty-folder states and activity recreation.
3. Batch filter current/all/selected, compare, direct page editing and page overview work with synthetic pages. Filter operations never change the saved document until Save.
4. Selected PDF/JPEG exports contain only chosen pages in order. Verify A4/Letter/Fit geometry, margins/page numbers, and Android save/share receiving apps.
5. Build, focused tests, lint, install on the connected phone, and inspect actual changed surfaces. Record hardware-dependent unknowns explicitly; no general CamScanner parity claim.

## Upgrade verification (2026-11-16)

- Build: FreeScanner `1.1.0` / version code `2`, debug application and instrumentation APKs assembled. Twelve JVM tests passed; lint reported zero errors and sixteen version/toolchain warnings.
- Physical installation: both APKs installed successfully with `adb install -r` on the Infinix X6885 running Android 16. App data was not cleared and the main application was not uninstalled.
- Initial physical run: 31 reported cases, 29 passed. The workspace repository class could not initialize because one Kotlin JUnit method inferred a non-void return type; its signature was corrected to `Unit`. The other failure timed out after 60 seconds inside Android's native `PdfDocumentProxy.render` while checking all 20 exported pages.
- Focused physical run after the signature and two product fixes: 18 cases, 17 passed. These include search/folder/grid/selection recreation, merge order, batch filtering and comparison, PDF options, replacement-page selection, maximum-length duplicate title editing, and folder/bulk-operation validation. The duplicate shared-source close/reopen case timed out in its coroutine wait; its exact blocked operation was not established.
- The phone disconnected before the diagnostic installation, and neither USB nor mDNS exposed another authorized connection. The duplicate shared-source case subsequently passed unchanged on the Android 15 emulator in 0.193 seconds. This is not proof that its Android 16 timeout is resolved. No product workaround, removed assertion, increased timeout, or passing physical stress-test claim was substituted.
- Android 15 emulator: the final 40-case instrumentation suite passed in 24.514 seconds, including the complete workspace repository class, schema migration, both added product regressions, and all 20 exported-page render assertions. Output is retained in `app/build/reports/rea/emulator-tests.txt`. The emulator used API 35, 1080 × 2400 at 420 dpi, 3072 MB RAM, and SwiftShader graphics. This does not replace the unresolved Android 16 results.

## Rendered workflow proof (2026-11-17)

The final APK was installed and cold-started on the Android 15 emulator. Actual rendered library list/grid, saved-page overview, comparison, selected-page filtering, empty-folder recovery, duplicate-folder error, import progress, export settings and sharesheet were inspected. A real 20-image gallery selection showed `Adding pages...` before reaching `1 of 20`; its temporary draft and gallery files were removed afterward.

The selected-page export was saved through Android's document picker. Independent `pdfinfo` reported one Letter page (612 × 792 points); Poppler rendering showed only synthetic page 2, with margins and a separate `1 / 1` footer. The exported PDF is `app/build/reports/rea/selected-letter.pdf`.

The local rendered evidence record and screenshot inventory are in `app/build/reports/rea/ui-evidence.json`; the bundled evidence checker passed. Keyboard focus/activation, zero-duration animator navigation and 130% export text were observed. This is a scoped native-phone review, not complete TalkBack/WCAG certification or tablet/desktop verification.

Final singular/plural count corrections were rebuilt and checked on the running emulator after the phone disconnected; that final copy-only APK was not reinstalled on the phone. APK: `app/build/outputs/apk/debug/app-debug.apk`, 11,277,384 bytes. The two Android 16 timeouts above, physical camera quality, first-use/offline scanner availability, and matched-output CamScanner image-quality equivalence remain unverified or unresolved.

## Expanded device inspection (2026-11-17)

The renewed device session again identified the Infinix X6885, Android 16, CamScanner `7.28.5.2611020000`, and FreeScanner `1.1.0`. The installed CamScanner base APK SHA-256 still matches the REA artifact above, so that inventory was reused rather than analyzed again. The following additions are direct ADB/UI observations, not decompiled implementation claims.

### Actual Tools catalog

| Group | Entries observed on the phone | Evidence |
| --- | --- | --- |
| Scan | ID Cards, Extract Text, ID Photo Maker, Formula, photo translation, Book, Slides, Whiteboard, Timestamp | `reference-tools-expanded-1.png` |
| Import | Import Images, Import Files | Tools catalog and existing import inspection |
| Convert | To Word, To Excel, To PPT, PDF to Images, PDF to Long Image | `reference-tools-convert.png` |
| Edit | Sign, Add Watermark, Smart Erase, Erase Marks, Restore Photo, Merge Files, Extract PDF Pages, Reorder Pages, Lock, Compress | `reference-tools-edit.png` |
| Utilities | AI Chat, Solver AI, CountCam, Print, Scan Code | `reference-tools-expanded-2.png` |

These are 31 catalog entries, not 31 tested or equivalent implementations. FreeScanner 1.1 does not implement most of these capabilities; its earlier local-workflow upgrade must not be described as a full CamScanner replacement.

### Newly exercised workflows

1. **OCR:** Android image sharing → **Extract Text - CamScanner** → choose recognition region / Auto Detect → Next → actual recognized text. The synthetic page's printed text and stamp were recognized; its drawn signature also produced an extra `W`. The result exposes Copy, Select Text, To Word, Share, and Translate. Proofread explicitly displays **This feature is Premium**. Evidence: `reference-ocr-owned-input.png`, `reference-ocr-text-actions.png`.
2. **Word conversion:** OCR result → To Word → **Converting with CS AI** → editable one-page result. The result states **You can edit converted documents for free. Upgrade to export.** AI Formatting, AI Writing, Save as PDF, and Export Document are visible. Conversion was witnessed; paid export was not purchased or bypassed. Evidence: `reference-word-conversion-result.png`.
3. **Signature creation:** share the synthetic image to the normal CamScanner target → enhanced page review → Sign → Sign & Stamp → Draw. A blank **Create a Signature** canvas exposes Cancel and Done. Creation was canceled; no existing stored signature was opened or copied and no test signature was committed. Evidence: `reference-shared-image-review.png`, `reference-signature-draw-controls.png`. Signature placement/export remain unverified.
4. **PDF import and editing:** share the generated two-page PDF → **Open PDF** → owned PDF viewer → Edit PDF. Modify exposes Edit Text, Smart Erase, Mosaic and Sign; Markup exposes Sign, Add Watermark, Comment, Highlight, Brush and Insert Text; Page exposes Crop, Filter and Manage Pages. A tutorial initially intercepted tab taps; the toolbars were recorded again after dismissing it. Evidence: `reference-pdf-import-viewer.png`, `reference-pdf-markup-verified.png`, `reference-pdf-page-verified.png`.
5. **Watermark preview:** on that owned PDF, Markup → Add Watermark → enter **FS SAMPLE** → Confirm → Adjust Style. The preview displays the entered text and exposes Single/Tile, color choices, an opacity control, and Spacing. Tile carries a Premium badge. The preview was witnessed; paid export and PDF round-trip preservation were not verified. Evidence: `reference-watermark-preview.png`, `reference-watermark-style.png`.

All screenshot paths in this section are relative to `app/build/reports/rea/`. Only generated images and the generated PDF were supplied to these workflows. Ordinary Android share targets were used to select inputs explicitly rather than guessing which private gallery thumbnails to select. The live-camera OCR entry was identified but its continuously updating viewfinder prevented an idle UI hierarchy; no camera photo was taken.

### Expansion decision

The current PRD deliberately excludes accounts/cloud storage and leaves OCR/Word/signatures outside version 1.1. The renewed request calls for a broader product. Before changing that contract, distinguish a complete local document-tool suite from an expansion that also sends documents to online AI/conversion services or adds cloud accounts/synchronization. Service choice, document-upload permission, and ongoing costs must not be silently introduced. Advanced AI, specialized camera modes, translation, QR recognition, lock/compression, Excel/PPT conversion and other catalog-only entries remain unverified beyond their visible entry points.



## Approved local-suite implementation (1.2)

The user explicitly chose `local_document_suite`: OCR/editable text/TXT+DOCX, PDF import/extraction/split/compression/password output, signatures/drawing/highlight/text/watermarks, long image, ID-card composition, QR/barcode recognition, printing, and working tool navigation. This changes the version 1.1 scope intentionally; no document-upload or paid-service permission was granted.

FreeScanner independently implements these outcomes using bundled Latin ML Kit OCR/barcodes, OS PDF import rendering, PDFBox-generated output/encryption, and editable normalized overlays. It retains the existing ML Kit document-scanner capture flow. Word output contains recognized/edited text with page separation; it does not claim CamScanner's AI format reconstruction. Imported PDF pages become editable raster pages; original vector/text objects are not retained. Signatures are drawn onto the current page, not copied from CamScanner's stored signature library.

These implementations are not evidence that catalog-only CamScanner features have been fully tested or matched. Specialized camera modes, translation, AI restoration/erasure/chat, Excel/PPT conversion, accounts/cloud, premium entitlements, and general image-quality parity remain outside this approved expansion.

