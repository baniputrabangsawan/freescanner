# Android document scanner | Architecture

## Stack

| Layer | Initial choice | Responsibility |
| --- | --- | --- |
| App | Kotlin, Jetpack Compose, Material 3, ViewModel, StateFlow | Library, draft review, manual crop, filters, document detail, and export UI |
| Scanner | Google ML Kit Document Scanner in Full mode | SDK-owned camera, automatic/manual capture, detection, and initial perspective correction; app-owned gallery import and subsequent page editing |
| Custom option | CameraX and OpenCV if benchmark results justify the work | Owned viewfinder, four-corner detection, manual crop, rectification, and owned filters |
| Data | Room and internal app files | Document metadata, page order, and managed image files |
| Export | Android Storage Access Framework, FileProvider, and sharesheet | User-selected export locations and temporary read access |
| Tooling | OMP, VS Code, JDK, Android SDK, Gradle Wrapper, and ADB | Build and test on a physical Android phone |

Start with one Gradle app module. Pin compatible toolchain and library versions. Choose the minimum Android version after checking the target devices and selected dependencies. The core app processes documents on-device. ML Kit Document Scanner may download its components through Google Play services on first use. Verify first-use setup, offline use after setup, and unsupported-device behavior. [Android ML Kit scanner setup](https://developers.google.com/ml-kit/vision/doc-scanner/android)

## Engine decision and boundaries

- Implement a small scanner boundary that returns scanned page URIs, optional PDF, and typed cancellation or failure. Keep provider types outside the Library and document repository.
- Record the Full-mode spike results in the REA matrix. Record which capabilities belong to the SDK UI and which are implemented in the app. Verify whether each requested filter is available with equivalent behavior.
- If the app only receives processed scanner output, persist it as a processed page. Offer post-save recrop or re-filter only when the workflow captures the required original and edit metadata. With a custom engine, retain the original and store crop geometry, rotation, and filter settings for non-destructive edits.
- Use SDK PDF output when it reflects final page edits and ordering. Regenerate when the app changes saved page order or composed output, using Android PDF generation or an evaluated equivalent. Reuse the same page rendering rules across previews and export.

## Persistence and failure recovery

| Entity | Fields and constraints |
| --- | --- |
| Document | ID, title, created time, updated time, and status |
| Page | ID, document ID, position, managed file reference, rotation or render metadata when supported, and optional original reference |
| Draft | Stable ID and staged page references, restored after activity recreation where feasible |

Use internal relative paths and foreign keys. Make page order writes atomic. Copy external or SDK-provided URIs into managed storage while access is valid. Stage page files before committing metadata. Clean up failed or abandoned stages and reconcile orphan files after interruptions. For deletions, remove metadata before reclaiming unreferenced files, with retryable cleanup. Export from a stable page-order snapshot and preserve the saved source document if the export fails.

Decode thumbnails at their display size. Process full-size work off the main thread and keep concurrency bounded. Write temporary output before exposing it to the user. Use a content URI with temporary read access when sharing. Keep photos, document text, and private paths out of diagnostic logs. Store no document or OCR data on a server for the initial release.

## Verification

- Test page ordering and restoration, interrupted saves, replacement and deletion, and failed exports. Check single and multi-page PDFs in an independent viewer.
- Use the same fixture and device for each comparison. Record cold scanner availability, time from capture to review, and export time and size, including a larger multi-page case.
- Run `./gradlew testDebugUnitTest lintDebug assembleDebug`. Check `adb devices`, then run `./gradlew installDebug` when the phone is connected. Verify capture, review, export, and sharing through the app's normal entry points.
- Check migration compatibility if the Room schema changes after users have saved documents.

The full REA prompt, feature scope, and acceptance matrix are in [PRD.md](PRD.md). Mobbin-backed visuals and screen behavior are in [DESIGN.md](DESIGN.md).

## Implemented application

- Application ID: `com.freescanner`; launcher: `MainActivity`; application-scoped dependencies: `ScannerApplication`. Every activity shares one repository mutation lock and export source leases.
- Pinned build: JDK 17, Gradle 8.9 with distribution checksum, AGP 8.7.3, Kotlin/Compose compiler 2.0.21, Compose BOM 2024.12.01, Room 2.6.1, ML Kit scanner 16.0.0. `minSdk = 26`, compile/target SDK 35. The wrapper discovers the SDK through `ANDROID_HOME` or an untracked `local.properties`.
- Build/check: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Device tests: `./gradlew connectedDebugAndroidTest`. Install: `./gradlew installDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `model/Documents.kt`: document/draft/page models and normalized convex crop geometry. `data/`: Room schema and repository. `imaging/`: bounded import, EXIF correction, conservative gallery-paper detection, perspective rendering, and four image filters. `export/`: PDF/JPEG generation and SAF writes. `AppViewModel` and `ui/`: lifecycle state and Compose surfaces.
- Room metadata lives in `documents.db`; immutable managed JPEG sources live under internal `files/documents/sources`. Metadata-only drafts share sources until replacement/removal. A save commits the draft's ordered metadata atomically. A retained draft whose original document was deleted saves as a new document.
- Import accepts up to 50 MiB per image and normalizes to a longest side no greater than 3000 px. Power-of-two codec sampling may produce smaller images. Export renders up to 2400 px; thumbnail decoding is bounded relative to the requested display size and crop. No claim of original-resolution preservation.
- ML Kit returns processed JPEGs, not guaranteed original camera frames. App crop/filter/rotation edits remain non-destructive relative to these managed inputs; they cannot reverse a crop or filter already applied inside the SDK. Gallery imports retain their normalized source for app-owned edits.
- Gallery corner detection uses conservative bright-paper segmentation and edge contrast. Candidate corners come from the component's diameter pair plus the boundary extremes on either side of it, then are ordered clockwise from the image's top-left; this keeps width/height correct at any rotation instead of following image axes. Only components that pass every gate compete for "largest", so a rejected blob cannot veto the page. Uncertain scenes return a full-image crop; users can adjust all corners. Black & White uses local-mean adaptive thresholding; Enhanced color uses a hue-preserving value curve.
- Export caches use unique `cache/exports/export-*` directories, staged completion, document-order snapshots, and a minimum 24-hour app cleanup retention window. Android may evict cache files; an expired export can be regenerated. The non-exported FileProvider exposes only this directory with temporary read grants.
- Export destinations must be `content://` URIs from Android pickers. Multiple JPEGs use a selected directory; incomplete newly created JPEG batches are reclaimed where the provider allows. A failed single-file provider write can leave an incomplete destination; the source document remains intact.
- Automatic cloud backup and device-transfer backup of app data are excluded. No app-owned document server, account, OCR, or network upload is implemented.

## Verification scope

Build and lint commands are executable in this project. Synthetic unit/device tests cover crop validity, EXIF, filtering, perspective geometry, draft isolation/restoration, ingestion failure, source cleanup/leases, PDF order/orientation, JPEG selection, and UI editing. Physical capture quality, first-use scanner downloads, matched CamScanner parity, and accessibility acceptance require observed device evidence; compilation is not evidence for these claims.

## Observed verification (2026-11-16)

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: passed. Four geometry unit tests passed. Lint reported zero errors and 16 dependency/target-version advisories; dependencies remain deliberately pinned rather than silently upgraded.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=60000`: all 24 tests passed on the Google APIs x86_64 Android 15/API 35 emulator with 3072 MiB configured RAM. This includes crop/filter/EXIF pixel checks, interrupted-stage cleanup, canceled edits, process-independent repository restoration, source leases, deleted-source draft recovery, unsafe destination rejection, 20-page PDF rendering, and Compose recreation checks for crop/JPEG selection.
- `./gradlew installDebug`: passed on the emulator. A subsequent install verified the visually corrected review layout. Actual screenshots confirmed page count/Add page visibility, blue selection states, crop controls, and review/crop layouts at font scale 1.3.
- Normal UI walkthrough: selected two synthetic JPEGs in Android's file picker → reviewed detected crops → applied Enhanced color → named and saved → saved a PDF through CreateDocument → shared it through Android's sharesheet. Android Print rendered both shared pages; opening the saved file from Files rendered it in Google Drive's independent PDF viewer.
- The walkthrough's PDF had two pages, 547 × 842 pt page geometry, and 290821 bytes (`pdfinfo`). A separate selected-folder JPEG batch saved `Scanner smoke-001.jpg` and `Scanner smoke-002.jpg`; the second exported image visibly retained its page-2 text, blue stamp, and red signature.
- The synthetic stress test exported twenty alternating flat red/blue 1000 × 1400 px pages in 694 ms, producing 95366 bytes; PdfRenderer verified all twenty pages in order. These unusually compressible fixtures are a correctness/resource smoke test, not a real-document size or phone-performance benchmark.
- The saved library survived an emulator shutdown/restart. The SDK scanner entry opened a Google Play services screen saying “Something went wrong / Try again later”; Cancel correctly returned to the preceding library without an empty draft. Successful camera capture was not observed on this emulator; the setup failure's cause is unverified.
- Initial physical device: Infinix X6885, Android 16/API 36, approximately 7.5 GiB RAM, Play services `26.44.32 (260400-1015688330)`. Five storage/export tests passed before USB disconnection interrupted the run. This was not a completed physical acceptance run. CamScanner `7.28.5.2611020000` was installed, but no matched behavior/quality benchmark was completed.

| Requirement | Observed evidence | Remaining acceptance limits |
| --- | --- | --- |
| SC-01 | Real gallery picker import; SDK setup entry and cancellation; restored-result and empty-draft cancellation tests | Successful physical capture and automatic/manual SDK behavior |
| SC-02 | Synthetic corner detection/perspective/rotation tests; actual crop preview and accessible position controls | Difficult physical backgrounds and lighting |
| SC-03 | Pixel-verified four output modes; actual color-stamp/signature export | Matched shadow/tiny-text comparisons against CamScanner |
| SC-04 | Repository ordering/replacement/cancel tests; multi-page review and crop/selection recreation UI test | Full physical-device editing walkthrough |
| SC-05 | Save/reopen/rename UI test; confirmed deletion implementation; repository and emulator restart persistence | Wider Android device coverage |
| SC-06 | PDF/JPEG batch saved through real Android pickers; sharesheet → Print; independent PDF viewer; 20-page PDF and selection tests | Additional receiving apps and providers |
| SC-07 | Invalid/oversized imports, interrupted-stage cleanup, canceled edits, source leases, unsafe destination rejection, SDK setup cancellation | Actual disk-full/provider-failure conditions and first-use/offline scanner setup |

Non-sensitive input images, exported PDF/JPEG examples, and screenshots are retained under `app/build/reports/smoke/`. Automated reports are in `app/build/reports/tests/`, `app/build/reports/androidTests/`, and `app/build/reports/lint-results-debug.html`. These are generated local artifacts, not source-controlled assets.

No physical camera-quality, offline-scanner, CamScanner-parity, or complete TalkBack compliance claim is made.

## Version 1.1 local workflow upgrade

REA installation, exact artifact identity, observed reference UI routes, independent product decisions, and analysis limits are recorded in [REA_ANALYSIS.md](REA_ANALYSIS.md). REA CLI 6.0.0 inventoried the official installed base APK; the missing JADX provider prevents decompiled Android-code claims. ADB UI observations remain labeled separately from REA Evidence.

Room schema 2 adds a `folders` table and a nullable restrictive `documents.folderId` reference. `MIGRATION_1_2` adds these without dropping existing document/page tables. Exported schemas 1 and 2 are retained. Folder names are validated and normalized for duplicate rejection; nonempty folders cannot be deleted. Saved documents and isolated edit drafts retain their own folder assignments.

Duplicate and merge generate fresh document/page identities while sharing immutable managed sources. Merge writes a new draft in explicit document-selection order, preserving each input's page order and edit recipes. Bulk move/delete and selected-page filter changes validate their whole selection before a transaction; cleanup retains files referenced by any saved document or draft.

The library now supports title search, six sort orders, folder scope, list/grid, and explicit ordered selection. The page workspace supports direct page editing, batch filter targets, and comparison against managed inputs. Saveable screen state keeps library scope and review selection through navigation and activity recreation.

`PdfOptions` and `calculatePdfLayout` define A4/Letter/Fit-image sizing, per-image orientation, optional margins, and footer page numbers. Both PDF and JPEG honor the selected page IDs in saved document order. The PDF footer is reserved outside rendered image content. Default PDF output is A4 with margins; Fit image remains an explicit option.

### Version 1.1 verification boundary

See [REA_ANALYSIS.md](REA_ANALYSIS.md#upgrade-verification-2026-11-16) for exact physical/emulator results and [rendered workflow proof](REA_ANALYSIS.md#rendered-workflow-proof-2026-11-17) for the final APK and UI evidence. Twelve JVM tests and forty Android 15 instrumentation cases passed; lint reported no errors. The Android 16 twenty-page renderer timeout and duplicate close/reopen timeout are unresolved, not passing release claims. Final copy-only corrections were rebuilt and exercised on the emulator after physical-device disconnection.


## Version 1.2 local document suite

- Room schema 3 adds per-page annotation/OCR JSON and page kind through additive `MIGRATION_2_3`; schemas 1/2 remain migration inputs. Immutable managed source bytes, saved-document identity, page order, folders, and independent drafts are retained.
- `PageMetadataCodec` validates bounded typed metadata. `setPageText` accepts an expected full page snapshot so stale recognition cannot overwrite newer edits/corrections. Render changes invalidate OCR; rotation transforms normalized overlays. Crop changes require removing overlays, with a UI warning; cropping an ID sheet changes its kind to an ordinary document.
- `ImageProcessor.renderPage` is the shared annotated renderer for previews, OCR, and output. Low-level `render` supplies the unannotated editor base. Published Compose bitmaps remain GC-owned; unpublished and exported intermediates are explicitly recycled. Rotated render targets are mutable.
- App-scoped bundled ML Kit Latin OCR and barcode models operate without the app INTERNET permission. The primary scanner still uses Google Play services and may need its own first-use download. QR content is untrusted text, never executed automatically.
- `PdfImporter` uses the OS `PdfRenderer` for external PDFs, sequentially rendering at most 2400 px per side from a bounded 50 MiB input. Each generated JPEG is copied byte-for-byte into managed storage before the callback returns. Committed pages survive later failure/cancellation. Password input uses public API 35; older devices require an unlocked PDF.
- PDFBox Android 2.0.27.0 writes generated image PDFs, JPEG compression, invisible Latin text, and AES-256 protection; it does not render arbitrary external PDFs. OCR geometry is cleared on manual text correction, so corrected searchable text uses page-level layout rather than claiming original character placement. Unsupported font characters fail visibly and can use image-only PDF or TXT/DOCX.
- `DocumentExporter` retains its snapshot/source-lease and 24-hour export-cache lifecycle for PDF/JPEG, selected separate PDFs, long JPEG, TXT, and DOCX. Long JPEG is bounded to 16,000 px per side and 16 million pixels; selections that cannot remain readable fail rather than dropping pages. SAF directory writes choose MIME per file and remove only newly created batch outputs on failure.
- ID-1 front/back composition uses a 2100×2970 A4 sheet and 85.6×54 mm contained slots. ID sheets override PDF geometry to A4 without margins/footer. Physical output requires printing at 100%, not printer scaling.
- `PdfPrintAdapter` opens only newly generated unprotected PDFs and honors requested page ranges/cancellation. Printing launches Android's print UI; it never silently submits a physical print.
- Password fields use memory-only state and redacted option strings, not saved-instance state, Room, preferences, or logs. Draft text/annotation edits survive activity recreation in ViewModel memory; only explicit editor/document saves are durable across process death.
- Home/Files/Tools and document/page tools are wired through `AppViewModel` and `MainActivity`; output honors current draft or saved snapshot and explicit page selection. All heavy work runs off the main thread. Recognition/import cancellation waits for native bitmap users before releasing resources.
- Dependency/font attribution is packaged under `assets/notices/`, including Apache 2.0, PDFBox NOTICE, and Liberation Sans SIL OFL 1.1.


### Scanner result URI correction

`MainActivity` sends the explicit camera/gallery source to `AppViewModel.receiveImageResult`. Scanner results accept `content://` and absolute local `file://` URIs; gallery results remain `content://` only. The old shared picker-only check rejected valid scanner files before review. Remote URLs and nonlocal/relative file URIs remain rejected before any page is imported.

The corrected debug APK was installed in place on the Infinix X6885. All five `InputLifecycleTest` cases passed, including scanner-file preview pixels and document save after deleting the temporary input, gallery-file rejection, mixed invalid-result rejection, cancellation, restoration, and replacement selection. Evidence: `app/build/reports/scan-result-fix/instrumentation.txt`. This verifies result handling with synthetic input, not a newly photographed physical document.


### Bahasa Indonesia (1.2.1)

Bahasa antarmuka FreeScanner adalah Bahasa Indonesia. `IndonesianLocale` menggunakan `LocaleManager.applicationLocales` pada API 33 ke atas dan konteks berkonfigurasi `id-ID` untuk aplikasi/aktivitas pada semua versi yang didukung. Locale tanggal bawaan juga mengikuti Indonesia; bahasa sistem ponsel tidak diubah.

Label layar, deskripsi aksesibilitas, pesan operasi/kesalahan, dan judul dokumen baru diterjemahkan tanpa mengganti identitas enum, tag pengujian, kunci penyimpanan/JSON, atau isi dokumen pengguna. Format dan migrasi Room tidak berubah. Build `1.2.1` memakai version code `4`.

Verifikasi terarah pada Infinix X6885 dengan bahasa sistem `en-US`: build berhasil; 16 uji JVM lulus; lint melaporkan 0 kesalahan dan 21 peringatan. Tampilan Berkas dan Alat telah diperiksa langsung. Uji input scanner, filter batch, workspace pustaka, dan dua jalur editor teks lulus; alur edit/batal/ganti nama/seleksi ekspor juga lulus setelah pencarian fixture menghindari dokumen pengguna di luar viewport.

Satu kegagalan lama tetap tercatat: `TextEditorScreenTest.pageSwitchKeepsCorrectionsAndExportWaitsForExplicitSave` mengharapkan callback TXT tetapi mendapat daftar kosong. Kegagalan yang sama telah tercatat sebelum terjemahan; perubahan bahasa tidak mengklaim perbaikannya. Bukti awal: `app/build/reports/indonesian/instrumentation.txt`.

APK akhir `1.2.1` berhasil dipasang sebagai pembaruan di ponsel yang sama. Uji locale lulus dengan konteks Inggris yang dibungkus menjadi `id-ID`, tombol sistem **Batal**, dan nama bulan **Januari** (`app/build/reports/indonesian/locale-final.txt`). Pemeriksaan visual akhir mencakup Beranda dan dialog tambah halaman; screenshot tersimpan di `app/build/reports/indonesian/`. Total hasil terarah yang diperoleh: 13 dari 14 kasus perangkat lulus setelah koreksi, dengan satu kegagalan editor TXT lama di atas.


### Beranda dan simpan ke HP (1.3.0)

Layar awal adalah Beranda dengan pintasan ringkas, pencarian, thumbnail dokumen terbaru, dan draf terbatas. Dokumen membuka overview semua halaman; memilih halaman tetap membuka draf pada ID halaman yang tepat. Tombol **Simpan ke HP** di dokumen maupun tinjauan menggunakan pipeline PDF yang sama dengan ekspor, mencakup semua ID halaman dari snapshot aktif. Penyimpanan tetap melalui `ACTION_CREATE_DOCUMENT`, bukan izin akses semua berkas atau path penyimpanan tetap. **Simpan dokumen** tetap merupakan penyimpanan pustaka aplikasi.

Build dan pemasangan pembaruan pada Infinix X6885/Android 16 berhasil. Enam uji perangkat lulus: `DocumentSaveTest`, `AppJourneyTest`, tiga `LibraryWorkspaceTest`, dan `BatchPageReviewTest`. Regresi baru memeriksa overview dua halaman, pemulihan aktivitas, urutan warna kedua halaman pada PDF dari overview dan draf, pembatalan pemilih penyimpanan, serta sumber asli yang tetap utuh. Uji pemilih menggunakan monitor pembatalan; alur pemilih Android sesungguhnya juga dijalankan manual dengan gambar uji nonpribadi.

PDF hasil simpan nyata di Downloads diperiksa dengan `pdfinfo` dan `pdftoppm`: dua halaman A4, 134.483 byte, urutan halaman 1 lalu 2. Bukti lokal: `app/build/reports/layout-save/instrumentation.txt`, `saved-on-phone.pdf`, dan `saved-page-1.png`/`saved-page-2.png`. Tidak ada klaim kesetaraan seluruh fitur, algoritme, atau kualitas kamera CamScanner.


### Koreksi gambar ke galeri (1.3.1)

Laporan pengguna dikonfirmasi oleh kode: `ScannerApp.quickExport` pada 1.3.0 memaksa `ExportFormat.PDF` untuk tombol simpan. Pada 1.3.1, tombol langsung menggunakan JPEG dan diberi label **Simpan ke galeri**. `AppViewModel.export` mengarahkan SAVE untuk JPEG/gambar panjang ke `DocumentExporter.saveToGallery`, bukan pemilih berkas PDF. Ekspor PDF tetap tersedia secara eksplisit, terpisah dari galeri.

Galeri menggunakan `MediaStore.Images`, MIME `image/jpeg`, dan album `Pictures/FreeScanner`. Android 10+ membuat entri `IS_PENDING=1`, menulis seluruh gambar, kemudian memublikasikannya; kegagalan/pembatalan mencoba menghapus seluruh entri baru dari batch. Berkas yang sudah ada tidak ditimpa. Android 8–9 menggunakan izin tulis penyimpanan saat runtime, dibatasi `maxSdkVersion=28`, dan nama berkas unik. Tidak ditambahkan izin membaca semua foto atau akses semua berkas. Dasar API: [panduan media bersama Android](https://developer.android.com/training/data-storage/shared/media).

Build, lint (0 kesalahan, 22 peringatan), dan pemasangan pembaruan di Infinix X6885/Android 16 berhasil. `DocumentSaveTest` yang diperbarui serta `AppJourneyTest` lulus. Regresi memeriksa JPEG nyata melalui URI MediaStore, MIME/ekstensi/album, `IS_PENDING=0`, dimensi/piksel, dua halaman menjadi dua foto, pengulangan tanpa menimpa, putaran draf, sumber asli tetap utuh, dan PDF hanya melalui pilihan eksplisit.

Pembuktian manual melalui UI normal menghasilkan `FSGallery-proof-001.jpg` (66.230 byte) dan `FSGallery-proof-002.jpg` (66.790 byte), masing-masing JPEG 850×1200 di album FreeScanner. Foto pertama dibuka dari URI MediaStore dengan aplikasi Foto; tidak hanya diperiksa pesan sukses. Bukti lokal: `app/build/reports/gallery-save/` (hasil uji, screenshot, dan dua JPEG). Cabang izin/penyimpanan Android 8–9 belum diuji pada perangkat lama; uji perangkat di atas menggunakan API 36.

### Tambah foto berulang (1.3.2)

`startInput` membuat draf dari `documentId` aktif jika tidak ada draf yang sedang dibuka. Identitas halaman awal disimpan untuk membedakan pembatalan tanpa perubahan dari draf yang sudah menerima halaman. Hasil penambahan memilih halaman baru pertama; penggantian tetap memilih posisi halaman yang diganti. Beranda, footer dokumen, dan tinjauan menyediakan **Tambah foto** dengan pemilihan kamera/impor yang sama. Simpan mempertahankan identitas dokumen sumber.

Build `assembleDebug assembleDebugAndroidTest` berhasil, menghasilkan versi `1.3.2` / version code `7`. Sembilan kasus dari `AddPhotoTest`, `InputLifecycleTest`, `BatchPageReviewTest`, `DocumentSaveTest`, dan `AppJourneyTest` lulus pada emulator Android 15. Regresi baru mencakup tambah berulang melalui Beranda/dokumen/draf, pembatalan, urutan dan piksel halaman, penyimpanan pada dokumen bernama yang sama, serta pembukaan ulang.

Pembuktian manual menggunakan pemilih berkas Android sebenarnya: impor FOTO 1, simpan sebagai `SERTIFIKAT-UJI-APPEND`, tambah FOTO 2 dari Beranda, tambah FOTO 3 dari tinjauan, lalu simpan kembali. Overview menampilkan ketiga foto berurutan dengan nama yang tetap. Screenshot dan hasil uji: `app/build/reports/add-photo/`. Semua masukan merupakan gambar sintetis nonpribadi.

APK dan APK uji dipasang sebagai pembaruan di Infinix X6885/Android 16 (`versionName=1.3.2`). Pada percobaan pertama delapan kasus lulus; `AddPhotoTest` gagal karena hierarki Compose belum ditemukan saat interaksi awal. Log juga merekam `Usf_Hiber freeze uid ... com.freescanner.test` pada 09:20:36, tetapi tidak membuktikan pembekuan sebagai penyebab kegagalan. Regresi diperbaiki dengan menunggu teks UI sebelum berinteraksi. Setelah itu `AddPhotoTest` lulus di ponsel (`OK (1 test)`, 11,482 detik; `app/build/reports/add-photo/phone-add-photo.txt`), dan delapan kasus lain lulus pada percobaan pertama (`app/build/reports/add-photo/phone-instrumentation.txt`).

Kamera fisik untuk menambah halaman tidak diuji ulang; jalur kamera memakai pemindai ML Kit yang sama dengan pemindaian awal. Emulator verifikasi memakai profil sementara di tmpfs karena ruang disk workstation tidak cukup; data aplikasi pengguna tidak dihapus.

### Koreksi transisi sebelum kamera (1.3.3)

Laporan pengguna dan rekaman layar mengungkap navigasi yang tidak perlu: `addPhotos` membuka overview melalui `openDocument`, lalu `startInput` menampilkan tinjauan melalui `showDraft` sebelum pemindai diluncurkan. Target dokumen kini diteruskan sebagai parameter tanpa membuka overview. Draf input tetap tersimpan secara internal melalui `newInputDraftId`, tetapi tidak menjadi `draftId` atau `state.draft` sebelum hasil kembali; pemulihan aktivitas sebelum hasil juga tidak menampilkan tinjauan.

Saat hasil tiba, `receivePages` membuka draf target dan mempertahankan pemulihan impor parsial. Pembatalan membuang salinan yang identitas halaman awalnya belum berubah. Pilihan PDF dalam dialog tambah membawa target yang sama, termasuk pemeriksaan pembatalan draf salinan. Dialog persiapan ML Kit tetap menyediakan progres dan pembatalan ketika SDK belum siap; ini tidak mengganti layar asal dengan dokumen.

Verifikasi 1.3.3 pada Infinix X6885/Android 16: APK aplikasi dan APK uji berhasil dipasang sebagai pembaruan. Build `assembleDebug assembleDebugAndroidTest` berhasil. Dua belas kasus terkait tercakup dan lulus secara bertahap: sebelas lulus dalam run `phone-foreground-regressions.txt`; satu `AddPhotoTest` kemudian lulus dalam `phone-cancellation-final.txt` (10,871 detik) setelah sinkronisasi tes menunggu pesan penyelesaian pembatalan, bukan hanya keadaan layar draf yang kini memang kosong sebelum pembatalan selesai. Asersi penghapusan draf, urutan/piksel foto, judul, identitas dokumen, hasil input saat pemulihan, penggantian halaman, galeri, PDF eksplisit, serta filter batch dipertahankan. Ini bukan hasil satu run akhir dengan dua belas kasus sekaligus.

Run awal terputus pada tenggat host saat proses aplikasi berada pada `cgroup.events: frozen 1`; setelah aplikasi dibawa ke depan status menjadi `frozen 0` dan run berlanjut (12 kasus, 2 gagal). Kegagalan run tersebut tidak dihitung sebagai lulus. Fixture uji transisi kini membuka aktivitas agar model standalone diuji saat aplikasi berada di depan. Tidak ada timeout yang dinaikkan untuk meloloskan uji akhir.

Pembuktian UI nyata memakai foto sintetis: buat `FS-TRANSISI-UJI`, buka **Tambah foto** dari Beranda, pilih kamera, lalu batalkan. Screenshot dan rekaman menunjukkan Beranda di belakang dialog, dilanjutkan UI kamera ML Kit (`Position document in frame`, `Capture`), tanpa overview/tinjauan dokumen di antara keduanya. Pembatalan kembali ke Beranda dengan dokumen tetap satu halaman. Impor foto berikutnya menampilkan `2 dari 2`, lalu tersimpan kembali sebagai dokumen bernama sama dengan dua halaman. Bukti lokal: `app/build/reports/append-splash/after.mp4`, `after-home-source-menu.png`, `after-headers-01.png`, `after-camera-cancel-home.png`, `after-saved-two-photos.png`. Dokumen sintetis dibersihkan; dokumen pengguna tidak dihapus.

Batas: pembukaan dan pembatalan kamera fisik diuji, tetapi pengambilan foto fisik baru tidak dilakukan. Progres persiapan SDK tetap ada bila diperlukan. Verifikasi ini mencakup alur input terkait, bukan jaminan seluruh aplikasi bebas bug.

### Scroll gambar di dalam daftar (1.3.4)

`PageImage` memakai `transformable` dengan pan tanpa syarat, sehingga drag satu jari di atas gambar diambil oleh transformasi walaupun skala masih 1×; callback kemudian mengosongkan pan dan daftar induk tidak menerima drag. Perbaikan memakai overload `canPan = { zoom > 1f }`, sesuai pola Compose untuk gambar dalam kontainer scroll. Pinch tetap aktif, pan gambar hanya mengambil drag ketika diperbesar, dan reset ke 1× mengembalikan scroll induk. Perubahan berlaku pada halaman dokumen, pratinjau tinjauan, dan thumbnail kisi Berkas tanpa mengubah data tersimpan.

`PageImageScrollTest` mengirim gesture sentuh nyata di atas gambar yang sudah dirender, bukan aksi semantik `performScrollTo`: halaman berikutnya harus terlihat setelah swipe pada ukuran halaman dan thumbnail; pinch mempertahankan pan gambar, lalu reset harus mengembalikan kemampuan menggulir daftar. Versi aplikasi `1.3.4`, version code `9`.

Verifikasi pada Infinix X6885/Android 16: build `assembleDebug assembleDebugAndroidTest` berhasil dan APK 1.3.4 dipasang sebagai pembaruan. Ketiga kasus `PageImageScrollTest` lulus dalam 6,429 detik (`app/build/reports/scroll-fix/instrumentation.txt`). Pada UI aplikasi sesungguhnya, mode **Halaman** digeser dari tengah gambar: halaman pertama berpindah dari batas atas y=877 ke y=276, halaman kedua muncul, lalu geseran berikutnya memunculkan halaman ketiga. Tab **Berkas** dalam mode **Kisi** juga berhasil digulir. Dokumen hanya dibuka dan digulir; tidak diedit atau dihapus. Tidak ada klaim pengujian seluruh aplikasi; pinch/reset dan thumbnail dibuktikan oleh regresi sentuhan terarah.

