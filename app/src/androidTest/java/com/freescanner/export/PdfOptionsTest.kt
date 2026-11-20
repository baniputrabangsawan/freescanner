package com.freescanner.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.data.DocumentRepository
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.PdfOptions
import com.freescanner.model.PdfPageSize
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfOptionsTest {
    @Test fun selectedPagesA4MarginsAndNumbersRenderOutsideImage(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "pdf-options-$id").apply { check(mkdirs()) }
        val databaseName = "pdf-options-$id.db"
        val processor = ImageProcessor(context)
        val repository = DocumentRepository(context, processor, databaseName, root)
        val exports = mutableListOf<File>()
        try {
            val sources = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
                val image = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                val file = File(root, "$index.png")
                file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
                Uri.fromFile(file)
            }
            val draft = repository.addPages(repository.createDraft().id, sources, detectEdges = false)
            val saved = repository.saveDraft(draft.id, "PDF preferences")
            val selected = saved.copy(pages = listOf(saved.pages.last()))
            val exporter = DocumentExporter(context, repository, processor)
            val file = exporter.pdf(selected, PdfOptions(PdfPageSize.A4, margin = true, pageNumbers = true)).also { exports.add(it) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                        val output = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertEquals(Color.WHITE, output.getPixel(5, 5))
                            val middle = output.getPixel(page.width / 2, page.height / 2)
                            assertTrue(Color.blue(middle) > 200 && Color.red(middle) < 25)
                            var numberPixels = 0
                            for (y in page.height - 24 until page.height - 5) {
                                for (x in page.width / 2 - 35 until page.width / 2 + 35) {
                                    if (Color.red(output.getPixel(x, y)) < 180) numberPixels++
                                }
                            }
                            assertTrue("Page number must render below the image", numberPixels > 5)
                        } finally { output.recycle() }
                    }
                }
            }
            val landscape = selected.copy(pages = selected.pages.map { it.copy(rotation = 90) })
            val letter = exporter.pdf(landscape, PdfOptions(PdfPageSize.LETTER, margin = false)).also { exports.add(it) }
            ParcelFileDescriptor.open(letter, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.openPage(0).use { page ->
                    assertEquals(792, page.width); assertEquals(612, page.height)
                } }
            }
            assertEquals(saved, repository.getDocument(saved.id))
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
            exports.forEach { it.parentFile?.deleteRecursively() }
            root.deleteRecursively()
        }
    }
}
