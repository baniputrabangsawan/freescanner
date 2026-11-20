package com.freescanner

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddPhotoTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun homeAppendAndRepeatedPhotoKeepOneNamedDocumentAndCancelWithoutChanges(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
        val repository = app.repository
        val title = "SERTIFIKAT-${System.nanoTime()}"
        val directory = File(app.cacheDir, "exports/export-add-${System.nanoTime()}").apply { check(mkdirs()) }
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN)
        val files = colors.mapIndexed { index, color ->
            File(directory, "$index.png").also { file ->
                val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }
        val calls = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                val index = calls.incrementAndGet()
                return if (index <= 2) Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(
                    FileProvider.getUriForFile(app, "${app.packageName}.exports", files[index]),
                )) else Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        var seedId: String? = null
        var savedId: String? = null
        var editingId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val seed = repository.createDraft().also { seedId = it.id }
            val initial = repository.addPages(seed.id, listOf(Uri.fromFile(files.first())), detectEdges = false)
            val saved = repository.saveDraft(initial.id, title).also { savedId = it.id }
            seedId = null
            val sourceBytes = repository.sourceFile(saved.pages.first()).readBytes()
            instrumentation.addMonitor(monitor)
            lateinit var model: AppViewModel
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15_000) { model.state.value.busy == null && model.state.value.documents.any { it.id == saved.id } }
            awaitComposeText("Cari dokumen")
            compose.onNodeWithText("Cari dokumen").performTextReplacement(title)
            compose.onNodeWithContentDescription("Tambah foto ke $title").performClick()
            compose.runOnIdle {
                assertNull(model.state.value.documentId)
                assertNull(model.state.value.draft)
            }
            choosePhoto()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft?.pages?.size == 2 }
            editingId = model.state.value.draft!!.id
            assertEquals(saved.id, model.state.value.draft!!.sourceDocumentId)
            assertEquals(title, model.state.value.draft!!.title)
            assertEquals(model.state.value.draft!!.pages.last().id, model.state.value.reviewPageId)
            compose.onNodeWithText("2 dari 2").assertIsDisplayed()
            assertEquals(saved, repository.getDocument(saved.id))
            scenario.recreate()
            awaitComposeText("Tambah foto")
            scenario.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.onNodeWithText("Tambah foto").performScrollTo().performClick()
            choosePhoto()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft?.pages?.size == 3 }
            compose.onNodeWithText("3 dari 3").assertIsDisplayed()
            assertEquals(editingId, model.state.value.draft!!.id)
            assertEquals(saved, repository.getDocument(saved.id))
            compose.onNodeWithText("Simpan dokumen").performClick()
            compose.onNode(hasText("Simpan") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft == null && model.state.value.documents.any { it.id == saved.id && it.pages.size == 3 } }
            val updated = requireNotNull(repository.getDocument(saved.id))
            assertEquals(title, updated.title)
            assertEquals(saved.id, updated.id)
            assertEquals(saved.pages.first(), updated.pages.first())
            assertTrue(sourceBytes.contentEquals(repository.sourceFile(updated.pages.first()).readBytes()))
            updated.pages.forEachIndexed { index, page ->
                val bitmap = BitmapFactory.decodeFile(repository.sourceFile(page).absolutePath)
                try {
                    val actual = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue(kotlin.math.abs(Color.red(colors[index]) - Color.red(actual)) < 12)
                    assertTrue(kotlin.math.abs(Color.green(colors[index]) - Color.green(actual)) < 12)
                    assertTrue(kotlin.math.abs(Color.blue(colors[index]) - Color.blue(actual)) < 12)
                } finally { bitmap.recycle() }
            }
            compose.onNodeWithContentDescription("Kembali").performClick()
            compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
            updated.pages.forEach { compose.onNodeWithTag("document-page-${it.id}").performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText("Tambah foto").performClick()
            choosePhoto()
            compose.waitUntil(10_000) {
                calls.get() == 3 && model.state.value.busy == null && model.state.value.draft == null &&
                    model.state.value.message == "Pemindaian atau impor dibatalkan."
            }
            assertEquals(updated, repository.getDocument(saved.id))
            assertEquals(saved.id, model.state.value.documentId)
            assertNull(repository.getDraft(editingId!!))
            assertTrue(repository.drafts.first { true }.none { it.sourceDocumentId == saved.id })
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            editingId?.let { repository.discardDraft(it) }
            seedId?.let { repository.discardDraft(it) }
            savedId?.let { id -> repository.drafts.first { true }.filter { it.sourceDocumentId == id }.forEach { repository.discardDraft(it.id) }; repository.deleteDocument(id) }
            directory.deleteRecursively()
        }
    }

    /** The physical phone can resume the activity before Compose attaches its first hierarchy. */
    private fun awaitComposeText(text: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun choosePhoto() {
        compose.onNode(hasText("Impor foto") and hasAnyAncestor(isDialog())).performClick()
    }
}
