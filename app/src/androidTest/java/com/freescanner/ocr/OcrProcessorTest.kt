package com.freescanner.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.data.DocumentRepository
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.ScanPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File
    private lateinit var databaseName: String
    private lateinit var repository: DocumentRepository
    private lateinit var ocr: OcrProcessor

    @Before fun prepare() {
        val id = UUID.randomUUID()
        directory = File(context.cacheDir, "owned-ocr-test-$id").apply { check(mkdirs()) }
        databaseName = "owned-ocr-test-$id.db"
        val processor = ImageProcessor(context)
        repository = DocumentRepository(context, processor, databaseName, File(directory, "storage"))
        ocr = OcrProcessor(repository, processor)
    }

    @After fun cleanup(): Unit = runBlocking {
        ocr.close()
        repository.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
        Unit
    }

    @Test fun realLatinTextHasNormalizedBoxesAndOnlySelectedSourceIsLeased(): Unit = runBlocking {
        val draft = repository.addPages(repository.createDraft().id, listOf(image("printed", true)), detectEdges = false)
        val document = repository.saveDraft(draft.id, "Owned Latin fixture")
        val page = document.pages.single()
        // An unrelated invalid source must not be inspected for a one-page OCR request.
        val snapshot = document.copy(pages = listOf(page, ScanPage("unselected", "never-read")))
        val result = ocr.recognize(snapshot, page.id)
        assertTrue(result.text.contains("LOCAL", ignoreCase = true))
        assertTrue(result.text.contains("Indonesia", ignoreCase = true))
        assertTrue(result.lines.isNotEmpty())
        assertTrue(result.lines.all { it.isValid() && it.right > it.left && it.bottom > it.top })
        assertTrue(result.lines.any { it.left in 0.04f..0.2f && it.top in 0.08f..0.4f })
        assertTrue(repository.sourceFile(page).isFile)
        // Recognition does not silently persist data; the integration owns explicit saves.
        assertEquals(null, repository.getDocument(document.id)?.pages?.single()?.text)
    }

    @Test fun aRealBlankPageReturnsEmptyTextRatherThanAnInventedFallback(): Unit = runBlocking {
        val draft = repository.addPages(repository.createDraft().id, listOf(image("blank", false)), detectEdges = false)
        val document = repository.saveDraft(draft.id, "Owned blank fixture")
        val result = ocr.recognize(document, document.pages.single().id)
        assertEquals("", result.text)
        assertTrue(result.lines.isEmpty())
    }

    private fun image(name: String, printed: Boolean): Uri {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            if (printed) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 76f }
                canvas.drawText("LOCAL SCANNER", 110f, 300f, paint)
                paint.textSize = 62f
                canvas.drawText("Hello Indonesia 2026", 110f, 480f, paint)
            }
            val file = File(directory, "$name.jpg")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
            return Uri.fromFile(file)
        } finally {
            bitmap.recycle()
        }
    }
}
