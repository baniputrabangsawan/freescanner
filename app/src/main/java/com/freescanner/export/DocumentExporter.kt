package com.freescanner.export

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.freescanner.data.DocumentRepository
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanPage
import com.freescanner.model.PdfOptions
import com.freescanner.model.ExportQuality
import com.freescanner.pdf.GeneratedPdfWriter
import com.freescanner.pdf.LongImageLayout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Exports one immutable page-order snapshot, retaining completed share files for at least 24 hours. */
class DocumentExporter(
    context: Context,
    private val repository: DocumentRepository,
    private val processor: ImageProcessor,
) {
    private val context = context.applicationContext
    private val exportRoot = File(this.context.cacheDir, "exports")
    private val mutex = Mutex()

    suspend fun pdf(document: ScanDocument, options: PdfOptions = PdfOptions()): File = operation {
        require(document.pages.isNotEmpty()) { "Pilih setidaknya satu halaman untuk diekspor." }
        repository.withDocumentSources(document) { snapshot ->
            exportBatch { directory ->
                val completed = File(directory, "${safeName(snapshot.title)}.pdf")
                writePdf(snapshot, options, completed)
                completed
            }
        }
    }

    suspend fun separatePdfs(document: ScanDocument, selectedPageIds: Set<String>, options: PdfOptions = PdfOptions()): List<File> = operation {
        val selected = selectPages(document, selectedPageIds)
        repository.withDocumentSources(selected) { snapshot ->
            exportBatch { directory ->
                snapshot.pages.map { page ->
                    val index = document.pages.indexOfFirst { it.id == page.id }
                    val file = File(directory, "${safeName(document.title)}-${(index + 1).toString().padStart(3, '0')}.pdf")
                    writePdf(snapshot.copy(pages = listOf(page)), options, file)
                    file
                }
            }
        }
    }

    suspend fun jpeg(document: ScanDocument, selectedPageIds: Set<String>): List<File> = operation {
        val selected = selectPages(document, selectedPageIds)
        repository.withDocumentSources(selected) { snapshot ->
            exportBatch { directory ->
                snapshot.pages.map { page ->
                    val index = document.pages.indexOfFirst { it.id == page.id }
                    val file = File(directory, "${safeName(snapshot.title)}-${(index + 1).toString().padStart(3, '0')}.jpg")
                    val bitmap = render(page, ExportQuality.HIGH.maxDimension)
                    try { writeJpeg(bitmap, file, ExportQuality.HIGH.jpegQuality) } finally { bitmap.recycle() }
                    file
                }
            }
        }
    }

    suspend fun longImage(document: ScanDocument, selectedPageIds: Set<String>, quality: ExportQuality = ExportQuality.HIGH): File = operation {
        val selected = selectPages(document, selectedPageIds)
        repository.withDocumentSources(selected) { snapshot ->
            exportBatch { directory ->
                // First measure without retaining decoded pages. Crop output dimensions are measured
                // by the same renderer used below, so perspective/rotation cannot break placement.
                val dimensions = snapshot.pages.map { page ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = render(page, quality.maxDimension)
                    try { bitmap.width to bitmap.height } finally { bitmap.recycle() }
                }
                val plan = LongImageLayout.calculate(dimensions, quality.maxDimension)
                val combined = try { Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) {
                    throw IOException("Memori tidak cukup untuk gambar panjang. Pilih lebih sedikit halaman atau ekspor JPEG terpisah.")
                }
                try {
                    val canvas = Canvas(combined)
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    var top = 0
                    snapshot.pages.forEachIndexed { index, page ->
                        currentCoroutineContext().ensureActive()
                        val bitmap = render(page, minOf(quality.maxDimension, maxOf(plan.width, plan.heights[index])))
                        try {
                            canvas.drawBitmap(bitmap, null, RectF(0f, top.toFloat(), plan.width.toFloat(),
                                (top + plan.heights[index]).toFloat()), paint)
                        } finally { bitmap.recycle() }
                        top += plan.heights[index]
                    }
                    val completed = File(directory, "${safeName(snapshot.title)}-long.jpg")
                    writeJpeg(combined, completed, quality.jpegQuality)
                    completed
                } finally { combined.recycle() }
            }
        }
    }

    suspend fun text(document: ScanDocument, selectedPageIds: Set<String>, docx: Boolean): File = operation {
        val selected = selectPages(document, selectedPageIds)
        // Text is immutable snapshot metadata, not source image data; do not unnecessarily lease sources.
        exportBatch { directory ->
            val completed = File(directory, "${safeName(selected.title)}.${if (docx) "docx" else "txt"}")
            val staged = File(directory, "${completed.name}.part")
            currentCoroutineContext().ensureActive()
            TextDocumentWriter.write(selected, staged, docx)
            finishFile(staged, completed)
            completed
        }
    }

    private fun selectPages(document: ScanDocument, selectedPageIds: Set<String>): ScanDocument {
        require(selectedPageIds.isNotEmpty()) { "Pilih setidaknya satu halaman untuk diekspor." }
        require(selectedPageIds.all { selected -> document.pages.any { it.id == selected } }) { "Halaman yang dipilih tidak lagi tersedia." }
        return document.copy(pages = document.pages.filter { it.id in selectedPageIds })
    }

    private suspend fun writePdf(document: ScanDocument, options: PdfOptions, completed: File) {
        val staged = File(completed.parentFile, "${completed.name}.part")
        GeneratedPdfWriter(context).write(document, options, staged, ::render)
        finishFile(staged, completed)
    }

    private suspend fun writeJpeg(bitmap: Bitmap, completed: File, quality: Int) {
        val staged = File(completed.parentFile, "${completed.name}.part")
        currentCoroutineContext().ensureActive()
        staged.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw IOException("Tidak dapat mengodekan gambar ini. Pilih lebih sedikit halaman atau periksa ruang penyimpanan yang tersedia.")
            }
            output.fd.sync()
        }
        finishFile(staged, completed)
    }

    private suspend fun <T> exportBatch(block: suspend (File) -> T): T {
        val directory = newExportDirectory()
        try {
            val result = block(directory)
            currentCoroutineContext().ensureActive()
            directory.setLastModified(System.currentTimeMillis())
            return result
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    suspend fun copyTo(file: File, destination: Uri): Unit = operation {
        require(destination.scheme == "content") { "Pilih tujuan melalui pemilih berkas Android." }
        validateExportFile(file)
        try {
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Tidak dapat membuka tujuan yang dipilih. Pilih lokasi lain.")
            output.use { target -> file.inputStream().use { source -> copyCancellable(source, target) } }
        } catch (failure: Throwable) {
            // "wt" already truncated the user's file; remove the incomplete document so the
            // chosen location does not keep a corrupt copy that looks like a successful export.
            withContext(NonCancellable) {
                try { DocumentFile.fromSingleUri(context, destination)?.delete() }
                catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            }
            throw failure
        }
    }

    /** Creates only new files; if any write fails, removes all outputs created by this batch. */
    suspend fun saveFiles(files: List<File>, treeUri: Uri): Unit = operation {
        require(treeUri.scheme == "content") { "Pilih folder melalui pemilih berkas Android." }
        require(files.isNotEmpty()) { "Pilih setidaknya satu berkas untuk disimpan." }
        files.forEach { validateExportFile(it); mimeType(it) }
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Tidak dapat membuka folder yang dipilih. Pilih folder lain.")
        if (!tree.isDirectory || !tree.canWrite()) throw IOException("Folder yang dipilih tidak dapat ditulisi. Pilih folder lain.")
        val created = mutableListOf<DocumentFile>()
        try {
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val destination = tree.createFile(mimeType(file), file.name)
                    ?: throw IOException("Tidak dapat membuat berkas di folder yang dipilih. Periksa ruang penyimpanan yang tersedia.")
                created.add(destination)
                val output = context.contentResolver.openOutputStream(destination.uri, "wt")
                    ?: throw IOException("Tidak dapat menulis berkas di folder yang dipilih.")
                output.use { target -> file.inputStream().use { source -> copyCancellable(source, target) } }
            }
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            for (destination in created.asReversed()) {
                // Providers may refuse rollback; still try every newly created file and preserve the original failure.
                try {
                    destination.delete()
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    /** Publishes new JPEG copies to the gallery, never overwriting or modifying source images. */
    suspend fun saveToGallery(files: List<File>): List<Uri> = operation {
        require(files.isNotEmpty()) { "Pilih setidaknya satu gambar untuk disimpan." }
        val dimensions = files.map { file ->
            validateExportFile(file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(mimeType(file) == "image/jpeg" && bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Hanya gambar JPEG yang dapat disimpan ke galeri. Gunakan ekspor berkas untuk PDF atau teks."
            }
            bounds.outWidth to bounds.outHeight
        }
        @Suppress("DEPRECATION")
        val legacyDirectory = if (Build.VERSION.SDK_INT < 29) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FreeScanner").also {
                if (!it.isDirectory && !it.mkdirs()) throw IOException("Tidak dapat membuat album FreeScanner. Periksa izin dan ruang penyimpanan.")
            }
        } else null
        val resolver = context.contentResolver
        val created = ArrayList<Uri>(files.size)
        try {
            files.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.WIDTH, dimensions[index].first)
                    put(MediaStore.Images.Media.HEIGHT, dimensions[index].second)
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FreeScanner")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.DATA, File(legacyDirectory, "${file.nameWithoutExtension}-${UUID.randomUUID()}.jpg").absolutePath)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Tidak dapat membuat gambar di galeri. Periksa ruang penyimpanan.")
                created.add(uri)
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Tidak dapat menulis gambar ke galeri.")
                output.use { target -> file.inputStream().use { source -> copyCancellable(source, target) } }
            }
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 29) {
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                for (uri in created) {
                    if (resolver.update(uri, published, null, null) != 1) {
                        throw IOException("Gambar belum dapat ditampilkan di galeri. Coba simpan kembali.")
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            created
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (uri in created.asReversed()) {
                    try { resolver.delete(uri, null, null) }
                    catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
                }
            }
            throw failure
        }
    }

    private suspend fun render(page: ScanPage, maxDimension: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        // Do not interrupt native rendering: cancellation is observed only after ownership of the
        // returned bitmap is established, so no in-flight native operation sees a recycled bitmap.
        val bitmap = processor.renderPage(repository.sourceFile(page), page, maxDimension)
        try { currentCoroutineContext().ensureActive() } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
        return bitmap
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> throw IllegalArgumentException("Jenis berkas ekspor tidak didukung.")
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private suspend fun copyCancellable(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = runInterruptible(Dispatchers.IO) { source.read(buffer) }
            if (count < 0) break
            runInterruptible(Dispatchers.IO) { target.write(buffer, 0, count) }
        }
        target.flush()
        currentCoroutineContext().ensureActive()
    }

    private fun newExportDirectory(): File {
        if (!exportRoot.isDirectory && !exportRoot.mkdirs()) {
            throw IOException("Tidak dapat membuat penyimpanan ekspor. Periksa ruang penyimpanan yang tersedia.")
        }
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        exportRoot.listFiles()?.forEach { directory ->
            if (directory.isDirectory && directory.name.startsWith("export-") && directory.lastModified() < cutoff) {
                directory.deleteRecursively()
            }
        }
        val directory = File(exportRoot, "export-${UUID.randomUUID()}")
        if (!directory.mkdir()) throw IOException("Tidak dapat membuat penyimpanan ekspor. Periksa ruang penyimpanan yang tersedia.")
        return directory
    }

    private suspend fun finishFile(staged: File, completed: File) {
        currentCoroutineContext().ensureActive()
        if (!staged.isFile || staged.length() == 0L || !staged.renameTo(completed)) {
            throw IOException("Tidak dapat menyelesaikan ekspor. Periksa ruang penyimpanan yang tersedia.")
        }
    }

    private fun validateExportFile(file: File) {
        val parent = file.canonicalFile.parentFile
        require(parent?.parentFile == exportRoot.canonicalFile && parent.name.startsWith("export-")) { "Berkas ekspor tidak valid." }
        if (!file.isFile || file.length() == 0L) throw IOException("Hasil ekspor ini tidak lagi tersedia. Ekspor kembali dokumennya.")
    }

    private fun safeName(title: String): String = title
        .replace(Regex("[^\\p{L}\\p{N} _-]"), "_")
        .trim(' ', '.', '_')
        .take(80)
        .ifBlank { "Dokumen" }

    private companion object {
        const val RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}
