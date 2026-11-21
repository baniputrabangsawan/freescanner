package com.freescanner

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.print.PrintManager
import android.print.PrintAttributes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freescanner.ui.ScannerApp
import com.freescanner.printing.PdfPrintAdapter
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(IndonesianLocale.wrap(base))
    }

    private val model: AppViewModel by viewModels()
    private val scanResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) model.inputCanceled()
        else {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            if (pages.isEmpty()) {
                // RESULT_OK without pages would otherwise strand the draft startInput created.
                model.inputCanceled()
                model.report("Pemindai tidak mengembalikan halaman yang dapat dibaca. Coba lagi atau impor foto.")
            }
            else model.receiveImageResult(pages.map { it.imageUri }, InputSource.CAMERA)
        }
    }
    private val importResult = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.receiveImageResult(uris, InputSource.GALLERY)
    }
    private val replacementImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.receiveImageResult(listOfNotNull(uri), InputSource.GALLERY)
    }
    private val pdfImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { model.receivePdf(it) }
    private val cardImport = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.receiveIdCard(it) }
    private val codeImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { model.receiveCode(it) }
    private val exportDestination = registerForActivityResult(object : ActivityResultContract<AppEvent.Export, Uri?>() {
        override fun createIntent(context: Context, input: AppEvent.Export): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.format.mimeType).putExtra(Intent.EXTRA_TITLE, input.files.single().name)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }) { model.saveExport(it) }
    private val exportDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { model.saveExport(it, directory = true) }
    private val galleryPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.galleryPermissionResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScannerApp(model) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.events.collect { event ->
                    try {
                        when (event) {
                            is AppEvent.Input -> when (event.source) {
                                InputSource.CAMERA -> launchScanner(event.replacing)
                                InputSource.GALLERY -> if (event.replacing) replacementImport.launch(arrayOf("image/*")) else importResult.launch(arrayOf("image/*"))
                            }
                            AppEvent.ImportPdf -> pdfImport.launch(arrayOf("application/pdf"))
                            AppEvent.ImportIdCard -> cardImport.launch(arrayOf("image/*"))
                            AppEvent.ImportCode -> codeImport.launch(arrayOf("image/*"))
                            AppEvent.GalleryPermission -> galleryPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            is AppEvent.Print -> getSystemService(PrintManager::class.java).print(
                                event.title, PdfPrintAdapter(event.file, event.title, event.pageCount),
                                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build(),
                            )
                            is AppEvent.ScannerReady -> {
                                if (model.finishScannerSetup(event.generation)) {
                                    scanResult.launch(IntentSenderRequest.Builder(event.intentSender).build())
                                }
                            }
                            is AppEvent.Export -> when {
                                event.action == ExportAction.SHARE -> share(event.files, event.format)
                                event.files.size == 1 -> exportDestination.launch(event)
                                else -> exportDirectory.launch(null)
                            }
                        }
                    } catch (_: Exception) {
                        model.report("Tidak ada aplikasi yang dapat membuka tindakan ini. Coba tujuan lain atau pasang aplikasi pembaca yang sesuai.")
                    }
                }
            }
        }
    }

    private fun launchScanner(replacing: Boolean) {
        val generation = model.beginScannerSetup()
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(false)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .apply { if (replacing) setPageLimit(1) }
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                model.scannerPrepared(generation, sender)
            }
            .addOnFailureListener { error ->
                if (!model.finishScannerSetup(generation)) return@addOnFailureListener
                val message = if (error is MlKitException && error.errorCode == MlKitException.UNSUPPORTED) {
                    "Pemindaian tidak didukung di perangkat ini. Anda tetap dapat mengimpor dan mengedit foto."
                } else {
                    "Pemindai gagal disiapkan. Periksa Layanan Google Play dan koneksi Anda, lalu coba lagi. Impor foto tetap tersedia."
                }
                model.report(message)
            }
    }


    private fun share(files: List<File>, format: ExportFormat) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.exports", it) })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = format.mimeType
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(contentResolver, "Dokumen hasil pindai", uris.first()).also { data ->
                uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan dokumen").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
