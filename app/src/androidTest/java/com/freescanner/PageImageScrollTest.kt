package com.freescanner

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.ui.PageImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageImageScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullPageDragReachesTheNextPageWithoutZooming(): Unit = withPreview(1200) {
        swipePreview()
        compose.onNodeWithText("Halaman berikutnya").assertIsDisplayed()
        compose.onNodeWithText("Atur ulang perbesaran").assertDoesNotExist()
    }

    @Test fun libraryThumbnailDragScrollsTheContainingList(): Unit = withPreview(360) {
        swipePreview()
        compose.onNodeWithText("Halaman berikutnya").assertIsDisplayed()
    }

    @Test fun pinchKeepsImagePanningAndResetReturnsSingleFingerScrolling(): Unit = withPreview(320) {
        compose.onNodeWithTag("scroll-preview").performTouchInput {
            pinch(
                Offset(width * .4f, height * .35f), Offset(width * .6f, height * .45f),
                Offset(width * .2f, height * .2f), Offset(width * .8f, height * .6f),
                durationMillis = 400,
            )
        }
        compose.onNodeWithText("Atur ulang perbesaran").assertIsDisplayed()
        swipePreview()
        compose.onNodeWithText("Halaman berikutnya").assertIsNotDisplayed()
        compose.onNodeWithText("Atur ulang perbesaran").performClick()
        swipePreview()
        compose.onNodeWithText("Halaman berikutnya").assertIsDisplayed()
    }

    private fun swipePreview() {
        // Start inside the rendered image, not its label or the list's empty margins.
        compose.onNodeWithTag("scroll-preview").performTouchInput {
            swipeUp(startY = height * .6f, endY = height * .1f, durationMillis = 400)
        }
    }

    private fun withPreview(maxDimension: Int, block: () -> Unit): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
        val input = File(app.cacheDir, "scroll-input-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(100, 160, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val store = ViewModelStore()
        val draft = app.repository.createDraft()
        try {
            val page = app.repository.addPages(draft.id, listOf(Uri.fromFile(input)), detectEdges = false).pages.single()
            lateinit var model: AppViewModel
            compose.runOnUiThread {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        AppViewModel(app, SavedStateHandle()) as T
                })[AppViewModel::class.java]
            }
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.width(260.dp).height(360.dp).verticalScroll(rememberScrollState())) {
                        PageImage(page, model, Modifier.fillMaxWidth().height(480.dp).testTag("scroll-preview"), "Halaman uji", maxDimension)
                        Text("Halaman berikutnya", Modifier.fillMaxWidth().height(200.dp))
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Halaman uji").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Halaman uji").assertIsDisplayed()
            compose.onNodeWithText("Halaman berikutnya").assertIsNotDisplayed()
            block()
        } finally {
            compose.runOnUiThread { store.clear() }
            app.repository.discardDraft(draft.id)
            input.delete()
        }
    }
}
