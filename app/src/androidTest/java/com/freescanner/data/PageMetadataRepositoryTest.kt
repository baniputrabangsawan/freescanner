package com.freescanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.Corner
import com.freescanner.model.Crop
import com.freescanner.model.OcrLine
import com.freescanner.model.OcrText
import com.freescanner.model.PageAnnotation
import com.freescanner.model.PageFilter
import com.freescanner.model.PageKind
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanPage
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageMetadataRepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var inputCache: File
    private lateinit var databaseName: String
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        root = File(context.filesDir, "page-metadata-test-$id").apply { check(mkdirs()) }
        inputCache = File(context.cacheDir, "page-metadata-input-$id").apply { check(mkdirs()) }
        databaseName = "page-metadata-test-$id.db"
        repository = openRepository()
    }

    @After
    fun tearDown() = runBlocking {
        try {
            repository.close()
        } finally {
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
            inputCache.deleteRecursively()
        }
        Unit
    }

    @Test
    fun annotationsAndTextSurviveSaveCopyMergeAndReopenWhileDraftEditsStayIsolated() = runBlocking {
        var draft = generatedDraft(kind = PageKind.ID_CARD)
        draft = repository.addGeneratedPages(draft.id, listOf(image()))
        for ((index, page) in draft.pages.withIndex()) {
            val edited = repository.updatePage(draft.id, page.copy(
                rotation = index * 90,
                filter = if (index == 0) PageFilter.GRAYSCALE else PageFilter.ENHANCED,
                annotations = annotations("Page $index: café \"quoted\"\nsecond line"),
            )).pages[index]
            repository.setPageText(draft.id, edited, recognizedText("Page $index — résumé"))
        }
        draft = repository.movePage(draft.id, draft.pages.last().id, -1)
        val original = repository.saveDraft(draft.id, "Annotated original")
        assertEquals(draft.pages, original.pages)
        val sourceBytes = original.pages.associate { it.sourcePath to repository.sourceFile(it).readBytes() }
        val duplicate = repository.duplicateDocument(original.id)
        assertNotEquals(original.id, duplicate.id)
        assertFreshCopies(original.pages, duplicate.pages)

        val edit = repository.createDraft(original.id)
        val editedPage = repository.updatePage(edit.id, edit.pages[0].copy(annotations = annotations("Draft only"))).pages[0]
        val changedDraft = repository.setPageText(edit.id, editedPage, OcrText("Private draft correction"))
        assertEquals(original, repository.getDocument(original.id))
        assertEquals(duplicate, repository.getDocument(duplicate.id))

        val mergedDraft = repository.mergeDocuments(listOf(duplicate.id, original.id), "Merged metadata")
        assertFreshCopies(duplicate.pages + original.pages, mergedDraft.pages)
        val merged = repository.saveDraft(mergedDraft.id, mergedDraft.title)
        assertEquals(mergedDraft.pages, merged.pages)
        reopen()
        assertEquals(original, repository.getDocument(original.id))
        assertEquals(duplicate, repository.getDocument(duplicate.id))
        assertEquals(merged, repository.getDocument(merged.id))
        assertEquals(changedDraft, repository.getDraft(edit.id))
        for (page in merged.pages) {
            assertArrayEquals(sourceBytes.getValue(page.sourcePath), repository.sourceFile(page).readBytes())
        }
    }

    @Test
    fun clockwiseRotationMovesInkAndLabelTogetherAndDropsOldTextOnlyInDraft() = runBlocking {
        val draft = generatedDraft(kind = PageKind.ID_CARD)
        val initial = repository.updatePage(draft.id, draft.pages.single().copy(annotations = annotations("Signature")))
        val recognized = repository.setPageText(draft.id, initial.pages.single(), recognizedText("Signed"))
        val original = repository.saveDraft(draft.id, "Signed original")
        val edit = repository.createDraft(original.id)
        val before = edit.pages.single()
        val ink = before.annotations[0] as PageAnnotation.Ink
        val label = before.annotations[1] as PageAnnotation.Label
        val rotated = repository.updatePage(edit.id, before.copy(rotation = 90))
        assertEquals(before.copy(
            rotation = 90,
            annotations = listOf(
                ink.copy(points = listOf(Corner(0.75f, 0.125f), Corner(0.25f, 0.5f))),
                label.copy(x = 0.25f, y = 0.25f, rotation = 120f),
            ),
            text = null,
        ), rotated.pages.single())
        assertEquals(recognized.pages, original.pages)
        assertEquals(original, repository.getDocument(original.id))
        reopen()
        assertEquals(rotated, repository.getDraft(edit.id))
        assertEquals(original, repository.getDocument(original.id))
    }

    @Test
    fun filterCropAndAnnotationChangesInvalidateOnlyTheAffectedPagesText() = runBlocking {
        var draft = generatedDraft(kind = PageKind.ID_CARD)
        draft = repository.addGeneratedPages(draft.id, listOf(image(), image()), PageKind.ID_CARD)
        for (page in draft.pages) repository.setPageText(draft.id, page, recognizedText(page.id))
        draft = requireNotNull(repository.getDraft(draft.id))
        val first = draft.pages[0]
        val filtered = repository.applyFilter(draft.id, setOf(first.id), PageFilter.BLACK_WHITE)
        assertEquals(first.copy(filter = PageFilter.BLACK_WHITE, text = null), filtered.pages[0])
        assertEquals(draft.pages.drop(1), filtered.pages.drop(1))

        val crop = Crop(listOf(Corner(0.125f, 0.125f), Corner(0.875f, 0.125f), Corner(0.875f, 0.875f), Corner(0.125f, 0.875f)))
        val cropped = repository.updatePage(draft.id, filtered.pages[1].copy(crop = crop))
        assertEquals(filtered.pages[1].copy(crop = crop, kind = PageKind.DOCUMENT, text = null), cropped.pages[1])
        assertEquals(filtered.pages[0], cropped.pages[0])
        assertEquals(filtered.pages[2], cropped.pages[2])

        val annotated = repository.updatePage(draft.id, cropped.pages[2].copy(annotations = annotations("Confidential")))
        assertEquals(cropped.pages[2].copy(annotations = annotations("Confidential"), text = null), annotated.pages[2])
        assertEquals(cropped.pages.take(2), annotated.pages.take(2))
        reopen()
        assertEquals(annotated, repository.getDraft(draft.id))
    }

    @Test
    fun lateRecognitionCannotOverwriteNewerImageEditOrManualTextCorrection() = runBlocking {
        val draft = generatedDraft()
        val pendingRecognition = draft.pages.single()
        val editedImage = repository.updatePage(draft.id, pendingRecognition.copy(filter = PageFilter.GRAYSCALE))
        expectRejected { repository.setPageText(draft.id, pendingRecognition, recognizedText("Stale image result")) }
        assertEquals(editedImage, repository.getDraft(draft.id))

        val recognized = repository.setPageText(draft.id, editedImage.pages.single(), recognizedText("Original spelling"))
        val pendingAgain = recognized.pages.single()
        val correction = requireNotNull(pendingAgain.text).edited("Corrected spelling — café")
        val corrected = repository.setPageText(draft.id, pendingAgain, correction)
        expectRejected { repository.setPageText(draft.id, pendingAgain, recognizedText("Late recognition result")) }
        assertEquals(corrected, repository.getDraft(draft.id))
        assertEquals(OcrText("Corrected spelling — café"), corrected.pages.single().text)
        reopen()
        assertEquals(corrected, repository.getDraft(draft.id))
    }

    @Test
    fun generatedPrivateJpegKeepsExactEncodedBytesAndKindAfterInputDeletionAndReopen() = runBlocking {
        val input = image(width = 137, height = 93)
        val encoded = input.readBytes()
        val imported = repository.addGeneratedPages(repository.createDraft().id, listOf(input), PageKind.ID_CARD)
        val page = imported.pages.single()
        assertEquals(PageKind.ID_CARD, page.kind)
        assertNotEquals(input.canonicalFile, repository.sourceFile(page).canonicalFile)
        assertArrayEquals(encoded, repository.sourceFile(page).readBytes())
        assertArrayEquals(encoded, input.readBytes())
        assertTrue(input.delete())
        val saved = repository.saveDraft(imported.id, "Generated card")
        reopen()
        assertEquals(saved, repository.getDocument(saved.id))
        assertArrayEquals(encoded, repository.sourceFile(page).readBytes())
    }

    @Test
    fun rejectedGeneratedInputsLeaveExistingDraftAndManagedSourcesUnchanged() = runBlocking {
        val draft = generatedDraft()
        val page = repository.updatePage(draft.id, draft.pages.single().copy(annotations = annotations("Keep me"))).pages.single()
        val before = repository.setPageText(draft.id, page, recognizedText("Keep this text"))
        val sourceDirectory = File(root, "sources")
        val sourceBytes = requireNotNull(sourceDirectory.listFiles()).associate { it.name to it.readBytes() }
        val outsideCache = image(directory = root)
        val png = image(format = Bitmap.CompressFormat.PNG)
        val oversized = image(width = 3001, height = 1)
        val inputs = listOf(outsideCache, png, oversized)
        val inputBytes = inputs.associateWith { it.readBytes() }
        for (input in inputs) {
            expectRejected { repository.addGeneratedPages(draft.id, listOf(input), PageKind.ID_CARD) }
            assertEquals(before, repository.getDraft(draft.id))
            assertEquals(sourceBytes.keys, requireNotNull(sourceDirectory.listFiles()).map { it.name }.toSet())
            for ((name, bytes) in sourceBytes) assertArrayEquals(bytes, File(sourceDirectory, name).readBytes())
            assertArrayEquals(inputBytes.getValue(input), input.readBytes())
        }
        assertFalse(outsideCache.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        reopen()
        assertEquals(before, repository.getDraft(draft.id))
    }

    private fun openRepository() = DocumentRepository(context, ImageProcessor(context), databaseName, root)

    private suspend fun reopen() {
        repository.close()
        repository = openRepository()
    }

    private suspend fun generatedDraft(kind: PageKind = PageKind.DOCUMENT): ScanDraft =
        repository.addGeneratedPages(repository.createDraft().id, listOf(image()), kind)

    private fun annotations(label: String): List<PageAnnotation> = listOf(
        PageAnnotation.Ink(listOf(Corner(0.125f, 0.25f), Corner(0.5f, 0.75f)), Color.BLUE, 0.015f, true),
        PageAnnotation.Label(label, 0.25f, 0.75f, 0.08f, Color.RED, 30f, 0.35f, true, 0.45f),
    )

    private fun recognizedText(text: String) = OcrText(text, listOf(OcrLine(text, 0.125f, 0.25f, 0.875f, 0.5f)))

    private fun assertFreshCopies(expected: List<ScanPage>, actual: List<ScanPage>) {
        assertEquals(expected.size, actual.size)
        assertEquals(actual.size, actual.map { it.id }.toSet().size)
        assertTrue(actual.none { copy -> expected.any { it.id == copy.id } })
        assertEquals(expected, actual.zip(expected) { copy, source -> copy.copy(id = source.id) })
    }

    private fun image(
        directory: File = inputCache,
        width: Int = 120,
        height: Int = 80,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): File {
        val file = File(directory, "input-${UUID.randomUUID()}.image")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            // Nonuniform pixels and deliberately low JPEG quality expose an accidental re-encode.
            for (x in 0 until width step 3) bitmap.setPixel(x, height / 2, Color.rgb(x % 256, 45, 197))
            file.outputStream().use { check(bitmap.compress(format, 63, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: IOException) {
            return
        }
        throw AssertionError("The stale result or unsafe generated page must be rejected without mutation.")
    }
}
