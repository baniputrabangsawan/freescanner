package com.freescanner.pdf

import android.content.Context
import android.graphics.Bitmap
import com.freescanner.model.OcrText
import com.freescanner.model.PageKind
import com.freescanner.model.PdfLayout
import com.freescanner.model.PdfOptions
import com.freescanner.model.PdfPageSize
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanPage
import com.freescanner.model.calculatePdfLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.File
import java.io.IOException
import java.io.FilterOutputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only writes our rendered images. Never parses or renders externally supplied PDFs. */
internal class GeneratedPdfWriter(private val context: Context) {
    suspend fun write(
        document: ScanDocument,
        options: PdfOptions,
        output: File,
        render: suspend (ScanPage, Int) -> Bitmap,
    ) {
        require(document.pages.isNotEmpty()) { "Pilih setidaknya satu halaman untuk diekspor." }
        require(options.password.toByteArray(Charsets.UTF_8).size <= 127) { "Gunakan kata sandi PDF maksimal 127 bita UTF-8." }
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(output.parentFile)).use { pdf ->
            // Asset verified in the pinned PDFBox-Android v2.0.27.0 tree. Liberation Sans 2.1.5,
            // SIL OFL 1.1 (Google 2010 / Red Hat 2012); no guessed system font or substitution.
            val needsFont = options.pageNumbers || (options.searchableText && document.pages.any { !it.text?.text.isNullOrBlank() })
            val font = if (needsFont) context.assets.open(FONT_ASSET).use { PDType0Font.load(pdf, it, true) } else null
            document.pages.forEachIndexed { index, scanPage ->
                currentCoroutineContext().ensureActive()
                val bitmap = render(scanPage, options.quality.maxDimension)
                try {
                    val idCard = scanPage.kind == PageKind.ID_CARD
                    val geometry = if (idCard) options.copy(pageSize = PdfPageSize.A4, margin = false, pageNumbers = false) else options
                    val layout = calculatePdfLayout(bitmap.width, bitmap.height, geometry)
                    val page = PDPage(PDRectangle(layout.width.toFloat(), layout.height.toFloat()))
                    pdf.addPage(page)
                    // Stream the JPEG into the scratch-backed PDF, rather than retain all page bitmaps.
                    val jpeg = File(output.parentFile, "image-${UUID.randomUUID()}.jpg")
                    try {
                        jpeg.outputStream().use { stream ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.jpegQuality, stream)) {
                                throw IOException("Tidak dapat mengodekan halaman ini. Periksa ruang penyimpanan yang tersedia.")
                            }
                        }
                        val image = jpeg.inputStream().use { input ->
                            PDImageXObject(pdf, input, com.tom_roush.pdfbox.cos.COSName.DCT_DECODE,
                                bitmap.width, bitmap.height, 8, com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE)
                        }
                        PDPageContentStream(pdf, page).use { stream ->
                            stream.setNonStrokingColor(255, 255, 255)
                            stream.addRect(0f, 0f, layout.width.toFloat(), layout.height.toFloat())
                            stream.fill()
                            stream.drawImage(image, layout.left, layout.height - layout.top - layout.imageHeight,
                                layout.imageWidth, layout.imageHeight)
                            if (options.searchableText && font != null) scanPage.text?.let { writeText(stream, font, it, layout) }
                            if (options.pageNumbers && !idCard && font != null) {
                                val label = "${index + 1} / ${document.pages.size}"
                                stream.beginText()
                                stream.setRenderingMode(RenderingMode.FILL)
                                stream.setNonStrokingColor(64, 64, 64)
                                stream.setFont(font, 10f)
                                stream.setHorizontalScaling(100f)
                                stream.newLineAtOffset((layout.width - font.getStringWidth(label) / 100f) / 2f, 12f)
                                stream.showText(label)
                                stream.endText()
                            }
                        }
                    } finally { jpeg.delete() }
                } finally { bitmap.recycle() }
            }
            currentCoroutineContext().ensureActive()
            if (options.password.isNotEmpty()) {
                val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), options.password, AccessPermission())
                policy.encryptionKeyLength = 256
                policy.isPreferAES = true
                pdf.protect(policy)
            }
            output.outputStream().use { stream ->
                // PDFBox closes its supplied output. Keep our descriptor open until the durable
                // write completes; the outer owner closes it after sync, including on failure.
                val pdfOutput = object : FilterOutputStream(stream) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                    override fun close() = flush()
                }
                pdf.save(pdfOutput)
                stream.fd.sync()
            }
            currentCoroutineContext().ensureActive()
        }
    }

    private fun writeText(stream: PDPageContentStream, font: PDType0Font, text: OcrText, layout: PdfLayout) {
        require(text.isValid()) { "Geometri teks halaman ini tidak valid. Kenali teksnya kembali atau ekspor PDF berisi gambar saja." }
        if (text.text.isBlank()) return
        // Encoding is validated before writing: never silently replace or drop unsupported glyphs.
        val cleanText = printable(text.text)
        try {
            font.encode(cleanText.replace('\n', ' '))
            text.lines.forEach { font.encode(printable(it.text).replace('\n', ' ')) }
        } catch (_: IllegalArgumentException) {
            throw IOException("Teks ini berisi karakter yang tidak dapat disematkan oleh font PDF. Nonaktifkan teks yang dapat dicari atau ekspor TXT/DOCX.")
        }
        stream.beginText()
        stream.setRenderingMode(RenderingMode.NEITHER)
        if (text.lines.isNotEmpty()) {
            text.lines.forEach { line ->
                val value = printable(line.text).replace('\n', ' ')
                if (value.isNotEmpty()) {
                    val height = ((line.bottom - line.top) * layout.imageHeight).coerceAtLeast(0.1f)
                    putLine(stream, font, value, layout.left + line.left * layout.imageWidth,
                        layout.height - layout.top - line.bottom * layout.imageHeight + height * 0.18f,
                        (line.right - line.left) * layout.imageWidth, height * 0.82f)
                }
            }
        } else {
            // Edited text has no trustworthy OCR boxes; preserve reading order in an invisible
            // page-wide layer, without claiming recognizer coordinates for arbitrary corrections.
            val lines = cleanText.split('\n')
            val size = minOf(12f, layout.imageHeight / (lines.size.coerceAtLeast(1) * 1.25f)).coerceAtLeast(0.01f)
            lines.forEachIndexed { index, line ->
                if (line.isNotEmpty()) putLine(stream, font, line, layout.left,
                    layout.height - layout.top - (index + 1) * size * 1.25f, layout.imageWidth, size)
            }
        }
        stream.endText()
    }

    private fun putLine(stream: PDPageContentStream, font: PDType0Font, value: String, x: Float, y: Float, width: Float, size: Float) {
        val naturalWidth = font.getStringWidth(value) * size / 1000f
        stream.setFont(font, size)
        stream.setHorizontalScaling(if (naturalWidth > 0f) (width / naturalWidth * 100f).coerceIn(0.01f, 1000f) else 100f)
        stream.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x, y))
        stream.showText(value)
    }

    private fun printable(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').also {
        require(it.none { char -> char.isISOControl() && char != '\n' }) {
            "Teks yang dikenali berisi karakter kontrol. Edit teks atau ekspor PDF berisi gambar saja."
        }
    }

    private companion object {
        const val FONT_ASSET = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    }
}
