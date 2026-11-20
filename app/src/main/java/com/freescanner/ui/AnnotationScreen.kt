package com.freescanner.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freescanner.AppViewModel
import com.freescanner.annotations.AnnotationScene
import com.freescanner.annotations.AnnotationSession
import com.freescanner.annotations.AnnotationTool
import com.freescanner.annotations.PageViewport
import com.freescanner.annotations.inkPaint
import com.freescanner.annotations.transformInk
import com.freescanner.model.Corner
import com.freescanner.model.PageAnnotation
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanPage
import com.freescanner.model.isValid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun AnnotationScreen(
    page: ScanPage,
    model: AppViewModel,
    tool: AnnotationTool,
    onCancel: () -> Unit,
    onSave: (List<PageAnnotation>) -> Unit,
) {
    val session: AnnotationSession = viewModel(key = "annotation-editor")
    remember(page.id) { session.open(page, tool); page.id }
    val cancel = { session.close(); onCancel() }
    BackHandler(onBack = cancel)
    var bitmap by remember(page.id, page.sourcePath, page.crop, page.rotation, page.filter) { mutableStateOf<Bitmap?>(null) }
    var error by remember(page.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(page.id, page.sourcePath, page.crop, page.rotation, page.filter) {
        var pending: Bitmap? = null
        try {
            val snapshot = ScanDocument("annotation-preview", "", 0L, 0L, listOf(page))
            model.repository.withDocumentSources(snapshot) { document ->
                runInterruptible(Dispatchers.IO) {
                    val base = document.pages.single()
                    // The source contains no overlays: draw the editable list exactly once in the canvas.
                    pending = model.processor.render(model.repository.sourceFile(base), base.crop, base.rotation, base.filter, 1600)
                }
            }
            bitmap = pending
            pending = null // Published bitmaps belong to the Compose display list; let GC release them.
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: Exception) {
            error = failure.message ?: "Halaman ini tidak dapat dibuka. Batalkan, lalu buka kembali."
        } finally {
            pending?.recycle()
        }
    }
    val image = bitmap
    val withinLimits = session.annotations.size <= 1_000 &&
        session.annotations.sumOf { if (it is PageAnnotation.Ink) it.points.size else 0 } <= 100_000 &&
        session.annotations.sumOf { if (it is PageAnnotation.Label) it.text.length else 0 } <= 100_000
    val valid = withinLimits && session.annotations.all { it.isValid() }
    val sceneResult = remember(image, session.annotations) {
        image?.let { runCatching { AnnotationScene(it.width, it.height, session.annotations.filter { item -> item.isValid() }) } }
    }
    val renderError = sceneResult?.exceptionOrNull()?.message
    val selected = session.annotations.getOrNull(session.selected)
    val controlsHeight = (LocalConfiguration.current.screenHeightDp * 0.36f).dp
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF0EFF4)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = cancel) { Text("Batal") }
                Text("Anotasi halaman", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    val result = session.annotations.toList()
                    session.close()
                    onSave(result)
                }, enabled = image != null && valid && renderError == null) { Text("Simpan") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnotationTool.entries.forEach { item ->
                    FilterChip(selected = session.tool == item, onClick = { session.chooseTool(item) }, label = { Text(item.label()) })
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFCCCCCC)), contentAlignment = Alignment.Center) {
                when {
                    image != null -> AnnotationPreview(image, sceneResult?.getOrNull(), session)
                    error != null -> Text(error.orEmpty(), modifier = Modifier.padding(24.dp))
                    else -> CircularProgressIndicator(Modifier.semantics { contentDescription = "Memuat pratinjau anotasi" })
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = session::undo, enabled = session.canUndo) { Text("Urungkan") }
                    OutlinedButton(onClick = { session.change(emptyList()); session.selected = -1 }, enabled = session.annotations.isNotEmpty()) { Text("Hapus semua anotasi") }
                    if (selected != null) OutlinedButton(onClick = {
                        session.change(session.annotations.filterIndexed { index, _ -> index != session.selected })
                        session.selected = -1
                    }) { Text("Hapus yang dipilih") }
                }
                if (session.annotations.isNotEmpty()) {
                    Text("Pilih anotasi yang ada untuk diedit atau dihapus", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        session.annotations.forEachIndexed { index, annotation ->
                            FilterChip(selected = session.selected == index, onClick = {
                                session.chooseTool(if (annotation is PageAnnotation.Label) AnnotationTool.TEXT else if ((annotation as PageAnnotation.Ink).highlight) AnnotationTool.HIGHLIGHT else AnnotationTool.PEN)
                                session.selected = index
                            }, label = {
                                Text("${index + 1}: ${if (annotation is PageAnnotation.Label) annotation.text.take(20).ifBlank { "Teks kosong" } else if ((annotation as PageAnnotation.Ink).highlight) "Sorot" else "Goresan"}")
                            })
                        }
                    }
                }
                if (selected is PageAnnotation.Label) {
                    LabelControls(selected, { session.update(session.selected, it, group = true) }, session::endChange)
                } else if (selected is PageAnnotation.Ink) {
                    InkControls(selected, image?.let { it.width.toFloat() / it.height } ?: 1f,
                        { session.update(session.selected, it, group = true) }, session::endChange)
                } else if (session.tool == AnnotationTool.TEXT || session.tool == AnnotationTool.WATERMARK) {
                    OutlinedTextField(value = session.text, onValueChange = { if (it.length <= 2000) session.text = it },
                        label = { Text(if (session.tool == AnnotationTool.WATERMARK) "Teks marka air" else "Teks untuk disisipkan") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Button(onClick = {
                        val watermark = session.tool == AnnotationTool.WATERMARK
                        val label = PageAnnotation.Label(session.text, color = session.color, opacity = if (watermark) 0.25f else 1f, rotation = if (watermark) -30f else 0f)
                        session.change(session.annotations + label)
                        session.selected = session.annotations.lastIndex
                    }, enabled = session.text.isNotBlank() && PageAnnotation.Label(session.text).isValid()) { Text("Tambah ${if (session.tool == AnnotationTool.WATERMARK) "marka air" else "teks"}") }
                    Text("Tambahkan teks, lalu seret atau gunakan kontrol posisi, ukuran, dan rotasi.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(if (session.tool == AnnotationTool.SIGN) "Gambar tanda tangan langsung di halaman. Tidak ada yang disimpan sebelum memilih Simpan."
                        else "Gambar di halaman. Pilih anotasi di bawah pratinjau untuk menyesuaikan atau menghapusnya.", style = MaterialTheme.typography.bodySmall)
                    ColorControls(session.color, { session.color = it })
                    ValueSlider("Lebar goresan", session.width, 0.0005f..0.15f, "${(session.width * 1000).roundToInt()} ‰ dari sisi terpendek", { session.width = it })
                }
                if (!withinLimits) Text("Halaman ini memiliki terlalu banyak anotasi, titik goresan, atau karakter teks. Urungkan atau hapus anotasi sebelum menyimpan.", color = MaterialTheme.colorScheme.error)
                else if (!valid) Text("Teks tidak boleh kosong atau berisi karakter kontrol. Perbaiki teks atau hapus anotasi sebelum menyimpan.", color = MaterialTheme.colorScheme.error)
                if (renderError != null) Text(renderError, color = MaterialTheme.colorScheme.error)
                session.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun AnnotationTool.label() = when (this) {
    AnnotationTool.SIGN -> "Tanda tangan"
    AnnotationTool.PEN -> "Pena"
    AnnotationTool.HIGHLIGHT -> "Sorot"
    AnnotationTool.TEXT -> "Teks"
    AnnotationTool.WATERMARK -> "Marka air"
}

@Composable
private fun LabelControls(label: PageAnnotation.Label, onChange: (PageAnnotation.Label) -> Unit, onFinish: () -> Unit) {
    OutlinedTextField(value = label.text, onValueChange = { if (it.length <= 2000) onChange(label.copy(text = it)) }, label = { Text("Teks anotasi") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    ColorControls(label.color, { onFinish(); onChange(label.copy(color = it)); onFinish() })
    ValueSlider("Posisi horizontal", label.x, 0f..1f, "${(label.x * 100).roundToInt()}%", { onChange(label.copy(x = it)) }, onFinish)
    ValueSlider("Posisi vertikal", label.y, 0f..1f, "${(label.y * 100).roundToInt()}%", { onChange(label.copy(y = it)) }, onFinish)
    ValueSlider("Ukuran teks", label.size, 0.005f..0.5f, "${(label.size * 100).roundToInt()}% dari sisi terpendek", { onChange(label.copy(size = it)) }, onFinish)
    ValueSlider("Rotasi", label.rotation, -360f..360f, "${label.rotation.roundToInt()} derajat", { onChange(label.copy(rotation = it)) }, onFinish)
    ValueSlider("Opasitas", label.opacity, 0f..1f, "${(label.opacity * 100).roundToInt()}%", { onChange(label.copy(opacity = it)) }, onFinish)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !label.tiled, onClick = { onFinish(); onChange(label.copy(tiled = false)); onFinish() }, label = { Text("Tunggal") })
        FilterChip(selected = label.tiled, onClick = { onFinish(); onChange(label.copy(tiled = true)); onFinish() }, label = { Text("Berulang") })
    }
    if (label.tiled) ValueSlider("Jarak marka air", label.spacing, 0.05f..2f, "${(label.spacing * 100).roundToInt()}% dari sisi terpendek", { onChange(label.copy(spacing = it)) }, onFinish)
}

@Composable
private fun InkControls(ink: PageAnnotation.Ink, aspect: Float, onChange: (PageAnnotation.Ink) -> Unit, onFinish: () -> Unit) {
    val x = (ink.points.minOf { it.x } + ink.points.maxOf { it.x }) / 2f
    val y = (ink.points.minOf { it.y } + ink.points.maxOf { it.y }) / 2f
    ColorControls(ink.color, { onFinish(); onChange(ink.copy(color = it)); onFinish() })
    ValueSlider("Lebar goresan", ink.width, 0.0005f..0.2f, "${(ink.width * 1000).roundToInt()} ‰ dari sisi terpendek", { onChange(ink.copy(width = it)) }, onFinish)
    ValueSlider("Posisi horizontal", x, 0f..1f, "${(x * 100).roundToInt()}%", { onChange(transformInk(ink, aspect, x = it, y = y)) }, onFinish)
    ValueSlider("Posisi vertikal", y, 0f..1f, "${(y * 100).roundToInt()}%", { onChange(transformInk(ink, aspect, x = x, y = it)) }, onFinish)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Perkecil" to Pair(0.85f, 0f), "Perbesar" to Pair(1.15f, 0f), "Putar ke kiri" to Pair(1f, -15f), "Putar ke kanan" to Pair(1f, 15f)).forEach { (name, transform) ->
            OutlinedButton(onClick = { onFinish(); onChange(transformInk(ink, aspect, x, y, transform.first, transform.second)); onFinish() }) { Text(name) }
        }
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, description: String, onChange: (Float) -> Unit, onFinish: () -> Unit = {}) {
    Text("$label: $description", style = MaterialTheme.typography.labelMedium)
    Slider(value = value.coerceIn(range), onValueChange = onChange, onValueChangeFinished = onFinish, valueRange = range,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
private fun ColorControls(color: Int, onChange: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Hitam" to android.graphics.Color.BLACK, "Biru" to 0xFF1565C0.toInt(), "Merah" to 0xFFB71C1C.toInt(), "Kuning" to android.graphics.Color.YELLOW, "Putih" to android.graphics.Color.WHITE).forEach { (name, value) ->
            FilterChip(selected = color == value, onClick = { onChange(value) }, label = { Text(name) })
        }
    }
}

@Composable
internal fun AnnotationPreview(bitmap: Bitmap, scene: AnnotationScene?, session: AnnotationSession) {
    val livePath = remember(bitmap) { Path() }
    val livePoints = remember(bitmap) { ArrayList<Corner>() }
    var livePaint by remember(bitmap) { mutableStateOf<Paint?>(null) }
    var revision by remember(bitmap) { mutableIntStateOf(0) }
    val latestSession by rememberUpdatedState(session)
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val bitmapBounds = remember(bitmap) { Rect(0, 0, bitmap.width, bitmap.height) }
    Canvas(Modifier.fillMaxSize().testTag("annotation-preview").semantics { contentDescription = "Pratinjau anotasi halaman. Gambar dengan jari; gunakan kontrol di bawah untuk mengatur posisi teks dan mengedit anotasi." }
        .pointerInput(bitmap, session.tool, session.color, session.width, session.selected) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val viewport = PageViewport.fit(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                if (!viewport.contains(down.position.x, down.position.y)) return@awaitEachGesture
                val state = latestSession
                val selectedIndex = state.selected
                val label = state.annotations.getOrNull(selectedIndex) as? PageAnnotation.Label
                val drawInk = state.tool == AnnotationTool.SIGN || state.tool == AnnotationTool.PEN || state.tool == AnnotationTool.HIGHLIGHT
                val start = viewport.point(down.position.x, down.position.y)
                if (drawInk) {
                    livePoints.clear()
                    livePoints.add(start)
                    livePath.reset()
                    livePath.moveTo(start.x * bitmap.width, start.y * bitmap.height)
                    livePaint = inkPaint(state.color, state.width * min(bitmap.width, bitmap.height), state.tool == AnnotationTool.HIGHLIGHT)
                    revision++
                } else if (label == null) return@awaitEachGesture
                down.consume()
                var completed = false
                state.endChange()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = viewport.point(change.position.x, change.position.y)
                        if (drawInk && livePoints.size == 100_000 && point != livePoints.last()) {
                            state.warning = "Goresan ini mencapai 100.000 titik. Gerakan berikutnya tidak ditambahkan. Urungkan untuk menggambar ulang goresan yang lebih pendek."
                        }
                        if (drawInk && point != livePoints.last() && livePoints.size < 100_000) {
                            livePoints.add(point)
                            livePath.lineTo(point.x * bitmap.width, point.y * bitmap.height)
                            revision++
                        } else if (!drawInk && label != null) {
                            state.update(selectedIndex, label.copy(x = (label.x + point.x - start.x).coerceIn(0f, 1f), y = (label.y + point.y - start.y).coerceIn(0f, 1f)), group = true)
                        }
                        change.consume()
                        if (!change.pressed) { completed = true; break }
                    }
                    if (drawInk && completed) state.change(state.annotations + PageAnnotation.Ink(livePoints.toList(), state.color, state.width, state.tool == AnnotationTool.HIGHLIGHT))
                } finally {
                    state.endChange()
                    livePaint = null
                    livePoints.clear()
                    livePath.reset()
                    revision++
                }
            }
        }) {
        val viewport = PageViewport.fit(size.width, size.height, bitmap.width, bitmap.height)
        revision // Observe incremental path changes without copying the point list on every event.
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val save = canvas.save()
            try {
                canvas.translate(viewport.left, viewport.top)
                canvas.scale(viewport.width / bitmap.width, viewport.height / bitmap.height)
                canvas.clipRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                canvas.drawBitmap(bitmap, null, bitmapBounds, bitmapPaint)
                scene?.draw(canvas)
                livePaint?.let { paint ->
                    if (livePoints.size == 1) {
                        paint.style = Paint.Style.FILL
                        val point = livePoints[0]
                        canvas.drawCircle(point.x * bitmap.width, point.y * bitmap.height, paint.strokeWidth / 2f, paint)
                        paint.style = Paint.Style.STROKE
                    } else canvas.drawPath(livePath, paint)
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }
}
