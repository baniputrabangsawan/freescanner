package com.freescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freescanner.AppViewModel
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanPage
import com.freescanner.model.LocalTool
import java.text.DateFormat
import java.util.Date

private val ReviewCanvas = Color(0xFFC9C9C9)
private val ReviewPageIdsSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) }, restore = { it.toSet() },
)
private const val THIS_PAGE = "This page"
private const val ALL_PAGES = "All pages"
private const val CHOOSE_PAGES = "Choose pages"

@Composable
internal fun ReviewScreen(
    draft: ScanDraft,
    model: AppViewModel,
    onAdd: () -> Unit,
    onReplace: (String) -> Unit,
    onCrop: (String) -> Unit,
    onTool: (LocalTool, String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val page = draft.pages.firstOrNull { it.id == state.reviewPageId } ?: draft.pages.firstOrNull()
    val index = draft.pages.indexOfFirst { it.id == page?.id }
    val pageIds = remember(draft.pages) { draft.pages.map { it.id }.toSet() }
    var target by rememberSaveable(draft.id) { mutableStateOf(THIS_PAGE) }
    var chosenIds by rememberSaveable(draft.id, stateSaver = ReviewPageIdsSaver) {
        mutableStateOf(setOfNotNull(page?.id))
    }
    var pendingIds by rememberSaveable(draft.id, stateSaver = ReviewPageIdsSaver) {
        mutableStateOf(setOfNotNull(page?.id))
    }
    var choosingPages by rememberSaveable(draft.id) { mutableStateOf(false) }
    var comparing by rememberSaveable(draft.id) { mutableStateOf(false) }
    var removeId by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(pageIds, page?.id) {
        chosenIds = chosenIds.intersect(pageIds)
        pendingIds = pendingIds.intersect(pageIds)
    }
    val filterIds = when (target) {
        ALL_PAGES -> pageIds
        CHOOSE_PAGES -> chosenIds.intersect(pageIds)
        else -> setOfNotNull(page?.id)
    }
    val previewHeight = (LocalConfiguration.current.screenHeightDp * .28f).coerceIn(160f, 280f).dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FilledTonalButton(
            onClick = onAdd, enabled = state.busy == null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.AddAPhoto, null)
            Text("Tambah foto", Modifier.padding(start = 8.dp))
        }
        if (page == null) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tambahkan halaman pertama ke dokumen Anda.")
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { model.selectReviewPage(draft.pages[index - 1].id) }, enabled = index > 0) { Text("Sebelumnya") }
                Text("${index + 1} dari ${draft.pages.size}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { model.selectReviewPage(draft.pages[index + 1].id) }, enabled = index < draft.pages.lastIndex) { Text("Berikutnya") }
            }
            val preview = if (comparing) page.copy(crop = Crop.Full, rotation = 0, filter = PageFilter.ORIGINAL, annotations = emptyList(), text = null) else page
            PageImage(
                preview, model,
                Modifier.fillMaxWidth().height(previewHeight).background(ReviewCanvas).padding(12.dp)
                    .testTag("review-current-${page.id}"),
                if (comparing) "Halaman asli tersimpan ${index + 1}" else "Pratinjau halaman ${index + 1}",
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { onCrop(page.id) }) { Text("Pangkas") }
                TextButton(onClick = { model.rotate(page) }) { Text("Putar") }
                TextButton(onClick = { onReplace(page.id) }) { Text("Ganti") }
                TextButton(onClick = { removeId = page.id }) { Text("Hapus") }
                TextButton(onClick = { onTool(LocalTool.OCR, page.id) }) { Text("Ekstrak teks") }
                TextButton(onClick = { onTool(LocalTool.PEN, page.id) }) { Text("Anotasi") }
            }
            TextButton(onClick = { comparing = !comparing }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(if (comparing) "Tampilkan hasil edit" else "Bandingkan dengan asli")
            }
            Text(
                if (comparing) "Asli tersimpan • hasil edit SDK pemindai mungkin sudah diterapkan."
                else "Perbandingan menggunakan masukan tersimpan, bukan foto mentah dari kamera.",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall,
            )
            Text("Terapkan filter ke", Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(THIS_PAGE, ALL_PAGES, CHOOSE_PAGES).forEach { option ->
                    FilterChip(
                        selected = target == option,
                        onClick = {
                            if (option == CHOOSE_PAGES) {
                                pendingIds = if (target == CHOOSE_PAGES) chosenIds.intersect(pageIds) else setOf(page.id)
                                choosingPages = true
                            } else target = option
                        },
                        label = { Text(when (option) { THIS_PAGE -> "Halaman ini"; ALL_PAGES -> "Semua halaman"; else -> "Pilih halaman" }) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("filter-target-$option"),
                    )
                }
            }
            if (target == CHOOSE_PAGES) Text(
                "${filterIds.size} halaman dipilih", Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageFilter.entries.forEach { filter ->
                    val selected = filterIds.isNotEmpty() && draft.pages.all { it.id !in filterIds || it.filter == filter }
                    Column(
                        Modifier.width(112.dp)
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(8.dp))
                            .selectable(selected, enabled = filterIds.isNotEmpty(), role = Role.RadioButton) {
                                model.filterPages(filterIds, filter)
                            }
                            .semantics { stateDescription = if (selected) "Filter dipilih" else "Tidak dipilih" }
                            .testTag("filter-${filter.name}").padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PageImage(page.copy(filter = filter), model, Modifier.fillMaxWidth().height(80.dp), "Pratinjau ${filter.label}", 160)
                        Text(filter.label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { model.move(page.id, -1) }, enabled = index > 0) { Text("Pindahkan ke depan") }
                TextButton(onClick = { model.move(page.id, 1) }, enabled = index < draft.pages.lastIndex) { Text("Pindahkan ke belakang") }
            }
            PageThumbnailStrip(draft.pages, page.id, model, onSelect = model::selectReviewPage)
            Text(
                "Tekan lama halaman lalu seret untuk mengubah urutan, atau gunakan Pindahkan ke depan / belakang.",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (choosingPages) ChooseFilterPagesDialog(
        pages = draft.pages, model = model, selectedIds = pendingIds,
        onSelection = { pendingIds = it }, onDismiss = { choosingPages = false },
        onConfirm = { chosenIds = pendingIds.intersect(pageIds); target = CHOOSE_PAGES; choosingPages = false },
    )
    val removePage = draft.pages.firstOrNull { it.id == removeId }
    if (removePage != null) AlertDialog(
        onDismissRequest = { removeId = null },
        title = { Text("Hapus halaman ${draft.pages.indexOf(removePage) + 1}?") },
        text = { Text("Halaman ini akan dihapus dari draf. Dokumen terakhir yang disimpan tidak berubah sampai Anda menyimpan.") },
        confirmButton = { TextButton(onClick = { removeId = null; model.remove(removePage.id) }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = { removeId = null }) { Text("Batal") } },
    )
}

@Composable
private fun PageThumbnailStrip(pages: List<ScanPage>, selectedId: String, model: AppViewModel, onSelect: (String) -> Unit) {
    val dragThreshold = with(LocalDensity.current) { 40.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        pages.forEachIndexed { index, page ->
            key(page.id) {
                var dragX by remember { mutableFloatStateOf(0f) }
                Column(
                    Modifier.width(80.dp)
                        .border(if (page.id == selectedId) 2.dp else 1.dp, if (page.id == selectedId) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(6.dp))
                        .selectable(page.id == selectedId, role = Role.Tab) { onSelect(page.id) }
                        .semantics {
                            contentDescription = "Pilih halaman ${index + 1}"
                            customActions = listOf(
                                CustomAccessibilityAction("Pindahkan halaman ke depan") {
                                    if (index > 0) { model.move(page.id, -1); true } else false
                                },
                                CustomAccessibilityAction("Pindahkan halaman ke belakang") {
                                    if (index < pages.lastIndex) { model.move(page.id, 1); true } else false
                                },
                            )
                        }
                        .pointerInput(page.id, index, pages.size, dragThreshold) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragX = 0f },
                                onDragCancel = { dragX = 0f },
                                onDrag = { change, amount -> change.consume(); dragX += amount.x },
                                onDragEnd = {
                                    if (dragX > dragThreshold && index < pages.lastIndex) model.move(page.id, 1)
                                    else if (dragX < -dragThreshold && index > 0) model.move(page.id, -1)
                                    dragX = 0f
                                },
                            )
                        }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PageImage(page, model, Modifier.fillMaxWidth().height(76.dp), "", 160)
                    Text("${index + 1}", Modifier.padding(4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ChooseFilterPagesDialog(
    pages: List<ScanPage>, model: AppViewModel, selectedIds: Set<String>,
    onSelection: (Set<String>) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih halaman") },
        text = {
            Column {
                Text("Pilih halaman, lalu pilih filter.")
                TextButton(onClick = { onSelection(pages.map { it.id }.toSet()) }) { Text("Pilih semua") }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(page.id in selectedIds, role = Role.Checkbox) { checked ->
                                    onSelection(if (checked) selectedIds + page.id else selectedIds - page.id)
                                }
                                .semantics(mergeDescendants = true) { contentDescription = "Pilih halaman ${index + 1}" }
                                .testTag("choose-filter-page-${page.id}").padding(4.dp),
                        ) {
                            PageImage(page, model, Modifier.fillMaxWidth().height(92.dp), "", 160)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = page.id in selectedIds, onCheckedChange = null)
                                Text("Halaman ${index + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = pages.any { it.id in selectedIds }) { Text("Konfirmasi pilihan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
internal fun DocumentScreen(
    document: ScanDocument,
    model: AppViewModel,
    onExport: () -> Unit,
    onTools: () -> Unit,
    onSaveToPhone: () -> Unit,
    onShare: () -> Unit,
    onAddPhotos: () -> Unit,
) {
    var fullPages by rememberSaveable(document.id) { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val actionsEnabled = document.pages.isNotEmpty() && state.busy == null
    val modified = remember(document.updatedAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(document.updatedAt))
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stackActions = maxWidth < 320.dp || fontScale > 1.3f
        Column(Modifier.fillMaxSize().background(Color(0xFFF2F4F7))) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (fullPages) 1 else 2),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = RoundedCornerShape(22.dp), color = Color.Transparent) {
                        Column(
                            Modifier.fillMaxWidth().background(HeroBrush).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Semua halaman", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text(
                                    "${document.pages.size} halaman • Diperbarui $modified",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = .9f),
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { model.editDocument(document.id) },
                                    enabled = actionsEnabled,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Blue),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Edit halaman") }
                                TextButton(
                                    onClick = onExport,
                                    enabled = actionsEnabled,
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Ekspor") }
                                TextButton(
                                    onClick = onTools,
                                    enabled = actionsEnabled,
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Alat") }
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = !fullPages,
                                    onClick = { fullPages = false },
                                    label = { Text("Ringkasan") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color.White.copy(alpha = .16f),
                                        labelColor = Color.White,
                                        selectedContainerColor = Color.White,
                                        selectedLabelColor = Palette.Blue,
                                    ),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Ringkasan semua halaman" },
                                )
                                FilterChip(
                                    selected = fullPages,
                                    onClick = { fullPages = true },
                                    label = { Text("Halaman") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color.White.copy(alpha = .16f),
                                        labelColor = Color.White,
                                        selectedContainerColor = Color.White,
                                        selectedLabelColor = Palette.Blue,
                                    ),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Tampilkan halaman penuh" },
                                )
                            }
                        }
                    }
                }
                if (document.pages.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Dokumen ini belum memiliki halaman.",
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Ketuk halaman untuk mengeditnya.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(document.pages, key = { _, page -> page.id }) { index, page ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(
                                enabled = state.busy == null,
                                role = Role.Button,
                                onClickLabel = "Edit halaman ${index + 1}",
                            ) { model.editDocument(document.id, page.id) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Edit halaman dokumen ${index + 1}"
                            }
                            .testTag("document-page-${page.id}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PageImage(
                            page, model,
                            Modifier.fillMaxWidth().aspectRatio(1f / 1.4142f)
                                .border(1.dp, Color(0xFFD8DEE7), RoundedCornerShape(2.dp))
                                .background(Color.White, RoundedCornerShape(2.dp)).padding(6.dp),
                            "", if (fullPages) 1200 else 320,
                        )
                        Text(
                            "Halaman ${index + 1} dari ${document.pages.size}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Column {
                    FilledTonalButton(
                        onClick = onAddPhotos, enabled = state.busy == null,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.AddAPhoto, null)
                        Text("Tambah foto", Modifier.padding(start = 8.dp))
                    }
                if (stackActions) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onSaveToPhone,
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Simpan ke galeri") }
                        OutlinedButton(
                            onClick = onShare,
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Bagikan") }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onSaveToPhone,
                            enabled = actionsEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Simpan ke galeri") }
                        OutlinedButton(
                            onClick = onShare,
                            enabled = actionsEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Bagikan") }
                    }
                }
                }
            }
        }
    }
}
