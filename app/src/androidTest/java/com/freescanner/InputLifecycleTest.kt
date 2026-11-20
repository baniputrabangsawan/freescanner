package com.freescanner

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class InputLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun canceledNewImportReturnsToLibraryWithoutLeavingEmptyDraft(): Unit = runBlocking {
        val handle = SavedStateHandle()
        val (model, store) = createModel(handle)
        var draftId: String? = null
        try {
            withTimeout(10_000) { model.state.first { it.busy == null } }
            instrumentation.runOnMainSync { model.startInput(InputSource.GALLERY) }
            val event = withTimeout(10_000) { model.events.first() }
            assertEquals(InputSource.GALLERY, (event as AppEvent.Input).source)
            assertNull(model.state.value.draft)
            assertNull(handle.get<String>("draftId"))
            draftId = handle.get<String>("newInputDraftId") ?: error("Input target was not prepared")
            instrumentation.runOnMainSync { model.inputCanceled() }
            withTimeout(10_000) { app.repository.drafts.first { drafts -> drafts.none { it.id == draftId } } }
            assertNull(app.repository.getDraft(draftId))
            assertNull(model.state.value.documentId)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            draftId?.let { app.repository.discardDraft(it) }
        }
    }

    @Test fun importResultDuringRestorationIsNotDropped(): Unit = runBlocking {
        val file = File(app.cacheDir, "input-lifecycle-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val draft = app.repository.createDraft()
        val handle = SavedStateHandle(mapOf("draftId" to draft.id))
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val model = provider(store, handle)[AppViewModel::class.java]
                model.receivePages(listOf(Uri.fromFile(file)), detectEdges = false)
            }
            val restored = withTimeout(10_000) { app.repository.drafts.first { drafts -> drafts.any { it.id == draft.id && it.pages.size == 1 } } }
            assertEquals(1, restored.first { it.id == draft.id }.pages.size)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.repository.discardDraft(draft.id)
            file.delete()
        }
    }

    @Test fun replacingLastPageKeepsReplacementSelected(): Unit = runBlocking {
        val file = File(app.cacheDir, "replacement-lifecycle-${System.nanoTime()}.png")
        val image = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
        val draft = app.repository.createDraft()
        val original = app.repository.addPages(draft.id, List(2) { Uri.fromFile(file) }, detectEdges = false)
        val handle = SavedStateHandle(mapOf(
            "draftId" to draft.id, "replacePageId" to original.pages.last().id,
            "reviewPageId" to original.pages.last().id,
        ))
        val (model, store) = createModel(handle)
        try {
            instrumentation.runOnMainSync { model.receivePages(listOf(Uri.fromFile(file)), detectEdges = false) }
            val state = withTimeout(10_000) { model.state.first {
                it.busy == null && it.draft?.pages?.lastOrNull()?.id != original.pages.last().id && it.draft?.pages?.size == 2
            } }
            val replacement = state.draft ?: error("Missing replacement draft")
            assertEquals(original.pages.first(), replacement.pages.first())
            assertEquals(replacement.pages.last().id, state.reviewPageId)
            assertEquals(2, replacement.pages.size)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.repository.discardDraft(draft.id)
            file.delete()
        }
    }

    @Test fun scannerFileResultCanBeReviewedAndSavedWhileGalleryFileResultIsRejected(): Unit = runBlocking {
        val file = File(app.cacheDir, "scanner-result-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { bitmap.recycle() }
        val draft = app.repository.createDraft()
        val (model, store) = createModel(SavedStateHandle(mapOf("draftId" to draft.id)))
        var savedId: String? = null
        try {
            withTimeout(10_000) { model.state.first { it.busy == null && it.draft?.id == draft.id } }
            instrumentation.runOnMainSync { model.receiveImageResult(listOf(Uri.fromFile(file)), InputSource.GALLERY) }
            assertEquals(draft, app.repository.getDraft(draft.id))
            assertTrue(model.state.value.message?.contains("lokasi gambar yang tidak valid") == true)

            instrumentation.runOnMainSync { model.receiveImageResult(listOf(Uri.fromFile(file)), InputSource.CAMERA) }
            val ready = withTimeout(10_000) { model.state.first { it.busy == null && it.draft?.pages?.size == 1 } }
            val page = requireNotNull(ready.draft).pages.single()
            assertEquals(com.freescanner.model.Crop.Full, page.crop)
            assertEquals(page.id, ready.reviewPageId)
            assertTrue(file.delete())
            val preview = app.processor.renderPage(app.repository.sourceFile(page), page)
            try {
                assertEquals(100, preview.width)
                assertEquals(200, preview.height)
                assertTrue(Color.blue(preview.getPixel(50, 100)) > 240)
                assertTrue(Color.red(preview.getPixel(50, 100)) < 15)
            } finally { preview.recycle() }

            instrumentation.runOnMainSync { model.saveDraft("Scanner result regression") }
            val saved = withTimeout(10_000) { model.state.first { it.busy == null && it.documentId != null && it.draft == null } }
            savedId = requireNotNull(saved.documentId)
            assertEquals(listOf(page), requireNotNull(app.repository.getDocument(savedId)).pages)
            assertTrue(app.repository.sourceFile(page).isFile)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            savedId?.let { app.repository.deleteDocument(it) }
            app.repository.discardDraft(draft.id)
            file.delete()
        }
    }

    @Test fun scannerRejectsRemoteAndNonlocalFileResultsWithoutImportingAnyPage(): Unit = runBlocking {
        val draft = app.repository.createDraft()
        val (model, store) = createModel(SavedStateHandle(mapOf("draftId" to draft.id)))
        try {
            withTimeout(10_000) { model.state.first { it.busy == null && it.draft?.id == draft.id } }
            for (uri in listOf("https://example.org/scan.jpg", "file://remote/scan.jpg", "file:relative.jpg")) {
                instrumentation.runOnMainSync {
                    model.dismissMessage()
                    model.receiveImageResult(listOf(Uri.parse("content://example/scan"), Uri.parse(uri)), InputSource.CAMERA)
                }
                assertTrue(model.state.value.message?.contains("lokasi gambar yang tidak valid") == true)
                assertEquals(draft, app.repository.getDraft(draft.id))
                assertNull(model.state.value.busy)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.repository.discardDraft(draft.id)
        }
    }

    private fun createModel(handle: SavedStateHandle): Pair<AppViewModel, ViewModelStore> {
        val store = ViewModelStore()
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = provider(store, handle)[AppViewModel::class.java] }
        return model to store
    }

    private fun provider(store: ViewModelStore, handle: SavedStateHandle) = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app, handle) as T
    })
}
