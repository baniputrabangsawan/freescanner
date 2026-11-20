package com.freescanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.export.DocumentExporter
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.PageFilter
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private lateinit var processor: ImageProcessor
    private lateinit var repository: DocumentRepository
    private val exports = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        root = File(context.cacheDir, "repository-test-$id").apply { check(mkdirs()) }
        databaseName = "repository-test-$id.db"
        processor = ImageProcessor(context)
        repository = openRepository()
    }

    @After
    fun tearDown() = runBlocking {
        repository.close()
        context.deleteDatabase(databaseName)
        exports.forEach { it.parentFile?.deleteRecursively() }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun canceledEditPreservesSavedDocument() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("first", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Original")
        val originalFile = repository.sourceFile(saved.pages.single())
        val bytes = originalFile.readBytes()
        val edit = repository.createDraft(saved.id)
        repository.updatePage(edit.id, edit.pages.single().copy(rotation = 90, filter = PageFilter.GRAYSCALE))
        repository.addPages(edit.id, listOf(image("replacement", Color.BLUE)), replacePageId = edit.pages.single().id, detectEdges = false)
        repository.discardDraft(edit.id)
        assertEquals(saved, repository.getDocument(saved.id))
        assertTrue(bytes.contentEquals(originalFile.readBytes()))
        assertTrue(repository.drafts.first().isEmpty())
    }

    @Test
    fun documentsAndRecoverableDraftSurviveRepositoryRestart() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("saved", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Persistent")
        val draft = repository.createDraft(saved.id)
        val updated = repository.updatePage(draft.id, draft.pages.single().copy(rotation = 270, filter = PageFilter.BLACK_WHITE))
        repository.close()
        repository = openRepository()
        assertEquals(saved, repository.getDocument(saved.id))
        assertEquals(updated, repository.getDraft(draft.id))
        assertEquals(listOf(saved), repository.documents.first())
        assertEquals(listOf(updated), repository.drafts.first())
        assertTrue(repository.sourceFile(saved.pages.single()).isFile)
    }

    @Test
    fun reorderAndRotationProducePdfWithMatchingPageCountOrientationAndOrder() = runBlocking {
        val draft = repository.addPages(
            repository.createDraft().id,
            listOf(image("red", Color.RED, 120, 240), image("blue", Color.BLUE, 240, 120)),
            detectEdges = false,
        )
        repository.movePage(draft.id, draft.pages[1].id, -1)
        draft.pages.forEach { repository.updatePage(draft.id, it.copy(rotation = 90)) }
        val saved = repository.saveDraft(draft.id, "Ordered")
        assertEquals(listOf(draft.pages[1].id, draft.pages[0].id), saved.pages.map { it.id })
        val pdf = DocumentExporter(context, repository, processor).pdf(saved).also { exports.add(it) }
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    assertTrue(page.height > page.width)
                    assertRenderedColor(page, Color.BLUE)
                }
                renderer.openPage(1).use { page ->
                    assertTrue(page.width > page.height)
                    assertRenderedColor(page, Color.RED)
                }
            }
        }
    }

    @Test
    fun failedImportPreservesSourceAndAlreadyImportedDraftWork() = runBlocking {
        val original = repository.addPages(repository.createDraft().id, listOf(image("original", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(original.id, "Safe")
        val edit = repository.createDraft(saved.id)
        val broken = File(root, "broken.jpg").apply { writeText("not an image") }
        var failed = false
        try {
            repository.addPages(edit.id, listOf(image("recoverable", Color.BLUE), Uri.fromFile(broken)), detectEdges = false)
        } catch (_: IOException) {
            failed = true
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Invalid input must fail", failed)
        assertEquals(saved, repository.getDocument(saved.id))
        val recovered = requireNotNull(repository.getDraft(edit.id))
        assertEquals(2, recovered.pages.size)
        recovered.pages.forEach { assertTrue(repository.sourceFile(it).isFile) }
        assertTrue(File(root, "sources").listFiles().orEmpty().none { it.extension == "part" })
        repository.close()
        repository = openRepository()
        assertEquals(recovered, repository.getDraft(edit.id))
    }

    @Test
    fun cleanupRetainsSharedSourceUntilLastReferenceIsRemoved() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("shared", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Shared")
        val edit = repository.createDraft(saved.id)
        val source = repository.sourceFile(saved.pages.single())
        repository.deleteDocument(saved.id)
        assertTrue(source.isFile)
        assertNotNull(repository.getDraft(edit.id))
        repository.close()
        repository = openRepository()
        assertTrue(source.isFile)
        repository.discardDraft(edit.id)
        assertFalse(source.exists())
    }

    @Test
    fun exportLeasePreventsConcurrentSourceReclamation() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("lease", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Lease")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val reader = launch {
                repository.withDocumentSources(saved) {
                    entered.complete(Unit)
                    release.await()
                    assertTrue(repository.sourceFile(saved.pages.single()).isFile)
                }
            }
            entered.await()
            val deletion = async { repository.deleteDocument(saved.id) }
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            reader.join()
            deletion.await()
        }
        assertFalse(repository.sourceFile(saved.pages.single()).exists())
    }

    @Test
    fun cancellationReleasesSourceLeaseAndLeavesSavedDocumentIntact() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("cancel", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Cancel")
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val operation = launch {
            repository.withDocumentSources(saved) {
                entered.complete(Unit)
                never.await()
            }
        }
        entered.await()
        operation.cancelAndJoin()
        assertEquals(saved, repository.getDocument(saved.id))
        repository.deleteDocument(saved.id)
        assertFalse(repository.sourceFile(saved.pages.single()).exists())
    }

    @Test
    fun initializationReclaimsOnlyUnreferencedManagedFiles() = runBlocking {
        val draft = repository.addPages(repository.createDraft().id, listOf(image("kept", Color.RED)), detectEdges = false)
        repository.close()
        val sources = File(root, "sources")
        val orphan = File(sources, "${UUID.randomUUID()}.jpg").apply { writeText("orphan") }
        val stage = File(sources, "${UUID.randomUUID()}.part").apply { writeText("interrupted") }
        val rawStage = File.createTempFile("image-input-", ".part", sources).apply { writeText("raw interrupted import") }
        val imageStage = File.createTempFile("image-output-", ".part", sources).apply { writeText("normalized interrupted import") }
        val unrelated = File(sources, "unrelated.txt").apply { writeText("keep") }
        repository = openRepository()
        assertEquals(draft, repository.getDraft(draft.id))
        assertTrue(repository.sourceFile(draft.pages.single()).isFile)
        assertFalse(orphan.exists())
        assertFalse(stage.exists())
        assertFalse(rawStage.exists())
        assertFalse(imageStage.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun jpegSelectionUsesDocumentOrderAndLeavesSourcesUnchanged() = runBlocking {
        val draft = repository.addPages(
            repository.createDraft().id,
            listOf(image("one", Color.RED), image("two", Color.GREEN), image("three", Color.BLUE)),
            detectEdges = false,
        )
        val saved = repository.saveDraft(draft.id, "Selected")
        val selected = linkedSetOf(saved.pages[2].id, saved.pages[0].id)
        val files = DocumentExporter(context, repository, processor).jpeg(saved, selected)
        exports.addAll(files)
        assertEquals(listOf("Selected-001.jpg", "Selected-003.jpg"), files.map { it.name })
        assertEquals(saved, repository.getDocument(saved.id))
        assertTrue(saved.pages.all { repository.sourceFile(it).isFile })
    }

    @Test
    fun emptyDraftCannotBeSavedAndRemainsRecoverable() = runBlocking {
        val draft = repository.createDraft()
        var rejected = false
        try {
            repository.saveDraft(draft.id, "Empty")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(draft, repository.getDraft(draft.id))
        assertTrue(repository.documents.first().isEmpty())
    }

    @Test
    fun retainedDraftCanSaveAfterItsSourceDocumentWasDeleted() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("retained", Color.BLUE)), detectEdges = false)
        val original = repository.saveDraft(imported.id, "Original")
        val retained = repository.createDraft(original.id)
        repository.deleteDocument(original.id)
        val recovered = repository.saveDraft(retained.id, "Recovered")
        assertTrue(recovered.id != original.id)
        assertEquals(retained.pages, recovered.pages)
        assertTrue(repository.sourceFile(recovered.pages.single()).isFile)
        assertEquals(listOf(recovered), repository.documents.first())
    }

    @Test
    fun fileUriExportCannotOverwritePrivateFiles() = runBlocking {
        val imported = repository.addPages(repository.createDraft().id, listOf(image("safe", Color.RED)), detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Safe")
        val exporter = DocumentExporter(context, repository, processor)
        val pdf = exporter.pdf(saved).also { exports.add(it) }
        val privateFile = File(root, "private.db").apply { writeText("must remain intact") }
        var rejected = false
        try {
            exporter.copyTo(pdf, Uri.fromFile(privateFile))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("must remain intact", privateFile.readText())
        assertEquals(saved, repository.getDocument(saved.id))
    }

    @Test
    fun twentyPagePdfPreservesEveryPageAndReportsExportCost() = runBlocking {
        val red = image("stress-red", Color.RED, 1000, 1400)
        val blue = image("stress-blue", Color.BLUE, 1000, 1400)
        val imported = repository.addPages(repository.createDraft().id, List(20) { if (it % 2 == 0) red else blue }, detectEdges = false)
        val saved = repository.saveDraft(imported.id, "Twenty pages")
        val start = android.os.SystemClock.elapsedRealtime()
        val pdf = DocumentExporter(context, repository, processor).pdf(saved).also { exports.add(it) }
        println("FreeScanner synthetic 20-page export: ${android.os.SystemClock.elapsedRealtime() - start} ms; ${pdf.length()} bytes")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(20, renderer.pageCount)
                repeat(20) { index ->
                    renderer.openPage(index).use { assertRenderedColor(it, if (index % 2 == 0) Color.RED else Color.BLUE) }
                }
            }
        }
        assertEquals(saved, repository.getDocument(saved.id))
    }

    private fun openRepository() = DocumentRepository(context, processor, databaseName, root)

    private fun image(name: String, color: Int, width: Int = 120, height: Int = 240): Uri {
        val file = File(root, "$name.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return Uri.fromFile(file)
    }

    private fun assertRenderedColor(page: PdfRenderer.Page, expected: Int) {
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue(kotlin.math.abs(Color.red(color) - Color.red(expected)) < 20)
            assertTrue(kotlin.math.abs(Color.blue(color) - Color.blue(expected)) < 20)
        } finally {
            bitmap.recycle()
        }
    }
}
