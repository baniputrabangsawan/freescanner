package com.freescanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.model.Corner
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class ImageProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val processor = ImageProcessor(context)
    private lateinit var directory: File

    @Before fun prepare() {
        directory = File(context.cacheDir, "imaging-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    @Test fun identityAndClockwiseQuarterTurns() {
        val source = saveQuadrants()
        withRender(source) { bitmap ->
            assertEquals(120, bitmap.width)
            assertEquals(80, bitmap.height)
            assertCorners(bitmap, intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))
        }
        withRender(source, rotation = 90) { bitmap ->
            assertEquals(80, bitmap.width)
            assertEquals(120, bitmap.height)
            assertCorners(bitmap, intArrayOf(Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE))
        }
        withRender(source, rotation = 180) { bitmap ->
            assertCorners(bitmap, intArrayOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN))
        }
        withRender(source, rotation = -90) { bitmap ->
            assertCorners(bitmap, intArrayOf(Color.GREEN, Color.BLUE, Color.YELLOW, Color.RED))
        }
        withRender(source, maximum = 60) { bitmap ->
            assertEquals(60, bitmap.width)
            assertEquals(40, bitmap.height)
        }
    }

    @Test fun perspectiveMapsAllFourSourceCornersToRectangle() {
        val crop = Crop(listOf(Corner(.2f, .125f), Corner(.85f, .25f), Corner(.775f, .875f), Corner(.1f, .75f)))
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        crop.corners.forEachIndexed { i, point ->
            canvas.drawCircle(point.x * 200, point.y * 160, 14f, Paint().apply { color = colors[i] })
        }
        val source = save(bitmap)
        withRender(source, crop = crop) { output ->
            assertEquals(136, output.width)
            assertEquals(102, output.height)
            assertColor(Color.RED, output.getPixel(3, 3))
            assertColor(Color.GREEN, output.getPixel(output.width - 4, 3))
            assertColor(Color.BLUE, output.getPixel(output.width - 4, output.height - 4))
            assertColor(Color.YELLOW, output.getPixel(3, output.height - 4))
        }
    }

    @Test fun filtersRetainHueAndProduceActualGrayAndBinaryPixels() {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(80, 40, 20))
        val source = save(bitmap)
        withRender(source, filter = PageFilter.ENHANCED) { output ->
            val pixel = output.getPixel(60, 40)
            assertTrue(Color.red(pixel) > Color.green(pixel))
            assertTrue(Color.green(pixel) > Color.blue(pixel))
            assertTrue(abs(Color.red(pixel) - 2 * Color.green(pixel)) <= 2)
            assertTrue(abs(Color.green(pixel) - 2 * Color.blue(pixel)) <= 2)
            assertTrue(pixel != Color.rgb(80, 40, 20))
        }
        withRender(source, filter = PageFilter.GRAYSCALE) { output ->
            val pixel = output.getPixel(60, 40)
            assertEquals(Color.red(pixel), Color.green(pixel))
            assertEquals(Color.green(pixel), Color.blue(pixel))
            assertEquals(49, Color.red(pixel))
        }
        val shadow = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        for (x in 0 until shadow.width) {
            val value = 90 + x * 140 / shadow.width
            for (y in 0 until shadow.height) shadow.setPixel(x, y, Color.rgb(value, value, value))
        }
        Canvas(shadow).drawRect(10f, 70f, 230f, 74f, Paint().apply { color = Color.rgb(25, 25, 25) })
        val shadowSource = save(shadow)
        withRender(shadowSource, filter = PageFilter.BLACK_WHITE) { output ->
            var black = 0
            var white = 0
            for (y in 0 until output.height) for (x in 0 until output.width) {
                val pixel = output.getPixel(x, y)
                assertTrue(pixel == Color.BLACK || pixel == Color.WHITE)
                if (pixel == Color.BLACK) black++ else white++
            }
            assertTrue(black > 500)
            assertTrue(white > 30_000)
            assertEquals(Color.WHITE, output.getPixel(15, 30))
            assertEquals(Color.WHITE, output.getPixel(220, 30))
            assertEquals(Color.BLACK, output.getPixel(15, 71))
            assertEquals(Color.BLACK, output.getPixel(220, 71))
        }
    }

    @Test fun ingestHonorsEveryExifRotationAndMirror() {
        val expectations = listOf(
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            intArrayOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            intArrayOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            intArrayOf(Color.RED, Color.YELLOW, Color.BLUE, Color.GREEN),
            intArrayOf(Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE),
            intArrayOf(Color.BLUE, Color.GREEN, Color.RED, Color.YELLOW),
            intArrayOf(Color.GREEN, Color.BLUE, Color.YELLOW, Color.RED),
        )
        for (orientation in 1..8) {
            val source = saveQuadrants(Bitmap.CompressFormat.JPEG)
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val output = File(directory, "normalized-$orientation.jpg")
            processor.ingest(Uri.fromFile(source), output)
            val decoded = BitmapFactory.decodeFile(output.path)
            try {
                assertEquals(if (orientation >= 5) 80 else 120, decoded.width)
                assertEquals(if (orientation >= 5) 120 else 80, decoded.height)
                assertCorners(decoded, expectations[orientation - 1], 35)
                assertTrue(ExifInterface(output).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1)
            } finally {
                decoded.recycle()
            }
        }
    }

    @Test fun detectsClearPaperAndDeclinesAmbiguousScenes() {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(45, 45, 45))
            drawPath(Path().apply {
                moveTo(70f, 35f); lineTo(335f, 55f); lineTo(320f, 270f); lineTo(50f, 250f); close()
            }, Paint().apply { color = Color.WHITE })
        }
        val detected = processor.detectCorners(save(bitmap))
        assertTrue(detected.isValid())
        assertFalse(detected == Crop.Full)
        val expected = listOf(Corner(.175f, .117f), Corner(.8375f, .183f), Corner(.8f, .9f), Corner(.125f, .833f))
        detected.corners.zip(expected).forEach { (actual, point) ->
            assertTrue(abs(actual.x - point.x) < .025f)
            assertTrue(abs(actual.y - point.y) < .025f)
        }
        val blank = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertEquals(Crop.Full, processor.detectCorners(save(blank)))
        val photo = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        assertEquals(Crop.Full, processor.detectCorners(save(photo)))
        val ambiguous = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(ambiguous).apply {
            drawColor(Color.rgb(40, 40, 40))
            val paint = Paint().apply { color = Color.WHITE }
            drawRect(25f, 35f, 180f, 265f, paint)
            drawRect(220f, 35f, 375f, 265f, paint)
        }
        assertEquals(Crop.Full, processor.detectCorners(save(ambiguous)))
    }

    @Test fun rotatedPaperCropsInsideThePageWithoutDeskBleed() {
        // A 800x600 page on a brown desk, photographed at an angle, through the real import path
        // (ingest -> JPEG -> detect -> render). Before the corner rewrite the min/max (x+y) rule
        // swapped width/height past ~45 degrees and failed outright near 135; a whole-pixel outline
        // also left a strip of desk inside the crop once refinement existed.
        val width = 1600
        val height = 1200
        for (degrees in listOf(0, 25, 55, 90, 135)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(58, 46, 38))
                save()
                rotate(degrees.toFloat(), width / 2f, height / 2f)
                drawRect(
                    width / 2f - 400f,
                    height / 2f - 300f,
                    width / 2f + 400f,
                    height / 2f + 300f,
                    Paint().apply { color = Color.rgb(238, 236, 229) },
                )
                restore()
            }
            val normalized = File(directory, "rotated-$degrees.jpg")
            processor.ingest(Uri.fromFile(save(bitmap)), normalized)

            val detected = processor.detectCorners(normalized)
            assertTrue("rot=$degrees should be detected, got $detected", detected != Crop.Full)
            assertTrue("rot=$degrees quad must stay valid", detected.isValid())

            // Ground truth corners, rotated about the page centre, in normalized image space.
            val radians = Math.toRadians(degrees.toDouble())
            val cos = Math.cos(radians).toFloat()
            val sin = Math.sin(radians).toFloat()
            val truth = listOf(-400f to -300f, 400f to -300f, 400f to 300f, -400f to 300f).map { (x, y) ->
                Corner(
                    (width / 2f + x * cos - y * sin) / width,
                    (height / 2f + x * sin + y * cos) / height,
                )
            }
            val worst = truth.maxOf { point ->
                detected.corners.minOf { found ->
                    hypot((found.x - point.x) * width, (found.y - point.y) * height)
                }
            }
            assertTrue("rot=$degrees corner error ${"%.1f".format(worst)} px", worst < 12f)

            withRender(normalized, crop = detected, maximum = 900) { rendered ->
                // The desk is warm and dark; the page and its shading are neutral and bright.
                var desk = 0
                val pixels = IntArray(rendered.width * rendered.height)
                rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                for (pixel in pixels) {
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val luminance = (77 * r + 150 * g + 29 * b) shr 8
                    if (luminance < 120 && r - b >= 12) desk++
                }
                val fraction = desk.toDouble() / pixels.size
                assertTrue("rot=$degrees desk inside crop ${"%.5f".format(fraction)}", fraction < .001)
            }
        }
    }

    @Test fun ingestSamplesLargeImagesAndFlattensTransparencyOntoWhite() {
        val large = Bitmap.createBitmap(4000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val normalized = File(directory, "bounded.jpg")
        processor.ingest(Uri.fromFile(save(large)), normalized)
        val decoded = BitmapFactory.decodeFile(normalized.path)
        try {
            assertEquals(2000, decoded.width)
            assertEquals(500, decoded.height)
        } finally {
            decoded.recycle()
        }
        val transparent = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val flattened = File(directory, "opaque.jpg")
        processor.ingest(Uri.fromFile(save(transparent)), flattened)
        val opaque = BitmapFactory.decodeFile(flattened.path)
        try {
            assertColor(Color.WHITE, opaque.getPixel(40, 40))
        } finally {
            opaque.recycle()
        }
    }

    @Test fun rejectsUnreadableInputWithoutOverwritingDestination() {
        val source = File(directory, "invalid").apply { writeText("Not an image") }
        val output = File(directory, "existing.jpg").apply { writeText("preserve") }
        try {
            processor.ingest(Uri.fromFile(source), output)
            throw AssertionError("Invalid image accepted")
        } catch (_: IOException) {
            assertEquals("preserve", output.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("image-") })
        }
    }

    @Test fun rejectsInputsAboveByteLimitAndRemovesStagingFiles() {
        val source = File(directory, "oversized")
        RandomAccessFile(source, "rw").use { it.setLength(50L * 1024 * 1024 + 1) }
        val output = File(directory, "rejected.jpg")
        try {
            processor.ingest(Uri.fromFile(source), output)
            throw AssertionError("Oversized input accepted")
        } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("50 MiB"))
            assertFalse(output.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("image-") })
        }
    }

    private fun saveQuadrants(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, 60f, 40f, Paint().apply { color = Color.RED })
            drawRect(60f, 0f, 120f, 40f, Paint().apply { color = Color.GREEN })
            drawRect(60f, 40f, 120f, 80f, Paint().apply { color = Color.BLUE })
            drawRect(0f, 40f, 60f, 80f, Paint().apply { color = Color.YELLOW })
        }
        return save(bitmap, format)
    }

    private fun save(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val file = File.createTempFile("fixture-", if (format == Bitmap.CompressFormat.JPEG) ".jpg" else ".png", directory)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun withRender(source: File, crop: Crop = Crop.Full, rotation: Int = 0, filter: PageFilter = PageFilter.ORIGINAL, maximum: Int = 2400, block: (Bitmap) -> Unit) {
        val bitmap = processor.render(source, crop, rotation, filter, maximum)
        try { block(bitmap) } finally { bitmap.recycle() }
    }

    private fun assertCorners(bitmap: Bitmap, expected: IntArray, tolerance: Int = 0) {
        val points = listOf(1 to 1, 3 to 1, 3 to 3, 1 to 3)
        points.forEachIndexed { index, (x, y) ->
            assertColor(expected[index], bitmap.getPixel(bitmap.width * x / 4, bitmap.height * y / 4), tolerance)
        }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 0) {
        assertTrue("Expected red ${Color.red(expected)}, got ${Color.red(actual)}", abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue("Expected green ${Color.green(expected)}, got ${Color.green(actual)}", abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue("Expected blue ${Color.blue(expected)}, got ${Color.blue(actual)}", abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }
}
