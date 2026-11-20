package com.freescanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.freescanner.ExportAction
import com.freescanner.ExportFormat
import com.freescanner.model.ExportQuality
import com.freescanner.model.PageKind
import com.freescanner.model.PdfOptions
import com.freescanner.model.PdfPageSize
import com.freescanner.model.ScanDocument

private val ExportPageSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })

@Composable
internal fun ExportDialog(
    document: ScanDocument,
    onDismiss: () -> Unit,
    onExport: (ExportFormat, ExportAction, Set<String>, PdfOptions) -> Unit,
    initialFormat: ExportFormat = ExportFormat.PDF,
    initialOptions: PdfOptions = PdfOptions(),
    initialProtection: Boolean = false,
) {
    var format by rememberSaveable(document.id, initialFormat) { mutableStateOf(initialFormat) }
    var selected by rememberSaveable(document.id, stateSaver = ExportPageSaver) { mutableStateOf(document.pages.map { it.id }.toSet()) }
    var pageSize by rememberSaveable(document.id) { mutableStateOf(initialOptions.pageSize) }
    var margin by rememberSaveable(document.id) { mutableStateOf(initialOptions.margin) }
    var numbers by rememberSaveable(document.id) { mutableStateOf(initialOptions.pageNumbers) }
    var quality by rememberSaveable(document.id) { mutableStateOf(initialOptions.quality) }
    var searchable by rememberSaveable(document.id) { mutableStateOf(initialOptions.searchableText) }
    // Deliberately not saveable: secrets never enter saved-instance state or preferences.
    var protect by remember(document.id) { mutableStateOf(initialProtection || initialOptions.password.isNotEmpty()) }
    var password by remember(document.id) { mutableStateOf(initialOptions.password) }
    var confirmation by remember(document.id) { mutableStateOf("") }
    val selectedPages = document.pages.filter { it.id in selected }
    val selectedIds = selectedPages.map { it.id }.toSet()
    val pdf = format == ExportFormat.PDF || format == ExportFormat.SEPARATE_PDFS
    val text = format == ExportFormat.TXT || format == ExportFormat.DOCX
    val missingText = text && selectedPages.any { it.text == null }
    val blankText = text && selectedPages.none { !it.text?.text.isNullOrBlank() }
    val passwordError = pdf && protect && (password.isEmpty() || password != confirmation || password.toByteArray(Charsets.UTF_8).size > 127)
    val enabled = selectedPages.isNotEmpty() && !missingText && !blankText && !passwordError
    fun export(action: ExportAction) {
        onExport(format, action, selectedIds, PdfOptions(pageSize, margin, numbers, quality, searchable,
            if (pdf && protect) password else ""))
        password = ""
        confirmation = ""
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ekspor dokumen") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExportHeading("Format")
            Column(Modifier.selectableGroup()) {
                ExportFormat.entries.forEach { value ->
                    ExportChoice(value.label, format == value) { format = value }
                }
            }
            ExportHeading("Halaman")
            Text("${selectedIds.size} dari ${document.pages.size} halaman. Ekspor mengikuti urutan dokumen.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selected = if (selectedIds.size == document.pages.size) emptySet() else document.pages.map { it.id }.toSet() }) {
                Text(if (selectedIds.size == document.pages.size) "Hapus pilihan" else "Pilih semua")
            }
            document.pages.forEachIndexed { index, page ->
                ExportToggle("Ekspor halaman ${index + 1}", page.id in selectedIds) { checked -> selected = if (checked) selected + page.id else selected - page.id }
            }
            if (pdf || format == ExportFormat.LONG_IMAGE) {
                ExportHeading("Kualitas gambar dan ukuran berkas")
                Column(Modifier.selectableGroup()) {
                    ExportQuality.entries.forEach { value -> ExportChoice(value.label, quality == value) { quality = value } }
                }
                Text("Kualitas lebih rendah mengurangi resolusi gambar dan ukuran JPEG; semua halaman terpilih tetap disertakan.", style = MaterialTheme.typography.bodySmall)
            }
            if (pdf) {
                ExportHeading("Ukuran halaman PDF")
                Column(Modifier.selectableGroup()) {
                    PdfPageSize.entries.forEach { size -> ExportChoice(size.label, pageSize == size) { pageSize = size } }
                }
                ExportToggle("Margin halaman", margin) { margin = it }
                ExportToggle("Nomor halaman", numbers) { numbers = it }
                Text("Orientasi tegak atau mendatar mengikuti gambar tiap halaman. Nomor halaman menghitung halaman yang diekspor.", style = MaterialTheme.typography.bodySmall)
                if (selectedPages.any { it.kind == PageKind.ID_CARD }) {
                    Text("Lembar kartu identitas selalu memakai A4 tanpa margin atau nomor halaman agar ukuran asli kartu tetap terjaga.", style = MaterialTheme.typography.bodySmall)
                }
                ExportToggle("Teks yang dapat dicari", searchable) { searchable = it }
                Text("Menambahkan teks hasil pengenalan atau suntingan secara tak terlihat. Halaman yang belum dikenali tetap berupa gambar; kenali dahulu di Teks. Karakter yang tidak didukung font memerlukan PDF hanya gambar atau TXT/DOCX.", style = MaterialTheme.typography.bodySmall)
                ExportToggle("Lindungi PDF dengan kata sandi", protect) {
                    protect = it
                    if (!it) { password = ""; confirmation = "" }
                }
                if (protect) {
                    OutlinedTextField(password, { password = it }, label = { Text("Kata sandi PDF") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Konfirmasi kata sandi PDF") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = confirmation.isNotEmpty() && confirmation != password, modifier = Modifier.fillMaxWidth())
                    Text("Enkripsi AES-256. Kata sandi tidak disimpan; simpan di tempat aman. Maksimal 127 byte UTF-8.", style = MaterialTheme.typography.bodySmall)
                    if (passwordError) Text("Masukkan kata sandi sesuai batas, lalu ulangi dengan persis agar ekspor tersedia.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (format == ExportFormat.LONG_IMAGE) Text("Halaman disusun vertikal dengan dimensi terbatas. Pilihan yang sangat panjang mungkin perlu dikurangi atau diekspor sebagai JPEG terpisah.", style = MaterialTheme.typography.bodySmall)
            if (format == ExportFormat.JPEG) Text("Tiap halaman terpilih disimpan sebagai foto JPEG di album FreeScanner pada galeri. Bagikan mengirim gambar, bukan PDF.", style = MaterialTheme.typography.bodySmall)
            if (format == ExportFormat.SEPARATE_PDFS) Text("Tiap halaman terpilih menjadi PDF terpisah. Simpan memilih folder; Bagikan mengirim semua berkas terpilih.", style = MaterialTheme.typography.bodySmall)
            if (text) {
                Text("Mengekspor teks hasil pengenalan atau suntingan di perangkat, bukan gambar atau format dokumen asli.", style = MaterialTheme.typography.bodySmall)
                if (missingText) Text("Kenali teks di setiap halaman terpilih melalui Teks sebelum mengekspor; tidak ada halaman yang dilewati diam-diam.", color = MaterialTheme.colorScheme.error)
                else if (blankText) Text("Halaman terpilih tidak memiliki teks hasil pengenalan atau suntingan untuk diekspor.", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        Column(Modifier.fillMaxWidth()) {
            Button(onClick = { export(ExportAction.SAVE) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when {
                    format == ExportFormat.JPEG || format == ExportFormat.LONG_IMAGE -> "Simpan ke galeri"
                    pdf -> "Simpan PDF ke HP"
                    else -> "Simpan ke HP"
                })
            }
            TextButton(onClick = { export(ExportAction.SHARE) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bagikan") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Batal") } })
}

@Composable
private fun ExportHeading(label: String) {
    Text(label, Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun ExportChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ExportToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
