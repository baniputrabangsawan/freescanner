package com.freescanner.annotations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.freescanner.model.Corner
import com.freescanner.model.PageAnnotation
import com.freescanner.model.ScanPage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Activity-retained, memory-only edits. Neither opening nor canceling writes document data. */
internal class AnnotationSession : ViewModel() {
    private var pageKey: String? = null
    var annotations by mutableStateOf<List<PageAnnotation>>(emptyList())
        private set
    var selected by mutableIntStateOf(-1)
    var tool by mutableStateOf(AnnotationTool.PEN)
    var text by mutableStateOf("")
    var color by mutableIntStateOf(android.graphics.Color.BLACK)
    var width by mutableFloatStateOf(0.004f)
    var warning by mutableStateOf<String?>(null)
    private val history = ArrayDeque<List<PageAnnotation>>()
    var canUndo by mutableStateOf(false)
        private set
    private var grouped = false

    fun open(page: ScanPage, initialTool: AnnotationTool) {
        if (pageKey == page.id) return
        close()
        pageKey = page.id
        annotations = page.annotations.toList()
        chooseTool(initialTool)
    }

    fun chooseTool(value: AnnotationTool) {
        endChange()
        tool = value
        selected = -1
        warning = null
        color = if (value == AnnotationTool.HIGHLIGHT) android.graphics.Color.YELLOW else android.graphics.Color.BLACK
        width = if (value == AnnotationTool.HIGHLIGHT) 0.035f else 0.004f
    }

    fun change(value: List<PageAnnotation>, group: Boolean = false) {
        if (value == annotations) return
        if (!group || !grouped) {
            if (history.size == 50) history.removeFirst()
            history.addLast(annotations)
        }
        grouped = group
        annotations = value
        canUndo = history.isNotEmpty()
    }

    fun update(index: Int, value: PageAnnotation, group: Boolean = false) {
        if (index !in annotations.indices) return
        change(annotations.toMutableList().apply { this[index] = value }, group)
    }

    fun endChange() { grouped = false }
    fun undo() {
        endChange()
        if (history.isNotEmpty()) annotations = history.removeLast()
        selected = -1
        canUndo = history.isNotEmpty()
    }

    fun close() {
        pageKey = null
        annotations = emptyList()
        history.clear()
        selected = -1
        text = ""
        warning = null
        grouped = false
        canUndo = false
    }
}

/** Pixel-space transformation, preserving shape on non-square pages and keeping the whole stroke on-page. */
internal fun transformInk(
    ink: PageAnnotation.Ink,
    aspect: Float,
    x: Float = (ink.points.minOf { it.x } + ink.points.maxOf { it.x }) / 2f,
    y: Float = (ink.points.minOf { it.y } + ink.points.maxOf { it.y }) / 2f,
    scale: Float = 1f,
    degrees: Float = 0f,
): PageAnnotation.Ink {
    val width = max(aspect, 1f)
    val height = max(1f / aspect, 1f)
    val left = ink.points.minOf { it.x } * width
    val right = ink.points.maxOf { it.x } * width
    val top = ink.points.minOf { it.y } * height
    val bottom = ink.points.maxOf { it.y } * height
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val transformed = ink.points.map { point ->
        val dx = (point.x * width - centerX) * scale
        val dy = (point.y * height - centerY) * scale
        Corner(dx * cosine - dy * sine, dx * sine + dy * cosine)
    }
    val minX = transformed.minOf { it.x }
    val maxX = transformed.maxOf { it.x }
    val minY = transformed.minOf { it.y }
    val maxY = transformed.maxOf { it.y }
    val fit = min(1f, min(width / (maxX - minX).coerceAtLeast(0.00001f), height / (maxY - minY).coerceAtLeast(0.00001f)))
    val destinationX = (x * width).coerceIn(-minX * fit, width - maxX * fit)
    val destinationY = (y * height).coerceIn(-minY * fit, height - maxY * fit)
    return ink.copy(points = transformed.map {
        Corner(((it.x * fit + destinationX) / width).coerceIn(0f, 1f), ((it.y * fit + destinationY) / height).coerceIn(0f, 1f))
    })
}

internal data class PageViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float) = x >= left && y >= top && x <= left + width && y <= top + height
    fun point(x: Float, y: Float) = Corner(((x - left) / width).coerceIn(0f, 1f), ((y - top) / height).coerceIn(0f, 1f))
    companion object {
        fun fit(areaWidth: Float, areaHeight: Float, imageWidth: Int, imageHeight: Int): PageViewport {
            val scale = min(areaWidth / imageWidth, areaHeight / imageHeight)
            val width = imageWidth * scale
            val height = imageHeight * scale
            return PageViewport((areaWidth - width) / 2f, (areaHeight - height) / 2f, width, height)
        }
    }
}
