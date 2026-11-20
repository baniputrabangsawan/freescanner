package com.freescanner

import android.app.Application
import android.content.Context
import com.freescanner.data.DocumentRepository
import com.freescanner.export.DocumentExporter
import com.freescanner.imaging.ImageProcessor
import com.freescanner.ocr.OcrProcessor
import com.freescanner.pdf.PdfImporter
import com.freescanner.tools.MediaTools

/** All activities share the same mutation lock, source leases, and file reconciliation. */
class ScannerApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(IndonesianLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        IndonesianLocale.applyTo(this)
    }

    val processor: ImageProcessor by lazy { ImageProcessor(this) }
    val repository: DocumentRepository by lazy { DocumentRepository(this, processor) }
    val exporter: DocumentExporter by lazy { DocumentExporter(this, repository, processor) }
    val ocr: OcrProcessor by lazy { OcrProcessor(repository, processor) }
    val pdfImporter: PdfImporter by lazy { PdfImporter(this) }
    val mediaTools: MediaTools by lazy { MediaTools(this, processor) }
}
