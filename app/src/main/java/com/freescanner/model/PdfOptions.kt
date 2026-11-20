package com.freescanner.model

enum class PdfPageSize(val label: String, val width: Int, val height: Int) {
    FIT_IMAGE("Sesuaikan gambar", 0, 0), A4("A4", 595, 842), LETTER("Letter", 612, 792),
}
enum class ExportQuality(val label: String, val maxDimension: Int, val jpegQuality: Int) {
    HIGH("Kualitas tinggi", 2400, 95),
    STANDARD("Seimbang", 1800, 82),
    SMALL("Berkas kecil", 1200, 60),
}


data class PdfOptions(
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val margin: Boolean = true,
    val pageNumbers: Boolean = false,
    val quality: ExportQuality = ExportQuality.HIGH,
    val searchableText: Boolean = true,
    /** Ephemeral export input. Never persist this field or include it in logs. */
    val password: String = "",
) {
    override fun toString(): String =
        "PdfOptions(pageSize=$pageSize, margin=$margin, pageNumbers=$pageNumbers, quality=$quality, searchableText=$searchableText, password=<redacted>)"
}
