package com.freescanner.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private val documents = listOf(
        ScanDocument("b", "Travel receipt", 100, 400, emptyList(), "travel"),
        ScanDocument("a", "Annual invoice", 300, 200, emptyList()),
        ScanDocument("c", "Bank statement", 200, 300, emptyList(), "finance"),
    )

    @Test fun allDocumentsIncludesFoldersButFolderScopeRestrictsSearch() {
        assertEquals(listOf("b", "c", "a"), LibraryQuery.documents(documents).map { it.id })
        assertEquals(listOf("b"), LibraryQuery.documents(documents, " RECEIPT ").map { it.id })
        assertTrue(LibraryQuery.documents(documents, "receipt", "finance").isEmpty())
        assertEquals(listOf("c"), LibraryQuery.documents(documents, folderId = "finance").map { it.id })
        assertTrue(LibraryQuery.documents(documents, folderId = "empty").isEmpty())
    }

    @Test fun eachSortOrderUsesItsNamedField() {
        val expected = mapOf(
            LibrarySort.MODIFIED_NEWEST to listOf("b", "c", "a"),
            LibrarySort.MODIFIED_OLDEST to listOf("a", "c", "b"),
            LibrarySort.CREATED_NEWEST to listOf("a", "c", "b"),
            LibrarySort.CREATED_OLDEST to listOf("b", "c", "a"),
            LibrarySort.NAME_ASCENDING to listOf("a", "c", "b"),
            LibrarySort.NAME_DESCENDING to listOf("b", "c", "a"),
        )
        expected.forEach { (sort, ids) ->
            assertEquals(sort.label, ids, LibraryQuery.documents(documents, sort = sort).map { it.id })
        }
    }

    @Test fun equalDatesAndCaseInsensitiveNamesUseAscendingIdForEverySort() {
        val equal = listOf(
            ScanDocument("z", "INVOICE", 100, 200, emptyList()),
            ScanDocument("a", "invoice", 100, 200, emptyList()),
        )
        LibrarySort.entries.forEach { sort ->
            assertEquals(sort.label, listOf("a", "z"), LibraryQuery.documents(equal, sort = sort).map { it.id })
        }
    }

    @Test fun searchAndNameSortDoNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val invoices = listOf(
                ScanDocument("z", "Invoice", 1, 1, emptyList()),
                ScanDocument("a", "invoice", 1, 1, emptyList()),
            )
            assertEquals(listOf("a", "z"), LibraryQuery.documents(invoices, "INVOICE", sort = LibrarySort.NAME_ASCENDING).map { it.id })
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun draftsRemainSeparateSearchableAndScopedWithNewestFirst() {
        val drafts = listOf(
            ScanDraft("b", null, "Trip invoice", 200, emptyList(), "travel"),
            ScanDraft("a", "saved", "Trip invoice edits", 200, emptyList()),
            ScanDraft("c", null, "Letter", 100, emptyList(), "travel"),
        )
        assertEquals(listOf("a", "b"), LibraryQuery.drafts(drafts, "INVOICE").map { it.id })
        assertEquals(listOf("b"), LibraryQuery.drafts(drafts, "invoice", "travel").map { it.id })
        assertEquals(listOf("b", "c"), LibraryQuery.drafts(drafts, folderId = "travel").map { it.id })
        assertTrue(LibraryQuery.drafts(drafts, "absent").isEmpty())
    }
}
