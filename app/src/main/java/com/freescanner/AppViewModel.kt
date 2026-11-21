package com.freescanner

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.content.IntentSender
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanPage
import com.freescanner.model.ScanFolder
import com.freescanner.model.PdfOptions
import com.freescanner.model.PageAnnotation
import com.freescanner.model.PageKind
import com.freescanner.model.OcrText
import com.freescanner.tools.CodeResult
import android.os.Build
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

internal data class AppState(
    val documents: List<ScanDocument> = emptyList(),
    val drafts: List<ScanDraft> = emptyList(),
    val folders: List<ScanFolder> = emptyList(),
    val reviewPageId: String? = null,
    val documentId: String? = null,
    val draft: ScanDraft? = null,
    val busy: String? = "Membuka dokumen…",
    val message: String? = null,
    val progress: String? = null,
    val codes: List<CodeResult>? = null,
    val pdfPasswordUri: Uri? = null,
)

internal enum class InputSource { CAMERA, GALLERY }
internal enum class ExportFormat(val label: String, val mimeType: String, val extension: String) {
    PDF("PDF", "application/pdf", "pdf"),
    JPEG("JPEG", "image/jpeg", "jpg"),
    SEPARATE_PDFS("PDF terpisah", "application/pdf", "pdf"),
    LONG_IMAGE("Gambar panjang", "image/jpeg", "jpg"),
    TXT("Teks", "text/plain", "txt"),
    DOCX("Word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
}
internal enum class ExportAction { SAVE, SHARE }
internal sealed interface AppEvent {
    data class Input(val source: InputSource, val replacing: Boolean = false) : AppEvent
    data object ImportPdf : AppEvent
    data object ImportIdCard : AppEvent
    data object ImportCode : AppEvent
    data object GalleryPermission : AppEvent
    data class Print(val file: File, val title: String, val pageCount: Int) : AppEvent
    data class ScannerReady(val intentSender: IntentSender, val generation: Int) : AppEvent
    data class Export(val files: List<File>, val format: ExportFormat, val action: ExportAction) : AppEvent
}

internal class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val applicationGraph = application as ScannerApplication
    val processor = applicationGraph.processor
    val repository = applicationGraph.repository
    private val exporter = applicationGraph.exporter
    private val mutableState = MutableStateFlow(AppState(documentId = saved["documentId"], reviewPageId = saved["reviewPageId"]))
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<AppEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var scannerGeneration = 0
    private var activeOperation: Job? = null

    fun beginScannerSetup(): Int {
        scannerGeneration += 1
        mutableState.update { it.copy(busy = "Menyiapkan pemindai…") }
        return scannerGeneration
    }

    fun scannerPrepared(generation: Int, sender: IntentSender) {
        if (generation == scannerGeneration) eventChannel.trySend(AppEvent.ScannerReady(sender, generation))
    }

    fun finishScannerSetup(generation: Int): Boolean {
        if (generation != scannerGeneration) return false
        mutableState.update { it.copy(busy = null) }
        return true
    }

    fun cancelScannerSetup() {
        scannerGeneration += 1
        mutableState.update { it.copy(busy = null) }
        inputCanceled()
    }

    init {
        viewModelScope.launch {
            repository.documents.catch { report("Dokumen tidak dapat dibuka. Mulai ulang aplikasi untuk mencoba lagi.") }
                .collect { docs -> mutableState.update { it.copy(documents = docs) } }
        }
        viewModelScope.launch {
            repository.drafts.catch { report("Draf tidak dapat dibuka. Berkas yang tersimpan tidak diubah.") }
                .collect { drafts -> mutableState.update { it.copy(drafts = drafts) } }
        }
        viewModelScope.launch {
            repository.folders.catch { report("Folder tidak dapat dibuka. Dokumen Anda tidak diubah.") }
                .collect { folders -> mutableState.update { it.copy(folders = folders) } }
        }
        viewModelScope.launch {
            try {
                val draft = saved.get<String>("draftId")?.let { repository.getDraft(it) }
                mutableState.update { it.copy(draft = draft, busy = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(busy = null, message = "Draf tidak dapat dipulihkan. Dokumen yang tersimpan tidak diubah.") } }
        }
    }

    fun report(message: String) { mutableState.update { it.copy(message = message) } }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun openDocument(id: String) {
        if (state.value.busy != null) return
        saved["documentId"] = id
        mutableState.update { it.copy(documentId = id) }
    }
    fun backToLibrary() {
        if (state.value.busy != null) return
        saved["documentId"] = null
        mutableState.update { it.copy(documentId = null) }
    }

    fun startInput(
        source: InputSource,
        replacePageId: String? = null,
        targetDocumentId: String? = null,
    ) = operation("Menyiapkan ${if (source == InputSource.CAMERA) "pemindai" else "impor"}…") {
        val existing = state.value.draft
        val draft = existing ?: repository.createDraft(targetDocumentId ?: state.value.documentId)
        saved["newInputDraftId"] = if (existing == null) draft.id else null
        saved["inputInitialPageIds"] = if (existing == null) ArrayList(draft.pages.map { it.id }) else null
        // Keep the draft out of the visible screen until the camera/picker returns. Publishing it
        // here replaced Home or the open document with an empty draft before the scanner appeared.
        saved["replacePageId"] = replacePageId
        eventChannel.send(AppEvent.Input(source, replacing = replacePageId != null))
    }

    fun inputCanceled() {
        viewModelScope.launch {
            state.first { it.busy == null }
            operation("Kembali ke dokumen…") {
                saved["replacePageId"] = null
                saved["inputDocumentId"] = null
                val newId = saved.get<String>("newInputDraftId")
                saved["newInputDraftId"] = null
                val initialPages = saved.get<ArrayList<String>>("inputInitialPageIds").orEmpty()
                saved["inputInitialPageIds"] = null
                if (newId != null && repository.getDraft(newId)?.pages?.map { it.id } == initialPages) {
                    repository.discardDraft(newId)
                    if (saved.get<String>("draftId") == newId) saved["draftId"] = null
                    if (state.value.draft?.id == newId) {
                        saved["draftId"] = null
                        mutableState.update { it.copy(draft = null) }
                    }
                }
                report("Pemindaian atau impor dibatalkan.")
            }
        }
    }

    fun receiveImageResult(uris: List<Uri>, source: InputSource) {
        // The explicitly launched scanner can return local files; Android's gallery picker must
        // supply content URIs. Do not apply the picker's narrower contract to SDK scan results.
        if (uris.any { uri ->
            uri.scheme != "content" && !(source == InputSource.CAMERA && uri.scheme == "file" &&
                uri.authority.isNullOrEmpty() && uri.path?.startsWith("/") == true)
        }) {
            report("Aplikasi yang dipilih mengembalikan lokasi gambar yang tidak valid. Pilih gambar melalui pemilih berkas Android.")
            return
        }
        receivePages(uris, detectEdges = source == InputSource.GALLERY)
    }

    fun receivePages(uris: List<Uri>, detectEdges: Boolean) {
        if (uris.isEmpty()) { inputCanceled(); return }
        viewModelScope.launch {
            state.first { it.busy == null }
            operation("Menambahkan halaman…") {
                val draftId = state.value.draft?.id ?: saved.get<String>("draftId")
                    ?: saved.get<String>("newInputDraftId") ?: throw IllegalStateException("Draf tidak tersedia")
                val replacement = saved.get<String>("replacePageId")
                val replacementIndex = if (replacement == null) -1 else repository.getDraft(draftId)?.pages?.indexOfFirst { it.id == replacement } ?: -1
                // The input has returned; exposing this draft now also preserves partial imports.
                showDraft(repository.getDraft(draftId) ?: throw IOException("Draf tidak tersedia"))
                val imported = repository.addPages(draftId, uris, replacement, detectEdges)
                showDraft(imported, if (replacementIndex >= 0) imported.pages.getOrNull(replacementIndex)?.id else imported.pages.lastOrNull()?.id)
                saved["replacePageId"] = null
                saved["newInputDraftId"] = null
                saved["inputInitialPageIds"] = null
                saved["inputDocumentId"] = null
            }
        }
    }

    fun editDocument(id: String, pageId: String? = null) = operation("Membuka draf untuk diedit…") {
        showDraft(repository.createDraft(id), pageId)
    }
    fun resumeDraft(id: String) = operation("Memulihkan draf…") {
        showDraft(repository.getDraft(id) ?: throw IOException("Draf tidak tersedia"))
    }
    fun keepDraft() {
        if (state.value.busy != null) return
        saved["draftId"] = null
        mutableState.update { it.copy(draft = null) }
    }
    fun discardDraft() = operation("Membuang draf…") {
        state.value.draft?.let { repository.discardDraft(it.id) }
        saved["draftId"] = null
        mutableState.update { it.copy(draft = null) }
    }
    fun saveDraft(title: String) = operation("Menyimpan dokumen…") {
        val draft = state.value.draft ?: return@operation
        val document = repository.saveDraft(draft.id, title)
        saved["draftId"] = null
        saved["documentId"] = document.id
        mutableState.update { it.copy(draft = null, documentId = document.id, message = "Dokumen disimpan") }
    }
    fun updateCrop(page: ScanPage, crop: Crop) = updatePage(page.copy(crop = crop,
        annotations = if (crop == page.crop) page.annotations else emptyList()))
    fun rotate(page: ScanPage) = updatePage(page.copy(rotation = (page.rotation + 90) % 360))
    fun filterPages(pageIds: Set<String>, filter: PageFilter) = operation("Menerapkan filter…") {
        val draft = state.value.draft ?: return@operation
        showDraft(repository.applyFilter(draft.id, pageIds, filter))
    }
    private fun updatePage(page: ScanPage) = operation("Memperbarui halaman…") {
        val draft = state.value.draft ?: return@operation
        showDraft(repository.updatePage(draft.id, page))
    }
    fun move(pageId: String, delta: Int) = operation("Memindahkan halaman…") {
        val draft = state.value.draft ?: return@operation
        showDraft(repository.movePage(draft.id, pageId, delta))
    }
    fun remove(pageId: String) = operation("Menghapus halaman…") {
        val draft = state.value.draft ?: return@operation
        showDraft(repository.removePage(draft.id, pageId))
    }
    fun rename(id: String, title: String) = operation("Mengganti nama dokumen…") { repository.renameDocument(id, title) }
    fun delete(id: String) = operation("Menghapus dokumen…") {
        repository.deleteDocument(id)
        if (state.value.documentId == id) {
            saved["documentId"] = null
            mutableState.update { it.copy(documentId = null) }
        }
    }

    fun createFolder(name: String) = operation("Membuat folder…") { repository.createFolder(name) }
    fun renameFolder(id: String, name: String) = operation("Mengganti nama folder…") { repository.renameFolder(id, name) }
    fun deleteFolder(id: String) = operation("Menghapus folder kosong…") { repository.deleteFolder(id) }
    fun moveDocuments(ids: Set<String>, folderId: String?) = operation("Memindahkan dokumen…") {
        repository.moveDocuments(ids, folderId)
        report("Dokumen dipindahkan")
    }
    fun duplicateDocument(id: String) = operation("Menggandakan dokumen…") {
        val copy = repository.duplicateDocument(id)
        saved["documentId"] = copy.id
        mutableState.update { it.copy(documentId = copy.id, message = "Dokumen digandakan") }
    }
    fun mergeDocuments(ids: List<String>, title: String) = operation("Menggabungkan ke draf baru…") {
        showDraft(repository.mergeDocuments(ids, title), null)
    }
    fun deleteDocuments(ids: Set<String>) = operation("Menghapus dokumen terpilih…") {
        repository.deleteDocuments(ids)
        if (state.value.documentId in ids) {
            saved["documentId"] = null
            mutableState.update { it.copy(documentId = null) }
        }
        report("Dokumen terpilih dihapus")
    }

    fun selectReviewPage(id: String) {
        if (state.value.draft?.pages?.any { it.id == id } != true) return
        saved["reviewPageId"] = id
        mutableState.update { it.copy(reviewPageId = id) }
    }

    fun saveAnnotations(page: ScanPage, annotations: List<PageAnnotation>) =
        updatePage(page.copy(annotations = annotations))

    fun savePageText(id: String, value: String) = operation("Menyimpan teks…") {
        val draft = state.value.draft ?: throw IllegalArgumentException("Buka dokumen yang dapat diedit terlebih dahulu.")
        val page = draft.pages.single { it.id == id }
        showDraft(repository.setPageText(draft.id, page, page.text?.edited(value) ?: OcrText(value)), id)
    }

    fun recognizePages(ids: Set<String>) = operation("Mengenali teks…") {
        val draft = state.value.draft ?: throw IllegalArgumentException("Buka dokumen yang dapat diedit terlebih dahulu.")
        require(ids.isNotEmpty() && ids.all { id -> draft.pages.any { it.id == id } }) { "Pilih halaman yang tersedia untuk dikenali teksnya." }
        val snapshot = workingDocument() ?: throw IllegalArgumentException("Buka dokumen terlebih dahulu.")
        val pages = draft.pages.filter { it.id in ids }
        pages.forEachIndexed { index, page ->
            mutableState.update { it.copy(progress = "Mengenali teks halaman ${index + 1} dari ${pages.size}…") }
            val text = applicationGraph.ocr.recognize(snapshot, page.id)
            showDraft(repository.setPageText(draft.id, page, text))
        }
        report("Teks berhasil dikenali. Periksa hasilnya, lalu simpan dokumen Anda.")
    }

    fun requestPdf(targetDocumentId: String? = null) = operation("Membuka pemilih PDF…") {
        saved["inputDocumentId"] = targetDocumentId
        eventChannel.send(AppEvent.ImportPdf)
    }
    fun requestIdCard() = operation("Membuka gambar kartu…") { eventChannel.send(AppEvent.ImportIdCard) }
    fun requestCode() = operation("Membuka gambar kode…") { eventChannel.send(AppEvent.ImportCode) }
    fun clearCodes() { mutableState.update { it.copy(codes = null) } }
    fun receiveCode(uri: Uri?) {
        if (uri == null) return
        operation("Membaca kode…") {
            val results = applicationGraph.mediaTools.scanCodes(uri)
            mutableState.update { it.copy(codes = results) }
        }
    }
    fun receiveIdCard(uris: List<Uri>) {
        if (uris.isEmpty()) return
        operation("Menata kartu identitas…") {
            val file = applicationGraph.mediaTools.createIdCard(uris)
            try {
                val draft = state.value.draft ?: repository.createDraft()
                showDraft(draft)
                showDraft(repository.addGeneratedPages(draft.id, listOf(file), PageKind.ID_CARD))
            } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
        }
    }

    fun receivePdf(uri: Uri?, password: String = "") {
        if (uri == null) { saved["inputDocumentId"] = null; return }
        operation("Mengimpor PDF…") {
            mutableState.update { it.copy(pdfPasswordUri = null) }
            val draft = state.value.draft ?: repository.createDraft(saved.get<String>("inputDocumentId")).also {
                saved["newInputDraftId"] = it.id
                saved["inputInitialPageIds"] = ArrayList(it.pages.map { page -> page.id })
                showDraft(it)
            }
            try {
                applicationGraph.pdfImporter.importPages(uri, password) { file, index, total ->
                    mutableState.update { it.copy(progress = "Mengimpor halaman ${index + 1} dari $total…") }
                    showDraft(repository.addGeneratedPages(draft.id, listOf(file)))
                }
                saved["newInputDraftId"] = null
                saved["inputInitialPageIds"] = null
                saved["inputDocumentId"] = null
            } catch (failure: Exception) {
                // Password failure keeps the draft for retry; any other failure must discard the
                // untouched draft, including IllegalArgumentException from an oversized, empty,
                // or over-page-limit PDF.
                if (failure.cause is SecurityException && Build.VERSION.SDK_INT >= 35) {
                    mutableState.update { it.copy(pdfPasswordUri = uri) }
                } else {
                    // Cancellation also lands here; suspension points fail immediately in a
                    // cancelled job, so the cleanup itself must be non-cancellable.
                    withContext(NonCancellable) {
                        if (saved.get<String>("newInputDraftId") == draft.id &&
                            repository.getDraft(draft.id)?.pages?.map { it.id } ==
                            saved.get<ArrayList<String>>("inputInitialPageIds").orEmpty()) {
                            repository.discardDraft(draft.id)
                            saved["draftId"] = null
                            saved["newInputDraftId"] = null
                            saved["inputInitialPageIds"] = null
                            mutableState.update { it.copy(draft = null) }
                        }
                    }
                    throw failure
                }
            }
        }
    }
    fun cancelPdfPassword() {
        mutableState.update { it.copy(pdfPasswordUri = null) }
        inputCanceled()
    }
    fun cancelOperation() {
        mutableState.update { it.copy(progress = "Membatalkan…") }
        activeOperation?.cancel()
    }

    fun printDocument() = operation("Menyiapkan cetakan…") {
        val document = workingDocument() ?: throw IllegalArgumentException("Buka dokumen terlebih dahulu.")
        require(document.pages.isNotEmpty()) { "Tambahkan halaman sebelum mencetak." }
        val file = exporter.pdf(document, PdfOptions(password = ""))
        eventChannel.send(AppEvent.Print(file, document.title, document.pages.size))
    }

    private fun workingDocument(): ScanDocument? {
        val current = state.value
        return current.draft?.let { ScanDocument(it.id, it.title, 0L, it.updatedAt, it.pages, it.folderId) }
            ?: current.documents.firstOrNull { it.id == current.documentId }
    }

    fun export(format: ExportFormat, action: ExportAction, selected: Set<String>, options: PdfOptions = PdfOptions()) = operation("Menyiapkan ${format.label}…") {
        val document = workingDocument() ?: throw IllegalArgumentException("Buka dokumen terlebih dahulu.")
        require(selected.isNotEmpty() && selected.all { id -> document.pages.any { it.id == id } }) { "Pilih halaman yang tersedia untuk diekspor." }
        val snapshot = document.copy(pages = document.pages.filter { it.id in selected })
        val files = when (format) {
            ExportFormat.PDF -> listOf(exporter.pdf(snapshot, options))
            ExportFormat.JPEG -> exporter.jpeg(document, selected)
            ExportFormat.SEPARATE_PDFS -> exporter.separatePdfs(document, selected, options)
            ExportFormat.LONG_IMAGE -> listOf(exporter.longImage(document, selected, options.quality))
            ExportFormat.TXT -> listOf(exporter.text(document, selected, docx = false))
            ExportFormat.DOCX -> listOf(exporter.text(document, selected, docx = true))
        }
        saved["exportFiles"] = ArrayList(files.map { it.absolutePath })
        if (action == ExportAction.SAVE && (format == ExportFormat.JPEG || format == ExportFormat.LONG_IMAGE)) {
            if (Build.VERSION.SDK_INT <= 28 &&
                ContextCompat.checkSelfPermission(applicationGraph, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                eventChannel.send(AppEvent.GalleryPermission)
            } else publishGallery(files)
        } else eventChannel.send(AppEvent.Export(files, format, action))
    }

    fun galleryPermissionResult(granted: Boolean) {
        if (!granted) {
            saved["exportFiles"] = null
            report("Izin penyimpanan diperlukan untuk menyimpan gambar ke galeri. Dokumen Anda tidak diubah.")
            return
        }
        viewModelScope.launch {
            state.first { it.busy == null }
            operation("Menyimpan gambar ke galeri…") { publishGallery(pendingExportFiles()) }
        }
    }

    private suspend fun publishGallery(files: List<File>) {
        val images = exporter.saveToGallery(files)
        saved["exportFiles"] = null
        report("${images.size} gambar disimpan ke galeri • Album FreeScanner")
    }

    private fun pendingExportFiles(): List<File> {
        val root = File(getApplication<Application>().cacheDir, "exports").canonicalFile
        val files = saved.get<ArrayList<String>>("exportFiles").orEmpty().map { path ->
            File(path).canonicalFile.also {
                require(it.toPath().startsWith(root.toPath()) && it.isFile) { "Hasil ekspor sudah tidak tersedia. Ekspor dokumen kembali." }
            }
        }
        check(files.isNotEmpty()) { "Hasil ekspor sudah tidak tersedia. Ekspor dokumen kembali." }
        return files
    }

    fun saveExport(destination: Uri?, directory: Boolean = false) {
        if (destination == null) { report("Penyimpanan dibatalkan. Dokumen Anda tidak diubah."); return }
        operation("Menulis berkas ekspor…") {
            val files = pendingExportFiles()
            if (directory) exporter.saveFiles(files, destination) else exporter.copyTo(files.single(), destination)
            saved["exportFiles"] = null
            report("Disimpan ke HP")
        }
    }

    private fun showDraft(draft: ScanDraft, pageId: String? = state.value.reviewPageId) {
        val selected = pageId?.takeIf { id -> draft.pages.any { it.id == id } } ?: draft.pages.firstOrNull()?.id
        saved["draftId"] = draft.id
        saved["reviewPageId"] = selected
        mutableState.update { it.copy(draft = draft, reviewPageId = selected) }
    }
    private suspend fun recoverDraft() {
        val id = saved.get<String>("draftId") ?: return
        try { repository.getDraft(id)?.let { showDraft(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the last known recoverable draft on screen. */ }
    }

    private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        mutableState.update { it.copy(busy = label, progress = null) }
        activeOperation = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) {
                withContext(NonCancellable) { recoverDraft() }
                report("Operasi dibatalkan. Halaman dan edit yang sudah selesai tetap tersimpan di draf.")
                throw e
            }
            catch (_: OutOfMemoryError) { report("Gambar ini terlalu besar untuk diproses. Coba gambar yang lebih kecil. Dokumen yang tersimpan tidak diubah.") }
            catch (failure: Exception) {
                report(if (failure is IllegalArgumentException || failure is IOException) failure.message ?: "Periksa pilihan Anda, lalu coba lagi."
                    else "Operasi tidak dapat diselesaikan. Periksa ruang penyimpanan serta akses ke gambar atau tujuan penyimpanan, lalu coba lagi. Dokumen yang tersimpan tidak diubah.")
                recoverDraft()
            } finally { mutableState.update { if (it.busy == label) it.copy(busy = null, progress = null) else it } }
        }
    }
}
