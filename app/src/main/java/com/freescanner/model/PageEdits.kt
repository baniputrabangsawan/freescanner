package com.freescanner.model

/** Coordinates refer to the cropped, rotated page, normalized to its current dimensions. */
sealed interface PageAnnotation {
    data class Ink(
        val points: List<Corner>,
        val color: Int = -0x1000000,
        val width: Float = 0.004f,
        val highlight: Boolean = false,
    ) : PageAnnotation

    /** x/y locate the center; size and spacing are fractions of the shorter page dimension. */
    data class Label(
        val text: String,
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val size: Float = 0.06f,
        val color: Int = -0x1000000,
        val rotation: Float = 0f,
        val opacity: Float = 1f,
        val tiled: Boolean = false,
        val spacing: Float = 0.3f,
    ) : PageAnnotation
}

fun PageAnnotation.isValid(): Boolean = when (this) {
    is PageAnnotation.Ink -> points.isNotEmpty() && points.size <= 100_000 &&
        points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f } &&
        width.isFinite() && width in 0.0005f..0.2f
    is PageAnnotation.Label -> text.isNotBlank() && text.length <= 2_000 &&
        text.none { it.isISOControl() && it != '\n' && it != '\t' } &&
        x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f &&
        size.isFinite() && size in 0.005f..0.5f && rotation.isFinite() && rotation in -360f..360f &&
        opacity.isFinite() && opacity in 0f..1f && spacing.isFinite() && spacing in 0.05f..2f
}

fun PageAnnotation.rotatedClockwise(turns: Int): PageAnnotation {
    val quarterTurns = ((turns % 4) + 4) % 4
    if (quarterTurns == 0) return this
    fun rotate(point: Corner): Corner = when (quarterTurns) {
        1 -> Corner(1f - point.y, point.x)
        2 -> Corner(1f - point.x, 1f - point.y)
        else -> Corner(point.y, 1f - point.x)
    }
    return when (this) {
        is PageAnnotation.Ink -> copy(points = points.map(::rotate))
        is PageAnnotation.Label -> {
            val center = rotate(Corner(x, y))
            copy(x = center.x, y = center.y, rotation = (rotation + quarterTurns * 90f) % 360f)
        }
    }
}

data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun isValid(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left in 0f..1f && top in 0f..1f && right in left..1f && bottom in top..1f && text.length <= 100_000
}

/** Missing text means not recognized; an empty result is a genuinely blank/unrecognized page. */
data class OcrText(val text: String, val lines: List<OcrLine> = emptyList()) {
    fun isValid(): Boolean {
        if (text.length > 1_000_000 || lines.size > 10_000) return false
        var lineCharacters = 0
        for (line in lines) {
            if (!line.isValid()) return false
            lineCharacters += line.text.length
            if (lineCharacters > 1_000_000) return false
        }
        return true
    }

    /** Arbitrary corrections no longer claim the recognizer's original character placement. */
    fun edited(value: String): OcrText = if (value == text) this else OcrText(value)
}
