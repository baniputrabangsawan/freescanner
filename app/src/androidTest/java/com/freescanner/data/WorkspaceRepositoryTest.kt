package com.freescanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanDocument
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceRepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        root = File(context.cacheDir, "workspace-test-$id").apply { check(mkdirs()) }
        databaseName = "workspace-test-$id.db"
        repository = openRepository()
    }

    @After
    fun tearDown() = runBlocking {
        repository.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun foldersPersistMovesLeaveDraftIsolatedAndNonemptyDeleteIsRejected() = runBlocking {
        val folder = repository.createFolder("  Receipts  ")
        assertEquals("Receipts", folder.name)
        val saved = saveDocument("Receipt", Color.RED)
        repository.moveDocuments(setOf(saved.id), folder.id)
        val edit = repository.createDraft(saved.id)
        assertEquals(folder.id, edit.folderId)
        repository.moveDocuments(setOf(saved.id), null)
        assertNull(requireNotNull(repository.getDocument(saved.id)).folderId)
        assertEquals(edit, repository.getDraft(edit.id))
        repository.renameFolder(folder.id, "  Tax records  ")
        val failure = expectRejected { repository.deleteFolder(folder.id) }
        assertTrue(failure.message.orEmpty().contains("pindah", ignoreCase = true))
        repository.close()
        repository = openRepository()
        assertEquals(listOf(folder.copy(name = "Tax records")), repository.folders.first())
        assertEquals(edit, repository.getDraft(edit.id))
        assertNull(requireNotNull(repository.getDocument(saved.id)).folderId)
        repository.discardDraft(edit.id)
        repository.deleteFolder(folder.id)
        assertTrue(repository.folders.first().isEmpty())
        assertEquals(saved.pages, requireNotNull(repository.getDocument(saved.id)).pages)
    }

    @Test
    fun folderNamesRejectNormalizedDuplicatesAndInvalidInput() = runBlocking {
        val first = repository.createFolder("Receipts")
        val second = repository.createFolder("Invoices")
        listOf(" receipts ", "RECEIPTS", "Ｒｅｃｅｉｐｔｓ").forEach { name ->
            expectRejected { repository.createFolder(name) }
            expectRejected { repository.renameFolder(second.id, name) }
        }
        listOf("", "  ", "x".repeat(81), "bad\nname", "bad\u0000name").forEach { name ->
            expectRejected { repository.createFolder(name) }
            expectRejected { repository.renameFolder(first.id, name) }
        }
        repository.renameFolder(first.id, " receipts ")
        assertEquals(setOf("receipts", "Invoices"), repository.folders.first().map { it.name }.toSet())
    }

    @Test
    fun moveDocumentsValidatesFolderAndEntireSelectionBeforeMutation() = runBlocking {
        val first = saveDocument("First", Color.RED)
        val second = saveDocument("Second", Color.BLUE)
        val folder = repository.createFolder("Work")
        expectRejected { repository.moveDocuments(setOf(first.id, second.id), "missing-folder") }
        expectRejected { repository.moveDocuments(setOf(first.id, "missing-document", second.id), folder.id) }
        assertEquals(first, repository.getDocument(first.id))
        assertEquals(second, repository.getDocument(second.id))
        repository.moveDocuments(setOf(first.id, second.id), folder.id)
        assertEquals(folder.id, requireNotNull(repository.getDocument(first.id)).folderId)
        assertEquals(folder.id, requireNotNull(repository.getDocument(second.id)).folderId)
        expectRejected { repository.deleteFolder(folder.id) }
        repository.moveDocuments(setOf(first.id, second.id), null)
        repository.deleteFolder(folder.id)
        assertTrue(repository.folders.first().isEmpty())
    }

    @Test
    fun blankDraftUsesLocalDateTitleAndRecoveredSaveRetainsFolder(): Unit = runBlocking {
        val formatter = DateTimeFormatter.ofPattern("'Pindai' yyyy-MM-dd HH.mm", Locale.ROOT)
        val before = LocalDateTime.now().format(formatter)
        val blank = repository.createDraft()
        val after = LocalDateTime.now().format(formatter)
        assertTrue(blank.title in setOf(before, after))
        val imported = repository.addPages(blank.id, listOf(image(Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, imported.title)
        val folder = repository.createFolder("Archive")
        repository.moveDocuments(setOf(saved.id), folder.id)
        val edit = repository.createDraft(saved.id)
        assertEquals(folder.id, edit.folderId)
        repository.deleteDocuments(setOf(saved.id))
        val recovered = repository.saveDraft(edit.id, "Recovered")
        assertNotEquals(saved.id, recovered.id)
        assertEquals(folder.id, recovered.folderId)
        assertTrue(repository.sourceFile(recovered.pages.single()).isFile)
        expectRejected { repository.deleteFolder(folder.id) }
    }

    @Test
    fun duplicateHasFreshIdsAndDeletionRetainsSharedSource() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image(Color.RED), image(Color.BLUE)), detectEdges = false)
        repository.updatePage(imported.id, imported.pages[0].copy(rotation = 90, filter = PageFilter.GRAYSCALE))
        val saved = repository.saveDraft(imported.id, "Original")
        val folder = repository.createFolder("Copies")
        repository.moveDocuments(setOf(saved.id), folder.id)
        val original = requireNotNull(repository.getDocument(saved.id))
        val bytes = original.pages.map { repository.sourceFile(it).readBytes() }
        val duplicate = repository.duplicateDocument(original.id)
        assertNotEquals(original.id, duplicate.id)
        assertEquals("Original salinan", duplicate.title)
        assertEquals(folder.id, duplicate.folderId)
        assertTrue(duplicate.pages.map { it.id }.toSet().intersect(original.pages.map { it.id }.toSet()).isEmpty())
        assertEquals(original.pages, duplicate.pages.zip(original.pages) { copy, source -> copy.copy(id = source.id) })
        assertEquals(original, repository.getDocument(original.id))
        repository.deleteDocuments(setOf(original.id))
        assertNull(repository.getDocument(original.id))
        duplicate.pages.forEachIndexed { index, page ->
            assertTrue(bytes[index].contentEquals(repository.sourceFile(page).readBytes()))
        }
        repository.close()
        repository = openRepository()
        assertEquals(duplicate, repository.getDocument(duplicate.id))
        repository.deleteDocuments(setOf(duplicate.id))
        duplicate.pages.forEach { assertFalse(repository.sourceFile(it).exists()) }
    }

    @Test
    fun mergePreservesExplicitDocumentAndPageOrderWithoutChangingOriginals() = runBlocking {
        val first = saveDocument("First", Color.RED, Color.GREEN)
        val secondImported = repository.addPages(repository.createDraft().id, listOf(image(Color.BLUE), image(Color.YELLOW)), detectEdges = false)
        repository.movePage(secondImported.id, secondImported.pages[1].id, -1)
        repository.updatePage(secondImported.id, secondImported.pages[0].copy(rotation = 270, filter = PageFilter.BLACK_WHITE))
        val second = repository.saveDraft(secondImported.id, "Second")
        val folder = repository.createFolder("Sources")
        repository.moveDocuments(setOf(first.id, second.id), folder.id)
        val firstBefore = requireNotNull(repository.getDocument(first.id))
        val secondBefore = requireNotNull(repository.getDocument(second.id))
        val merged = repository.mergeDocuments(listOf(second.id, first.id), "  Combined  ")
        val expected = secondBefore.pages + firstBefore.pages
        assertEquals("Combined", merged.title)
        assertNull(merged.sourceDocumentId)
        assertNull(merged.folderId)
        assertEquals(expected.map { it.sourcePath }, merged.pages.map { it.sourcePath })
        assertEquals(expected, merged.pages.zip(expected) { page, source -> page.copy(id = source.id) })
        assertEquals(merged.pages.size, merged.pages.map { it.id }.toSet().size)
        assertTrue(merged.pages.map { it.id }.toSet().intersect(expected.map { it.id }.toSet()).isEmpty())
        assertEquals(firstBefore, repository.getDocument(first.id))
        assertEquals(secondBefore, repository.getDocument(second.id))
        val draftsBefore = repository.drafts.first()
        expectRejected { repository.mergeDocuments(listOf(first.id, "missing", second.id), "Invalid") }
        assertEquals(draftsBefore, repository.drafts.first())
        repository.deleteDocuments(setOf(first.id, second.id))
        merged.pages.forEach { assertTrue(repository.sourceFile(it).isFile) }
        repository.close()
        repository = openRepository()
        assertEquals(merged, repository.getDraft(merged.id))
        val savedMerge = repository.saveDraft(merged.id, merged.title)
        assertEquals(merged.pages, savedMerge.pages)
        assertNull(savedMerge.folderId)
    }

    @Test
    fun batchFilterOnlyChangesSelectedPagesAndRejectsInvalidIdsAtomically() = runBlocking {
        val saved = saveDocument("Filters", Color.RED, Color.GREEN, Color.BLUE)
        val draft = repository.createDraft(saved.id)
        val targets = setOf(draft.pages[0].id, draft.pages[2].id)
        val filtered = repository.applyFilter(draft.id, targets, PageFilter.BLACK_WHITE)
        assertEquals(draft.pages.map { if (it.id in targets) it.copy(filter = PageFilter.BLACK_WHITE) else it }, filtered.pages)
        assertEquals(saved, repository.getDocument(saved.id))
        expectRejected {
            repository.applyFilter(draft.id, setOf(draft.pages[1].id, "missing-page"), PageFilter.GRAYSCALE)
        }
        assertEquals(filtered, repository.getDraft(draft.id))
        repository.discardDraft(draft.id)
        assertEquals(saved, repository.getDocument(saved.id))
    }

    @Test
    fun bulkDeleteRejectsInvalidSelectionBeforeDeletingAnything() = runBlocking {
        val first = saveDocument("First", Color.RED)
        val second = saveDocument("Second", Color.BLUE)
        val edit = repository.createDraft(first.id)
        val firstFile = repository.sourceFile(first.pages.single())
        val secondFile = repository.sourceFile(second.pages.single())
        val firstBytes = firstFile.readBytes()
        val secondBytes = secondFile.readBytes()
        expectRejected { repository.deleteDocuments(setOf(first.id, "missing-document", second.id)) }
        expectRejected { repository.deleteDocuments(setOf(first.id, edit.id, second.id)) }
        assertEquals(first, repository.getDocument(first.id))
        assertEquals(second, repository.getDocument(second.id))
        assertEquals(edit, repository.getDraft(edit.id))
        assertTrue(firstBytes.contentEquals(firstFile.readBytes()))
        assertTrue(secondBytes.contentEquals(secondFile.readBytes()))
        repository.deleteDocuments(setOf(first.id, second.id))
        assertTrue(repository.documents.first().isEmpty())
        assertTrue(firstFile.isFile)
        assertFalse(secondFile.exists())
        repository.discardDraft(edit.id)
        assertFalse(firstFile.exists())
    }

    @Test
    fun duplicatingMaximumLengthTitleRemainsEditable() = runBlocking {
        val original = saveDocument("x".repeat(200), Color.RED)
        val copy = repository.duplicateDocument(original.id)
        assertTrue(copy.title.length <= 200)
        assertTrue(copy.title.endsWith(" salinan"))
        val edit = repository.createDraft(copy.id)
        val rotated = repository.updatePage(edit.id, edit.pages.single().copy(rotation = 90))
        val saved = repository.saveDraft(edit.id, copy.title)
        assertEquals(copy.title, saved.title)
        assertEquals(rotated.pages, saved.pages)
        assertEquals(original, repository.getDocument(original.id))
    }

    private fun openRepository() = DocumentRepository(context, ImageProcessor(context), databaseName, root)

    private suspend fun saveDocument(title: String, vararg colors: Int): ScanDocument {
        val draft = repository.addPages(repository.createDraft().id, colors.map { image(it) }, detectEdges = false)
        return repository.saveDraft(draft.id, title)
    }

    private fun image(color: Int): Uri {
        val file = File(root, "input-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return Uri.fromFile(file)
    }

    private suspend fun expectRejected(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            return failure
        }
        throw AssertionError("The invalid operation must be rejected before changing any item.")
    }
}
