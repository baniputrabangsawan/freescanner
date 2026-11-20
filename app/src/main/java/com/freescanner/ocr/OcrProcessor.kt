package com.freescanner.ocr

import android.graphics.Bitmap
import com.freescanner.data.DocumentRepository
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.OcrLine
import com.freescanner.model.OcrText
import com.freescanner.model.ScanDocument
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** App-scoped, bundled Latin recognition. No image or recognized text leaves this device. */
class OcrProcessor(
    private val repository: DocumentRepository,
    private val processor: ImageProcessor,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mutex = Mutex()
    private var closed = false

    suspend fun recognize(document: ScanDocument, pageId: String): OcrText {
        val page = document.pages.singleOrNull { it.id == pageId }
            ?: throw IllegalArgumentException("Pilih halaman yang masih ada dalam dokumen ini.")
        // Validate/lease only this page, not the whole document for each OCR request.
        val selected = document.copy(pages = listOf(page))
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "Pengenalan teks sudah ditutup." }
                repository.withDocumentSources(selected) { snapshot ->
                    currentCoroutineContext().ensureActive()
                    val renderedPage = snapshot.pages.single()
                    val bitmap = processor.renderPage(repository.sourceFile(renderedPage), renderedPage)
                    val width = bitmap.width.toFloat()
                    val height = bitmap.height.toFloat()
                    val result = recognizeRetainingBitmap(bitmap) {
                        recognizer.process(InputImage.fromBitmap(it, 0))
                    }
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrLine(
                                text = line.text,
                                left = (box.left / width).coerceIn(0f, 1f),
                                top = (box.top / height).coerceIn(0f, 1f),
                                right = (box.right / width).coerceIn(0f, 1f),
                                bottom = (box.bottom / height).coerceIn(0f, 1f),
                            )
                        }
                    }
                    OcrText(result.text, lines).also {
                        require(it.isValid()) { "Teks di halaman ini melebihi kapasitas aman editor." }
                    }
                }
            }
        }
    }

    /** Waits for any active recognition before releasing the reusable SDK client. */
    suspend fun close() = mutex.withLock {
        if (!closed) {
            recognizer.close()
            closed = true
        }
    }
}

/** ML Kit's Task.await cancellation does not stop native use of its input bitmap. */
internal suspend fun <T> recognizeRetainingBitmap(bitmap: Bitmap, start: (Bitmap) -> Task<T>): T {
    try {
        currentCoroutineContext().ensureActive()
        val task = start(bitmap)
        val result = withContext(NonCancellable) { task.await() }
        currentCoroutineContext().ensureActive()
        return result
    } catch (failure: Exception) {
        // Cancellation takes precedence over a simultaneous SDK failure.
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        bitmap.recycle()
    }
}
