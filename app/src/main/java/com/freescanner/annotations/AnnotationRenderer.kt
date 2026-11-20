package com.freescanner.annotations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.freescanner.model.PageAnnotation
import com.freescanner.model.isValid
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

enum class AnnotationTool { SIGN, PEN, HIGHLIGHT, TEXT, WATERMARK }

/** Draws once into a caller-owned base image. Never pass an already annotated image. */
object AnnotationRenderer {
    fun draw(bitmap: Bitmap, annotations: List<PageAnnotation>) {
        require(!bitmap.isRecycled && bitmap.isMutable) { "Anotasi memerlukan gambar halaman yang dapat diubah." }
        if (annotations.isEmpty()) return
        AnnotationScene(bitmap.width, bitmap.height, annotations).draw(Canvas(bitmap))
    }
}

/** Prepared geometry shared by the live preview and bitmap export; no work per ink point on redraw. */
internal class AnnotationScene(width: Int, height: Int, annotations: List<PageAnnotation>) {
    private val width = width.toFloat()
    private val height = height.toFloat()
    private val entries: List<Entry>

    init {
        require(width > 0 && height > 0)
        require(annotations.all { it.isValid() }) { "Ada anotasi dengan geometri atau teks yang tidak valid." }
        val shortSide = min(this.width, this.height)
        entries = annotations.map { annotation ->
            when (annotation) {
                is PageAnnotation.Ink -> InkEntry(annotation, this.width, this.height, shortSide)
                is PageAnnotation.Label -> LabelEntry(annotation, this.width, this.height, shortSide)
            }
        }
    }

    fun draw(canvas: Canvas) {
        val save = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width, height)
            entries.forEach { it.draw(canvas) }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private sealed interface Entry { fun draw(canvas: Canvas) }

    private class InkEntry(annotation: PageAnnotation.Ink, width: Float, height: Float, shortSide: Float) : Entry {
        private val paint = inkPaint(annotation.color, annotation.width * shortSide, annotation.highlight)
        private val path = Path()
        private val first = annotation.points.first()
        private val x = first.x * width
        private val y = first.y * height
        private val dot = annotation.points.size == 1
        init {
            path.moveTo(x, y)
            for (index in 1 until annotation.points.size) {
                val point = annotation.points[index]
                path.lineTo(point.x * width, point.y * height)
            }
        }
        override fun draw(canvas: Canvas) {
            if (dot) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, paint.strokeWidth / 2f, paint)
            } else canvas.drawPath(path, paint)
        }
    }

    private class LabelEntry(
        private val annotation: PageAnnotation.Label,
        private val width: Float,
        private val height: Float,
        shortSide: Float,
    ) : Entry {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = annotation.color
            alpha = (Color.alpha(annotation.color) * annotation.opacity).roundToInt().coerceIn(0, 255)
            textSize = annotation.size * shortSide
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT
        }
        private val lines = annotation.text.replace("\t", "    ").split('\n')
        private val lineHeight = paint.fontSpacing
        private val firstBaseline = -(paint.ascent() + paint.descent()) / 2f - (lines.size - 1) * lineHeight / 2f
        private val centerX = annotation.x * width
        private val centerY = annotation.y * height
        private val textWidth = lines.maxOf { paint.measureText(it) }
        // A circumscribed square keeps rotated tiles apart and covers every visible edge.
        private val diameter = hypot(textWidth, lineHeight * lines.size)
        private val step = diameter + annotation.spacing * shortSide
        private val startX = centerX - ceil((centerX + diameter / 2f) / step) * step
        private val startY = centerY - ceil((centerY + diameter / 2f) / step) * step
        private val columns = ceil((width + diameter / 2f - startX) / step).toInt() + 1
        private val rows = ceil((height + diameter / 2f - startY) / step).toInt() + 1

        init {
            require(!annotation.tiled || columns.toLong() * rows <= 20_000L) {
                "Halaman ini terlalu sempit untuk jarak marka air tersebut. Perbesar jarak atau gunakan Tunggal."
            }
        }

        override fun draw(canvas: Canvas) {
            if (!annotation.tiled) drawAt(canvas, centerX, centerY)
            else for (row in 0 until rows) for (column in 0 until columns) {
                drawAt(canvas, startX + column * step, startY + row * step)
            }
        }

        private fun drawAt(canvas: Canvas, x: Float, y: Float) {
            val save = canvas.save()
            try {
                canvas.translate(x, y)
                canvas.rotate(annotation.rotation)
                lines.forEachIndexed { index, line ->
                    canvas.drawText(line, 0f, firstBaseline + index * lineHeight, paint)
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }
}

internal fun inkPaint(color: Int, width: Float, highlight: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    alpha = (Color.alpha(color) * if (highlight) 0.3f else 1f).roundToInt()
    strokeWidth = width
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
