package com.freescanner.ocr

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.ExportFormat
import com.freescanner.model.OcrText
import com.freescanner.model.ScanPage
import com.freescanner.ui.TextEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageSwitchKeepsCorrectionsAndExportWaitsForExplicitSave() {
        var pages by mutableStateOf(listOf(
            ScanPage("one", "unused-one", text = OcrText("First original")),
            ScanPage("two", "unused-two", text = OcrText("Second original")),
        ))
        var selected by mutableStateOf<String?>("one")
        val saves = mutableListOf<Pair<String, String>>()
        val exports = mutableListOf<ExportFormat>()
        compose.setContent {
            MaterialTheme {
                TextEditorScreen(
                    pages = pages, selectedPageId = selected, onSelectPage = { selected = it },
                    onRecognize = {}, onSaveText = { id, value ->
                        saves += id to value
                        pages = pages.map { if (it.id == id) it.copy(text = OcrText(value)) else it }
                    }, onExport = { exports += it }, onBack = {},
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("First corrected 😀")
        compose.onNodeWithText("Ekspor TXT").assertIsNotEnabled()
        compose.onNodeWithText("Halaman 2").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Second original")
        compose.onNodeWithText("Halaman 1 • belum disimpan").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("First corrected 😀")
        compose.runOnIdle { assertEquals(emptyList<Pair<String, String>>(), saves) }
        compose.onNodeWithText("Simpan teks halaman").performScrollTo().performClick()
        compose.onNodeWithText("Ekspor TXT").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("one" to "First corrected 😀"), saves)
            assertEquals(listOf(ExportFormat.TXT), exports)
        }
    }

    @Test fun rerecognitionRequiresConfirmationAndCanceledDialogPreservesCorrections() {
        val pages = listOf(ScanPage("one", "unused", text = OcrText("Saved original")))
        val recognition = mutableListOf<Set<String>>()
        compose.setContent {
            MaterialTheme {
                TextEditorScreen(pages, "one", {}, { recognition += it }, { _, _ -> }, {}, {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Unsaved correction")
        compose.onNodeWithText("Kenali teks halaman").performScrollTo().performClick()
        compose.onNodeWithText("Batal").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsaved correction")
        compose.runOnIdle { assertEquals(emptyList<Set<String>>(), recognition) }
        compose.onNodeWithText("Kenali teks halaman").performScrollTo().performClick()
        compose.onNodeWithText("Kenali teks lagi").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf("one")), recognition) }
    }

    @Test fun documentExportRequiresEveryPageButAllowsGenuinelyEmptyRecognizedPages() {
        var pages by mutableStateOf(listOf(
            ScanPage("one", "unused-one", text = OcrText("Actual text")),
            ScanPage("two", "unused-two", text = null),
        ))
        val exports = mutableListOf<ExportFormat>()
        compose.setContent {
            MaterialTheme {
                TextEditorScreen(pages, "one", {}, {}, { _, _ -> }, { exports += it }, {})
            }
        }
        compose.onNodeWithText("Ekspor TXT").assertIsNotEnabled()
        compose.onNodeWithText("Ekspor Word (.docx)").assertIsNotEnabled()
        compose.runOnIdle {
            pages = pages.map { if (it.id == "two") it.copy(text = OcrText("")) else it }
        }
        compose.onNodeWithText("Ekspor TXT").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ExportFormat.TXT), exports) }
    }
}
