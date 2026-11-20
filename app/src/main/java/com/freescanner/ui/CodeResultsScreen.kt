package com.freescanner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.freescanner.tools.CodeResult
import com.freescanner.tools.websiteUri
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeResultsScreen(
    results: List<CodeResult>,
    onChooseImage: () -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingWebsite by remember(results) { mutableStateOf<Uri?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pindai kode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(
                        if (results.isEmpty()) "Tidak ada kode yang dapat dibaca" else
                            "${results.size} kode ditemukan",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (results.isEmpty())
                            "Coba gambar yang lebih tajam dengan seluruh kode QR atau kode batang terlihat. Pengenalan berjalan di perangkat ini."
                        else "Isi kode adalah teks yang belum tepercaya. Tidak ada yang dibuka atau dijalankan secara otomatis.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onChooseImage, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Pilih gambar lain")
                    }
                }
            }
            itemsIndexed(results) { index, result ->
                val website = remember(result.text) { websiteUri(result.text) }
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${result.format}", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(
                                result.text.ifEmpty { "(Isi kode kosong)" },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(result.text))
                                    scope.launch { snackbar.showSnackbar("Kode disalin") }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Salin") }
                            if (website != null) {
                                TextButton(
                                    onClick = { pendingWebsite = website },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Buka situs web…") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    pendingWebsite?.let { website ->
        AlertDialog(
            onDismissRequest = { pendingWebsite = null },
            title = { Text("Buka situs web?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Anda akan meninggalkan FreeScanner. Lanjutkan hanya jika Anda memercayai alamat ini.")
                    SelectionContainer { Text(website.toString()) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingWebsite = null
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, website).addCategory(Intent.CATEGORY_BROWSABLE)
                        context.startActivity(Intent.createChooser(intent, "Buka situs web"))
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("Tidak ada aplikasi yang dapat membuka situs web ini") }
                    } catch (_: SecurityException) {
                        scope.launch { snackbar.showSnackbar("Situs web ini tidak dapat dibuka") }
                    }
                }) { Text("Buka situs web") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWebsite = null }) { Text("Batal") }
            },
        )
    }
}
