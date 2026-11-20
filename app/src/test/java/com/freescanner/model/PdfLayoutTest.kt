package com.freescanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    @Test fun a4AutoOrientsAndPreservesImageAspect() {
        val layout = calculatePdfLayout(1600, 1000, PdfOptions(PdfPageSize.A4, margin = true))
        assertEquals(842, layout.width)
        assertEquals(595, layout.height)
        assertEquals(1.6f, layout.imageWidth / layout.imageHeight, .001f)
        assertTrue(layout.left >= 24f && layout.top >= 24f)
        assertTrue(layout.left + layout.imageWidth <= layout.width - 24f + .01f)
    }

    @Test fun letterPortraitNumberFooterDoesNotCoverImage() {
        val layout = calculatePdfLayout(1000, 1800, PdfOptions(PdfPageSize.LETTER, margin = false, pageNumbers = true))
        assertEquals(612, layout.width)
        assertEquals(792, layout.height)
        assertTrue(layout.top + layout.imageHeight <= 772.01f)
    }

    @Test fun fitImageHonorsAspectAndExtremePagesRemainRenderable() {
        val layout = calculatePdfLayout(1000, 2000, PdfOptions(PdfPageSize.FIT_IMAGE, margin = false))
        assertEquals(421, layout.width)
        assertEquals(842, layout.height)
        assertEquals(0f, layout.left, .001f)
        assertEquals(0f, layout.top, .001f)
        val narrow = calculatePdfLayout(1, 3000, PdfOptions(PdfPageSize.FIT_IMAGE, margin = true, pageNumbers = true))
        assertTrue(narrow.imageWidth > 0f && narrow.imageHeight > 0f)
        assertTrue(narrow.top + narrow.imageHeight <= narrow.height - 44.0f + .01f)
    }
}
