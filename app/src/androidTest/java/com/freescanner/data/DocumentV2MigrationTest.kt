package com.freescanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanFolder
import com.freescanner.model.ScanPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentV2MigrationTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private var repository: DocumentRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        root = File(context.filesDir, "v2-metadata-migration-$id").apply { check(mkdirs()) }
        databaseName = "v2-metadata-migration-$id.db"
    }

    @After
    fun tearDown() = runBlocking {
        try {
            repository?.close()
        } finally {
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun versionTwoRetainsIndependentSavedAndDraftRecipesFoldersOrderAndSourceBytes() = runBlocking {
        val savedFolder = ScanFolder("saved-folder", "Archive", 500L)
        val draftFolder = ScanFolder("draft-folder", "Working", 600L)
        val first = legacyPage("same-page-one", Color.RED, 90, PageFilter.GRAYSCALE)
        val second = legacyPage("same-page-two", Color.BLUE, 270, PageFilter.ENHANCED)
        val saved = ScanDocument("saved-id", "Saved résumé", 1000L, 2000L, listOf(first, second), savedFolder.id)
        val draft = ScanDraft("draft-id", saved.id, "Unsaved receipt", 4000L, listOf(
            second.copy(rotation = 180, filter = PageFilter.BLACK_WHITE),
            first.copy(crop = Crop.Full, rotation = 0),
        ), draftFolder.id)
        val sourceBytes = saved.pages.associate { it.sourcePath to File(root, it.sourcePath).readBytes() }
        createVersionTwoDatabase(saved, draft, listOf(savedFolder, draftFolder))

        val migrated = openRepository().also { repository = it }
        // Full model equality includes new annotations=[], text=null and kind=DOCUMENT on legacy pages,
        // without overlooking any of the pre-existing metadata or shared page IDs across owners.
        assertEquals(saved, migrated.getDocument(saved.id))
        assertEquals(draft, migrated.getDraft(draft.id))
        assertEquals(listOf(saved), migrated.documents.first())
        assertEquals(listOf(draft), migrated.drafts.first())
        assertEquals(listOf(savedFolder, draftFolder), migrated.folders.first())
        for ((path, bytes) in sourceBytes) assertArrayEquals(bytes, File(root, path).readBytes())

        // Exercise the added columns on an existing row rather than merely validating migration SQL.
        val page = draft.pages[0].copy(annotations = listOf(
            PageAnnotation.Ink(listOf(Corner(0.25f, 0.25f), Corner(0.75f, 0.5f)), Color.BLUE, 0.01f),
            PageAnnotation.Label("Migrated draft", 0.25f, 0.5f, opacity = 0.4f),
        ))
        val annotated = migrated.updatePage(draft.id, page)
        val changedDraft = migrated.setPageText(draft.id, annotated.pages[0], OcrText(
            "Migrated café", listOf(OcrLine("Migrated café", 0.125f, 0.25f, 0.875f, 0.5f)),
        ))
        assertEquals(draft.pages.drop(1), changedDraft.pages.drop(1))
        assertEquals(saved, migrated.getDocument(saved.id))
        migrated.close()
        repository = null
        val reopened = openRepository().also { repository = it }
        assertEquals(saved, reopened.getDocument(saved.id))
        assertEquals(changedDraft, reopened.getDraft(draft.id))
        assertEquals(listOf(savedFolder, draftFolder), reopened.folders.first())
        for ((path, bytes) in sourceBytes) assertArrayEquals(bytes, File(root, path).readBytes())
    }

    private fun openRepository() = DocumentRepository(context, ImageProcessor(context), databaseName, root)

    private fun createVersionTwoDatabase(saved: ScanDocument, draft: ScanDraft, folders: List<ScanFolder>) {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            // Frozen schema 2 SQL/identity from app/schemas/com.freescanner.data.DocumentDatabase/2.json.
            // Creating this through current Room entities would conceal destructive migration bugs.
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`sourceDocumentId` TEXT, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `folderId` TEXT, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
            )
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_folderId` ON `documents` (`folderId`)")
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `pages` (`ownerId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `sourcePath` TEXT NOT NULL, `rotation` INTEGER NOT NULL, " +
                    "`filter` TEXT NOT NULL, `x0` REAL NOT NULL, `y0` REAL NOT NULL, `x1` REAL NOT NULL, " +
                    "`y1` REAL NOT NULL, `x2` REAL NOT NULL, `y2` REAL NOT NULL, `x3` REAL NOT NULL, " +
                    "`y3` REAL NOT NULL, PRIMARY KEY(`ownerId`, `id`), FOREIGN KEY(`ownerId`) " +
                    "REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_ownerId` ON `pages` (`ownerId`)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_sourcePath` ON `pages` (`sourcePath`)")
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '121607872ca6c084e5bac7ba51a3b6f5')")
            for (folder in folders) {
                old.execSQL("INSERT INTO folders (id,name,createdAt) VALUES(?,?,?)", arrayOf(folder.id, folder.name, folder.createdAt))
            }
            old.execSQL(
                "INSERT INTO documents (id,status,sourceDocumentId,title,createdAt,updatedAt,folderId) VALUES(?,?,?,?,?,?,?)",
                arrayOf(saved.id, "saved", null, saved.title, saved.createdAt, saved.updatedAt, saved.folderId),
            )
            old.execSQL(
                "INSERT INTO documents (id,status,sourceDocumentId,title,createdAt,updatedAt,folderId) VALUES(?,?,?,?,?,?,?)",
                arrayOf(draft.id, "draft", draft.sourceDocumentId, draft.title, 3000L, draft.updatedAt, draft.folderId),
            )
            // Reverse physical insertion proves each owner's stored positions, not row order, win.
            for ((position, page) in saved.pages.withIndex().reversed()) insertPage(old, saved.id, page, position)
            for ((position, page) in draft.pages.withIndex().reversed()) insertPage(old, draft.id, page, position)
            old.version = 2
        }
    }

    private fun legacyPage(id: String, color: Int, rotation: Int, filter: PageFilter): ScanPage {
        val path = "sources/${UUID.randomUUID()}.jpg"
        val file = File(root, path)
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, it)) }
        } finally {
            bitmap.recycle()
        }
        val crop = Crop(listOf(Corner(0.125f, 0.25f), Corner(0.875f, 0.125f), Corner(0.75f, 0.875f), Corner(0.25f, 0.75f)))
        return ScanPage(id, path, crop, rotation, filter)
    }

    private fun insertPage(database: SQLiteDatabase, ownerId: String, page: ScanPage, position: Int) {
        val corners = page.crop.corners
        database.execSQL(
            "INSERT INTO pages (ownerId,id,position,sourcePath,rotation,filter,x0,y0,x1,y1,x2,y2,x3,y3) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                ownerId, page.id, position, page.sourcePath, page.rotation, page.filter.name,
                corners[0].x, corners[0].y, corners[1].x, corners[1].y,
                corners[2].x, corners[2].y, corners[3].x, corners[3].y,
            ),
        )
    }
}
