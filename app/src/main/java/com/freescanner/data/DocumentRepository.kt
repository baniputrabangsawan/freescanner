package com.freescanner.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.Corner
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanPage
import com.freescanner.model.ScanFolder
import com.freescanner.model.OcrText
import com.freescanner.model.PageAnnotation
import com.freescanner.model.PageKind
import com.freescanner.model.isValid
import com.freescanner.model.rotatedClockwise
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns immutable normalized source images; edits only change a draft's independent metadata. */
class DocumentRepository internal constructor(
    context: Context,
    private val processor: ImageProcessor,
    databaseName: String,
    private val storageDirectory: File,
) {
    constructor(context: Context, processor: ImageProcessor) : this(
        context.applicationContext,
        processor,
        "documents.db",
        File(context.applicationContext.filesDir, "documents"),
    )

    private val database = Room.databaseBuilder(
        context.applicationContext, DocumentDatabase::class.java, databaseName,
    ).addMigrations(DocumentDatabase.MIGRATION_1_2, DocumentDatabase.MIGRATION_2_3).build()
    private val dao = database.documents()
    private val mutex = Mutex()
    private var initialized = false
    private val sourceDirectory = File(storageDirectory, "sources")
    private val cacheDirectory = context.applicationContext.cacheDir

    val documents: Flow<List<ScanDocument>> = dao.observe(SAVED)
        .onStart { locked { } }
        .map { records -> records.map { it.toDocument() } }
        .flowOn(Dispatchers.IO)

    val drafts: Flow<List<ScanDraft>> = dao.observe(DRAFT)
        .onStart { locked { } }
        .map { records -> records.map { it.toDraft() } }
        .flowOn(Dispatchers.IO)

    val folders: Flow<List<ScanFolder>> = dao.observeFolders()
        .onStart { locked { } }
        .map { records -> records.map { ScanFolder(it.id, it.name, it.createdAt) } }
        .flowOn(Dispatchers.IO)

    suspend fun createFolder(name: String): ScanFolder = locked {
        val cleanName = validateFolderName(name)
        val record = FolderRecord(newId(), cleanName, System.currentTimeMillis())
        database.withTransaction {
            requireAvailableFolderName(cleanName)
            dao.insertFolder(record)
        }
        ScanFolder(record.id, record.name, record.createdAt)
    }

    suspend fun renameFolder(id: String, name: String): Unit = locked {
        val cleanName = validateFolderName(name)
        database.withTransaction {
            requireFolder(id)
            requireAvailableFolderName(cleanName, exceptId = id)
            dao.renameFolder(id, cleanName)
        }
    }

    suspend fun deleteFolder(id: String): Unit = locked {
        database.withTransaction {
            requireFolder(id)
            require(!dao.folderHasDocuments(id)) {
                "Folder ini berisi dokumen atau draf. Pindahkan dokumen serta simpan atau buang drafnya sebelum menghapus folder."
            }
            dao.deleteFolder(id)
        }
    }

    suspend fun moveDocuments(ids: Set<String>, folderId: String?): Unit = locked {
        require(ids.isNotEmpty()) { "Pilih setidaknya satu dokumen untuk dipindahkan." }
        database.withTransaction {
            requireFolder(folderId)
            ids.forEach { requireSaved(it) }
            val now = System.currentTimeMillis()
            ids.forEach { dao.moveDocument(it, folderId, now) }
        }
    }

    suspend fun duplicateDocument(id: String): ScanDocument = locked {
        val source = requireSaved(id).toDocument()
        requireFolder(source.folderId)
        val now = System.currentTimeMillis()
        val copyTitle = validateTitle("${source.title.take(192)} salinan")
        val record = DocumentRecord(newId(), SAVED, null, copyTitle, now, now, source.folderId)
        database.withTransaction {
            dao.putDocument(record)
            dao.insertPages(source.pages.mapIndexed { index, page ->
                page.copy(id = newId()).toRecord(record.id, index)
            })
        }
        requireSaved(record.id).toDocument()
    }

    suspend fun mergeDocuments(ids: List<String>, title: String): ScanDraft = locked {
        require(ids.isNotEmpty()) { "Pilih setidaknya satu dokumen untuk digabungkan." }
        require(ids.toSet().size == ids.size) { "Pilih setiap dokumen hanya sekali." }
        val cleanTitle = validateTitle(title)
        val sources = ids.map { requireSaved(it).toDocument() }
        val now = System.currentTimeMillis()
        val record = DocumentRecord(newId(), DRAFT, null, cleanTitle, now, now)
        val pages = buildList {
            sources.forEach { source ->
                source.pages.forEach { page -> add(page.copy(id = newId()).toRecord(record.id, size)) }
            }
        }
        database.withTransaction {
            dao.putDocument(record)
            dao.insertPages(pages)
        }
        requireDraft(record.id).toDraft()
    }

    suspend fun deleteDocuments(ids: Set<String>): Unit = locked {
        require(ids.isNotEmpty()) { "Pilih setidaknya satu dokumen untuk dihapus." }
        ids.forEach { requireSaved(it) }
        deleteRecordsLocked(ids)
    }

    suspend fun createDraft(sourceDocumentId: String? = null): ScanDraft = locked {
        val source = sourceDocumentId?.let { requireSaved(it) }
        val now = System.currentTimeMillis()
        val folderId = source?.document?.folderId
        requireFolder(folderId)
        val title = source?.document?.title ?: Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DEFAULT_TITLE)
        val record = DocumentRecord(newId(), DRAFT, sourceDocumentId, title, now, now, folderId)
        database.withTransaction {
            dao.putDocument(record)
            if (source != null) {
                dao.insertPages(source.pages.map { it.copy(ownerId = record.id) })
            }
        }
        requireDraft(record.id).toDraft()
    }

    suspend fun getDraft(id: String): ScanDraft? = locked {
        dao.get(id)?.takeIf { it.document.status == DRAFT }?.toDraft()
    }

    suspend fun getDocument(id: String): ScanDocument? = locked {
        dao.get(id)?.takeIf { it.document.status == SAVED }?.toDocument()
    }

    /** Each successful page is durably attached before processing the next URI. */
    suspend fun addPages(
        draftId: String,
        uris: List<Uri>,
        replacePageId: String? = null,
        detectEdges: Boolean = true,
    ): ScanDraft = locked {
        require(uris.isNotEmpty()) { "Pilih setidaknya satu gambar." }
        require(replacePageId == null || uris.size == 1) { "Pilih satu gambar pengganti." }
        val initial = requireDraft(draftId).toDraft()
        require(replacePageId == null || initial.pages.any { it.id == replacePageId }) { "Halaman tidak lagi tersedia." }
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val sourceId = newId()
            val staged = File(sourceDirectory, "$sourceId.part")
            val completed = File(sourceDirectory, "$sourceId.jpg")
            try {
                runInterruptible(Dispatchers.IO) { processor.ingest(uri, staged) }
                requireReadableImage(staged)
                val crop = if (detectEdges) runInterruptible(Dispatchers.IO) { processor.detectCorners(staged) } else Crop.Full
                require(crop.isValid()) { "Area pangkas yang terdeteksi tidak valid. Coba gambar lain." }
                currentCoroutineContext().ensureActive()
                if (!staged.renameTo(completed)) throw IOException("Tidak dapat menyelesaikan impor gambar. Periksa ruang penyimpanan yang tersedia.")
                val page = ScanPage(newId(), "sources/$sourceId.jpg", crop)
                // Once normalization succeeds, persist the recoverable work even if cancellation arrives now.
                withContext(NonCancellable) {
                    val draft = requireDraft(draftId).toDraft()
                    val pages = draft.pages.toMutableList()
                    if (replacePageId != null) {
                        val index = pages.indexOfFirst { it.id == replacePageId }
                        check(index >= 0) { "Halaman tidak lagi tersedia." }
                        pages[index] = page
                    } else {
                        pages.add(page)
                    }
                    writeDraft(draft, pages)
                }
            } finally {
                staged.delete()
                withContext(NonCancellable) { cleanUnreferencedLocked() }
            }
        }
        requireDraft(draftId).toDraft()
    }
    /** Only internally generated, metadata-free JPEGs in this app's private cache may use this path. */
    internal suspend fun addGeneratedPages(draftId: String, files: List<File>, kind: PageKind = PageKind.DOCUMENT): ScanDraft = locked {
        require(files.isNotEmpty()) { "Pilih setidaknya satu halaman yang dihasilkan." }
        requireDraft(draftId)
        val cache = cacheDirectory.canonicalFile.toPath()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val input = file.canonicalFile
            require(input.toPath().startsWith(cache) && input.isFile && input.length() in 2..50L * 1024 * 1024) {
                "Halaman yang dihasilkan tidak tersedia atau terlalu besar."
            }
            val sourceId = newId()
            val staged = File(sourceDirectory, "$sourceId.part")
            val completed = File(sourceDirectory, "$sourceId.jpg")
            try {
                val copying = currentCoroutineContext()
                runInterruptible(Dispatchers.IO) {
                    input.inputStream().use { source ->
                        staged.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var count = source.read(buffer)
                            require(count >= 2 && buffer[0] == 0xff.toByte() && buffer[1] == 0xd8.toByte()) {
                                "Halaman yang dihasilkan bukan JPEG yang dinormalisasi."
                            }
                            var copied = 0L
                            while (count >= 0) {
                                copying.ensureActive()
                                copied += count
                                require(copied <= 50L * 1024 * 1024) { "Halaman yang dihasilkan terlalu besar." }
                                output.write(buffer, 0, count)
                                count = source.read(buffer)
                            }
                            output.fd.sync()
                        }
                    }
                }
                requireReadableImage(staged, maximumDimension = 3000)
                currentCoroutineContext().ensureActive()
                if (!staged.renameTo(completed)) throw IOException("Tidak dapat menyimpan halaman yang dihasilkan. Periksa ruang penyimpanan yang tersedia.")
                val page = ScanPage(newId(), "sources/$sourceId.jpg", kind = kind)
                withContext(NonCancellable) {
                    val draft = requireDraft(draftId).toDraft()
                    writeDraft(draft, draft.pages + page)
                }
            } finally {
                staged.delete()
                withContext(NonCancellable) { cleanUnreferencedLocked() }
            }
        }
        requireDraft(draftId).toDraft()
    }


    suspend fun updatePage(draftId: String, page: ScanPage): ScanDraft = locked {
        val draft = requireDraft(draftId).toDraft()
        val index = draft.pages.indexOfFirst { it.id == page.id }
        require(index >= 0) { "Halaman tidak lagi tersedia." }
        val previous = draft.pages[index]
        require(page.sourcePath == previous.sourcePath) { "Sumber halaman tidak dapat diubah melalui pengeditan." }
        require(page.crop == previous.crop || previous.annotations.isEmpty() || page.annotations.isEmpty()) {
            "Hapus anotasi halaman sebelum mengubah area pangkasnya."
        }
        var updated = page
        if (page.rotation != previous.rotation && page.annotations == previous.annotations) {
            val turns = (page.rotation - previous.rotation) / 90
            updated = updated.copy(annotations = page.annotations.map { it.rotatedClockwise(turns) })
        }
        if (page.crop != previous.crop && page.kind == PageKind.ID_CARD) updated = updated.copy(kind = PageKind.DOCUMENT)
        if (updated.crop != previous.crop || updated.rotation != previous.rotation ||
            updated.filter != previous.filter || updated.annotations != previous.annotations) updated = updated.copy(text = null)
        validatePage(updated)
        val pages = draft.pages.toMutableList().apply { this[index] = updated }
        writeDraft(draft, pages)
        requireDraft(draftId).toDraft()
    }

    /** A late recognition result must not overwrite a newer image edit or a text correction. */
    suspend fun setPageText(draftId: String, expected: ScanPage, text: OcrText): ScanDraft = locked {
        require(text.isValid()) { "Teks yang dikenali terlalu besar atau memiliki batas yang tidak valid." }
        val draft = requireDraft(draftId).toDraft()
        val index = draft.pages.indexOfFirst { it.id == expected.id }
        require(index >= 0 && draft.pages[index] == expected) { "Halaman berubah saat pengenalan teks. Kenali teksnya kembali." }
        val pages = draft.pages.toMutableList().apply { this[index] = expected.copy(text = text) }
        writeDraft(draft, pages)
        requireDraft(draftId).toDraft()
    }

    suspend fun applyFilter(draftId: String, pageIds: Set<String>, filter: PageFilter): ScanDraft = locked {
        val draft = requireDraft(draftId).toDraft()
        require(pageIds.isNotEmpty()) { "Pilih setidaknya satu halaman untuk diberi filter." }
        require(draft.pages.count { it.id in pageIds } == pageIds.size) {
            "Satu atau beberapa halaman yang dipilih tidak lagi tersedia. Pilih kembali halamannya."
        }
        writeDraft(draft, draft.pages.map { page -> if (page.id in pageIds && page.filter != filter) page.copy(filter = filter, text = null) else page })
        requireDraft(draftId).toDraft()
    }

    suspend fun movePage(draftId: String, pageId: String, delta: Int): ScanDraft = locked {
        val draft = requireDraft(draftId).toDraft()
        val pages = draft.pages.toMutableList()
        val index = pages.indexOfFirst { it.id == pageId }
        require(index >= 0) { "Halaman tidak lagi tersedia." }
        val target = (index.toLong() + delta).coerceIn(0L, pages.lastIndex.toLong()).toInt()
        pages.add(target, pages.removeAt(index))
        writeDraft(draft, pages)
        requireDraft(draftId).toDraft()
    }

    suspend fun removePage(draftId: String, pageId: String): ScanDraft = locked {
        val draft = requireDraft(draftId).toDraft()
        require(draft.pages.any { it.id == pageId }) { "Halaman tidak lagi tersedia." }
        writeDraft(draft, draft.pages.filterNot { it.id == pageId })
        cleanUnreferencedLocked()
        requireDraft(draftId).toDraft()
    }

    suspend fun saveDraft(draftId: String, title: String): ScanDocument = locked {
        val draftRecord = requireDraft(draftId)
        val draft = draftRecord.toDraft()
        require(draft.pages.isNotEmpty()) { "Tambahkan setidaknya satu halaman sebelum menyimpan." }
        val cleanTitle = validateTitle(title)
        requireFolder(draft.folderId)
        draft.pages.forEach { validatePage(it, checkFile = true) }
        val source = draft.sourceDocumentId?.let { dao.get(it)?.takeIf { record -> record.document.status == SAVED } }
        val now = System.currentTimeMillis()
        val saved = DocumentRecord(
            source?.document?.id ?: newId(), SAVED, null, cleanTitle,
            source?.document?.createdAt ?: draftRecord.document.createdAt, now, draft.folderId,
        )
        database.withTransaction {
            if (source != null) dao.deletePages(saved.id)
            dao.putDocument(saved)
            dao.insertPages(draft.pages.mapIndexed { index, page -> page.toRecord(saved.id, index) })
            dao.deleteDocument(draftId)
        }
        cleanUnreferencedLocked()
        requireSaved(saved.id).toDocument()
    }

    suspend fun discardDraft(id: String): Unit = locked {
        val record = dao.get(id) ?: return@locked
        require(record.document.status == DRAFT) { "Item ini bukan draf." }
        database.withTransaction { dao.deleteDocument(id) }
        cleanUnreferencedLocked()
    }

    suspend fun renameDocument(id: String, title: String): Unit = locked {
        requireSaved(id)
        dao.rename(id, validateTitle(title), System.currentTimeMillis())
    }

    suspend fun deleteDocument(id: String): Unit = locked {
        val record = dao.get(id) ?: return@locked
        require(record.document.status == SAVED) { "Item ini bukan dokumen tersimpan." }
        deleteRecordsLocked(setOf(id))
    }

    /** Resolves only app-generated relative names. Callers rendering must hold a source lease. */
    fun sourceFile(page: ScanPage): File {
        require(SOURCE_PATH.matches(page.sourcePath)) { "Referensi gambar tersimpan tidak valid." }
        return File(storageDirectory, page.sourcePath)
    }

    /** Holds reclamation/mutations until the immutable snapshot has finished rendering. */
    suspend fun <T> withDocumentSources(document: ScanDocument, block: suspend (ScanDocument) -> T): T = locked {
        val snapshot = document.copy(pages = document.pages.map { page ->
            page.copy(
                crop = Crop(page.crop.corners.toList()),
                annotations = page.annotations.map { annotation ->
                    if (annotation is PageAnnotation.Ink) annotation.copy(points = annotation.points.toList()) else annotation
                },
                text = page.text?.let { it.copy(lines = it.lines.toList()) },
            )
        })
        require(snapshot.pages.isNotEmpty()) { "Dokumen tidak memiliki halaman." }
        require(snapshot.pages.map { it.id }.toSet().size == snapshot.pages.size) { "Urutan halaman tersimpan tidak valid." }
        val references = dao.referencedSources().toHashSet()
        snapshot.pages.forEach {
            require(it.sourcePath in references) { "Dokumen berubah. Buka kembali dan ekspor ulang." }
            validatePage(it, checkFile = true)
        }
        block(snapshot)
    }

    internal suspend fun close(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { database.close() }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) {
                if (!sourceDirectory.isDirectory && !sourceDirectory.mkdirs()) {
                    throw IOException("Tidak dapat membuka penyimpanan dokumen. Periksa ruang penyimpanan yang tersedia.")
                }
                cleanUnreferencedLocked()
                initialized = true
            }
            block()
        }
    }

    private suspend fun requireDraft(id: String): DocumentWithPages = dao.get(id)
        ?.takeIf { it.document.status == DRAFT }
        ?: throw IllegalArgumentException("Draf tidak lagi tersedia.")

    private suspend fun requireSaved(id: String): DocumentWithPages = dao.get(id)
        ?.takeIf { it.document.status == SAVED }
        ?: throw IllegalArgumentException("Dokumen tersimpan tidak lagi tersedia.")


    private suspend fun requireFolder(id: String?) {
        require(id == null || dao.getFolder(id) != null) {
            "Folder tidak lagi tersedia. Pilih folder lain atau pindahkan dokumen ke Dokumen."
        }
    }

    private suspend fun requireAvailableFolderName(name: String, exceptId: String? = null) {
        val normalized = normalizeFolderName(name)
        require(dao.getFolders().none { it.id != exceptId && normalizeFolderName(it.name) == normalized }) {
            "Folder dengan nama ini sudah ada. Pilih nama lain."
        }
    }

    private suspend fun deleteRecordsLocked(ids: Set<String>) = withContext(NonCancellable) {
        database.withTransaction { ids.forEach { dao.deleteDocument(it) } }
        cleanUnreferencedLocked()
    }
    private suspend fun writeDraft(draft: ScanDraft, pages: List<ScanPage>) {
        pages.forEach { validatePage(it) }
        database.withTransaction {
            dao.deletePages(draft.id)
            dao.insertPages(pages.mapIndexed { index, page -> page.toRecord(draft.id, index) })
            dao.rename(draft.id, draft.title, System.currentTimeMillis())
        }
    }

    private suspend fun cleanUnreferencedLocked() {
        val references = dao.referencedSources().toHashSet()
        sourceDirectory.listFiles()?.forEach { file ->
            currentCoroutineContext().ensureActive()
            // Never traverse directories or follow unexpected names while reconciling private storage.
            if (file.isFile && (file.name.endsWith(".part") || SOURCE_PATH.matches("sources/${file.name}"))) {
                if ("sources/${file.name}" !in references) file.delete()
            }
        }
    }

    private fun validatePage(page: ScanPage, checkFile: Boolean = false) {
        require(page.id.isNotBlank() && page.crop.isValid() && page.rotation in 0..270 && page.rotation % 90 == 0) {
            "Pengaturan halaman tersimpan tidak valid."
        }
        require(page.annotations.size <= 1_000 && page.annotations.all { it.isValid() } &&
            page.annotations.sumOf { if (it is PageAnnotation.Ink) it.points.size else 0 } <= 100_000 &&
            page.annotations.sumOf { if (it is PageAnnotation.Label) it.text.length else 0 } <= 100_000) { "Anotasi halaman ini terlalu banyak atau tidak valid." }
        require(page.text?.isValid() != false) { "Teks yang dikenali tidak valid." }
        val file = sourceFile(page)
        if (checkFile) {
            require(file.canonicalFile.parentFile == sourceDirectory.canonicalFile) { "Referensi gambar tersimpan tidak valid." }
            if (!file.isFile || file.length() == 0L) throw IOException("Gambar dokumen hilang. Pulihkan dari cadangan atau hapus halaman ini.")
        }
    }

    private fun requireReadableImage(file: File, maximumDimension: Int = Int.MAX_VALUE) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (!file.isFile || file.length() == 0L || bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > maximumDimension || bounds.outHeight > maximumDimension) {
            throw IOException("Gambar ini tidak dapat dibaca. Pilih gambar lain.")
        }
    }

    private fun DocumentWithPages.orderedPages(): List<ScanPage> {
        val ordered = pages.sortedBy { it.position }
        require(ordered.withIndex().all { (index, page) -> page.position == index }) { "Urutan halaman tersimpan tidak valid." }
        return ordered.map { record ->
            val page = ScanPage(
                record.id, record.sourcePath,
                Crop(listOf(Corner(record.x0, record.y0), Corner(record.x1, record.y1), Corner(record.x2, record.y2), Corner(record.x3, record.y3))),
                record.rotation, PageFilter.valueOf(record.filter),
                PageMetadataCodec.decodeAnnotations(record.annotationsJson), PageMetadataCodec.decodeText(record.ocrJson), PageKind.valueOf(record.kind),
            )
            validatePage(page)
            page
        }
    }

    private fun DocumentWithPages.toDocument() = ScanDocument(
        document.id, document.title, document.createdAt, document.updatedAt, orderedPages(), document.folderId,
    )

    private fun DocumentWithPages.toDraft() = ScanDraft(
        document.id, document.sourceDocumentId, document.title, document.updatedAt, orderedPages(), document.folderId,
    )

    private fun ScanPage.toRecord(ownerId: String, position: Int): PageRecord {
        val corners = crop.corners
        return PageRecord(
            ownerId, id, position, sourcePath, rotation, filter.name,
            corners[0].x, corners[0].y, corners[1].x, corners[1].y,
            corners[2].x, corners[2].y, corners[3].x, corners[3].y,
            PageMetadataCodec.encodeAnnotations(annotations), PageMetadataCodec.encodeText(text), kind.name,
        )
    }

    private fun validateTitle(title: String): String {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200 && trimmed.none { it.isISOControl() }) {
            "Masukkan judul sepanjang 1 hingga 200 karakter."
        }
        return trimmed
    }

    private fun validateFolderName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 80 && name.none { it.isISOControl() }) {
            "Masukkan nama folder sepanjang 1 hingga 80 karakter tanpa karakter kontrol."
        }
        return trimmed
    }

    private fun normalizeFolderName(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val DRAFT = "draft"
        const val SAVED = "saved"
        val DEFAULT_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("'Pindai' yyyy-MM-dd HH.mm", Locale.ROOT)
        val SOURCE_PATH = Regex("sources/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
    }
}
