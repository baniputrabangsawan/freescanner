package com.freescanner.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.imaging.ImageProcessor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MediaToolsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tools = MediaTools(context, ImageProcessor(context))
    private lateinit var directory: File
    private val outputs = mutableListOf<File>()

    @Before fun prepare() {
        directory = File(context.cacheDir, "media-fixtures-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        outputs.forEach { it.delete() }
        directory.deleteRecursively()
    }

    @Test fun bundledScannerDecodesActualQrAndEanPixels(): Unit = runBlocking {
        val qr = saveQr()
        val qrBytes = qr.readBytes()
        val decoded = tools.scanCodes(Uri.fromFile(qr))
        assertTrue(decoded.toString(), decoded.contains(CodeResult("FreeScanner local QR", "QR code")))
        assertTrue(qrBytes.contentEquals(qr.readBytes()))
        val ean = saveEan13()
        val eanResults = tools.scanCodes(Uri.fromFile(ean))
        assertTrue(eanResults.toString(), eanResults.contains(CodeResult("5901234123457", "EAN-13")))
    }

    @Test fun emptyImageProducesNoInventedCode(): Unit = runBlocking {
        val image = solidImage("blank.png", 700, 500, Color.WHITE)
        assertTrue(tools.scanCodes(Uri.fromFile(image)).isEmpty())
    }

    @Test fun actualA4JpegPlacesFrontAndBackAtPhysicalId1Dimensions(): Unit = runBlocking {
        val front = solidImage("front.jpg", 856, 540, Color.RED, Bitmap.CompressFormat.JPEG)
        ExifInterface(front).apply {
            setAttribute(ExifInterface.TAG_ARTIST, "Synthetic private metadata")
            saveAttributes()
        }
        val back = solidImage("back.png", 856, 540, Color.BLUE)
        val originalFront = front.readBytes()
        val originalBack = back.readBytes()
        val output = tools.createIdCard(listOf(Uri.fromFile(front), Uri.fromFile(back)))
        outputs += output
        assertEquals(File(context.cacheDir, "media-tools").canonicalFile, output.parentFile?.canonicalFile)
        val sheet = requireNotNull(BitmapFactory.decodeFile(output.path))
        try {
            assertEquals(2100, sheet.width)
            assertEquals(2970, sheet.height)
            // 10 px/mm: 856 × 540 px is 85.6 × 54 mm on 210 × 297 mm A4.
            assertColoredBounds(sheet, Color.RED, 622, 845, 1478, 1385)
            assertColoredBounds(sheet, Color.BLUE, 622, 1585, 1478, 2125)
            assertColor(Color.WHITE, sheet.getPixel(1050, 1485))
        } finally {
            sheet.recycle()
        }
        assertTrue(originalFront.contentEquals(front.readBytes()))
        assertTrue(originalBack.contentEquals(back.readBytes()))
        assertNull(ExifInterface(output).getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(ExifInterface(output).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test fun singleSquareInputIsCenteredWithoutStretchingOrCropping(): Unit = runBlocking {
        val image = solidImage("square.png", 600, 600, Color.RED)
        val output = tools.createIdCard(listOf(Uri.fromFile(image)))
        outputs += output
        val sheet = requireNotNull(BitmapFactory.decodeFile(output.path))
        try {
            // A square fits inside the ID-1 slot as 54 × 54 mm, not an invented ID ratio.
            assertColoredBounds(sheet, Color.RED, 780, 1215, 1320, 1755)
            assertColor(Color.WHITE, sheet.getPixel(700, 1485))
            assertColor(Color.WHITE, sheet.getPixel(1050, 1100))
        } finally {
            sheet.recycle()
        }
    }

    @Test fun failedSecondInputRemovesOnlyOwnedStagingAndPreservesOriginal(): Unit = runBlocking {
        val image = solidImage("front.png", 856, 540, Color.RED)
        val original = image.readBytes()
        val root = File(context.cacheDir, "media-tools")
        val previous = root.list()?.toSet().orEmpty()
        try {
            tools.createIdCard(listOf(Uri.fromFile(image), Uri.fromFile(File(directory, "missing.png"))))
            fail("An unreadable back must not produce a partial ID sheet")
        } catch (_: IOException) {
            // Expected provider/file read failure, not a fake partially blank sheet.
        }
        assertEquals(previous, root.list()?.toSet().orEmpty())
        assertTrue(original.contentEquals(image.readBytes()))
    }

    @Test fun decodedCommandsAndMalformedUrlsAreNeverEligibleForWebsiteActions() {
        listOf(
            "javascript:alert(1)", "data:text/html,test", "intent://scan/#Intent;end",
            "file:///private/test", "https:example.com", "https:///example.com",
            "https://trusted.example@evil.example/", "https://example.com\\@evil.example",
            "https://example.com\n/path", "https://example.com:99999/", "not a URL",
        ).forEach { assertNull(it, websiteUri(it)) }
        assertEquals("https://example.com/path?q=value#part", websiteUri("https://example.com/path?q=value#part").toString())
        assertNotNull(websiteUri("HTTP://example.com/"))
    }

    @Test fun layoutMaintainsAspectRatioAndPhysicalScaleForBothSlots() {
        for (count in 1..2) {
            for (index in 0 until count) {
                val card = IdCardLayout.fit(856, 540, index, count)
                assertEquals(85.6f, card.width() / IdCardLayout.WIDTH * 210f, .001f)
                assertEquals(54f, card.height() / IdCardLayout.HEIGHT * 297f, .001f)
                assertEquals(1050f, card.centerX(), .001f)
                val portrait = IdCardLayout.fit(300, 800, index, count)
                assertEquals(300f / 800f, portrait.width() / portrait.height(), .0001f)
                assertTrue(portrait.left >= card.left && portrait.right <= card.right)
                assertTrue(portrait.top >= card.top && portrait.bottom <= card.bottom)
            }
        }
    }

    private fun solidImage(
        name: String,
        width: Int,
        height: Int,
        color: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            return save(name, bitmap, format)
        } finally {
            bitmap.recycle()
        }
    }

    private fun save(
        name: String,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): File = File(directory, name).also { file ->
        file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
    }

    private fun assertColoredBounds(bitmap: Bitmap, color: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        // JPEG boundary ringing is allowed, but physical placement must be within two pixels.
        assertColor(color, bitmap.getPixel(left + 2, centerY))
        assertColor(color, bitmap.getPixel(right - 3, centerY))
        assertColor(color, bitmap.getPixel(centerX, top + 2))
        assertColor(color, bitmap.getPixel(centerX, bottom - 3))
        assertColor(Color.WHITE, bitmap.getPixel(left - 3, centerY))
        assertColor(Color.WHITE, bitmap.getPixel(right + 2, centerY))
        assertColor(Color.WHITE, bitmap.getPixel(centerX, top - 3))
        assertColor(Color.WHITE, bitmap.getPixel(centerX, bottom + 2))
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue("Expected $expected, got $actual", abs(Color.red(expected) - Color.red(actual)) < 45)
        assertTrue("Expected $expected, got $actual", abs(Color.green(expected) - Color.green(actual)) < 45)
        assertTrue("Expected $expected, got $actual", abs(Color.blue(expected) - Color.blue(actual)) < 45)
    }

    private fun saveQr(): File {
        // Independently generated QR version 2/M fixture for the literal payload below.
        val rows = listOf(
            "1111111010001111001111111", "1000001000101110001000001",
            "1011101001110001001011101", "1011101011111010101011101",
            "1011101011100101101011101", "1000001010011110101000001",
            "1111111010101010101111111", "0000000010000011100000000",
            "1000101111111100011111001", "1010100101110101100011000",
            "1001101010011101000010100", "0110110000111000100110111",
            "1010111001010110011100101", "1110000100101011110011010",
            "0001001011011111010010100", "0010100110110100011110111",
            "1111011000001101111111011", "0000000011000110100010001",
            "1111111010000000101010000", "1000001000101010100010110",
            "1011101011010110111110111", "1011101000101000001100011",
            "1011101000111110011100010", "1000001001010101010100110",
            "1111111011101101001110111",
        )
        val module = 16
        val quiet = 4
        val size = (rows.size + quiet * 2) * module
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, bit ->
                    if (bit == '1') canvas.drawRect(
                        ((x + quiet) * module).toFloat(), ((y + quiet) * module).toFloat(),
                        ((x + quiet + 1) * module).toFloat(), ((y + quiet + 1) * module).toFloat(), paint,
                    )
                }
            }
            return save("qr.png", bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveEan13(): File {
        // EAN-13 5901234123457, including its real checksum digit and quiet zones.
        val left = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
        val even = arrayOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
        val digits = "901234"
        val parity = "LGGLLG" // Leading digit 5.
        val bits = buildString {
            append("101")
            digits.forEachIndexed { index, digit -> append(if (parity[index] == 'L') left[digit - '0'] else even[digit - '0']) }
            append("01010")
            "123457".forEach { digit -> left[digit - '0'].forEach { append(if (it == '0') '1' else '0') } }
            append("101")
        }
        val bitmap = Bitmap.createBitmap((bits.length + 24) * 6, 360, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            bits.forEachIndexed { index, bit ->
                if (bit == '1') canvas.drawRect(((index + 12) * 6).toFloat(), 30f, ((index + 13) * 6).toFloat(), 330f, paint)
            }
            return save("ean.png", bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
