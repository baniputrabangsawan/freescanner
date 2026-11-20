package com.freescanner.model

import java.util.Locale

enum class LibrarySort(val label: String) {
    MODIFIED_NEWEST("Diubah terbaru"),
    MODIFIED_OLDEST("Diubah terlama"),
    CREATED_NEWEST("Dibuat terbaru"),
    CREATED_OLDEST("Dibuat terlama"),
    NAME_ASCENDING("Nama A–Z"),
    NAME_DESCENDING("Nama Z–A"),
}

/** A null folder is the All documents view, not an unfiled-only filter. */
object LibraryQuery {
    fun documents(
        documents: List<ScanDocument>,
        search: String = "",
        folderId: String? = null,
        sort: LibrarySort = LibrarySort.MODIFIED_NEWEST,
    ): List<ScanDocument> {
        val query = search.trim().lowercase(Locale.ROOT)
        val comparator = when (sort) {
            LibrarySort.MODIFIED_NEWEST -> compareByDescending<ScanDocument> { it.updatedAt }
            LibrarySort.MODIFIED_OLDEST -> compareBy<ScanDocument> { it.updatedAt }
            LibrarySort.CREATED_NEWEST -> compareByDescending<ScanDocument> { it.createdAt }
            LibrarySort.CREATED_OLDEST -> compareBy<ScanDocument> { it.createdAt }
            LibrarySort.NAME_ASCENDING -> compareBy<ScanDocument> { it.title.lowercase(Locale.ROOT) }
            LibrarySort.NAME_DESCENDING -> compareByDescending<ScanDocument> { it.title.lowercase(Locale.ROOT) }
        }.thenBy { it.id }
        return documents.filter {
            (folderId == null || it.folderId == folderId) && it.title.lowercase(Locale.ROOT).contains(query)
        }.sortedWith(comparator)
    }

    /** Drafts have no creation date and always appear newest first in their own section. */
    fun drafts(drafts: List<ScanDraft>, search: String = "", folderId: String? = null): List<ScanDraft> {
        val query = search.trim().lowercase(Locale.ROOT)
        return drafts.filter {
            (folderId == null || it.folderId == folderId) && it.title.lowercase(Locale.ROOT).contains(query)
        }.sortedWith(compareByDescending<ScanDraft> { it.updatedAt }.thenBy { it.id })
    }
}
