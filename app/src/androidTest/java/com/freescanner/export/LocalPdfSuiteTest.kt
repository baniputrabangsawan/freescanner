package com.freescanner.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.CancellationSignal
import android.print.PageRange
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.data.DocumentRepository
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.Corner
import com.freescanner.model.ExportQuality
import com.freescanner.model.OcrLine
import com.freescanner.model.OcrText
import com.freescanner.model.PageAnnotation
import com.freescanner.model.PageKind
import com.freescanner.model.PdfOptions
import com.freescanner.model.PdfPageSize
import com.freescanner.model.ScanDocument
import com.freescanner.pdf.LongImageLayout
import com.freescanner.pdf.PdfImporter
import com.freescanner.printing.PdfPrintAdapter
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPdfSuiteTest {
    @Test fun aesPasswordAndInvisibleLatinTextAreReal(): Unit = fixture { f ->
        val page = f.document.pages.first().copy(text = OcrText("Résumé Indonesia 123", listOf(OcrLine("Résumé Indonesia 123", .1f, .2f, .9f, .3f))))
        val snapshot = f.document.copy(pages = listOf(page))
        val output = f.keep(f.exporter.pdf(snapshot, PdfOptions(password = "test-only-π")))
        try { PDDocument.load(output).use { fail("Encrypted PDF opened without a password") } } catch (_: InvalidPasswordException) { }
        PDDocument.load(output, "test-only-π").use { pdf ->
            assertTrue(pdf.isEncrypted)
            assertEquals(256, pdf.encryption.length)
            assertEquals(COSName.getPDFName("AESV3"), pdf.encryption.stdCryptFilterDictionary.cryptFilterMethod)
            assertTrue(PDFTextStripper().getText(pdf).contains("Résumé Indonesia 123"))
        }
        val imageOnly = f.keep(f.exporter.pdf(snapshot, PdfOptions(searchableText = false)))
        PDDocument.load(imageOnly).use { assertTrue(PDFTextStripper().getText(it).isBlank()) }
        assertEquals(f.document, f.repository.getDocument(f.document.id))
    }

    @Test fun unsupportedGlyphFailsActionablyRatherThanLosingText(): Unit = fixture { f ->
        val snapshot = f.document.copy(pages = listOf(f.document.pages.first().copy(text = OcrText("Test 漢字"))))
        try { f.keep(f.exporter.pdf(snapshot)); fail("Unsupported glyph should not disappear silently") } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("TXT/DOCX"))
        }
        val imageOnly = f.keep(f.exporter.pdf(snapshot, PdfOptions(searchableText = false)))
        assertTrue(imageOnly.length() > 0)
    }

    @Test fun separatePdfsAndLongImageFollowDocumentOrderAndKeepAnnotations(): Unit = fixture { f ->
        val first = f.document.pages.first().copy(annotations = listOf(PageAnnotation.Ink(
            listOf(Corner(.1f, .5f), Corner(.9f, .5f)), Color.BLACK, .1f)))
        val snapshot = f.document.copy(pages = listOf(first) + f.document.pages.drop(1))
        val selected = linkedSetOf(snapshot.pages.last().id, first.id)
        val outputs = f.exporter.separatePdfs(snapshot, selected, PdfOptions(margin = false)).onEach { f.keep(it) }
        assertEquals(listOf("Synthetic-001.pdf", "Synthetic-003.pdf"), outputs.map { it.name })
        outputs.forEach { file -> PDDocument.load(file).use { assertEquals(1, it.numberOfPages) } }
        val combined = f.keep(f.exporter.longImage(snapshot, selected, ExportQuality.SMALL))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(combined.path))
        try {
            assertTrue(bitmap.height > bitmap.width)
            val red = bitmap.getPixel(bitmap.width / 2, bitmap.height / 8)
            val blue = bitmap.getPixel(bitmap.width / 2, bitmap.height * 7 / 8)
            assertTrue(Color.red(red) > 180 && Color.blue(red) < 80)
            assertTrue(Color.blue(blue) > 180 && Color.red(blue) < 80)
            val ink = bitmap.getPixel(bitmap.width / 2, bitmap.height / 4)
            assertTrue(Color.red(ink) < 80 && Color.blue(ink) < 80)
        } finally { bitmap.recycle() }
        assertEquals(f.document, f.repository.getDocument(f.document.id))
    }

    @Test fun idCardSheetIgnoresGeometryThatWouldChangePhysicalSize(): Unit = fixture { f ->
        val snapshot = f.document.copy(pages = listOf(f.document.pages.first().copy(kind = PageKind.ID_CARD)))
        val file = f.keep(f.exporter.pdf(snapshot, PdfOptions(PdfPageSize.LETTER, margin = true, pageNumbers = true)))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.openPage(0).use { page ->
                assertEquals(595, page.width); assertEquals(842, page.height)
            } }
        }
        PDDocument.load(file).use { assertTrue(PDFTextStripper().getText(it).isBlank()) }
    }

    @Test fun smallQualityChangesEmbeddedImageResolutionAndCompression(): Unit = fixture { f ->
        val snapshot = f.document.copy(pages = listOf(f.document.pages.first()))
        val high = f.keep(f.exporter.pdf(snapshot, PdfOptions(quality = ExportQuality.HIGH)))
        val small = f.keep(f.exporter.pdf(snapshot, PdfOptions(quality = ExportQuality.SMALL)))
        PDDocument.load(high).use { a -> PDDocument.load(small).use { b ->
            val highImage = a.getPage(0).resources.xObjectNames.first().let { a.getPage(0).resources.getXObject(it) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject }
            val smallImage = b.getPage(0).resources.xObjectNames.first().let { b.getPage(0).resources.getXObject(it) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject }
            assertTrue(highImage.height > smallImage.height)
            assertTrue(smallImage.height <= 1200)
            assertEquals(COSName.DCT_DECODE, smallImage.cosObject.getDictionaryObject(COSName.FILTER))
        } }
        assertTrue(small.length() < high.length())
    }

    @Test fun importerRetainsDurablePagesWhenLaterCallbackFailsAndRemovesTemps(): Unit = fixture { f ->
        val pdf = f.keep(f.exporter.pdf(f.document, PdfOptions(searchableText = false)))
        val uri = FileProvider.getUriForFile(f.context, "${f.context.packageName}.exports", pdf)
        val before = f.context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("pdf-import-") }.map { it.name }.toSet()
        val durable = File(f.root, "durable.jpg")
        var callbackFile: File? = null
        var count = 0
        try {
            PdfImporter(f.context).importPages(uri) { file, index, total ->
                assertEquals(3, total)
                assertEquals(count++, index)
                assertTrue(file.isFile)
                callbackFile = file
                if (index == 0) file.copyTo(durable) else throw IOException("Synthetic callback failure")
            }
            fail("Callback failure should propagate")
        } catch (failure: IOException) { assertEquals("Synthetic callback failure", failure.message) }
        assertEquals(2, count)
        assertTrue(durable.isFile)
        assertFalse(requireNotNull(callbackFile).exists())
        val after = f.context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("pdf-import-") }.map { it.name }.toSet()
        assertEquals(before, after)
        assertEquals(f.document, f.repository.getDocument(f.document.id))
    }

    @Test fun longImageLimitsNeverDropPagesAndRejectUnreadableSelections() {
        val plan = LongImageLayout.calculate(List(20) { 1000 to 1400 }, 2400)
        assertEquals(20, plan.heights.size)
        assertEquals(plan.height, plan.heights.sum())
        assertTrue(plan.width.toLong() * plan.height <= LongImageLayout.MAX_PIXELS)
        assertTrue(plan.height <= LongImageLayout.MAX_SIDE)
        try { LongImageLayout.calculate(List(1000) { 100 to 1000 }, 2400); fail("Unreadable output should be rejected") }
        catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("lebih sedikit halaman")) }
    }

    @Test fun printSubsetProducesOnlyRequestedImageAndCancellationWritesNothing(): Unit = fixture { f ->
        val input = f.keep(f.exporter.pdf(f.document, PdfOptions(margin = false)))
        val output = File(f.root, "print-subset.pdf")
        output.outputStream().use {
            PdfPrintAdapter.writeSelected(input, 3, listOf(2), it, CancellationSignal())
        }
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        assertTrue(Color.blue(color) > 180 && Color.red(color) < 80)
                    } finally { bitmap.recycle() }
                }
            }
        }
        val bytes = java.io.ByteArrayOutputStream()
        val signal = CancellationSignal().apply { cancel() }
        try {
            PdfPrintAdapter.writeSelected(input, 3, listOf(0), bytes, signal)
            fail("Cancelled printing must not write pages")
        } catch (_: android.os.OperationCanceledException) { }
        assertEquals(0, bytes.size())
    }

    @Test fun overlappingOutOfBoundsAndDisjointPrintRangesDoNotPrintEverything() {
        val indices = PdfPrintAdapter.selectedIndices(arrayOf(PageRange(5, 99), PageRange(1, 2), PageRange(2, 2)), 7)
        assertEquals(listOf(1, 2, 5, 6), indices)
        assertEquals(listOf(PageRange(1, 2), PageRange(5, 6)), PdfPrintAdapter.compactRanges(indices).toList())
        assertEquals((0..6).toList(), PdfPrintAdapter.selectedIndices(arrayOf(PageRange.ALL_PAGES), 7))
        assertTrue(PdfPrintAdapter.selectedIndices(arrayOf(PageRange(10, 20)), 7).isEmpty())
    }

    private fun fixture(block: suspend (Fixture) -> Unit): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "local-pdf-test-$id").apply { check(mkdirs()) }
        val database = "local-pdf-test-$id.db"
        val processor = ImageProcessor(context)
        val repository = DocumentRepository(context, processor, database, root)
        val exports = mutableListOf<File>()
        try {
            val uris = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
                val file = File(root, "$index.png")
                val image = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
                try { image.eraseColor(color); file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { image.recycle() }
                Uri.fromFile(file)
            }
            val draft = repository.addPages(repository.createDraft().id, uris, detectEdges = false)
            val document = repository.saveDraft(draft.id, "Synthetic")
            block(Fixture(context, root, repository, DocumentExporter(context, repository, processor), document, exports))
        } finally {
            repository.close()
            context.deleteDatabase(database)
            exports.mapNotNull { it.parentFile }.distinct().forEach { it.deleteRecursively() }
            root.deleteRecursively()
        }
    }

    private class Fixture(val context: Context, val root: File, val repository: DocumentRepository,
        val exporter: DocumentExporter, val document: ScanDocument, val outputs: MutableList<File>) {
        fun keep(file: File): File = file.also { outputs += it }
    }
}
