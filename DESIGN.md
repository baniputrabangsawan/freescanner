# Android document scanner | Design specification

Use the inspected Mobbin references below for the color direction and screen structure. The references are iOS screens. Implement the patterns with Android system insets, controls, and accessibility semantics. ML Kit supplies its own scan UI when selected. Apply the custom camera and crop specification to an owned scanner flow if the engine evaluation calls for one.

## Reference map

| Screen | Pattern to implement | Mobbin reference |
| --- | --- | --- |
| Document library | White canvas, a Files heading, a top-right add entry, and rows with a leading icon or thumbnail, title, and smaller date. Keep document rows easy to scan with generous vertical space. | [Slack on Mobbin](https://mobbin.com/screens/3f150aac-2a62-48aa-9791-022f2f5a2250?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) |
| Capture | Dark viewfinder, blue outline and translucent fill over detected paper, upper capture controls, and white shutter centered at the bottom. | [Apple Mail on Mobbin](https://mobbin.com/screens/4f73de4e-6461-44bb-9267-684f9264b638?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) |
| Multi-page capture | Keep the most recent page thumbnail near the shutter and display a short confirmation when the scanner is ready for the next page. | [Docusign on Mobbin](https://mobbin.com/screens/dca0b7af-a971-42e4-b325-e44b65004ff6?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) |
| Crop | Give the photo most of the screen. Use four outlined corner handles connected by a thin guide, with cancel at upper left and a blue confirmation control at upper right. | [Google Drive on Mobbin](https://mobbin.com/screens/1e8afd16-6fe4-420f-b810-dca6f8234a5f?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) |
| Filter and review | Put the document on a neutral gray canvas. Place filter thumbnails and text labels below the preview. Indicate selection with a blue outline or fill. Keep crop, rotate, and delete below the filter strip. | [Apple Mail on Mobbin](https://mobbin.com/screens/b7c5eff7-39fb-4edd-8387-674027bf7c47?utm_source=chatgpt&utm_medium=ai-assistant&utm_source_platform=mcp) |

## Colors

These implementation HEX values are visual approximations derived from the above screens. Use the blue already present across the scanner and editor references as the common action color. Keep camera controls white over a dark background and the library on white.

| Token | Value | Assignment |
| --- | --- | --- |
| Library and toolbar surface | `#FFFFFF` | File list, top bar, and editor controls |
| Main text | `#1C1C1E` | Titles, filenames, and labels |
| Secondary text | `#5D5D63` | Dates and page counts |
| Editor canvas | `#C9C9C9` | Backdrop behind the page |
| Crop chrome | `#F0EFF4` | Area around the crop surface |
| Primary action | `#005EBF` | Confirm actions and selected labels with accessible contrast |
| Detection overlay | `#2494F2` | Paper outline and low-opacity fill |
| Camera chrome | `#17181A` | Capture controls over the viewfinder |
| Shutter and inverse text | `#FFFFFF` | Camera shutter and text on dark or blue surfaces |

## Screen behavior

- **Library.** Show **Documents** at the top. Place **New scan** at the upper right. The entry offers **Scan with camera** and **Import photo**. Each row shows the document name, page count, and modified date. Opening a document shows its pages. Use a labeled overflow action for Rename and Delete. The empty state contains one short sentence and **New scan**.
- **Capture.** Give most of the viewport to the viewfinder. On an owned scanner screen, provide **Cancel**, flash, and **Auto / Manual** controls. Keep a large manual shutter and make the latest page thumbnail open review. Display **Ready for next page** after a successful capture.
- **Crop.** Use corner handles with larger invisible touch areas than the drawn circles. Keep **Cancel** and **Apply crop** accessible. Provide a reset action and prevent a crop from becoming an invalid shape.
- **Page review.** Present one page at a time with its position, such as **2 of 4**. Place available filter choices under the preview. Provide **Add page** and a clear **Save** action. For reordering, provide a screen-reader-accessible move action in addition to drag.
- **Export.** Offer **PDF** and **JPEG**. Keep separate **Save to device** and **Share** actions. Show progress for longer jobs. On failure the user can return to the same document without losing page order or edits.

## Accessibility and implementation checks

Use Android system typography, scalable labels, minimum 48 dp touch targets, and readable color contrast. Give screen readers descriptions for capture, crop, rotate, page position, and selected filter. Keep actions available with enlarged fonts, display busy states, and make crop and filter selection understandable without color alone. Test the UI on an actual Android phone with parent-sized text settings.

Use [PRD.md](PRD.md) for product acceptance and [ARCHITECTURE.md](ARCHITECTURE.md) for the scanner engine boundary.

## Local workflow upgrade (1.1)

The library is a document workspace, not a marketing empty state: camera/import shortcuts, title search, folder scope, six sort orders, list/grid, and explicit bulk selection. Keep the established white/blue identity. Selection shows a count and merge order; changing search/scope clears hidden selected documents before any action. Folder deletion is restricted to empty folders and always confirmed.

Saved documents open an overview grid with direct page editing and a full-page view toggle. Review separates page tools from filter targets (This page / All pages / Choose pages) and supports comparison against the managed input. PDF export retains explicit page selection and format preferences across recreation.

References inspected for this upgrade: [Gusto document list/search](https://mobbin.com/screens/965f5cd0-b65e-4dbf-acc0-63cc48fc8643), [Manus file rows](https://mobbin.com/screens/94b5bdc9-653b-4c47-82da-20284e74c329), and the synthetic CamScanner workflows recorded in [REA_ANALYSIS.md](REA_ANALYSIS.md). CamScanner branding, ads, cloud indicators, and nonfunctional premium tools are not copied.

## Local document suite (1.2)

Retain the native white/blue identity and Files workspace. Home contains real capture/import shortcuts, recent files, and recoverable drafts; Tools groups only functional local actions. Document-dependent tools use an explicit document chooser when no file is open. References inspected before implementation: [Dropbox document review](https://mobbin.com/screens/564d678a-2987-4512-9277-24da01770e96) and [Docusign document stage/tools](https://mobbin.com/screens/dca0b7af-a971-42e4-b325-e44b65004ff6).

Text has an honest bundled-Latin label, current/all-page recognition, explicit correction saves, unsaved-change protection, and clearly scoped whole-document TXT/Word actions. Recognition and PDF import expose progress and cancellation; completed pages survive interruption.

Annotation preview and export share normalized cropped/rotated-page geometry. Separate signature, pen, highlight, text, and watermark controls; include undo/removal, position/style sliders and explicit save/cancel. Warn before cropping an annotated page; compare-original shows the managed source without overlays.

Output lists real formats, explicit page selection, quality, PDF geometry, searchable-text limits, and ephemeral password confirmation. Save and Share remain separate. ID-card output explains actual-size A4 printing. QR results are selectable untrusted text; opening an HTTP(S) website requires an explicit action.


## Bahasa antarmuka (1.2.1)

FreeScanner menggunakan Bahasa Indonesia sebagai bahasa aplikasi, terlepas dari bahasa sistem ponsel. Navigasi memakai **Beranda**, **Berkas**, dan **Alat**; tindakan utama memakai **Pindai**, **Simpan**, **Bagikan**, **Pangkas**, **Putar**, dan **Hapus**. Pesan kesalahan, dialog konfirmasi, label aksesibilitas, dan petunjuk editor juga berbahasa Indonesia.

Nama dokumen yang sudah tersimpan, teks hasil OCR, dan teks yang dimasukkan pengguna tidak diterjemahkan atau diubah. Nama merek serta istilah format seperti PDF, JPEG, OCR, QR, TXT, DOCX, A4, dan Word tetap dipertahankan. Judul dokumen baru menggunakan awalan **Pindai** dan salinan dokumen memakai akhiran **salinan**.

Locale aplikasi adalah `id-ID`: Android 13 ke atas menggunakan pengaturan bahasa per aplikasi, sedangkan konteks aplikasi/aktivitas menangani Android versi sebelumnya. Bahasa sistem ponsel tidak diubah. Dialog atau aplikasi eksternal yang tidak menghormati locale pemanggil dapat tetap mengikuti bahasa sistem.


## Beranda dan simpan ke HP (1.3)

Beranda menjadi layar awal: pintasan alat ringkas, pencarian, dokumen terbaru dengan thumbnail asli/jumlah halaman, lalu maksimal tiga draf. Pencarian mencakup seluruh dokumen tersimpan, bukan hanya lima dokumen terbaru. **Lihat semua** membuka workspace Berkas; tombol mengambang **Pindai** membuka kamera.

Mengetuk dokumen membuka kumpulan thumbnail bernomor untuk seluruh halamannya. **Ringkasan** menampilkan dua kolom, **Halaman** menampilkan halaman penuh; ketukan pada thumbnail membuka editor pada halaman yang dipilih.

Mulai 1.3.1, tombol langsung **Simpan ke galeri** di footer dokumen dan layar tinjau menyimpan setiap halaman sebagai foto JPEG di album **FreeScanner** (`Pictures/FreeScanner`). Dokumen beberapa halaman menghasilkan beberapa foto, bukan PDF. Hasil mengikuti pangkasan, filter, putaran, dan anotasi saat ini; simpan ulang membuat salinan baru tanpa menimpa foto yang sudah ada.

PDF hanya dibuat jika dipilih melalui **Ekspor**, dengan tindakan **Simpan PDF ke HP** dan pemilih lokasi Android. Pilihan JPEG dan gambar panjang dalam dialog ekspor juga menggunakan galeri; format teks tetap memakai pemilih berkas. **Bagikan** pada overview dokumen tetap membagikan PDF. **Simpan dokumen** menyimpan pustaka aplikasi, bukan galeri. Perbaikan ini menggantikan perilaku tombol 1.3.0 yang memaksa PDF.

### Tambah foto ke dokumen yang sama (1.3.2)

Setiap dokumen terbaru di Beranda memiliki tindakan **Tambah foto**. Dokumen yang dibuka juga menampilkan tombol **Tambah foto** di footer, dan tinjauan menempatkannya sebelum navigasi halaman agar tidak tersembunyi di baris alat yang digeser.

Pilih **Pindai dengan kamera** atau **Impor foto**. Foto baru ditambahkan setelah halaman lama dan langsung dipilih untuk ditinjau. **Simpan dokumen** mempertahankan nama serta identitas dokumen tersimpan; bukan membuat dokumen terpisah. Membatalkan pemilih tanpa menambah halaman mengembalikan dokumen semula tanpa meninggalkan draf baru. Draf yang telah berisi perubahan tetap dipertahankan.

### Transisi tambah foto (1.3.3)

**Tambah foto** dari Beranda mempertahankan Beranda sebagai latar dialog sumber; tidak membuka overview dokumen. Memilih kamera atau galeri menyiapkan target secara internal tanpa menampilkan tinjauan draf. Tinjauan baru dibuka setelah hasil foto kembali. Pembatalan kembali ke layar asal dan membuang salinan draf yang belum berubah. Identitas dokumen target dipertahankan untuk foto maupun PDF yang dipilih dari dialog tambah.

### Scroll halaman dan Berkas (1.3.4)

Pada perbesaran 1×, geseran satu jari di atas gambar harus menggulir daftar induk, termasuk halaman dokumen dan thumbnail pada kisi Berkas. Pinch tetap memperbesar gambar; setelah diperbesar, geseran menggeser gambar. **Atur ulang perbesaran** mengembalikan gambar ke 1× dan mengaktifkan kembali scroll daftar lewat geseran di atas gambar. Pengguna tidak perlu mencari celah kosong di antara halaman untuk scroll.


## Sistem visual 1.4 (2024 refresh)

Warna memakai peran semantik dengan kontras terukur, bukan nilai pinjaman: ink `#131A2B`, muted `#5A6478`, canvas `#F6F7FB`, surface `#FFFFFF`, border `#E3E7F0`, aksi `#1F4FE0` (6.46:1 dengan putih). Tujuh *tone* (biru, teal, violet, amber, rose, emerald, sky) masing-masing menyediakan tint permukaan dan tinta kuat; setiap pasangan tinta/tint diukur ≥4.88:1. Hero memakai gradien biru→violet dengan teks putih 6.58–7.19:1. Bentuk 8/12/16/22/28 dp, tipografi tebal untuk judul.

**Aturan pemakaian:** satu aksi terisi per layar; warna pada ubin alat menandai kategori, bukan prioritas. Ikon selalu berada di dalam lingkaran putih agar tetap terbaca di atas tint. `PageImage` tetap meneruskan geseran satu jari ke daftar saat 1×.

