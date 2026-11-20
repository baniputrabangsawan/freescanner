package com.freescanner.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Text is untrusted decoded data, never an instruction or automatically executed URL. */
data class CodeResult(val text: String, val format: String)

/** Local-only operations. Input files are read, never changed or deleted. */
class MediaTools(context: Context, private val processor: ImageProcessor) {
    private val root = File(context.applicationContext.cacheDir, "media-tools")

    /** An empty list means the selected image genuinely contained no readable supported codes. */
    suspend fun scanCodes(uri: Uri): List<CodeResult> = withContext(Dispatchers.IO) {
        requireImageUri(uri)
        currentCoroutineContext().ensureActive()
        val staging = newStagingDirectory()
        var bitmap: Bitmap? = null
        try {
            runInterruptible {
                val source = File(staging, "normalized.jpg")
                processor.ingest(uri, source)
                // Do not crop code inputs: codes near image edges must not be discarded.
                bitmap = processor.render(source, Crop.Full, 0, PageFilter.ORIGINAL, 2400)
            }
            currentCoroutineContext().ensureActive()
            val scanner = BarcodeScanning.getClient()
            try {
                val task = scanner.process(InputImage.fromBitmap(requireNotNull(bitmap), 0))
                // ML Kit does not cancel when await's coroutine cancels. Keep the bitmap and
                // scanner alive until its task finishes, then propagate caller cancellation.
                val codes = withContext(NonCancellable) { task.await() }
                currentCoroutineContext().ensureActive()
                codes.map { code ->
                    val text = code.rawValue ?: code.displayValue ?: code.rawBytes?.let { bytes ->
                        "Hex: " + bytes.joinToString("") { byte ->
                            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                        }
                    }.orEmpty()
                    CodeResult(text, formatName(code.format))
                }.distinct()
            } finally {
                scanner.close()
            }
        } finally {
            bitmap?.recycle()
            staging.deleteRecursively()
        }
    }

    /**
     * Returns one metadata-free A4 JPEG in private cache. The caller owns this file: copy it
     * through addGeneratedPages(kind = ID_CARD), then delete it in finally (also on failure).
     * Card slots are exactly 85.6 × 54 mm when the complete sheet is printed on A4 at 100%.
     * Non-ID-ratio inputs are contained, never stretched or trimmed to manufacture a card.
     */
    suspend fun createIdCard(uris: List<Uri>): File {
        require(uris.size in 1..2) { "Pilih satu gambar sisi depan, atau gambar sisi depan dan belakang" }
        val inputs = uris.toList()
        inputs.forEach(::requireImageUri)
        // Retain ownership across withContext's prompt-cancellation return boundary.
        var result: File? = null
        try {
            return withContext(Dispatchers.IO) {
                runInterruptible {
                    val staging = newStagingDirectory()
                    var sheet: Bitmap? = null
                    try {
                        val bitmapSheet = Bitmap.createBitmap(IdCardLayout.WIDTH, IdCardLayout.HEIGHT, Bitmap.Config.ARGB_8888)
                        sheet = bitmapSheet
                        val canvas = Canvas(bitmapSheet)
                        canvas.drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        inputs.forEachIndexed { index, uri ->
                            ImageProcessor.checkInterrupted()
                            val source = File(staging, "face-$index.jpg")
                            processor.ingest(uri, source)
                            val crop = processor.detectCorners(source)
                            val face = processor.render(source, crop, 0, PageFilter.ORIGINAL, 1800)
                            try {
                                ImageProcessor.checkInterrupted()
                                canvas.drawBitmap(face, null, IdCardLayout.fit(face.width, face.height, index, inputs.size), paint)
                            } finally {
                                face.recycle()
                                source.delete()
                            }
                        }
                        ImageProcessor.checkInterrupted()
                        val partial = File(staging, "sheet.part")
                        partial.outputStream().use { stream ->
                            if (!bitmapSheet.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                                throw IOException("Tidak dapat mengodekan lembar kartu identitas")
                            }
                            stream.fd.sync()
                        }
                        ImageProcessor.checkInterrupted()
                        val output = File(root, "id-card-${UUID.randomUUID()}.jpg")
                        result = output
                        if (!partial.renameTo(output)) throw IOException("Tidak dapat menyimpan lembar kartu identitas")
                        output
                    } finally {
                        sheet?.recycle()
                        staging.deleteRecursively()
                    }
                }
            }
        } catch (failure: Throwable) {
            result?.delete()
            throw failure
        }
    }

    private fun newStagingDirectory(): File {
        val directory = File(root, "stage-${UUID.randomUUID()}")
        if (!directory.mkdirs()) throw IOException("Tidak dapat menyiapkan pemrosesan gambar lokal")
        return directory
    }

    private fun requireImageUri(uri: Uri) {
        require(uri.scheme == "content" || uri.scheme == "file") { "Pilih gambar dari perangkat ini" }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR code"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        else -> "Kode batang"
    }
}

/** 10 px/mm keeps ID-1 and A4 dimensions exact without exceeding the 3000 px source cap. */
internal object IdCardLayout {
    const val WIDTH = 2100
    const val HEIGHT = 2970
    private const val CARD_WIDTH = 856f
    private const val CARD_HEIGHT = 540f
    private const val GAP = 200f

    fun fit(width: Int, height: Int, index: Int, count: Int): RectF {
        require(width > 0 && height > 0 && count in 1..2 && index in 0 until count)
        val groupHeight = count * CARD_HEIGHT + (count - 1) * GAP
        val slotTop = (HEIGHT - groupHeight) / 2f + index * (CARD_HEIGHT + GAP)
        val scale = min(CARD_WIDTH / width, CARD_HEIGHT / height)
        val fittedWidth = width * scale
        val fittedHeight = height * scale
        val left = (WIDTH - fittedWidth) / 2f
        val top = slotTop + (CARD_HEIGHT - fittedHeight) / 2f
        return RectF(left, top, left + fittedWidth, top + fittedHeight)
    }
}

/** Only whole, hierarchical HTTP(S) URLs are eligible for an explicit website action. */
internal fun websiteUri(text: String): Uri? {
    val value = text.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    return try {
        val parsed = URI(value)
        if ((!parsed.scheme.equals("https", ignoreCase = true) && !parsed.scheme.equals("http", ignoreCase = true)) ||
            parsed.isOpaque || parsed.host.isNullOrEmpty() || parsed.rawUserInfo != null ||
            parsed.port !in -1..65535
        ) null else Uri.parse(value)
    } catch (_: URISyntaxException) {
        null
    }
}
