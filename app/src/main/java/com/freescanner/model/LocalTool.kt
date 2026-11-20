package com.freescanner.model

/** Every catalog entry has a real route; online/proprietary features are deliberately absent. */
enum class LocalTool(val label: String, val group: String, val needsDocument: Boolean) {
    SCAN("Pindai dokumen", "Pindai & impor", false),
    IMPORT_IMAGES("Impor gambar", "Pindai & impor", false),
    IMPORT_PDF("Impor PDF", "Pindai & impor", false),
    ID_CARD("Tata letak kartu identitas", "Pindai & impor", false),
    QR("Baca QR / kode batang", "Pindai & impor", false),
    OCR("Ekstrak teks", "Teks", true),
    TXT("Ekspor teks", "Teks", true),
    DOCX("Ekspor Word", "Teks", true),
    SIGN("Tanda tangani", "Anotasi", true),
    PEN("Gambar", "Anotasi", true),
    HIGHLIGHT("Sorot", "Anotasi", true),
    TEXT("Sisipkan teks", "Anotasi", true),
    WATERMARK("Marka air", "Anotasi", true),
    PDF("Ekspor PDF", "PDF & keluaran", true),
    EXTRACT("Ekstrak halaman", "PDF & keluaran", true),
    SPLIT("Pisahkan PDF", "PDF & keluaran", true),
    COMPRESS("Kompres PDF", "PDF & keluaran", true),
    PROTECT("Lindungi PDF", "PDF & keluaran", true),
    LONG_IMAGE("Gambar panjang", "PDF & keluaran", true),
    PRINT("Cetak", "PDF & keluaran", true),
}
