package com.freescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedDocumentCanBeEditedCanceledAndRenamedThroughUi(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as ScannerApplication).repository
        val fixture = File(context.cacheDir, "journey-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("FREE SCANNER TEST", 60f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f })
            drawText("Non-sensitive sample document", 60f, 210f, Paint().apply { color = Color.BLUE; textSize = 26f })
        }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        val title = "Journey ${System.nanoTime()}"
        val draft = repository.createDraft()
        repository.addPages(draft.id, listOf(Uri.fromFile(fixture), Uri.fromFile(fixture)), detectEdges = false)
        val saved = repository.saveDraft(draft.id, title)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.onNodeWithText("Cari dokumen").performTextReplacement(title)
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
            compose.onNodeWithText("Edit halaman").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Putar")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Pangkas").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Posisi horizontal Kiri atas")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Posisi horizontal Kiri atas").performSemanticsAction(SemanticsActions.SetProgress) { it(.1f) }
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Kiri atas horizontal: 10%")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Batal").performClick()
            compose.onNodeWithText("Putar").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Memperbarui halaman…")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Kembali").performClick()
            compose.onNodeWithText("Buang perubahan").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Edit halaman")).fetchSemanticsNodes().isNotEmpty() }
            check(repository.getDocument(saved.id)?.pages?.all { it.rotation == 0 } == true)
            compose.onNodeWithContentDescription("Tindakan dokumen").performClick()
            compose.onNodeWithText("Ganti nama").performClick()
            compose.onNodeWithText("Nama dokumen").performTextReplacement("Renamed $title")
            compose.onNodeWithText("Simpan").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Renamed $title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Ekspor").performClick()
            compose.onNodeWithText("JPEG").performClick()
            compose.onNodeWithText("Ekspor halaman 2").performScrollTo().performClick()
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Ekspor halaman 2")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Ekspor halaman 1").assertIsOn()
            compose.onNodeWithText("Ekspor halaman 2").assertIsOff()
        } finally {
            scenario.close()
            repository.deleteDocument(saved.id)
            fixture.delete()
        }
    }
}
