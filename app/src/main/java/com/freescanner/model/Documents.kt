package com.freescanner.model

import kotlin.math.abs

data class Corner(val x: Float, val y: Float)

/** Normalized clockwise corners: top-left, top-right, bottom-right, bottom-left. */
data class Crop(val corners: List<Corner>) {
    fun isValid(): Boolean {
        if (corners.size != 4 || corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        var sign = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 0.0001f) return false
            sign += a.x * b.y - b.x * a.y
        }
        return abs(sign) >= 0.02f
    }

    companion object {
        val Full = Crop(listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f)))
    }
}

enum class PageFilter(val label: String) {
    ORIGINAL("Asli"), ENHANCED("Warna ditingkatkan"), GRAYSCALE("Skala abu-abu"), BLACK_WHITE("Hitam putih")
}
enum class PageKind { DOCUMENT, ID_CARD }


data class ScanPage(
    val id: String,
    val sourcePath: String,
    val crop: Crop = Crop.Full,
    val rotation: Int = 0,
    val filter: PageFilter = PageFilter.ORIGINAL,
    val annotations: List<PageAnnotation> = emptyList(),
    val text: OcrText? = null,
    val kind: PageKind = PageKind.DOCUMENT,
)

data class ScanDocument(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage>,
    val folderId: String? = null,
)

data class ScanDraft(
    val id: String,
    val sourceDocumentId: String?,
    val title: String,
    val updatedAt: Long,
    val pages: List<ScanPage>,
    val folderId: String? = null,
)

data class ScanFolder(val id: String, val name: String, val createdAt: Long)
