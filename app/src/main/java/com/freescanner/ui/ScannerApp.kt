package com.freescanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freescanner.AppViewModel
import com.freescanner.ExportAction
import com.freescanner.ExportFormat
import com.freescanner.InputSource
import com.freescanner.model.PdfOptions
import com.freescanner.model.LocalTool
import com.freescanner.model.ExportQuality
import com.freescanner.annotations.AnnotationTool
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val imagePermit = Semaphore(1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerApp(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val screenState = rememberSaveableStateHolder()
    val snackbars = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(SuiteTab.HOME) }
    var inputMenu by rememberSaveable { mutableStateOf(false) }
    var replacement by rememberSaveable { mutableStateOf<String?>(null) }
    var leavingDraft by rememberSaveable { mutableStateOf(false) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    var exportSmall by rememberSaveable { mutableStateOf(false) }
    var exportProtected by rememberSaveable { mutableStateOf(false) }
    var cropId by rememberSaveable { mutableStateOf<String?>(null) }
    var cropWarningId by rememberSaveable { mutableStateOf<String?>(null) }
    var annotationId by rememberSaveable { mutableStateOf<String?>(null) }
    var annotationTool by rememberSaveable { mutableStateOf(AnnotationTool.PEN) }
    var textEditor by rememberSaveable { mutableStateOf(false) }
    var documentTools by rememberSaveable { mutableStateOf(false) }
    var chooseForTool by rememberSaveable { mutableStateOf<LocalTool?>(null) }
    var addTargetDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTool by rememberSaveable { mutableStateOf<LocalTool?>(null) }
    val draft = state.draft
    val document = state.documents.firstOrNull { it.id == state.documentId }
    val outputDocument = remember(draft, document) {
        draft?.let { ScanDocument(it.id, it.title, 0L, it.updatedAt, it.pages, it.folderId) } ?: document
    }
    val inputTarget = state.documents.firstOrNull { it.id == addTargetDocumentId } ?: outputDocument
    val cropPage = draft?.pages?.firstOrNull { it.id == cropId }
    val annotationPage = draft?.pages?.firstOrNull { it.id == annotationId }
    val root = draft == null && state.documentId == null && state.codes == null

    fun quickExport(action: ExportAction) {
        val target = outputDocument ?: return
        if (target.pages.isNotEmpty()) {
            model.export(if (action == ExportAction.SAVE) ExportFormat.JPEG else ExportFormat.PDF, action, target.pages.map { it.id }.toSet())
        }
    }

    fun addPhotos(documentId: String? = null) {
        if (state.busy != null) return
        addTargetDocumentId = documentId ?: state.documentId
        replacement = null
        inputMenu = true
    }

    fun showOutput(tool: LocalTool) {
        if (tool == LocalTool.PRINT) { model.printDocument(); return }
        exportFormat = when (tool) {
            LocalTool.SPLIT -> ExportFormat.SEPARATE_PDFS
            LocalTool.LONG_IMAGE -> ExportFormat.LONG_IMAGE
            LocalTool.TXT -> ExportFormat.TXT
            LocalTool.DOCX -> ExportFormat.DOCX
            else -> ExportFormat.PDF
        }
        exportSmall = tool == LocalTool.COMPRESS
        exportProtected = tool == LocalTool.PROTECT
        exportDialog = true
    }
    fun routeTool(tool: LocalTool, pageId: String? = null, selectedDocumentId: String? = null) {
        when (tool) {
            LocalTool.SCAN -> model.startInput(InputSource.CAMERA)
            LocalTool.IMPORT_IMAGES -> model.startInput(InputSource.GALLERY)
            LocalTool.IMPORT_PDF -> model.requestPdf()
            LocalTool.ID_CARD -> model.requestIdCard()
            LocalTool.QR -> model.requestCode()
            else -> {
                val target = selectedDocumentId?.let { id -> state.documents.firstOrNull { it.id == id } } ?: outputDocument
                if (target == null) { chooseForTool = tool; return }
                if (selectedDocumentId != null) model.openDocument(selectedDocumentId)
                val editable = tool in listOf(LocalTool.OCR, LocalTool.SIGN, LocalTool.PEN, LocalTool.HIGHLIGHT, LocalTool.TEXT, LocalTool.WATERMARK) ||
                    (tool in listOf(LocalTool.TXT, LocalTool.DOCX) && target.pages.any { it.text == null })
                if (editable) {
                    pendingTool = tool
                    if (draft == null) model.editDocument(target.id, pageId)
                    else pageId?.let(model::selectReviewPage)
                } else showOutput(tool)
            }
        }
    }
    LaunchedEffect(pendingTool, draft?.id, draft?.pages?.isNotEmpty(), state.busy) {
        val tool = pendingTool
        if (tool != null && draft == null && state.busy == null) { pendingTool = null; return@LaunchedEffect }
        if (tool != null && draft != null && draft.pages.isNotEmpty() && state.busy == null) {
            pendingTool = null
            when (tool) {
                LocalTool.SIGN, LocalTool.PEN, LocalTool.HIGHLIGHT, LocalTool.TEXT, LocalTool.WATERMARK -> {
                    annotationTool = AnnotationTool.valueOf(tool.name)
                    annotationId = state.reviewPageId ?: draft.pages.first().id
                }
                LocalTool.OCR, LocalTool.TXT, LocalTool.DOCX -> {
                    textEditor = true
                    val missing = draft.pages.filter { it.text == null }.map { it.id }.toSet()
                    if (missing.isNotEmpty()) model.recognizePages(missing)
                }
                else -> showOutput(tool)
            }
        }
    }
    LaunchedEffect(draft?.id) {
        if (draft == null) { textEditor = false; annotationId = null; cropId = null }
    }
    LaunchedEffect(state.message) {
        state.message?.let { message -> snackbars.showSnackbar(message, duration = SnackbarDuration.Long); model.dismissMessage() }
    }
    BackHandler(enabled = state.busy != null || draft != null || state.documentId != null || state.codes != null || tab != SuiteTab.HOME) {
        when {
            state.busy != null -> Unit
            state.codes != null -> model.clearCodes()
            cropId != null -> cropId = null
            annotationId != null -> annotationId = null
            textEditor -> textEditor = false
            draft != null -> leavingDraft = true
            state.documentId != null -> model.backToLibrary()
            else -> tab = SuiteTab.HOME
        }
    }
    FreeScannerTheme {
        Box(Modifier.fillMaxSize()) {
            when {
                state.codes != null -> CodeResultsScreen(state.codes!!, model::requestCode, model::clearCodes)
                cropPage != null -> CropEditor(source = model.repository.sourceFile(cropPage), initial = cropPage.crop,
                    onCancel = { cropId = null }, onApply = { model.updateCrop(cropPage, it); cropId = null })
                annotationPage != null -> AnnotationScreen(annotationPage, model, annotationTool,
                    onCancel = { annotationId = null }, onSave = { model.saveAnnotations(annotationPage, it); annotationId = null })
                textEditor && draft != null -> TextEditorScreen(draft.pages, state.reviewPageId,
                    model::selectReviewPage, model::recognizePages, model::savePageText,
                    onExport = { exportFormat = it; exportSmall = false; exportProtected = false; exportDialog = true },
                    onBack = { textEditor = false }, busy = state.busy != null)
                else -> Scaffold(
                    topBar = {
                        TopAppBar(title = {
                            Column {
                                Text(
                                    when {
                                        draft != null -> "Tinjau halaman"
                                        document != null -> document.title
                                        else -> when (tab) {
                                            SuiteTab.HOME -> "FreeScanner"
                                            SuiteTab.FILES -> "Dokumen"
                                            SuiteTab.TOOLS -> "Alat"
                                        }
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (draft == null && document == null && tab == SuiteTab.HOME) {
                                    Text(
                                        "Semua dokumen tetap di ponsel ini",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }, navigationIcon = {
                            if (draft != null || state.documentId != null) IconButton(onClick = {
                                if (draft != null) leavingDraft = true else model.backToLibrary()
                            }, enabled = state.busy == null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                        }, actions = {
                            when {
                                draft != null -> {
                                    TextButton(onClick = { documentTools = true }, enabled = draft.pages.isNotEmpty() && state.busy == null) { Text("Alat") }
                                    TextButton(onClick = { saveDialog = true }, enabled = draft.pages.isNotEmpty() && state.busy == null) { Text("Simpan") }
                                }
                                document != null -> DocumentMenu(onRename = { renameId = document.id }, onDelete = { deleteId = document.id })
                                else -> TextButton(onClick = { replacement = null; addTargetDocumentId = null; inputMenu = true }, enabled = state.busy == null) { Icon(Icons.Default.Add, null); Text("Pindaian baru") }
                            }
                        })
                    },
                    bottomBar = {
                        if (draft != null) Surface(shadowElevation = 4.dp) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(onClick = { quickExport(ExportAction.SAVE) }, enabled = draft.pages.isNotEmpty() && state.busy == null) {
                                    Icon(Icons.Default.Download, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Simpan ke galeri")
                                }
                                Button(onClick = { saveDialog = true }, enabled = draft.pages.isNotEmpty() && state.busy == null) { Text("Simpan dokumen") }
                            }
                        }
                        if (root) Surface(color = Palette.Surface, shadowElevation = 12.dp) {
                            NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                                SuiteTab.entries.forEach { value -> NavigationBarItem(
                                    modifier = Modifier.testTag("tab-${value.name}"),
                                    selected = tab == value,
                                    onClick = { tab = value },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Palette.Blue,
                                        selectedTextColor = Palette.Blue,
                                        indicatorColor = Tones.Blue.tint,
                                        unselectedIconColor = Palette.InkMuted,
                                        unselectedTextColor = Palette.InkMuted,
                                    ),
                                    label = { Text(when (value) {
                                        SuiteTab.HOME -> "Beranda"; SuiteTab.FILES -> "Berkas"; SuiteTab.TOOLS -> "Alat"
                                    }, style = MaterialTheme.typography.labelMedium) },
                                    icon = { Icon(when (value) {
                                        SuiteTab.HOME -> Icons.Default.Home; SuiteTab.FILES -> Icons.Default.Folder; SuiteTab.TOOLS -> Icons.Default.Build
                                    }, null) },
                                ) }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (root && tab != SuiteTab.TOOLS) ExtendedFloatingActionButton(
                            onClick = { model.startInput(InputSource.CAMERA) },
                            containerColor = Palette.Blue,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            icon = { Icon(Icons.Default.CameraAlt, null) },
                            text = { Text("Pindai", style = MaterialTheme.typography.labelLarge) },
                        )
                    },
                ) { insets ->
                    Box(Modifier.fillMaxSize().padding(insets)) {
                        when {
                            draft != null -> screenState.SaveableStateProvider("review-${draft.id}") {
                                ReviewScreen(draft, model,
                                    onAdd = { addPhotos() },
                                    onReplace = { replacement = it; addTargetDocumentId = null; inputMenu = true },
                                    onCrop = { id -> if (draft.pages.first { it.id == id }.annotations.isEmpty()) cropId = id else cropWarningId = id },
                                    onTool = { tool, pageId -> routeTool(tool, pageId) })
                            }
                            document != null -> screenState.SaveableStateProvider("document-${document.id}") {
                                DocumentScreen(document, model, onExport = { exportFormat = ExportFormat.PDF; exportSmall = false; exportProtected = false; exportDialog = true },
                                    onTools = { documentTools = true },
                                    onSaveToPhone = { quickExport(ExportAction.SAVE) },
                                    onShare = { quickExport(ExportAction.SHARE) },
                                    onAddPhotos = { addPhotos() })
                            }
                            tab == SuiteTab.HOME -> screenState.SaveableStateProvider("home") {
                                HomeScreen(state.documents, state.drafts, { routeTool(it) }, model::openDocument, model::resumeDraft,
                                    model = model, onShowFiles = { tab = SuiteTab.FILES }, onAddPhotos = { addPhotos(it) })
                            }
                            tab == SuiteTab.TOOLS -> ToolsScreen { routeTool(it) }
                            else -> screenState.SaveableStateProvider("library") {
                                LibraryScreen(state.documents, state.drafts, state.folders, model,
                                    onNew = { replacement = null; addTargetDocumentId = null; inputMenu = true },
                                    onRename = { renameId = it }, onDelete = { deleteId = it })
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = when {
                    document != null -> 208.dp
                    root || draft != null -> 88.dp
                    else -> 8.dp
                }))
        }
        if (inputMenu) AlertDialog(onDismissRequest = { inputMenu = false; addTargetDocumentId = null }, title = { Text(if (replacement == null) "Tambah foto" else "Ganti halaman") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (replacement == null && inputTarget != null) Text(
                    "Foto baru masuk ke “${inputTarget.title}”, setelah ${inputTarget.pages.size} foto yang sudah ada.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = {
                    inputMenu = false
                    val target = addTargetDocumentId; addTargetDocumentId = null
                    model.startInput(InputSource.CAMERA, replacement, target)
                }, modifier = Modifier.fillMaxWidth()) { Text("Pindai dengan kamera") }
                OutlinedButton(onClick = {
                    inputMenu = false
                    val target = addTargetDocumentId; addTargetDocumentId = null
                    model.startInput(InputSource.GALLERY, replacement, target)
                }, modifier = Modifier.fillMaxWidth()) { Text("Impor foto") }
                if (replacement == null) OutlinedButton(onClick = {
                    inputMenu = false
                    val target = addTargetDocumentId; addTargetDocumentId = null
                    model.requestPdf(target)
                }, modifier = Modifier.fillMaxWidth()) { Text("Impor PDF") }
                Text("Dokumen tetap di ponsel ini.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { inputMenu = false; addTargetDocumentId = null }) { Text("Batal") } })
        if (documentTools) AlertDialog(onDismissRequest = { documentTools = false }, title = { Text("Alat dokumen") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                LocalTool.entries.filter { it.needsDocument }.forEach { tool ->
                    TextButton(onClick = { documentTools = false; routeTool(tool) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tool.label) }
                }
            }
        }, confirmButton = { TextButton(onClick = { documentTools = false }) { Text("Tutup") } })
        chooseForTool?.let { tool ->
            ChooseToolDocumentDialog(state.documents, tool,
                onChoose = { id -> chooseForTool = null; routeTool(tool, selectedDocumentId = id) },
                onImport = { chooseForTool = null; pendingTool = tool; model.startInput(InputSource.GALLERY) },
                onDismiss = { chooseForTool = null })
        }
        if (cropWarningId != null) AlertDialog(onDismissRequest = { cropWarningId = null }, title = { Text("Pangkas halaman beranotasi?") },
            text = { Text("Mengubah pangkasan akan menghapus anotasi dan teks yang dikenali pada halaman ini. Dokumen asli yang tersimpan tidak berubah sampai Anda menyimpan dokumen.") },
            confirmButton = { TextButton(onClick = { cropId = cropWarningId; cropWarningId = null }) { Text("Lanjutkan") } },
            dismissButton = { TextButton(onClick = { cropWarningId = null }) { Text("Batal") } })
        if (leavingDraft) AlertDialog(onDismissRequest = { leavingDraft = false }, title = { Text("Simpan draf Anda?") }, text = { Text("Dokumen terakhir yang disimpan tidak akan berubah. Simpan draf ini untuk dilanjutkan nanti, atau buang perubahannya.") },
            confirmButton = { TextButton(onClick = { leavingDraft = false; model.keepDraft() }) { Text("Simpan draf") } },
            dismissButton = { TextButton(onClick = { leavingDraft = false; model.discardDraft() }) { Text("Buang perubahan") } })
        if (saveDialog && draft != null) NameDialog("Simpan dokumen", draft.title, { saveDialog = false }) { title -> saveDialog = false; model.saveDraft(title) }
        val rename = state.documents.firstOrNull { it.id == renameId }
        if (rename != null) NameDialog("Ganti nama dokumen", rename.title, { renameId = null }) { title -> renameId = null; model.rename(rename.id, title) }
        val deleting = state.documents.firstOrNull { it.id == deleteId }
        if (deleting != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Hapus dokumen?") }, text = { Text("Hapus “${deleting.title}” dari ponsel ini? Salinan yang telah diekspor tidak akan dihapus.") },
            confirmButton = { TextButton(onClick = { deleteId = null; model.delete(deleting.id) }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Batal") } })
        if (exportDialog && outputDocument != null) ExportDialog(outputDocument, { exportDialog = false },
            onExport = { format, action, ids, options -> exportDialog = false; model.export(format, action, ids, options) },
            initialFormat = exportFormat, initialOptions = PdfOptions(quality = if (exportSmall) ExportQuality.SMALL else ExportQuality.HIGH),
            initialProtection = exportProtected)
        state.pdfPasswordUri?.let { uri ->
            var password by remember(uri) { mutableStateOf("") }
            AlertDialog(onDismissRequest = model::cancelPdfPassword, title = { Text("Kata sandi PDF diperlukan") },
                text = { OutlinedTextField(password, { password = it }, label = { Text("Kata sandi PDF") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Kata sandi digunakan sekali dan tidak disimpan. Jika gagal, buka kunci PDF di aplikasi asalnya.") }) },
                confirmButton = { TextButton(onClick = { val value = password; password = ""; model.receivePdf(uri, value) }, enabled = password.isNotEmpty()) { Text("Impor") } },
                dismissButton = { TextButton(onClick = { password = ""; model.cancelPdfPassword() }) { Text("Batal") } })
        }
        state.busy?.let { label ->
            AlertDialog(onDismissRequest = {}, confirmButton = {
                when (label) {
                    "Menyiapkan pemindai…" -> TextButton(onClick = model::cancelScannerSetup) { Text("Batal") }
                    "Mengimpor PDF…", "Mengenali teks…" -> TextButton(onClick = model::cancelOperation) { Text("Batal") }
                }
            }, text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp)); Text(state.progress ?: label)
                }
            })
        }
    }
}


@Composable
private fun DocumentMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Tindakan dokumen") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Hapus") }, onClick = { expanded = false; onDelete() })
        }
    }
}


@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 120) name = it }, label = { Text("Nama dokumen") }, singleLine = true, supportingText = { Text("Disimpan secara lokal di ponsel ini") })
    }, confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageImage(page: ScanPage, model: AppViewModel, modifier: Modifier, description: String, maxDimension: Int = 1200) {
    var bitmap by remember(page, maxDimension) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page, maxDimension) { mutableStateOf(false) }
    var zoom by remember(page) { mutableFloatStateOf(1f) }
    var pan by remember(page) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
        pan = if (zoom == 1f) Offset.Zero else pan + panChange
    }
    LaunchedEffect(page, maxDimension) {
        try {
            imagePermit.withPermit {
                val rendered = runInterruptible(Dispatchers.IO) {
                    model.processor.renderPage(model.repository.sourceFile(page), page, maxDimension)
                }
                bitmap = rendered
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        catch (_: OutOfMemoryError) { failed = true }
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(image.asImageBitmap(), description.ifBlank { null }, Modifier.fillMaxSize()
                // At 1x, a one-finger drag belongs to the surrounding page/library list.
                .then(if (maxDimension > 180) Modifier.transformable(transform, canPan = { zoom > 1f }) else Modifier)
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
                contentScale = ContentScale.Fit)
            failed -> Text("Pratinjau tidak tersedia", style = MaterialTheme.typography.labelSmall)
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (zoom > 1f) TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd).background(Color.White)) {
            Text("Atur ulang perbesaran")
        }
    }
}
