package com.freescanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freescanner.AppViewModel
import com.freescanner.model.LocalTool
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanDraft
import java.text.DateFormat
import java.util.Date

enum class SuiteTab { HOME, FILES, TOOLS }

private val SuiteToolGroups = LocalTool.entries.groupBy { it.group }
private val HomeShortcuts = listOf(
    LocalTool.IMPORT_IMAGES, LocalTool.IMPORT_PDF, LocalTool.OCR, LocalTool.PDF,
)

/** The parent owns navigation and document selection for every action. */
@Composable
internal fun ToolsScreen(onTool: (LocalTool) -> Unit) {
    LazyVerticalGrid(
        columns = suiteGridCells(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SuiteToolGroups.forEach { (group, tools) ->
            item(key = "heading-$group", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                SuiteHeading(group)
            }
            items(tools, key = { it.name }, contentType = { "tool" }) { tool ->
                SuiteToolTile(tool, onClick = { onTool(tool) })
            }
        }
    }
}

@Composable
internal fun HomeScreen(
    documents: List<ScanDocument>,
    drafts: List<ScanDraft>,
    onTool: (LocalTool) -> Unit,
    onOpen: (String) -> Unit,
    onResume: (String) -> Unit,
    model: AppViewModel,
    onShowFiles: () -> Unit,
    onAddPhotos: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val matchingDocuments = remember(documents, search) {
        documents.filter { search.isEmpty() || it.title.contains(search, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
    }
    val visibleDocuments = remember(matchingDocuments, search) {
        if (search.isEmpty()) matchingDocuments.take(5) else matchingDocuments
    }
    val recentDrafts = remember(drafts) { drafts.sortedByDescending { it.updatedAt }.take(3) }
    val dateFormat = suiteDateFormat()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "hero", contentType = "hero") {
            Surface(shape = RoundedCornerShape(26.dp), color = Color.Transparent) {
                Box(Modifier.fillMaxWidth().background(HeroBrush).padding(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Pindai. Rapikan. Simpan.",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                            )
                            Text(
                                if (documents.isEmpty()) "Mulai dari satu halaman; semua hasil tetap di ponsel ini."
                                else "${documents.size} dokumen tersimpan, siap diedit atau dibagikan.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = .92f),
                            )
                        }
                        Button(
                            onClick = { onTool(LocalTool.SCAN) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Blue),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Default.DocumentScanner, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Mulai pindai dokumen", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        item(key = "shortcuts", contentType = "shortcuts") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Alat cepat", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeShortcuts.forEach { tool ->
                        val tone = toolTone(tool)
                        Surface(
                            onClick = { onTool(tool) },
                            modifier = Modifier.weight(1f).semantics { role = Role.Button },
                            shape = RoundedCornerShape(18.dp),
                            color = tone.tint,
                            contentColor = tone.strong,
                        ) {
                            Column(
                                Modifier.heightIn(min = 92.dp).padding(horizontal = 6.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(Color.White),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(suiteToolIcon(tool), null, tint = tone.strong, modifier = Modifier.size(20.dp)) }
                                Text(
                                    when (tool) {
                                        LocalTool.IMPORT_IMAGES -> "Impor foto"
                                        LocalTool.IMPORT_PDF -> "Impor PDF"
                                        LocalTool.OCR -> "Teks OCR"
                                        else -> "Buat PDF"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "search", contentType = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cari dokumen") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, "Hapus pencarian")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    focusedBorderColor = Palette.Blue,
                    unfocusedBorderColor = Palette.Border,
                ),
            )
        }
        item(key = "document-heading", contentType = "heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SuiteHeading(
                        if (search.isEmpty()) "Dokumen terbaru" else "Hasil pencarian",
                        if (search.isEmpty()) "${documents.size} dokumen tersimpan"
                        else "${matchingDocuments.size} dokumen ditemukan",
                    )
                }
                TextButton(onClick = onShowFiles) { Text("Lihat semua") }
            }
        }
        if (visibleDocuments.isEmpty()) {
            item(key = "empty-documents", contentType = "empty") {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (search.isEmpty()) "Belum ada dokumen tersimpan" else "Dokumen tidak ditemukan",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (search.isEmpty()) "Pindai atau impor halaman, lalu simpan dokumen Anda."
                        else "Coba judul lain atau hapus pencarian untuk melihat dokumen terbaru.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (search.isNotEmpty()) {
                        TextButton(onClick = { query = "" }) { Text("Hapus pencarian") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onTool(LocalTool.SCAN) }) { Text("Pindai") }
                            TextButton(onClick = { onTool(LocalTool.IMPORT_IMAGES) }) { Text("Impor foto") }
                        }
                    }
                }
            }
        }
        items(visibleDocuments, key = { "document-${it.id}" }, contentType = { "saved-document" }) { document ->
            Surface(
                onClick = { onOpen(document.id) },
                modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Tones.Blue.tint,
                    ) {
                        val page = document.pages.firstOrNull()
                        if (page != null) {
                            PageImage(page, model, Modifier.size(58.dp, 76.dp).padding(4.dp), "", maxDimension = 200)
                        } else {
                            Box(Modifier.size(58.dp, 76.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Description, null, tint = Tones.Blue.strong)
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = Tones.Neutral.tint) {
                                Text(
                                    suitePageCount(document.pages.size),
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Tones.Neutral.strong,
                                )
                            }
                            Text(
                                dateFormat.format(Date(document.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onAddPhotos(document.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.AddAPhoto,
                            "Tambah foto ke ${document.title}",
                            tint = Palette.Blue,
                        )
                    }
                }
            }
        }
        if (search.isEmpty() && recentDrafts.isNotEmpty()) {
            item(key = "draft-heading", contentType = "heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SuiteHeading("Lanjutkan mengedit", "${drafts.size} draf") }
                    TextButton(onClick = onShowFiles) { Text("Lihat semua") }
                }
            }
            items(recentDrafts, key = { "draft-${it.id}" }, contentType = { "draft" }) { draft ->
                SuiteDocumentRow(
                    title = draft.title,
                    supporting = "Draf · ${suitePageCount(draft.pages.size)} · ${dateFormat.format(Date(draft.updatedAt))}",
                    icon = Icons.Default.History,
                    actionLabel = "Lanjutkan",
                    onClick = { onResume(draft.id) },
                )
            }
        }
    }
}

@Composable
internal fun ChooseToolDocumentDialog(
    documents: List<ScanDocument>,
    tool: LocalTool,
    onChoose: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val orderedDocuments = remember(documents) { documents.sortedByDescending { it.updatedAt } }
    val dateFormat = suiteDateFormat()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tool.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(
                        "Pilih dokumen · ${documents.size} tersedia",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                if (orderedDocuments.isEmpty()) {
                    Column(Modifier.weight(1f, fill = false).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Belum ada dokumen tersimpan", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Impor halaman dan simpan dokumen untuk menggunakan alat ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(orderedDocuments, key = { it.id }, contentType = { "document" }) { document ->
                            SuiteDocumentRow(
                                title = document.title,
                                supporting = "${suitePageCount(document.pages.size)} · ${dateFormat.format(Date(document.updatedAt))}",
                                icon = Icons.Default.Description,
                                onClick = { onChoose(document.id) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Impor dokumen")
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                        Text("Batal")
                    }
                }
            }
        }
    }
}

@Composable
private fun SuiteToolTile(tool: LocalTool, onClick: () -> Unit) {
    val tone = toolTone(tool)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 118.dp).semantics { role = Role.Button },
        shape = RoundedCornerShape(20.dp),
        color = tone.tint,
        contentColor = tone.strong,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(suiteToolIcon(tool), contentDescription = null, tint = tone.strong, modifier = Modifier.size(19.dp))
            }
            Text(
                tool.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuiteDocumentRow(
    title: String,
    supporting: String,
    icon: ImageVector,
    onClick: () -> Unit,
    actionLabel: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).semantics { role = Role.Button },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Tones.Neutral.tint),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Tones.Neutral.strong, modifier = Modifier.size(19.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actionLabel != null) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SuiteHeading(title: String, supporting: String? = null) {
    Column(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun suiteGridCells(): GridCells {
    // Two readable columns on ordinary phones at 130%; larger text reflows to one.
    val minSize = if (LocalDensity.current.fontScale > 1.5f) 220.dp else 156.dp
    return remember(minSize) { GridCells.Adaptive(minSize) }
}

@Composable
private fun suiteDateFormat(): DateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
}

private fun suitePageCount(count: Int): String = "$count halaman"

private fun suiteToolIcon(tool: LocalTool): ImageVector = when (tool) {
    LocalTool.SCAN -> Icons.Default.DocumentScanner
    LocalTool.IMPORT_IMAGES -> Icons.Default.Image
    LocalTool.IMPORT_PDF, LocalTool.PDF -> Icons.Default.PictureAsPdf
    LocalTool.ID_CARD -> Icons.Default.Badge
    LocalTool.QR -> Icons.Default.QrCodeScanner
    LocalTool.OCR -> Icons.Default.TextFields
    LocalTool.TXT -> Icons.Default.Notes
    LocalTool.DOCX -> Icons.Default.Article
    LocalTool.SIGN -> Icons.Default.Gesture
    LocalTool.PEN -> Icons.Default.Draw
    LocalTool.HIGHLIGHT -> Icons.Default.Highlight
    LocalTool.TEXT -> Icons.Default.Edit
    LocalTool.WATERMARK -> Icons.Default.BrandingWatermark
    LocalTool.EXTRACT -> Icons.Default.ContentCut
    LocalTool.SPLIT -> Icons.Default.CallSplit
    LocalTool.COMPRESS -> Icons.Default.Compress
    LocalTool.PROTECT -> Icons.Default.Lock
    LocalTool.LONG_IMAGE -> Icons.Default.ViewDay
    LocalTool.PRINT -> Icons.Default.Print
}
