package com.freescanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentDatabaseMigrationTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private var repository: DocumentRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        root = File(context.cacheDir, "migration-test-$id").apply { check(mkdirs()) }
        databaseName = "migration-test-$id.db"
    }

    @After
    fun tearDown() = runBlocking {
        repository?.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun migrationFromVersionOnePreservesSavedDraftPagesAndManagedSourceBytes() = runBlocking {
        val first = legacyPage("legacy-page-one", Color.RED, 90, PageFilter.GRAYSCALE)
        val second = legacyPage("legacy-page-two", Color.BLUE, 270, PageFilter.ENHANCED)
        val expectedDocument = ScanDocument("legacy-document", "Existing scan", 1000L, 2000L, listOf(first, second))
        val expectedDraft = ScanDraft("legacy-draft", expectedDocument.id, "Existing scan", 4000L, listOf(second, first))
        val originalBytes = listOf(first, second).associate { it.sourcePath to File(root, it.sourcePath).readBytes() }
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { old ->
            // Frozen SQL from app/schemas/com.freescanner.data.DocumentDatabase/1.json.
            // The current Room entities must not be used to create the pre-migration fixture.
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`sourceDocumentId` TEXT, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
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
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '212d31987c6124b7c3d4026111cf2c28')")
            old.execSQL(
                "INSERT INTO documents (id,status,sourceDocumentId,title,createdAt,updatedAt) VALUES(?,?,?,?,?,?)",
                arrayOf(expectedDocument.id, "saved", null, expectedDocument.title, expectedDocument.createdAt, expectedDocument.updatedAt),
            )
            old.execSQL(
                "INSERT INTO documents (id,status,sourceDocumentId,title,createdAt,updatedAt) VALUES(?,?,?,?,?,?)",
                arrayOf(expectedDraft.id, "draft", expectedDocument.id, expectedDraft.title, 3000L, expectedDraft.updatedAt),
            )
            // Insert in reverse physical order to prove stored positions, not row order, survive.
            insertLegacyPage(old, expectedDocument.id, second, 1)
            insertLegacyPage(old, expectedDocument.id, first, 0)
            insertLegacyPage(old, expectedDraft.id, first, 1)
            insertLegacyPage(old, expectedDraft.id, second, 0)
            old.version = 1
        }

        val migrated = openRepository().also { repository = it }
        assertEquals(expectedDocument, migrated.getDocument(expectedDocument.id))
        assertEquals(expectedDraft, migrated.getDraft(expectedDraft.id))
        assertEquals(listOf(expectedDocument), migrated.documents.first())
        assertEquals(listOf(expectedDraft), migrated.drafts.first())
        assertTrue(migrated.folders.first().isEmpty())
        originalBytes.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(File(root, path).readBytes())) }

        val folder = migrated.createFolder("Migrated files")
        migrated.moveDocuments(setOf(expectedDocument.id), folder.id)
        migrated.close()
        val reopened = openRepository().also { repository = it }
        assertEquals(expectedDocument.copy(folderId = folder.id), requireNotNull(reopened.getDocument(expectedDocument.id)).copy(updatedAt = expectedDocument.updatedAt))
        assertEquals(expectedDraft, reopened.getDraft(expectedDraft.id))
        assertEquals(listOf(folder), reopened.folders.first())
        originalBytes.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(File(root, path).readBytes())) }
    }

    private fun openRepository() = DocumentRepository(context, ImageProcessor(context), databaseName, root)

    private fun legacyPage(id: String, color: Int, rotation: Int, filter: PageFilter): ScanPage {
        val path = "sources/${UUID.randomUUID()}.jpg"
        val file = File(root, path)
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally {
            bitmap.recycle()
        }
        return ScanPage(id, path, Crop.Full, rotation, filter)
    }

    private fun insertLegacyPage(database: SQLiteDatabase, ownerId: String, page: ScanPage, position: Int) {
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
