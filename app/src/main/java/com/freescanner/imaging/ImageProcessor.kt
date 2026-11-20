package com.freescanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanPage
import com.freescanner.annotations.AnnotationRenderer
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Blocking, single-page operations. Call with runInterruptible(IO); caller owns returned bitmaps. */
class ImageProcessor(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /** Copies at most 50 MiB, samples before decoding, applies EXIF, and writes metadata-free JPEG. */
    fun ingest(uri: Uri, destination: File) {
        checkInterrupted()
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Tujuan gambar tidak valid")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Tidak dapat membuat tujuan gambar")
        val input = File.createTempFile("image-input-", ".part", parent)
        var stagedOutput: File? = null
        var bitmap: Bitmap? = null
        try {
            val output = File.createTempFile("image-output-", ".part", parent)
            stagedOutput = output
            resolver.openInputStream(uri)?.use { source ->
                input.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        checkInterrupted()
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_INPUT_BYTES) throw IOException("Gambar melebihi batas impor 50 MiB")
                        sink.write(buffer, 0, read)
                    }
                    if (count == 0L) throw IOException("Gambar yang dipilih kosong")
                }
            } ?: throw IOException("Tidak dapat membuka gambar yang dipilih")
            bitmap = decodeBounded(input, MAX_SOURCE_DIMENSION)
            val orientation = try {
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) {
                // Some decodable formats do not support EXIF.
                ExifInterface.ORIENTATION_NORMAL
            }
            val matrix = orientationMatrix(orientation)
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) bitmap.recycle()
                bitmap = oriented
            }
            if (bitmap.hasAlpha()) {
                val transparent = bitmap
                bitmap = Bitmap.createBitmap(transparent.width, transparent.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(transparent, 0f, 0f, null)
                    }
                } finally {
                    transparent.recycle()
                }
            }
            checkInterrupted()
            output.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IOException("Tidak dapat mengodekan gambar")
                stream.fd.sync()
            }
            checkInterrupted()
            if (!output.renameTo(destination)) throw IOException("Tidak dapat menyimpan gambar yang diimpor")
        } finally {
            bitmap?.recycle()
            input.delete()
            stagedOutput?.delete()
        }
    }

    /** Compose overlays once after crop/filter/rotation; the caller owns the mutable result. */
    fun renderPage(source: File, page: ScanPage, maxDimension: Int = 2400): Bitmap {
        val bitmap = render(source, page.crop, page.rotation, page.filter, maxDimension)
        try {
            if (page.annotations.isNotEmpty()) AnnotationRenderer.draw(bitmap, page.annotations)
            checkInterrupted()
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    /** A conservative bright-paper detector; weak contrast or ambiguous backgrounds return Full. */
    fun detectCorners(source: File): Crop {
        val bitmap = decodeBounded(source, 512)
        try {
            return PaperCornerDetector.detect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Opposing-edge maxima define width/height, rounded to pixels and proportionally capped at
     * maxDimension (1..3000). No upscaling. Rotation is clockwise degrees, a multiple of 90.
     */
    fun render(source: File, crop: Crop, rotation: Int, filter: PageFilter, maxDimension: Int = 2400): Bitmap {
        require(crop.isValid()) { "Area pangkas harus berupa segi empat cembung searah jarum jam di dalam gambar" }
        require(rotation % 90 == 0) { "Sudut putar harus kelipatan 90 derajat" }
        require(maxDimension in 1..MAX_SOURCE_DIMENSION) { "Dimensi render harus antara 1 dan 3000" }
        val cropWidth = crop.corners.maxOf { it.x } - crop.corners.minOf { it.x }
        val cropHeight = crop.corners.maxOf { it.y } - crop.corners.minOf { it.y }
        val decodeSize = (maxDimension / min(cropWidth, cropHeight)).roundToInt().coerceIn(1, MAX_SOURCE_DIMENSION)
        val original = decodeBounded(source, decodeSize)
        var result: Bitmap? = null
        try {
            checkInterrupted()
            val points = FloatArray(8)
            crop.corners.forEachIndexed { i, corner ->
                points[i * 2] = corner.x * original.width
                points[i * 2 + 1] = corner.y * original.height
            }
            fun distance(a: Int, b: Int): Float = hypot(points[a * 2] - points[b * 2], points[a * 2 + 1] - points[b * 2 + 1])
            val naturalWidth = max(distance(0, 1), distance(3, 2))
            val naturalHeight = max(distance(0, 3), distance(1, 2))
            val scale = min(1f, maxDimension / max(naturalWidth, naturalHeight))
            val width = max(1, (naturalWidth * scale).roundToInt())
            val height = max(1, (naturalHeight * scale).roundToInt())
            val matrix = Matrix()
            check(matrix.setPolyToPoly(points, 0, floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat()), 0, 4)) {
                "Tidak dapat meluruskan area pangkas ini"
            }
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            Canvas(result).apply {
                drawColor(Color.WHITE)
                drawBitmap(original, matrix, paint)
            }
            original.recycle()
            val degrees = ((rotation % 360) + 360) % 360
            if (degrees != 0) {
                // Bitmap.createBitmap(source, matrix) promises an immutable bitmap; filters and
                // overlays need mutable output. Render directly, without an extra mutable copy.
                val rotated = Bitmap.createBitmap(
                    if (degrees == 180) width else height,
                    if (degrees == 180) height else width, Bitmap.Config.ARGB_8888,
                )
                try {
                    Canvas(rotated).apply {
                        when (degrees) {
                            90 -> translate(height.toFloat(), 0f)
                            180 -> translate(width.toFloat(), height.toFloat())
                            270 -> translate(0f, width.toFloat())
                        }
                        rotate(degrees.toFloat())
                        paint.isFilterBitmap = false
                        drawBitmap(result, 0f, 0f, paint)
                    }
                } catch (failure: Throwable) {
                    rotated.recycle()
                    throw failure
                }
                result.recycle()
                result = rotated
            }
            applyFilter(result, filter)
            checkInterrupted()
            return result
        } catch (failure: Throwable) {
            result?.recycle()
            throw failure
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun applyFilter(bitmap: Bitmap, filter: PageFilter) {
        if (filter == PageFilter.ORIGINAL) return
        if (filter == PageFilter.BLACK_WHITE) {
            adaptiveThreshold(bitmap)
            return
        }
        val row = IntArray(bitmap.width)
        // A monotonic value curve scales all RGB channels equally, preserving hue/saturation.
        val valueCurve = ENHANCED_VALUES
        for (y in 0 until bitmap.height) {
            checkInterrupted()
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) {
                val pixel = row[x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                row[x] = if (filter == PageFilter.GRAYSCALE) {
                    val gray = luminance(pixel)
                    Color.rgb(gray, gray, gray)
                } else {
                    val value = max(r, max(g, b))
                    val target = valueCurve[value]
                    if (value == 0) Color.BLACK else Color.rgb(r * target / value, g * target / value, b * target / value)
                }
            }
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
    }

    /** Rolling box-mean threshold: one luminance byte/pixel, two rows; no full-size integral image. */
    private fun adaptiveThreshold(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val gray = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            checkInterrupted()
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) gray[y * width + x] = luminance(row[x]).toByte()
        }
        val radius = max(8, min(width, height) / 30)
        val columns = IntArray(width)
        fun addRow(y: Int, sign: Int) {
            for (x in 0 until width) columns[x] += sign * (gray[y * width + x].toInt() and 255)
        }
        for (y in 0..min(height - 1, radius)) addRow(y, 1)
        for (y in 0 until height) {
            checkInterrupted()
            if (y > 0) {
                if (y - radius - 1 >= 0) addRow(y - radius - 1, -1)
                if (y + radius < height) addRow(y + radius, 1)
            }
            val rows = min(height - 1, y + radius) - max(0, y - radius) + 1
            var sum = 0
            for (x in 0..min(width - 1, radius)) sum += columns[x]
            for (x in 0 until width) {
                if (x > 0) {
                    if (x - radius - 1 >= 0) sum -= columns[x - radius - 1]
                    if (x + radius < width) sum += columns[x + radius]
                }
                val area = rows * (min(width - 1, x + radius) - max(0, x - radius) + 1)
                val value = gray[y * width + x].toInt() and 255
                row[x] = if (value < sum / area - 12) Color.BLACK else Color.WHITE
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun decodeBounded(source: File, maximum: Int): Bitmap {
        checkInterrupted()
        if (source.length() !in 1..MAX_INPUT_BYTES) throw IOException("Gambar kosong atau melebihi batas 50 MiB")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > 200_000 || height > 200_000 || width.toLong() * height > 1_000_000_000L) {
            throw IOException("Dimensi gambar tidak didukung atau terlalu besar")
        }
        var sample = 1
        while ((max(width, height).toLong() + sample - 1) / sample > maximum) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = source.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Berkas yang dipilih tidak dapat dibaca sebagai gambar")
        try {
            checkInterrupted()
            // Codec sampling rules vary; enforce the bound even for unusual decoders.
            if (max(decoded.width, decoded.height) > maximum) {
                val scale = maximum.toFloat() / max(decoded.width, decoded.height)
                val scaled = Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true)
                if (scaled !== decoded) decoded.recycle()
                return scaled
            }
            return decoded
        } catch (failure: Throwable) {
            decoded.recycle()
            throw failure
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        val transform = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            else -> null
        }
        if (transform != null) setValues(transform)
    }

    companion object {
        private const val MAX_INPUT_BYTES = 50L * 1024 * 1024
        private const val MAX_SOURCE_DIMENSION = 3000
        private val ENHANCED_VALUES = IntArray(256) { value ->
            (((value - 8).coerceIn(0, 237) / 237.0).pow(.88) * 255).roundToInt()
        }
        internal fun luminance(pixel: Int): Int = (77 * Color.red(pixel) + 150 * Color.green(pixel) + 29 * Color.blue(pixel)) shr 8
        internal fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Pemrosesan gambar dibatalkan")
        }
    }
}
