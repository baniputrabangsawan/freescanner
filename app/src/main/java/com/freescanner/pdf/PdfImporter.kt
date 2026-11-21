package com.freescanner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Untrusted PDFs are decoded by the OS, never by PDFBox's optional-codec renderer. */
class PdfImporter(context: Context) {
    private val context = context.applicationContext

    /** index is zero based. The callback must finish copying the file before it returns. */
    suspend fun importPages(uri: Uri, password: String = "", onPage: suspend (file: File, index: Int, total: Int) -> Unit): Unit = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Pilih PDF melalui pemilih berkas Android." }
        if (password.isNotEmpty() && Build.VERSION.SDK_INT < 35) {
            throw IOException("Versi Android ini tidak dapat membuka PDF yang dilindungi kata sandi. Buka kunci PDF terlebih dahulu, lalu impor.")
        }
        val directory = File(context.cacheDir, "pdf-import-${UUID.randomUUID()}")
        if (!directory.mkdir()) throw IOException("Tidak dapat membuat penyimpanan impor PDF. Periksa ruang penyimpanan yang tersedia.")
        try {
            val input = File(directory, "input.pdf")
            copyBounded(uri, input)
            currentCoroutineContext().ensureActive()
            val descriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                if (Build.VERSION.SDK_INT >= 35) openWithPassword(descriptor, password) else PdfRenderer(descriptor)
            } catch (failure: Throwable) {
                descriptor.close()
                if (failure is SecurityException) {
                    throw IOException(if (Build.VERSION.SDK_INT >= 35) "Kata sandi PDF salah, belum diisi, atau perlindungannya tidak didukung. Masukkan kata sandi atau buka kunci PDF terlebih dahulu."
                        else "PDF ini dilindungi. Buka kuncinya sebelum mengimpor di versi Android ini.", failure)
                }
                throw failure
            }
            renderer.use {
                require(renderer.pageCount in 1..MAX_IMPORTED_PAGES) {
                    "PDF ini memiliki ${renderer.pageCount} halaman; impor dibatasi hingga $MAX_IMPORTED_PAGES halaman. Pisahkan berkasnya terlebih dahulu."
                }
                for (index in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    val file = File(directory, "page-$index.jpg")
                    try {
                        renderer.openPage(index).use { page ->
                            require(page.width > 0 && page.height > 0) { "Dimensi halaman PDF ${index + 1} tidak valid." }
                            val scale = minOf(2400.0 / page.width, 2400.0 / page.height)
                            val width = (page.width * scale).toInt().coerceIn(1, 2400)
                            val height = (page.height * scale).toInt().coerceIn(1, 2400)
                            val bitmap = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) {
                                throw IOException("Memori tidak cukup untuk mengimpor halaman PDF ${index + 1}. Tutup aplikasi lain dan coba lagi.")
                            }
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                // Native work is not interruptible: retain bitmap/descriptor ownership
                                // until render returns, then observe cancellation before durable ingestion.
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                currentCoroutineContext().ensureActive()
                                file.outputStream().use { output ->
                                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                                        throw IOException("Tidak dapat mengodekan halaman PDF ${index + 1}. Periksa ruang penyimpanan yang tersedia.")
                                    }
                                    output.fd.sync()
                                }
                            } finally { bitmap.recycle() }
                        }
                        currentCoroutineContext().ensureActive()
                        onPage(file, index, renderer.pageCount)
                    } finally { file.delete() }
                }
            }
        } finally {
            // Only this operation's private temporary directory; committed callback pages survive.
            directory.deleteRecursively()
        }
    }

    private suspend fun copyBounded(uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Tidak dapat membuka PDF yang dipilih.")
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_INPUT_BYTES) { "Impor PDF dibatasi hingga 50 MiB. Pisahkan atau kompres berkas ini terlebih dahulu." }
                    output.write(buffer, 0, count)
                }
                require(total > 0L) { "PDF yang dipilih kosong." }
                output.fd.sync()
            }
        }
    }

    @RequiresApi(35)
    private fun openWithPassword(descriptor: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())

    private companion object {
        const val MAX_INPUT_BYTES = 50L * 1024 * 1024
        const val MAX_IMPORTED_PAGES = 200
    }
}
