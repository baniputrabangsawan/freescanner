package com.freescanner.imaging

import android.graphics.Bitmap
import android.graphics.Color
import com.freescanner.model.Corner
import com.freescanner.model.Crop
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Bright, low-chroma connected paper against a darker border; never guesses on weak evidence. */
internal object PaperCornerDetector {
    fun detect(bitmap: Bitmap): Crop {
        val width = bitmap.width
        val height = bitmap.height
        if (min(width, height) < 48) return Crop.Full
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        for (x in 0 until width) {
            histogram[ImageProcessor.luminance(pixels[x])]++
            histogram[ImageProcessor.luminance(pixels[(height - 1) * width + x])]++
        }
        for (y in 1 until height - 1) {
            histogram[ImageProcessor.luminance(pixels[y * width])]++
            histogram[ImageProcessor.luminance(pixels[y * width + width - 1])]++
        }
        var accumulated = 0
        val percentile = ((width + height - 2) * 2 * .6).toInt()
        var border = 0
        for (value in histogram.indices) {
            accumulated += histogram[value]
            if (accumulated >= percentile) {
                border = value
                break
            }
        }
        if (border > 205) return Crop.Full
        val threshold = max(145, border + 30)
        val mask = ByteArray(pixels.size)
        for (index in pixels.indices) {
            if (index % width == 0) ImageProcessor.checkInterrupted()
            val color = pixels[index]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            if (ImageProcessor.luminance(color) >= threshold && max(r, max(g, b)) - min(r, min(g, b)) < 70) mask[index] = 1
        }
        val queue = IntArray(pixels.size)
        var largestSize = 0
        var secondLargestSize = 0
        var candidate: Crop? = null
        for (seed in mask.indices) {
            if (seed % width == 0) ImageProcessor.checkInterrupted()
            if (mask[seed].toInt() != 1) continue
            var read = 0
            var size = 1
            queue[0] = seed
            mask[seed] = 2
            var topLeft = seed
            var topRight = seed
            var bottomRight = seed
            var bottomLeft = seed
            var touchesBorder = false
            fun visit(index: Int) {
                if (mask[index].toInt() == 1) {
                    mask[index] = 2
                    queue[size++] = index
                }
            }
            while (read < size) {
                if (read % 4096 == 0) ImageProcessor.checkInterrupted()
                val index = queue[read++]
                val x = index % width
                val y = index / width
                if (x <= 2 || y <= 2 || x >= width - 3 || y >= height - 3) touchesBorder = true
                if (x + y < topLeft % width + topLeft / width) topLeft = index
                if (x - y > topRight % width - topRight / width) topRight = index
                if (x + y > bottomRight % width + bottomRight / width) bottomRight = index
                if (y - x > bottomLeft / width - bottomLeft % width) bottomLeft = index
                if (x > 0) visit(index - 1)
                if (x < width - 1) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y < height - 1) visit(index + width)
            }
            if (touchesBorder) continue
            val corners = listOf(topLeft, topRight, bottomRight, bottomLeft).map {
                Corner((it % width).toFloat() / width, (it / width).toFloat() / height)
            }
            val crop = Crop(corners)
            if (!crop.isValid()) continue
            var twiceArea = 0f
            for (i in 0..3) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                twiceArea += a.x * b.y - a.y * b.x
            }
            val area = abs(twiceArea) * .5f
            if (area !in .18f.. .94f || size / (area * pixels.size) < .82f) continue
            if (!edgesHaveContrast(pixels, width, height, corners)) continue
            if (size > largestSize) {
                secondLargestSize = largestSize
                largestSize = size
                candidate = crop
            } else {
                secondLargestSize = max(secondLargestSize, size)
            }
        }
        return if (secondLargestSize > largestSize * .4f) Crop.Full else candidate ?: Crop.Full
    }

    private fun edgesHaveContrast(pixels: IntArray, width: Int, height: Int, corners: List<Corner>): Boolean {
        fun value(x: Float, y: Float): Int {
            val ix = x.toInt().coerceIn(0, width - 1)
            val iy = y.toInt().coerceIn(0, height - 1)
            return ImageProcessor.luminance(pixels[iy * width + ix])
        }
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val dx = (b.x - a.x) * width
            val dy = (b.y - a.y) * height
            val length = hypot(dx, dy)
            if (length < 24) return false
            val nx = -dy / length * 3
            val ny = dx / length * 3
            var good = 0
            for (sample in 1..20) {
                val t = sample / 21f
                val x = a.x * width + dx * t
                val y = a.y * height + dy * t
                if (value(x + nx, y + ny) - value(x - nx, y - ny) >= 18) good++
            }
            if (good < 14) return false
        }
        return true
    }
}
