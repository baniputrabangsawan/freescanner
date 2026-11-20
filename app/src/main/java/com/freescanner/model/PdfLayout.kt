package com.freescanner.model

import kotlin.math.roundToInt

data class PdfLayout(val width: Int, val height: Int, val left: Float, val top: Float, val imageWidth: Float, val imageHeight: Float)

/** PDF points, preserving image aspect ratio; reserve the footer outside image content. */
fun calculatePdfLayout(imageWidth: Int, imageHeight: Int, options: PdfOptions): PdfLayout {
    require(imageWidth > 0 && imageHeight > 0) { "Dimensi gambar halaman harus lebih dari nol." }
    val landscape = imageWidth > imageHeight
    val width: Int
    val height: Int
    if (options.pageSize == PdfPageSize.FIT_IMAGE) {
        val scale = 842f / maxOf(imageWidth, imageHeight)
        width = (imageWidth * scale).roundToInt().coerceAtLeast(72)
        height = (imageHeight * scale).roundToInt().coerceAtLeast(72)
    } else {
        width = if (landscape) options.pageSize.height else options.pageSize.width
        height = if (landscape) options.pageSize.width else options.pageSize.height
    }
    val inset = if (options.margin) 24f else 0f
    val footer = if (options.pageNumbers) 20f else 0f
    val availableWidth = width - inset * 2
    val availableHeight = height - inset * 2 - footer
    val scale = minOf(availableWidth / imageWidth, availableHeight / imageHeight)
    val renderedWidth = imageWidth * scale
    val renderedHeight = imageHeight * scale
    return PdfLayout(width, height, inset + (availableWidth - renderedWidth) / 2,
        inset + (availableHeight - renderedHeight) / 2, renderedWidth, renderedHeight)
}
