package com.freescanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.freescanner.imaging.ImageProcessor
import com.freescanner.model.Corner
import com.freescanner.model.Crop
import com.freescanner.model.PageFilter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlin.math.min
import kotlin.math.roundToInt

private val CropStateSaver = Saver<Crop, ArrayList<Float>>(
    save = { crop -> ArrayList(crop.corners.flatMap { listOf(it.x, it.y) }) },
    restore = { values -> Crop(values.chunked(2).map { Corner(it[0], it[1]) }) },
)

@Composable
fun CropEditor(source: File, initial: Crop, onCancel: () -> Unit, onApply: (Crop) -> Unit) {
    val context = LocalContext.current
    var crop by rememberSaveable(source.path, initial, stateSaver = CropStateSaver) { mutableStateOf(initial.takeIf { it.isValid() } ?: Crop.Full) }
    var selectedCorner by rememberSaveable(source.path) { mutableIntStateOf(0) }
    val preview = remember(source) { mutableStateOf<Bitmap?>(null) }
    var error by remember(source) { mutableStateOf(false) }
    val labels = listOf("Kiri atas", "Kanan atas", "Kanan bawah", "Kiri bawah")
    LaunchedEffect(source) {
        var pending: Bitmap? = null
        try {
            runInterruptible(Dispatchers.IO) {
                pending = ImageProcessor(context).render(source, Crop.Full, 0, PageFilter.ORIGINAL, 1280)
            }
            preview.value = pending
            pending = null
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            error = true
        } finally {
            pending?.recycle()
        }
    }
    fun updateCorner(index: Int, point: Corner) {
        val candidate = Crop(crop.corners.toMutableList().apply {
            this[index] = Corner(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
        })
        if (candidate.isValid()) crop = candidate
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF0EFF4)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Batal") }
                Button(onClick = { onApply(crop) }, enabled = preview.value != null && crop.isValid()) { Text("Terapkan pangkasan") }
            }
            Text("Sesuaikan sudut dokumen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFC9C9C9)), contentAlignment = Alignment.Center) {
                val bitmap = preview.value
                when {
                    bitmap != null -> CropPreview(bitmap, crop, selectedCorner, labels, { selectedCorner = it }, ::updateCorner)
                    error -> Text("Gambar ini tidak dapat dibuka. Batalkan lalu impor ulang.", modifier = Modifier.padding(24.dp))
                    else -> CircularProgressIndicator(Modifier.semantics { contentDescription = "Memuat pratinjau pangkasan" })
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pilih sudut", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { crop = Crop.Full }) { Text("Atur ulang") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { index, label ->
                        OutlinedButton(onClick = { selectedCorner = index }, modifier = Modifier.semantics { selected = selectedCorner == index }) {
                            Text(if (selectedCorner == index) "$label ✓" else label)
                        }
                    }
                }
                val point = crop.corners[selectedCorner]
                Text("${labels[selectedCorner]} horizontal: ${(point.x * 100).roundToInt()}%")
                Slider(value = point.x, onValueChange = { updateCorner(selectedCorner, point.copy(x = it)) }, modifier = Modifier.semantics { contentDescription = "Posisi horizontal ${labels[selectedCorner]}" })
                Text("${labels[selectedCorner]} vertikal: ${(point.y * 100).roundToInt()}%")
                Slider(value = point.y, onValueChange = { updateCorner(selectedCorner, point.copy(y = it)) }, modifier = Modifier.semantics { contentDescription = "Posisi vertikal ${labels[selectedCorner]}" })
                Text("Seret sudut atau gunakan penggeser posisi. Sudut tidak boleh bersilangan.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CropPreview(bitmap: Bitmap, crop: Crop, selectedCorner: Int, labels: List<String>, onSelect: (Int) -> Unit, onMove: (Int, Corner) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    val scale = min(((size.width - margin * 2) / bitmap.width).coerceAtLeast(0f), ((size.height - margin * 2) / bitmap.height).coerceAtLeast(0f))
    val imageWidth = bitmap.width * scale
    val imageHeight = bitmap.height * scale
    val origin = Offset((size.width - imageWidth) / 2, (size.height - imageHeight) / 2)
    val currentCrop by rememberUpdatedState(crop)
    val currentMove by rememberUpdatedState(onMove)
    val currentSelect by rememberUpdatedState(onSelect)
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()) {
            if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
            drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt()))
            val path = Path().apply {
                crop.corners.forEachIndexed { i, corner ->
                    val x = origin.x + corner.x * imageWidth
                    val y = origin.y + corner.y * imageHeight
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(path, Color(0xFF2494F2).copy(alpha = .13f))
            drawPath(path, Color.White, style = Stroke(width = 4.dp.toPx()))
            drawPath(path, Color(0xFF005EBF), style = Stroke(width = 2.dp.toPx()))
        }
        if (imageWidth > 0 && imageHeight > 0) {
            crop.corners.forEachIndexed { index, point ->
                Box(
                    Modifier.offset { IntOffset((origin.x + point.x * imageWidth - margin).roundToInt(), (origin.y + point.y * imageHeight - margin).roundToInt()) }
                        .size(48.dp)
                        .semantics { contentDescription = "Sudut pangkasan ${labels[index]}"; selected = selectedCorner == index }
                        .clickable { onSelect(index) }
                        .pointerInput(index, imageWidth, imageHeight) {
                            detectDragGestures(onDragStart = { currentSelect(index) }) { change, drag ->
                                change.consume()
                                val corner = currentCrop.corners[index]
                                currentMove(index, Corner(corner.x + drag.x / imageWidth, corner.y + drag.y / imageHeight))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(if (selectedCorner == index) 22.dp else 18.dp).background(Color.White, CircleShape)) {
                        drawCircle(Color(0xFF005EBF), style = Stroke(3.dp.toPx()))
                        if (selectedCorner == index) drawCircle(Color(0xFF005EBF), radius = 4.dp.toPx())
                    }
                }
            }
        }
    }
}
