package com.freescanner.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
internal data class FolderRecord(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(
        entity = FolderRecord::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("folderId")],
)
internal data class DocumentRecord(
    @PrimaryKey val id: String,
    val status: String,
    val sourceDocumentId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val folderId: String? = null,
)

@Entity(
    tableName = "pages",
    primaryKeys = ["ownerId", "id"],
    foreignKeys = [ForeignKey(
        entity = DocumentRecord::class,
        parentColumns = ["id"],
        childColumns = ["ownerId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("ownerId"), Index("sourcePath")],
)
internal data class PageRecord(
    val ownerId: String,
    val id: String,
    val position: Int,
    val sourcePath: String,
    val rotation: Int,
    val filter: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
    @ColumnInfo(defaultValue = "'[]'") val annotationsJson: String = "[]",
    @ColumnInfo(defaultValue = "NULL") val ocrJson: String? = null,
    @ColumnInfo(defaultValue = "'DOCUMENT'") val kind: String = "DOCUMENT",
)

internal data class DocumentWithPages(
    @Embedded val document: DocumentRecord,
    @Relation(parentColumn = "id", entityColumn = "ownerId") val pages: List<PageRecord>,
)

@Dao
internal interface DocumentDao {
    @Transaction
    @Query("SELECT * FROM documents WHERE status = :status ORDER BY updatedAt DESC, id")
    fun observe(status: String): Flow<List<DocumentWithPages>>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentWithPages?

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id")
    fun observeFolders(): Flow<List<FolderRecord>>

    @Query("SELECT * FROM folders")
    suspend fun getFolders(): List<FolderRecord>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: String): FolderRecord?

    @Insert
    suspend fun insertFolder(record: FolderRecord)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM documents WHERE folderId = :id)")
    suspend fun folderHasDocuments(id: String): Boolean

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("UPDATE documents SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveDocument(id: String, folderId: String?, updatedAt: Long)

    @Query("SELECT DISTINCT sourcePath FROM pages")
    suspend fun referencedSources(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDocument(record: DocumentRecord)

    @Insert
    suspend fun insertPages(pages: List<PageRecord>)

    @Query("DELETE FROM pages WHERE ownerId = :id")
    suspend fun deletePages(id: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)
}

@Database(entities = [FolderRecord::class, DocumentRecord::class, PageRecord::class], version = 3, exportSchema = true)
internal abstract class DocumentDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "ALTER TABLE `documents` ADD COLUMN `folderId` TEXT " +
                        "REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_folderId` ON `documents` (`folderId`)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `annotationsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `ocrJson` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `pages` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'DOCUMENT'")
            }
        }
    }
}
