package com.freescanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanDraft
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchPageReviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<ScannerApplication>()

    @Test fun targetedBatchFiltersCompareAndRecreationNeverChangeSavedPages(): Unit = runBlocking {
        val fixture = File(app.cacheDir, "batch-review-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("OWNED TEST PAGE", 12f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLUE
                textSize = 19f
            })
        }
        fixture.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val repository = app.repository
        var seedId: String? = null
        var savedId: String? = null
        var unrelatedId: String? = null
        var editingId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val seed = repository.createDraft().also { seedId = it.id }
            repository.addPages(seed.id, List(3) { Uri.fromFile(fixture) }, detectEdges = false)
            val saved = repository.saveDraft(seed.id, "Batch review ${System.nanoTime()}")
            savedId = saved.id
            seedId = null
            val unrelated = repository.createDraft().also { unrelatedId = it.id }
            val untouched = repository.addPages(unrelated.id, listOf(Uri.fromFile(fixture)), detectEdges = false)
            val pages = saved.pages
            lateinit var model: AppViewModel
            val activity = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
            activity.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15_000) { model.state.value.busy == null && model.state.value.documents.any { it.id == saved.id } }
            activity.onActivity { model.openDocument(saved.id) }
            compose.onNodeWithText("Ringkasan").assertIsSelected()
            compose.onNodeWithTag("document-page-${pages.last().id}").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft?.sourceDocumentId == saved.id }
            val edit = model.state.value.draft ?: error("Missing editable draft")
            editingId = edit.id
            assertEquals(pages.last().id, model.state.value.reviewPageId)
            compose.onNodeWithTag("review-current-${pages.last().id}").assertIsDisplayed()
            compose.onNodeWithText("3 dari 3").assertIsDisplayed()

            compose.onNodeWithText("Bandingkan dengan asli").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Halaman asli tersimpan 3")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Tampilkan hasil edit").performClick()

            compose.onNodeWithText("Pilih halaman").performScrollTo().performClick()
            compose.onNodeWithTag("choose-filter-page-${pages.last().id}").assertIsOn()
            compose.onNodeWithTag("choose-filter-page-${pages.first().id}").assertIsOff().performClick()
            compose.onNodeWithText("Konfirmasi pilihan").performClick()
            compose.onNodeWithTag("filter-GRAYSCALE").performScrollTo().performClick()
            awaitFilters(edit.id, listOf(PageFilter.GRAYSCALE, PageFilter.ORIGINAL, PageFilter.GRAYSCALE), model)
            assertEquals(saved, repository.getDocument(saved.id))
            assertEquals(untouched, repository.getDraft(unrelated.id))

            activity.recreate()
            activity.onActivity { model = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft?.id == edit.id }
            compose.onNodeWithTag("filter-target-Choose pages").performScrollTo().assertIsSelected()
            compose.onNodeWithText("2 halaman dipilih").assertIsDisplayed()
            compose.onNodeWithTag("filter-BLACK_WHITE").performScrollTo().performClick()
            awaitFilters(edit.id, listOf(PageFilter.BLACK_WHITE, PageFilter.ORIGINAL, PageFilter.BLACK_WHITE), model)
            compose.onNodeWithTag("review-current-${pages.last().id}").performScrollTo().assertIsDisplayed()

            compose.onNodeWithTag("filter-target-All pages").performScrollTo().performClick()
            compose.onNodeWithTag("filter-ENHANCED").performScrollTo().performClick()
            awaitFilters(edit.id, List(3) { PageFilter.ENHANCED }, model)
            assertEquals(saved, repository.getDocument(saved.id))
            assertEquals(untouched, repository.getDraft(unrelated.id))

            compose.onNodeWithTag("filter-target-This page").performScrollTo().performClick()
            compose.onNodeWithTag("filter-ORIGINAL").performScrollTo().performClick()
            awaitFilters(edit.id, listOf(PageFilter.ENHANCED, PageFilter.ENHANCED, PageFilter.ORIGINAL), model)
            compose.onNodeWithContentDescription("Kembali").performClick()
            compose.onNodeWithText("Buang perubahan").performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null && model.state.value.draft == null }
            assertNull(repository.getDraft(edit.id))
            assertEquals(saved, repository.getDocument(saved.id))
            assertEquals(untouched, repository.getDraft(unrelated.id))
        } finally {
            scenario?.close()
            editingId?.let { repository.discardDraft(it) }
            unrelatedId?.let { repository.discardDraft(it) }
            seedId?.let { repository.discardDraft(it) }
            savedId?.let { repository.deleteDocument(it) }
            fixture.delete()
        }
    }

    private fun awaitFilters(draftId: String, filters: List<PageFilter>, model: AppViewModel) {
        compose.waitUntil(10_000) {
            val state = model.state.value
            val draft = state.draft
            state.busy == null && draft != null && draft.id == draftId && draft.pages.map { it.filter } == filters
        }
        val persisted: ScanDraft? = runBlocking { app.repository.getDraft(draftId) }
        assertEquals(filters, persisted?.pages?.map { it.filter })
    }
}
