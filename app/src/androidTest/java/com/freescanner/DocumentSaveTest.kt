package com.freescanner

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentSaveTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun homeAndDraftSaveImagesToGalleryWhilePdfRequiresExplicitExport(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
        val repository = app.repository
        val title = "Galeri-${System.nanoTime()}"
        val inputs = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            File(app.cacheDir, "$title-$index.png").also { file ->
                val image = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(color)
                    file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }
        }
        val pickerCalls = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT <= 28) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT && intent.action != Intent.ACTION_OPEN_DOCUMENT_TREE) return null
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/pdf", intent.type)
                assertEquals("$title.pdf", intent.getStringExtra(Intent.EXTRA_TITLE))
                pickerCalls.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        var seedId: String? = null
        var documentId: String? = null
        var editingId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val draft = repository.createDraft().also { seedId = it.id }
            repository.addPages(draft.id, inputs.map(Uri::fromFile), detectEdges = false)
            val document = repository.saveDraft(draft.id, title)
            documentId = document.id
            seedId = null
            val originalBytes = document.pages.map { repository.sourceFile(it).readBytes() }
            instrumentation.addMonitor(monitor)
            lateinit var model: AppViewModel
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15_000) { model.state.value.busy == null && model.state.value.documents.any { it.id == document.id } }
            compose.onNodeWithText("Cari dokumen").performTextReplacement(title)
            compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
            document.pages.forEach { page -> compose.onNodeWithTag("document-page-${page.id}").assertIsDisplayed() }
            scenario.recreate()
            scenario.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.onNodeWithText("Simpan ke galeri").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { model.state.value.busy == null && galleryRows(app, title).size == 2 }
            assertEquals(0, pickerCalls.get())
            val first = galleryRows(app, title)
            first.forEachIndexed { index, uri -> assertGalleryImage(app, uri, if (index == 0) Color.RED else Color.BLUE, 120, 180) }
            assertEquals(document, repository.getDocument(document.id))
            document.pages.forEachIndexed { index, page -> assertTrue(originalBytes[index].contentEquals(repository.sourceFile(page).readBytes())) }

            compose.onNodeWithText("Ekspor").performClick()
            compose.onNodeWithText("Simpan PDF ke HP").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { pickerCalls.get() == 1 && model.state.value.busy == null }
            assertPdfOrder(File(app.cacheDir, "exports").walkTopDown().single { it.isFile && it.name == "$title.pdf" })
            compose.onNodeWithTag("document-page-${document.pages.last().id}").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft != null }
            editingId = model.state.value.draft!!.id
            assertEquals(document.pages.last().id, model.state.value.reviewPageId)
            compose.onNodeWithText("Putar").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft?.pages?.last()?.rotation == 90 }
            compose.onNodeWithText("Simpan ke galeri").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { model.state.value.busy == null && galleryRows(app, title).size == 4 }
            assertEquals(1, pickerCalls.get())
            val all = galleryRows(app, title)
            assertGalleryImage(app, all[0], Color.RED, 120, 180)
            assertGalleryImage(app, all[1], Color.BLUE, 120, 180)
            assertGalleryImage(app, all[2], Color.RED, 120, 180)
            assertGalleryImage(app, all[3], Color.BLUE, 180, 120)
            assertEquals(document, repository.getDocument(document.id))
            document.pages.forEachIndexed { index, page -> assertTrue(originalBytes[index].contentEquals(repository.sourceFile(page).readBytes())) }
            compose.onNodeWithContentDescription("Kembali").performClick()
            compose.onNodeWithText("Buang perubahan").performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft == null }
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            galleryRows(app, title).forEach { app.contentResolver.delete(it, null, null) }
            editingId?.let { repository.discardDraft(it) }
            seedId?.let { repository.discardDraft(it) }
            documentId?.let { repository.deleteDocument(it) }
            inputs.forEach { it.delete() }
            File(app.cacheDir, "exports").listFiles()?.filter { dir -> dir.listFiles()?.any { it.name.startsWith(title) } == true }
                ?.forEach { it.deleteRecursively() }
        }
    }

    private fun galleryRows(app: ScannerApplication, title: String): List<Uri> {
        val images = mutableListOf<Uri>()
        app.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("$title%"),
            "${MediaStore.Images.Media._ID} ASC",
        )!!.use { cursor ->
            while (cursor.moveToNext()) images += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        return images
    }

    private fun assertGalleryImage(app: ScannerApplication, uri: Uri, expected: Int, width: Int, height: Int) {
        val columns = mutableListOf(MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= 29) columns += listOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING)
        app.contentResolver.query(uri, columns.toTypedArray(), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("image/jpeg", cursor.getString(0))
            assertTrue(cursor.getString(1).endsWith(".jpg"))
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals("Pictures/FreeScanner/", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
        }
        val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(width, image.width)
            assertEquals(height, image.height)
            val actual = image.getPixel(width / 2, height / 2)
            assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 12)
            assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 12)
        } finally { image.recycle() }
    }

    private fun assertPdfOrder(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                listOf(Color.RED, Color.BLUE).forEachIndexed { index, expected ->
                    renderer.openPage(index).use { page ->
                        val image = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val actual = image.getPixel(30, 45)
                            assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 12)
                            assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 12)
                        } finally { image.recycle() }
                    }
                }
            }
        }
    }
}
