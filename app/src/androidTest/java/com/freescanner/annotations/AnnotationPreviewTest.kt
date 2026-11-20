package com.freescanner.annotations

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.model.Corner
import com.freescanner.model.PageAnnotation
import com.freescanner.model.ScanPage
import com.freescanner.ui.AnnotationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnotationPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewIgnoresBitmapDensityAndDoesNotDoubleApplyHighlightsOrRecyclePublishedImages() {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            density = 72 // Deliberately differs from the display density.
        }
        val stroke = PageAnnotation.Ink(listOf(Corner(.2f, .5f), Corner(.8f, .5f)), Color.YELLOW, .1f, true)
        val session = AnnotationSession().apply { open(ScanPage("preview", "unused", annotations = listOf(stroke)), AnnotationTool.PEN) }
        val scene = AnnotationScene(bitmap.width, bitmap.height, session.annotations)
        val shown = mutableStateOf(true)
        compose.setContent {
            MaterialTheme { Box(Modifier.size(200.dp)) { if (shown.value) AnnotationPreview(bitmap, scene, session) } }
        }
        val pixels = compose.onNodeWithTag("annotation-preview").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2].toArgb()
        assertEquals(255, Color.red(center))
        assertEquals(255, Color.green(center))
        assertTrue(Color.blue(center) in 176..181)
        assertEquals(Color.WHITE, pixels[pixels.width / 2, pixels.height / 4].toArgb())
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        assertFalse(bitmap.isRecycled)
        // A published hardware display-list image is deliberately left to GC, including in tests.
    }

    @Test fun aRealGestureUsesFittedImageCoordinatesInsteadOfTheLetterboxedCanvas() {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val session = AnnotationSession().apply { open(ScanPage("gesture", "unused"), AnnotationTool.SIGN) }
        compose.setContent { MaterialTheme { Box(Modifier.size(200.dp)) { AnnotationPreview(bitmap, null, session) } } }
        compose.onNodeWithTag("annotation-preview").performTouchInput {
            down(Offset(width * .375f, height * .25f))
            moveTo(Offset(width * .625f, height * .75f))
            up()
        }
        compose.runOnIdle {
            val stroke = session.annotations.single() as PageAnnotation.Ink
            assertEquals(.25f, stroke.points.first().x, .01f)
            assertEquals(.25f, stroke.points.first().y, .01f)
            assertEquals(.75f, stroke.points.last().x, .01f)
            assertEquals(.75f, stroke.points.last().y, .01f)
            assertFalse(stroke.highlight)
        }
    }

    @Test fun retainedSessionPreservesUnsavedOverlaysAndCancelClearsOnlyTransientEdits() {
        val store = ViewModelStore()
        val original = PageAnnotation.Label("OWNED FIXTURE")
        val page = ScanPage("retained", "unused", annotations = listOf(original))
        compose.runOnIdle {
            val factory = ViewModelProvider.NewInstanceFactory()
            val first = ViewModelProvider(store, factory)[AnnotationSession::class.java]
            first.open(page, AnnotationTool.TEXT)
            first.change(emptyList())
            val recreated = ViewModelProvider(store, factory)[AnnotationSession::class.java]
            recreated.open(page, AnnotationTool.TEXT)
            assertTrue(recreated.annotations.isEmpty())
            recreated.undo()
            assertEquals(listOf(original), recreated.annotations)
            recreated.change(listOf(original.copy(text = "UNSAVED")))
            recreated.close()
            recreated.open(page, AnnotationTool.TEXT)
            assertEquals(listOf(original), recreated.annotations)
            assertEquals(listOf(original), page.annotations)
            store.clear()
        }
    }
}
