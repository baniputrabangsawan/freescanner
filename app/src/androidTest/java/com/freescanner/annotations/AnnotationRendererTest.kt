package com.freescanner.annotations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freescanner.model.Corner
import com.freescanner.model.PageAnnotation
import com.freescanner.model.isValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class AnnotationRendererTest {
    @Test fun highlightsCompositeOnceAndStrokeWidthUsesShorterSide() {
        val bitmap = white(400, 200)
        try {
            AnnotationRenderer.draw(bitmap, listOf(PageAnnotation.Ink(listOf(Corner(.2f, .5f), Corner(.8f, .5f)), Color.YELLOW, .1f, true)))
            val center = bitmap.getPixel(200, 100)
            assertEquals(255, Color.red(center))
            assertEquals(255, Color.green(center))
            assertTrue(Color.blue(center) in 177..180)
            assertEquals(Color.WHITE, bitmap.getPixel(200, 112))
            assertTrue(bitmap.getPixel(200, 108) != Color.WHITE)
            assertEquals(Color.WHITE, bitmap.getPixel(20, 100))
        } finally { bitmap.recycle() }
    }

    @Test fun aSinglePointProducesAVisibleRoundSignatureDot() {
        val bitmap = white(200, 400)
        try {
            AnnotationRenderer.draw(bitmap, listOf(PageAnnotation.Ink(listOf(Corner(.25f, .75f)), Color.RED, .05f)))
            assertEquals(Color.RED, bitmap.getPixel(50, 300))
            assertEquals(Color.WHITE, bitmap.getPixel(58, 300))
            assertEquals(Color.WHITE, bitmap.getPixel(150, 100))
        } finally { bitmap.recycle() }
    }

    @Test fun multilineLabelRotatesAroundItsNormalizedCenterAndHonorsOpacity() {
        val horizontal = white(400, 600)
        val vertical = white(400, 600)
        val transparent = white(400, 600)
        try {
            val label = PageAnnotation.Label("WIDE LABEL\nSECOND", .3f, .4f, .07f, Color.BLACK, opacity = .5f)
            AnnotationRenderer.draw(horizontal, listOf(label))
            AnnotationRenderer.draw(vertical, listOf(label.copy(rotation = 90f)))
            AnnotationRenderer.draw(transparent, listOf(label.copy(opacity = 0f)))
            val a = inkBounds(horizontal)
            val b = inkBounds(vertical)
            assertTrue(abs(a.width() - b.height()) <= 3)
            assertTrue(abs(a.height() - b.width()) <= 3)
            assertTrue(abs(a.centerX() - 120) < 4)
            assertTrue(abs(b.centerX() - 120) < 4)
            assertTrue(abs(a.centerY() - 240) < 8)
            assertTrue(abs(b.centerY() - 240) < 8)
            assertTrue(minimumRed(horizontal) in 126..129)
            assertEquals(Rect(), inkBounds(transparent))
        } finally { horizontal.recycle(); vertical.recycle(); transparent.recycle() }
    }

    @Test fun tiledWatermarkCoversMultipleRegionsAndSpacingChangesActualPixels() {
        val dense = white(400, 600)
        val sparse = white(400, 600)
        try {
            val label = PageAnnotation.Label("LOCAL", size = .05f, rotation = -30f, opacity = .4f, tiled = true, spacing = .05f)
            AnnotationRenderer.draw(dense, listOf(label))
            AnnotationRenderer.draw(sparse, listOf(label.copy(spacing = 1.5f)))
            assertTrue(nonWhiteCount(dense) > nonWhiteCount(sparse) * 2)
            val bounds = inkBounds(dense)
            assertTrue(bounds.left < 80 && bounds.right > 320)
            assertTrue(bounds.top < 100 && bounds.bottom > 500)
        } finally { dense.recycle(); sparse.recycle() }
    }

    @Test fun preparedPreviewAndExportHaveIdenticalPixelsWithoutModifyingTheBase() {
        val base = white(300, 500)
        val preview = base.copy(Bitmap.Config.ARGB_8888, true)
        val exported = base.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val edits = listOf(
                PageAnnotation.Ink(listOf(Corner(.1f, .2f), Corner(.9f, .8f)), Color.YELLOW, .04f, true),
                PageAnnotation.Label("SYNTHETIC\nONLY", .6f, .3f, .06f, Color.BLUE, -37f, .45f, true, .3f),
            )
            AnnotationScene(300, 500, edits).draw(Canvas(preview))
            AnnotationRenderer.draw(exported, edits)
            assertTrue(preview.sameAs(exported))
            assertEquals(Rect(), inkBounds(base))
            assertFalse(exported.sameAs(base))
        } finally { base.recycle(); preview.recycle(); exported.recycle() }
    }

    @Test fun letterboxingMapsPointerToPageAndRejectsOutsideStarts() {
        val viewport = PageViewport.fit(600f, 600f, 200, 400)
        assertEquals(150f, viewport.left, .001f)
        assertEquals(0f, viewport.top, .001f)
        assertFalse(viewport.contains(100f, 300f))
        assertEquals(Corner(.25f, .75f), viewport.point(225f, 450f))
        assertEquals(Corner(1f, 1f), viewport.point(700f, 800f))
    }

    @Test fun nonSquareInkRotationPreservesPixelLengthAndStaysInsidePage() {
        val stroke = PageAnnotation.Ink(listOf(Corner(.25f, .5f), Corner(.75f, .5f)))
        val rotated = transformInk(stroke, aspect = 2f, degrees = 90f)
        assertTrue(rotated.isValid())
        val dx = (rotated.points[1].x - rotated.points[0].x) * 400f
        val dy = (rotated.points[1].y - rotated.points[0].y) * 200f
        assertEquals(200f, hypot(dx, dy), .01f)
        assertEquals(.5f, rotated.points[0].x, .001f)
        assertEquals(0f, rotated.points[0].y, .001f)
        val moved = transformInk(rotated, 2f, x = 0f, y = 0f, scale = 2f)
        assertTrue(moved.isValid())
    }

    private fun white(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun inkBounds(bitmap: Bitmap): Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) != Color.WHITE) {
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        return if (right < 0) Rect() else Rect(left, top, right + 1, bottom + 1)
    }

    private fun minimumRed(bitmap: Bitmap): Int {
        var result = 255
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) result = minOf(result, Color.red(bitmap.getPixel(x, y)))
        return result
    }

    private fun nonWhiteCount(bitmap: Bitmap): Int {
        var result = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) != Color.WHITE) result++
        return result
    }
}
