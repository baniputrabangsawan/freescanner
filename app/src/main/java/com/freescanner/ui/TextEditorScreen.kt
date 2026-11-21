package com.freescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freescanner.ExportFormat
import com.freescanner.model.ScanPage

internal data class TextBuffer(val baseline: String?, val value: String) {
    val changed: Boolean get() = value != baseline.orEmpty()
}

/** Retain potentially large corrections across rotation without putting text into a Binder bundle. */
internal class TextEditorSession : ViewModel() {
    val buffers = mutableStateMapOf<String, TextBuffer>()
    private var firstPageId: String? = null

    fun open(pageId: String?) {
        if (firstPageId != pageId) {
            buffers.clear()
            firstPageId = pageId
        }
    }

    fun close() {
        buffers.clear()
        firstPageId = null
    }
}

@Composable
internal fun TextEditorScreen(
    pages: List<ScanPage>,
    selectedPageId: String?,
    onSelectPage: (String) -> Unit,
    onRecognize: (Set<String>) -> Unit,
    onSaveText: (String, String) -> Unit,
    onExport: (ExportFormat) -> Unit,
    onBack: () -> Unit,
    busy: Boolean = false,
) {
    val session: TextEditorSession = viewModel(key = "text-editor")
    // Saving ends text entry: drop focus so the IME actually closes and the export row below
    // is not covered by the keyboard.
    val focus = LocalFocusManager.current
    remember(pages.firstOrNull()?.id) { session.open(pages.firstOrNull()?.id); true }
    val buffers = session.buffers
    var pendingRecognition by remember { mutableStateOf<Set<String>?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val selected = pages.firstOrNull { it.id == selectedPageId } ?: pages.firstOrNull()
    LaunchedEffect(pages) {
        val ids = pages.mapTo(HashSet()) { it.id }
        buffers.keys.toList().filterNot { it in ids }.forEach(buffers::remove)
        pages.forEach { page ->
            val latest = page.text?.text
            val old = buffers[page.id]
            if (old == null || !old.changed || old.value == latest) {
                buffers[page.id] = TextBuffer(latest, latest.orEmpty())
            } else if (old.baseline != latest) {
                buffers[page.id] = old.copy(baseline = latest)
            }
        }
    }
    val dirty = buffers.values.any { it.changed }
    val complete = pages.isNotEmpty() && pages.all { it.text != null }
    val hasText = pages.any { !it.text?.text.isNullOrBlank() }
    val exportable = complete && hasText && !dirty && !busy
    fun leave() {
        if (!busy) {
            if (dirty) {
                confirmLeave = true
            } else {
                session.close()
                onBack()
            }
        }
    }
    fun recognize(ids: Set<String>) {
        if (ids.any { id -> pages.any { it.id == id && it.text != null } || buffers[id]?.changed == true }) {
            pendingRecognition = ids
        } else {
            onRecognize(ids)
        }
    }
    BackHandler { leave() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = ::leave, enabled = !busy) { Text("Kembali") }
            Text("Ekstrak & edit teks", style = MaterialTheme.typography.headlineSmall)
            Text("Pengenalan teks luring untuk aksara Latin, termasuk bahasa Indonesia dan Inggris. Periksa hasilnya sebelum menyimpan.", style = MaterialTheme.typography.bodyMedium)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Mengenali teks…", style = MaterialTheme.typography.bodySmall)
            }
            if (selected == null) {
                Text("Dokumen ini belum memiliki halaman. Kembali, lalu pindai atau impor halaman dahulu.")
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEachIndexed { index, page ->
                        FilterChip(
                            selected = page.id == selected.id,
                            onClick = { onSelectPage(page.id) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("Halaman ${index + 1}${if (buffers[page.id]?.changed == true) " • belum disimpan" else ""}") },
                        )
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { recognize(setOf(selected.id)) }, enabled = !busy) { Text("Kenali teks halaman") }
                    OutlinedButton(onClick = { recognize(pages.mapTo(LinkedHashSet()) { it.id }) }, enabled = !busy) { Text("Kenali semua teks") }
                }
                val buffer = buffers[selected.id] ?: TextBuffer(selected.text?.text, selected.text?.text.orEmpty())
                Text(
                    when {
                        buffer.changed -> "Koreksi belum disimpan — simpan halaman ini sebelum mengekspor."
                        selected.text == null -> "Teks belum dikenali. Kenali teks halaman ini, atau ketik di bawah."
                        selected.text.text.isBlank() -> "Tidak ada teks di halaman ini. Jika pengenalan tidak menemukan teks, coba gambar yang lebih jelas atau ketik teksnya."
                        else -> "Teks tersimpan. Anda dapat mengedit kesalahan pengenalan di bawah."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = buffer.value,
                    onValueChange = { value ->
                        if (value.length <= 1_000_000) buffers[selected.id] = buffer.copy(value = value)
                    },
                    enabled = !busy,
                    label = { Text("Teks halaman ${pages.indexOf(selected) + 1}") },
                    supportingText = { Text("${buffer.value.length} / 1.000.000 karakter") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    minLines = 8,
                    maxLines = 16,
                )
                Button(
                    onClick = {
                        onSaveText(selected.id, buffer.value)
                        focus.clearFocus(force = true)
                    },
                    enabled = !busy && (buffer.changed || selected.text == null),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Simpan teks halaman") }
                if (dirty) Text("Beralih halaman tetap menyimpan koreksi sementara di sini. Simpan tiap halaman yang diedit; keluar akan membuang perubahan yang belum disimpan.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Ekspor semua halaman dokumen", style = MaterialTheme.typography.titleMedium)
            Text("TXT dan Word menyertakan semua halaman, bukan hanya halaman yang ditampilkan.", style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    dirty -> "Simpan koreksi Anda sebelum mengekspor."
                    !complete -> "Gunakan Kenali semua teks, atau simpan teks tiap halaman, sebelum mengekspor. Halaman yang belum dikenali tidak pernah dilewati."
                    !hasText -> "Belum ada teks untuk diekspor."
                    else -> "Halaman tetap sesuai urutan dokumen. Word mempertahankan teks dan pemisah halaman, bukan tata letak pindai asli."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onExport(ExportFormat.TXT) }, enabled = exportable) { Text("Ekspor TXT") }
                OutlinedButton(onClick = { onExport(ExportFormat.DOCX) }, enabled = exportable) { Text("Ekspor Word (.docx)") }
            }
        }
    }
    pendingRecognition?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingRecognition = null },
            title = { Text("Ganti teks halaman?") },
            text = { Text("Pengenalan mengganti teks tersimpan dan koreksi yang belum disimpan pada ${ids.size} halaman terpilih. Halaman lainnya tidak berubah.") },
            confirmButton = {
                TextButton(onClick = {
                    ids.forEach { id -> buffers[id]?.let { buffers[id] = it.copy(value = it.baseline.orEmpty()) } }
                    pendingRecognition = null
                    onRecognize(ids)
                }, enabled = !busy) { Text("Kenali teks lagi") }
            },
            dismissButton = { TextButton(onClick = { pendingRecognition = null }) { Text("Batal") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Buang koreksi yang belum disimpan?") },
            text = { Text("Teks halaman yang tersimpan tetap ada di dokumen. Koreksi yang belum disimpan akan hilang.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; session.close(); onBack() }, enabled = !busy) { Text("Buang & kembali") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Lanjutkan mengedit") } },
        )
    }
}
