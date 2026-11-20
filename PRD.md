# Android document scanner | Product requirements

**Status** Implemented locally; physical scanner acceptance incomplete (see [verification evidence](ARCHITECTURE.md#observed-verification-2026-11-16))
**Platform** Android, tested on physical phones  
**Working title** Document Scanner. Final product name is open.  
**Behavior benchmark** CamScanner for Android  
**Visual reference** iOS screens inspected through Mobbin, adapted to Android in [DESIGN.md](DESIGN.md)

## Product goal

Turn a paper document or gallery image into a readable, correctly cropped file that can be saved and shared from the phone. Optimize for one-handed capture, legible controls, and reliable multi-page export.

The core path is **Scan or import → review crop → choose a filter → add pages → name and save → share PDF**.

## Release scope

| ID | Feature | Acceptance criteria |
| --- | --- | --- |
| SC-01 | Capture and import | Open camera or import from the gallery. Detect paper edges and capture automatically when supported. Keep manual capture available. Cancel returns the user to the previous screen. |
| SC-02 | Crop and straighten | Detect four corners, correct perspective, and support manual corner adjustment and rotation during review. When detection misses an edge, users can recover before saving. |
| SC-03 | Image filters | Provide equivalent choices for Original, Enhanced Color, Grayscale, and Black & White. Use capability-appropriate labels and verify the actual output. Pages preserve readable text and offer a mode that retains color stamps and signatures. |
| SC-04 | Multi-page review | Add, replace, rotate, remove, and reorder pages before saving. The final review shows page count and order. Canceling an edit preserves the last saved document. |
| SC-05 | Library | Save locally with a title. Reopen from the list, view pages, rename, and delete with confirmation. Saved documents survive app restart. |
| SC-06 | Export and share | Export a PDF with all pages in order and JPEG for selected pages. Open the exported PDF in an independent viewer. Save through the system picker or share through the Android sharesheet. |
| SC-07 | Recovery | Show actionable states for canceled capture, unavailable scanner setup, invalid or unreadable imports, failed writes, and storage limits. Preserve existing documents and recoverable draft work. |

**Future candidates:** full-text library search and additional OCR scripts. Account sync, subscriptions, and cloud storage are outside this local-only product.

### Local workflow upgrade (1.1)

Use [REA_ANALYSIS.md](REA_ANALYSIS.md) for the observed CamScanner feature matrix and evidence limits. This upgrade adds title search, six sort orders, document list/grid layouts, local folders, explicit bulk selection, duplicate/merge/move/delete actions, page overview/direct editing, batch filter targets and managed-original comparison, and selected-page PDF output with A4/Letter/Fit-image sizing, margins, and page numbers. These are independently implemented local workflows, not a claim of proprietary algorithm or full-product parity. Accounts, cloud sync, subscriptions, OCR/Word conversion, AI erasure, and signature tooling remain outside this release.

### Local document suite (1.2)

The user selected the complete local document suite after the expanded CamScanner inspection in [REA_ANALYSIS.md](REA_ANALYSIS.md). All document processing stays on-device; no cloud service, account, paid API, or proprietary CamScanner code/assets is introduced.

| Capability | Contract |
| --- | --- |
| Local OCR and text | Bundled Latin recognition, including English/Indonesian; current/all-page recognition, durable per-page corrections, UTF-8 TXT and genuine DOCX. Null recognition blocks text export; a genuinely blank recognized page stays in document order. |
| PDF import and management | System PDF picker, sequential OS rendering, recoverable pages committed before the next page, normal reorder/remove/merge, selected-page extraction and separate PDFs. Protected input requires Android 15/API 35 or an externally unlocked PDF. |
| PDF output | Three JPEG quality/resolution levels, optional invisible recognized text, AES-256 password protection, selected pages in document order, existing size/margin/numbering preferences. Passwords are never persisted. |
| Annotation | Signature ink, drawing, highlight, text, and customizable single/tiled watermarks; explicit save/cancel, undo, style/position controls, editable existing overlays. Changes invalidate OCR; rotation transforms overlays; changing crop explicitly removes overlays. |
| Other local tools | Bounded vertical long JPEG, real-size A4 ID-1 front/back layout, image-based QR/barcode decoding with no automatic execution, and Android print preview/range handling. |
| Navigation | Home, Files, and Tools; each listed tool has a functioning action or document selector. Saved originals remain isolated from draft edits until document Save. |

Specialist camera modes, Excel/PowerPoint conversion, translation, online AI/cleanup, cloud storage, subscriptions, and general CamScanner image-quality equivalence remain excluded. The primary camera continues to use Google ML Kit Document Scanner.



## Benchmark and acceptance

| Case | Inspect and record |
| --- | --- |
| Angled A4 and small receipts | Detected corners, manual adjustments, perspective, and preservation of text close to the edge. |
| White paper on a light table | Capture success and recovery when the paper outline is hard to detect. |
| Hard shadow and tiny text | Remaining shadows, lost detail, and legibility at the same zoom. |
| Colored stamp and signature | Legibility and color in Original and Enhanced modes. |
| Blank scene and photo without paper | False capture and recovery. |
| Multi-page document | Correct count and order, external PDF readability, size, and export duration. |

Use the same source file for processing comparisons and the same phone, paper, and lighting for camera comparisons. Record time to preview, export time, and manual corrections. Capture the reference version and scanner mode with every test run. Record parity per fixture and mode.

## Copy-ready REA agent prompt

> Use the installed GitHub REA skill. Read its SKILL.md and follow its project workflow. Reverse engineer the **observable Android behavior** of CamScanner using the official installed app, public documentation, normal user actions, and outputs from synthetic or non-sensitive test documents.
>
> First map capture and import, automatic capture, edge detection, crop handles, rotation, perspective correction, enhancement modes, multi-page editing, saving, PDF and JPEG export, and Android sharing. For each behavior, record the app version, source of evidence, exact steps, expected output, failure case, and acceptance test. Mark inaccessible or unobserved items **Unverified**. Use independently implemented code, models, and assets.
>
> Build a small ML Kit Document Scanner Full-mode spike. Compare it with CamScanner using the same input images for output comparisons and the same phone, paper, and lighting for capture comparisons. If a required camera control, crop behavior, filter, output quality, or performance target remains unmet, prototype only that gap with CameraX and OpenCV. Record the evidence for the engine choice and implement one primary scanning flow.
>
> Use [DESIGN.md](DESIGN.md) as the authority for color and layout. Read the specified Mobbin screens and keep the Android app's identity independent of CamScanner. Use [ARCHITECTURE.md](ARCHITECTURE.md) for storage, file lifecycle, the engine boundary, and build checks. Implement SC-01 through SC-07 end to end. Keep editable drafts and existing documents intact when possible.
>
> Run Gradle tests, lint, and assembleDebug. If a physical phone is attached through ADB, install the APK, open it, and run the complete capture-to-share path. Deliver the APK path, behavior matrix, matched-input examples, device and build details, measurements, and a list of unverified cases. Report equivalent outcomes only for tested fixtures.
