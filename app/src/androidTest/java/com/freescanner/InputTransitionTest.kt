package com.freescanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.freescanner.model.ScanDocument
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputTransitionTest {
    private val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun cameraAppendKeepsHomeAcrossRestorationUntilResultAndSavesSameDocument(): Unit = fixture { source, image ->
        val handle = SavedStateHandle()
        var store = ViewModelStore()
        var pendingId: String? = null
        try {
            var model = model(store, handle)
            ready(model)
            val originalBytes = app.repository.sourceFile(source.pages.single()).readBytes()
            instrumentation.runOnMainSync { model.startInput(InputSource.CAMERA, targetDocumentId = source.id) }
            assertEquals(InputSource.CAMERA, (withTimeout(10_000) { model.events.first() } as AppEvent.Input).source)
            assertNull(model.state.value.draft)
            assertNull(model.state.value.documentId)
            assertNull(handle.get<String>("draftId"))
            pendingId = requireNotNull(handle.get<String>("newInputDraftId"))
            assertEquals(source.id, app.repository.getDraft(pendingId)!!.sourceDocumentId)
            val restored = SavedStateHandle(mapOf(
                "newInputDraftId" to pendingId,
                "inputInitialPageIds" to handle.get<ArrayList<String>>("inputInitialPageIds"),
            ))
            instrumentation.runOnMainSync { store.clear() }
            store = ViewModelStore()
            model = model(store, restored)
            ready(model)
            assertNull(model.state.value.draft)
            assertNull(model.state.value.documentId)
            instrumentation.runOnMainSync { model.receiveImageResult(listOf(Uri.fromFile(image)), InputSource.CAMERA) }
            val review = withTimeout(10_000) { model.state.first { it.busy == null && it.draft?.pages?.size == 2 } }
            assertEquals(source.id, review.draft!!.sourceDocumentId)
            assertEquals(source.title, review.draft.title)
            assertEquals(review.draft.pages.last().id, review.reviewPageId)
            assertEquals(source, app.repository.getDocument(source.id))
            instrumentation.runOnMainSync { model.saveDraft(source.title) }
            withTimeout(10_000) { model.state.first { it.busy == null && it.draft == null && it.documentId == source.id } }
            val saved = requireNotNull(app.repository.getDocument(source.id))
            assertEquals(2, saved.pages.size)
            assertEquals(source.title, saved.title)
            assertTrue(originalBytes.contentEquals(app.repository.sourceFile(saved.pages.first()).readBytes()))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            pendingId?.let { app.repository.discardDraft(it) }
        }
    }

    @Test fun canceledCameraKeepsOpenDocumentAndDiscardsUnchangedInput(): Unit = fixture { source, _ ->
        val handle = SavedStateHandle(mapOf("documentId" to source.id))
        val store = ViewModelStore()
        var pendingId: String? = null
        try {
            val model = model(store, handle)
            ready(model)
            instrumentation.runOnMainSync { model.startInput(InputSource.CAMERA) }
            withTimeout(10_000) { model.events.first() }
            pendingId = requireNotNull(handle.get<String>("newInputDraftId"))
            assertNull(model.state.value.draft)
            assertEquals(source.id, model.state.value.documentId)
            instrumentation.runOnMainSync { model.inputCanceled() }
            withTimeout(10_000) { app.repository.drafts.first { drafts -> drafts.none { it.id == pendingId } } }
            withTimeout(10_000) { model.state.first { it.busy == null && it.message != null } }
            assertNull(model.state.value.draft)
            assertNull(handle.get<String>("draftId"))
            assertEquals(source.id, model.state.value.documentId)
            assertEquals(source, app.repository.getDocument(source.id))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            pendingId?.let { app.repository.discardDraft(it) }
        }
    }

    @Test fun pdfAppendTargetsNamedDocumentWithoutOpeningItBeforePicker(): Unit = fixture { source, _ ->
        val directory = File(app.cacheDir, "exports/export-transition-${System.nanoTime()}").apply { check(mkdirs()) }
        val pdf = File(directory, "input.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(100, 160, 1).create())
            page.canvas.drawColor(Color.BLUE)
            document.finishPage(page)
            pdf.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        val store = ViewModelStore()
        var draftId: String? = null
        try {
            val model = model(store, SavedStateHandle())
            ready(model)
            instrumentation.runOnMainSync { model.requestPdf(source.id) }
            assertEquals(AppEvent.ImportPdf, withTimeout(10_000) { model.events.first() })
            assertNull(model.state.value.documentId)
            assertNull(model.state.value.draft)
            ready(model)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", pdf)
            instrumentation.runOnMainSync { model.receivePdf(uri) }
            val review = withTimeout(15_000) { model.state.first { it.busy == null && it.draft?.pages?.size == 2 } }
            draftId = review.draft!!.id
            assertEquals(source.id, review.draft.sourceDocumentId)
            assertEquals(source.title, review.draft.title)
            assertEquals(source, app.repository.getDocument(source.id))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            draftId?.let { app.repository.discardDraft(it) }
            directory.deleteRecursively()
        }
    }

    private suspend fun ready(model: AppViewModel) {
        withTimeout(10_000) { model.state.first { it.busy == null } }
    }

    private fun model(store: ViewModelStore, handle: SavedStateHandle): AppViewModel {
        lateinit var result: AppViewModel
        instrumentation.runOnMainSync {
            result = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = AppViewModel(app, handle) as T
            })[AppViewModel::class.java]
        }
        return result
    }

    private fun fixture(block: suspend (ScanDocument, File) -> Unit): Unit = runBlocking {
        val image = File(app.cacheDir, "input-transition-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val seed = app.repository.createDraft()
        var saved: ScanDocument? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            val draft = app.repository.addPages(seed.id, listOf(Uri.fromFile(image)), detectEdges = false)
            val source = app.repository.saveDraft(draft.id, "TRANSISI-${System.nanoTime()}").also { saved = it }
            // Keep the instrumented application foreground while its standalone model is exercised.
            activity = ActivityScenario.launch(MainActivity::class.java)
            block(source, image)
        } finally {
            activity?.close()
            app.repository.discardDraft(seed.id)
            saved?.let { source ->
                app.repository.drafts.first().filter { it.sourceDocumentId == source.id }.forEach { app.repository.discardDraft(it.id) }
                app.repository.deleteDocument(source.id)
            }
            image.delete()
        }
    }
}
