package com.freescanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.data.DocumentRepository
import com.freescanner.model.ScanDocument
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun searchScopeGridAndVisibleSelectionSurviveRecreation(): Unit = runBlocking {
        val fixture = WorkspaceFixture()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            fixture.seed()
            val folder = fixture.repository.createFolder(fixture.folderName)
            fixture.folderId = folder.id
            fixture.repository.moveDocuments(setOf(fixture.documents[0].id), folder.id)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithTag("tab-FILES").performClick()
            awaitLibrary()
            search(fixture.prefix)
            reveal(fixture.documents[0].title).assertIsDisplayed()
            reveal(fixture.documents[1].title).assertIsDisplayed()
            reveal("Diubah terbaru").performClick()
            compose.onNodeWithText("Nama A–Z").performClick()
            reveal("Kisi").performClick()
            reveal("Pilih").performClick()
            reveal(fixture.documents[1].title).performClick()
            reveal("1 dipilih").assertIsDisplayed()
            scenario.recreate()
            awaitLibrary()
            reveal("1 dipilih").assertIsDisplayed()
            reveal("Nama A–Z").assertIsDisplayed()
            // Changing the query removes the old selection before Select all can act.
            search(fixture.documents[0].title)
            reveal("0 dipilih").assertIsDisplayed()
            reveal("Pilih semua yang terlihat").performClick()
            reveal("1 dipilih").assertIsDisplayed()
            reveal("Hapus yang dipilih").performClick()
            compose.onNode(isDialog()).assertIsDisplayed()
            compose.onNodeWithText("Batal").performClick()
            fixture.documents.forEach { assertNotNull(fixture.repository.getDocument(it.id)) }
            reveal("Batalkan pilihan").performClick()

            // The root includes a saved document already inside a folder.
            search(fixture.documents[1].title)
            reveal("Pilih").performClick()
            reveal("Pilih semua yang terlihat").performClick()
            reveal("Pindahkan").performClick()
            compose.onNodeWithText(fixture.folderName).performClick()
            withTimeout(10_000) { fixture.repository.documents.first { docs -> docs.filter { it.id in fixture.documentIds }.all { it.folderId == folder.id } } }
            search("")
            reveal(fixture.folderName).performClick()
            reveal(fixture.documents[0].title).assertIsDisplayed()
            reveal(fixture.documents[1].title).assertIsDisplayed()
            search("no-match-${fixture.prefix}")
            reveal("Tidak ada hasil").assertIsDisplayed()
            scenario.recreate()
            awaitLibrary()
            reveal("Tidak ada hasil").assertIsDisplayed()
            reveal(fixture.folderName).assertIsDisplayed()
            compose.onNodeWithContentDescription("Kembali ke Semua dokumen").performClick()
            reveal("Semua dokumen").assertIsDisplayed()
            search(fixture.prefix)
            reveal(fixture.documents[0].title).assertIsDisplayed()
            reveal(fixture.documents[1].title).assertIsDisplayed()
        } finally {
            scenario?.close()
            fixture.clean()
        }
    }

    @Test fun mergeUsesSavedSelectionOrderAndLeavesOriginalDocumentsUnchanged(): Unit = runBlocking {
        val fixture = WorkspaceFixture()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            fixture.seed()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithTag("tab-FILES").performClick()
            awaitLibrary()
            search(fixture.prefix)
            reveal("Pilih").performClick()
            reveal(fixture.documents[1].title).performClick()
            reveal(fixture.documents[0].title).performClick()
            reveal("2 dipilih").assertIsDisplayed()
            scenario.recreate()
            awaitLibrary()
            reveal("2 dipilih").assertIsDisplayed()
            reveal("Gabungkan").performClick()
            compose.onNodeWithText("Nama dokumen").performTextReplacement(fixture.mergeTitle)
            compose.onNode(hasText("Gabungkan") and hasAnyAncestor(isDialog())).performClick()
            val merged = withTimeout(10_000) { fixture.repository.drafts.first { list -> list.any { it.title == fixture.mergeTitle } }.first { it.title == fixture.mergeTitle } }
            fixture.extraDraftIds += merged.id
            assertEquals(fixture.documents.reversed().flatMap { it.pages }.map { it.sourcePath }, merged.pages.map { it.sourcePath })
            assertEquals(merged.pages.size, merged.pages.map { it.id }.toSet().size)
            val originalPageIds = fixture.documents.flatMap { it.pages }.map { it.id }.toSet()
            assertTrue(merged.pages.none { it.id in originalPageIds })
            fixture.documents.forEach { assertEquals(it, fixture.repository.getDocument(it.id)) }
        } finally {
            scenario?.close()
            fixture.clean()
        }
    }

    @Test fun folderCanBeCreatedRenamedAndEmptyDeletedThroughUi(): Unit = runBlocking {
        val fixture = WorkspaceFixture()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithTag("tab-FILES").performClick()
            awaitLibrary()
            reveal("Folder baru").performClick()
            compose.onNodeWithText("Nama folder").performTextReplacement(fixture.folderName)
            compose.onNodeWithText("Simpan").performClick()
            val folder = withTimeout(10_000) { fixture.repository.folders.first { list -> list.any { it.name == fixture.folderName } }.first { it.name == fixture.folderName } }
            fixture.folderId = folder.id
            reveal(fixture.folderName).performClick()
            reveal("Folder ini kosong").assertIsDisplayed()
            reveal(fixture.folderName).assertIsDisplayed()
            compose.onNodeWithContentDescription("Tindakan folder ${fixture.folderName}").performClick()
            compose.onNodeWithText("Ganti nama folder").performClick()
            val renamed = "${fixture.folderName} renamed"
            compose.onNodeWithText("Nama folder").performTextReplacement(renamed)
            compose.onNodeWithText("Simpan").performClick()
            withTimeout(10_000) { fixture.repository.folders.first { list -> list.any { it.id == folder.id && it.name == renamed } } }
            reveal(renamed).assertIsDisplayed()
            compose.onNodeWithContentDescription("Tindakan folder $renamed").performClick()
            compose.onNodeWithText("Hapus folder").performClick()
            compose.onNode(hasText("Hapus folder") and hasAnyAncestor(isDialog())).performClick()
            withTimeout(10_000) { fixture.repository.folders.first { list -> list.none { it.id == folder.id } } }
            fixture.folderId = null
            reveal("Semua dokumen").assertIsDisplayed()
        } finally {
            scenario?.close()
            fixture.clean()
        }
    }

    private fun awaitLibrary() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("Library workspace")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Membuka dokumen…")).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun reveal(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("Library workspace").performScrollToNode(hasText(text)) }.isSuccess
        }
        return compose.onNodeWithText(text)
    }

    private fun search(value: String) {
        reveal("Cari dokumen").performTextReplacement(value)
        compose.waitForIdle()
    }

    private class WorkspaceFixture {
        private val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
        val repository: DocumentRepository = app.repository
        val prefix = "Workspace-${System.nanoTime()}"
        val folderName = "$prefix folder"
        val mergeTitle = "$prefix merged"
        private val image = File(app.cacheDir, "$prefix.jpg")
        val documents = mutableListOf<ScanDocument>()
        val documentIds: Set<String> get() = documents.map { it.id }.toSet()
        private val initialDraftIds = mutableListOf<String>()
        val extraDraftIds = mutableListOf<String>()
        var folderId: String? = null

        suspend fun seed() {
            val bitmap = Bitmap.createBitmap(400, 550, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText("SYNTHETIC WORKSPACE TEST", 20f, 80f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f })
                }
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { bitmap.recycle() }
            for (name in listOf("Alpha", "Zulu")) {
                val draft = repository.createDraft()
                initialDraftIds += draft.id
                repository.addPages(draft.id, listOf(Uri.fromFile(image)), detectEdges = false)
                documents += repository.saveDraft(draft.id, "$prefix $name")
            }
        }

        suspend fun clean() {
            // IDs are fixture-owned; never clear or enumerate-delete the user's library.
            val ownMergeDrafts = repository.drafts.first().filter { it.title == mergeTitle }.map { it.id }
            (initialDraftIds + extraDraftIds + ownMergeDrafts).distinct().forEach { repository.discardDraft(it) }
            documents.forEach { repository.deleteDocument(it.id) }
            folderId?.let { repository.deleteFolder(it) }
            image.delete()
        }
    }
}
