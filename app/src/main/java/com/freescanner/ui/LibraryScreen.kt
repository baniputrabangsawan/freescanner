package com.freescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freescanner.AppViewModel
import com.freescanner.InputSource
import com.freescanner.model.LibraryQuery
import com.freescanner.model.LibrarySort
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import com.freescanner.model.ScanFolder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val DocumentOrderSaver = Saver<List<String>, ArrayList<String>>(
    save = { ArrayList(it) }, restore = { it.toList() },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryScreen(
    documents: List<ScanDocument>,
    drafts: List<ScanDraft>,
    folders: List<ScanFolder>,
    model: AppViewModel,
    onNew: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.MODIFIED_NEWEST) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = DocumentOrderSaver) { mutableStateOf(emptyList<String>()) }
    var createFolder by rememberSaveable { mutableStateOf(false) }
    var renameFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var bulkDialog by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(documents, search, folderId, sort) { LibraryQuery.documents(documents, search, folderId, sort) }
    val visibleDrafts = remember(drafts, search, folderId) { LibraryQuery.drafts(drafts, search, folderId) }
    val visibleIds = remember(visible) { visible.map { it.id }.toSet() }
    // Only visible IDs can reach an action, including while restored repository data is loading.
    val selectedIds = remember(selection, visibleIds) { selection.filter { it in visibleIds } }
    val orderedFolders = remember(folders) { folders.sortedWith(compareBy<ScanFolder> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }) }
    val folderCounts = remember(documents) { documents.groupingBy { it.folderId }.eachCount() }
    val draftCounts = remember(drafts) { drafts.groupingBy { it.folderId }.eachCount() }
    val currentFolder = folders.firstOrNull { it.id == folderId }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    fun cancelSelection() { selecting = false; selection = emptyList(); bulkDialog = null }
    fun changeFolder(id: String?) { folderId = id; search = ""; selection = emptyList(); bulkDialog = null }
    fun toggle(id: String) {
        selecting = true
        selection = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    BackHandler(enabled = selecting || folderId != null) {
        if (selecting) cancelSelection() else changeFolder(null)
    }

    LazyVerticalGrid(
        columns = if (grid) GridCells.Adaptive(156.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().testTag("Library workspace"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "capture", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.startInput(InputSource.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Pindai dengan kamera") }
                OutlinedButton(onClick = { model.startInput(InputSource.GALLERY) }, modifier = Modifier.fillMaxWidth()) { Text("Impor foto") }
            }
        }
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it; selection = emptyList(); bulkDialog = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Cari dokumen") },
                trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = ""; selection = emptyList() }) { Text("Bersihkan") } },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    focusedBorderColor = Palette.Blue,
                    unfocusedBorderColor = Palette.Border,
                ),
            )
        }
        item(key = "scope", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (folderId != null) IconButton(onClick = { changeFolder(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali ke Semua dokumen") }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (folderId != null) Text("Semua dokumen /", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(currentFolder?.name ?: if (folderId == null) "Semua dokumen" else "Folder", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text("${visible.size} dokumen · ${visibleDrafts.size} draf", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (currentFolder != null) LibraryFolderMenu(currentFolder, (folderCounts[currentFolder.id] ?: 0) + (draftCounts[currentFolder.id] ?: 0) == 0,
                    onRename = { renameFolderId = currentFolder.id }, onDelete = { deleteFolderId = currentFolder.id })
            }
        }
        item(key = "tools", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySortMenu(sort) { sort = it }
                FilterChip(selected = !grid, onClick = { grid = false }, label = { Text("Daftar") })
                FilterChip(selected = grid, onClick = { grid = true }, label = { Text("Kisi") })
                if (!selecting) TextButton(onClick = { selecting = true }, enabled = visible.isNotEmpty()) { Text("Pilih") }
            }
        }
        if (selecting) item(key = "selection", span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = Tones.Blue.tint, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${selectedIds.size} dipilih", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { cancelSelection() }) { Text("Batalkan pilihan") }
                    }
                    TextButton(onClick = { selection = (selectedIds + visible.map { it.id }).distinct() }, enabled = visible.isNotEmpty()) { Text("Pilih semua yang terlihat") }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { bulkDialog = "merge" }, enabled = selectedIds.size >= 2) { Text("Gabungkan") }
                        TextButton(onClick = { model.duplicateDocument(selectedIds.single()); cancelSelection() }, enabled = selectedIds.size == 1) { Text("Duplikat") }
                        TextButton(onClick = { bulkDialog = "move" }, enabled = selectedIds.isNotEmpty()) { Text("Pindahkan") }
                        TextButton(onClick = { bulkDialog = "delete" }, enabled = selectedIds.isNotEmpty()) { Text("Hapus yang dipilih") }
                    }
                    Text("Penggabungan mengikuti urutan pilihan Anda. Draf tidak dipilih atau dipindahkan.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (folderId == null && search.isBlank() && !selecting) {
            item(key = "folder-heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Folder (${folders.size})", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { createFolder = true }) { Text("Folder baru") }
                }
            }
            items(orderedFolders, key = { "folder-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                val count = folderCounts[folder.id] ?: 0
                val draftCount = draftCounts[folder.id] ?: 0
                ListItem(
                    headlineContent = { Text(folder.name) },
                    supportingContent = { Text("$count dokumen · $draftCount draf") },
                    trailingContent = { LibraryFolderMenu(folder, count + draftCount == 0, { renameFolderId = folder.id }, { deleteFolderId = folder.id }) },
                    modifier = Modifier.combinedClickable(onClick = { changeFolder(folder.id) }),
                )
            }
        }
        if (visibleDrafts.isNotEmpty()) {
            item(key = "draft-heading", span = { GridItemSpan(maxLineSpan) }) { LibraryHeading("Draf belum selesai") }
            items(visibleDrafts, key = { "draft-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { draft ->
                ListItem(
                    headlineContent = { Text(draft.title.ifBlank { "Draf tanpa judul" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${draft.pages.size} halaman · Ketuk untuk melanjutkan") },
                    leadingContent = { Text("Draf", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.combinedClickable(enabled = !selecting, onClick = { model.resumeDraft(draft.id) }),
                )
            }
        }
        if (visible.isNotEmpty()) item(key = "document-heading", span = { GridItemSpan(maxLineSpan) }) { LibraryHeading("Tersimpan di ponsel ini") }
        items(visible, key = { "document-${it.id}" }) { document ->
            LibraryDocument(document, model, grid, selecting, selectedIds.indexOf(document.id), dateFormat,
                onClick = { if (selecting) toggle(document.id) else model.openDocument(document.id) },
                onSelect = { toggle(document.id) }, onRename = { onRename(document.id) }, onDelete = { onDelete(document.id) })
        }
        if (visible.isEmpty() && visibleDrafts.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when { search.isNotBlank() -> "Tidak ada hasil"; folderId != null -> "Folder ini kosong"; else -> "Dokumen rapi, tanpa tumpukan kertas." }, style = MaterialTheme.typography.titleLarge)
                Text(when {
                    search.isNotBlank() -> "Coba judul dokumen lain atau bersihkan pencarian."
                    folderId != null -> "Pilih dokumen tersimpan di Semua dokumen, lalu pilih Pindahkan."
                    else -> "Pindai dokumen atau impor foto untuk memulai."
                }, style = MaterialTheme.typography.bodyMedium)
                if (search.isNotBlank()) TextButton(onClick = { search = ""; selection = emptyList() }) { Text("Bersihkan pencarian") }
                else if (folderId != null) OutlinedButton(onClick = { changeFolder(null) }) { Text("Kembali ke Semua dokumen") }
                else Button(onClick = onNew) { Text("Pindaian baru") }
            }
        }
    }

    if (createFolder) LibraryNameDialog("Folder baru", "Nama folder", "", { createFolder = false }) { name ->
        createFolder = false; model.createFolder(name)
    }
    folders.firstOrNull { it.id == renameFolderId }?.let { folder ->
        LibraryNameDialog("Ganti nama folder", "Nama folder", folder.name, { renameFolderId = null }) { name ->
            renameFolderId = null; model.renameFolder(folder.id, name)
        }
    }
    folders.firstOrNull { it.id == deleteFolderId }?.let { folder ->
        val empty = (folderCounts[folder.id] ?: 0) + (draftCounts[folder.id] ?: 0) == 0
        AlertDialog(onDismissRequest = { deleteFolderId = null }, title = { Text("Hapus folder?") },
            text = { Text(if (empty) "Hapus folder kosong “${folder.name}”?" else "Pindahkan semua dokumen dan selesaikan atau buang draf sebelum menghapus folder ini.") },
            confirmButton = { TextButton(enabled = empty, onClick = {
                deleteFolderId = null; if (folderId == folder.id) changeFolder(null); model.deleteFolder(folder.id)
            }) { Text("Hapus folder") } }, dismissButton = { TextButton(onClick = { deleteFolderId = null }) { Text("Batal") } })
    }
    when (bulkDialog) {
        "merge" -> LibraryNameDialog("Gabungkan dokumen", "Nama dokumen", "Dokumen gabungan", { bulkDialog = null },
            supporting = "Halaman mengikuti urutan pilihan:\n" + selectedIds.mapIndexed { index, id -> "${index + 1}. ${documents.firstOrNull { it.id == id }?.title.orEmpty()}" }.joinToString("\n") + "\nDokumen asli tidak berubah.",
            confirmLabel = "Gabungkan", enabled = selectedIds.size >= 2) { title ->
            model.mergeDocuments(selectedIds, title); cancelSelection()
        }
        "move" -> AlertDialog(onDismissRequest = { bulkDialog = null }, title = { Text("Pindahkan ${selectedIds.size} dokumen") }, text = {
            LazyVerticalGrid(columns = GridCells.Fixed(1), modifier = Modifier.heightIn(max = 360.dp)) {
                item { TextButton(onClick = { model.moveDocuments(selectedIds.toSet(), null); cancelSelection() }, modifier = Modifier.fillMaxWidth()) { Text("Tanpa folder (Semua dokumen)") } }
                items(orderedFolders, key = { it.id }) { folder ->
                    TextButton(onClick = { model.moveDocuments(selectedIds.toSet(), folder.id); cancelSelection() }, modifier = Modifier.fillMaxWidth()) { Text(folder.name) }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { bulkDialog = null }) { Text("Batal") } })
        "delete" -> AlertDialog(onDismissRequest = { bulkDialog = null }, title = { Text("Hapus ${selectedIds.size} dokumen?") },
            text = { Text("Hapus dokumen tersimpan yang dipilih dari ponsel ini? Tindakan ini tidak dapat dibatalkan. Salinan yang telah diekspor dan draf belum selesai tidak akan dihapus.") },
            confirmButton = { TextButton(onClick = { model.deleteDocuments(selectedIds.toSet()); cancelSelection() }, enabled = selectedIds.isNotEmpty()) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { bulkDialog = null }) { Text("Batal") } })
    }
}

@Composable
private fun LibraryHeading(title: String) {
    Text(title, Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryDocument(
    document: ScanDocument, model: AppViewModel, grid: Boolean, selecting: Boolean, selectionIndex: Int,
    dateFormat: DateFormat, onClick: () -> Unit, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    val isSelected = selectionIndex >= 0
    val modifier = Modifier.fillMaxWidth().semantics {
        if (selecting) {
            selected = isSelected
            stateDescription = if (isSelected) "Dipilih ke-${selectionIndex + 1} dalam urutan penggabungan" else "Tidak dipilih"
        }
    }.combinedClickable(onClick = onClick, onLongClick = onSelect, onLongClickLabel = "Pilih dokumen")
    val metadata = "${document.pages.size} halaman · ${dateFormat.format(Date(document.updatedAt))}"
    if (grid) {
        Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Column(Modifier.padding(12.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tones.Blue.tint)) {
                    document.pages.firstOrNull()?.let {
                        PageImage(it, model, Modifier.fillMaxWidth().height(150.dp).padding(6.dp), "", 360)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) LibraryCheckbox(document.title, isSelected, onSelect)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(document.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isSelected) Text(
                            "Pilihan ${selectionIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Blue,
                        )
                    }
                    if (!selecting) LibraryDocumentMenu(onRename, onDelete) else Unit
                }
            }
        }
    } else {
        ListItem(
            headlineContent = { Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Column { Text(metadata); if (isSelected) Text("Pilihan ${selectionIndex + 1}") } },
            leadingContent = {
                Box(Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(10.dp)).background(Tones.Blue.tint)) {
                    document.pages.firstOrNull()?.let { PageImage(it, model, Modifier.fillMaxSize().padding(3.dp), "", 180) }
                }
            },
            trailingContent = { if (selecting) LibraryCheckbox(document.title, isSelected, onSelect) else LibraryDocumentMenu(onRename, onDelete) },
            colors = ListItemDefaults.colors(
                containerColor = if (isSelected) Tones.Blue.tint else MaterialTheme.colorScheme.surface,
            ),
            modifier = modifier,
        )
        HorizontalDivider(color = Palette.Border)
    }
}

@Composable
private fun LibraryCheckbox(title: String, checked: Boolean, onSelect: () -> Unit) {
    Checkbox(checked = checked, onCheckedChange = { onSelect() }, modifier = Modifier.semantics { contentDescription = "Pilih $title" })
}

@Composable
private fun LibraryDocumentMenu(onRename: () -> Unit, onDelete: () -> Unit) {
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
private fun LibraryFolderMenu(folder: ScanFolder, empty: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Tindakan folder ${folder.name}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Ganti nama folder") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text(if (empty) "Hapus folder" else "Pindahkan dokumen dan draf sebelum menghapus") }, onClick = { expanded = false; onDelete() }, enabled = empty)
        }
    }
}

@Composable
private fun LibrarySortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Urutkan dokumen: ${sort.label}" }) { Text(sort.label) }
        DropdownMenu(expanded, { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSort(option) }, modifier = Modifier.semantics { selected = sort == option })
            }
        }
    }
}

@Composable
private fun LibraryNameDialog(
    title: String, label: String, initial: String, onDismiss: () -> Unit,
    supporting: String? = null, confirmLabel: String = "Simpan", enabled: Boolean = true,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(title, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 120) name = it }, label = { Text(label) }, singleLine = true)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = enabled && name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}
