package com.freescanner.imaging

import android.graphics.Bitmap
import android.graphics.Color
import com.freescanner.model.Corner
import com.freescanner.model.Crop
import kotlin.math.abs
import kotlin.math.atan2
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
                if (x > 0) visit(index - 1)
                if (x < width - 1) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y < height - 1) visit(index + width)
            }
            // Read this component's own outline before releasing it, then mark it done (3) so the
            // next component's boundary test cannot see it.
            val corners = if (touchesBorder) null else componentQuad(width, height, mask, queue, size)
            for (i in 0 until size) mask[queue[i]] = 3
            if (corners == null) continue
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
            // Only accepted components compete for "largest": a bright speck or a rejected
            // component must not veto an otherwise good paper candidate.
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

    /**
     * Four true corners of one component, independent of how the page is rotated.
     *
     * A diameter pair (farthest from centroid, then farthest from that point) gives two opposite
     * corners; the boundary points farthest on either side of that diameter give the other two.
     * This replaces the previous min/max (x+y) rule, which returned extreme points rather than
     * corners and swapped width/height once the page was turned past ~45 degrees.
     */
    private fun componentQuad(width: Int, height: Int, mask: ByteArray, component: IntArray, size: Int): List<Corner>? {
        if (size < 16) return null
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until size) {
            val index = component[i]
            sumX += index % width
            sumY += index / width
        }
        val centreX = (sumX / size).toFloat()
        val centreY = (sumY / size).toFloat()

        val boundary = ArrayList<Int>(size / 4 + 16)
        for (i in 0 until size) {
            val index = component[i]
            val x = index % width
            val y = index / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return null
            if (mask[index - 1].toInt() != 2 || mask[index + 1].toInt() != 2 ||
                mask[index - width].toInt() != 2 || mask[index + width].toInt() != 2) boundary.add(index)
            if (boundary.size > MAX_BOUNDARY) return null
        }
        if (boundary.size < 8) return null

        fun farthest(fromX: Float, fromY: Float): Int {
            var best = boundary[0]
            var bestDistance = -1.0
            for (index in boundary) {
                val dx = (index % width) - fromX
                val dy = (index / width) - fromY
                val distance = dx.toDouble() * dx + dy.toDouble() * dy
                if (distance > bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }

        val first = farthest(centreX, centreY)
        val second = farthest((first % width).toFloat(), (first / width).toFloat())
        if (first == second) return null
        val ax = (first % width).toFloat()
        val ay = (first / width).toFloat()
        val bx = (second % width).toFloat()
        val by = (second / width).toFloat()
        var positive = -1
        var negative = -1
        var positiveDistance = 0.0
        var negativeDistance = 0.0
        for (index in boundary) {
            val px = (index % width).toFloat()
            val py = (index / width).toFloat()
            val cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            if (cross > positiveDistance) {
                positiveDistance = cross.toDouble()
                positive = index
            }
            if (cross < negativeDistance) {
                negativeDistance = cross.toDouble()
                negative = index
            }
        }
        if (positive < 0 || negative < 0) return null
        val points = listOf(first, positive, second, negative).map { index ->
            Corner((index % width).toFloat() / width, (index / width).toFloat() / height)
        }
        return orderClockwise(points)
    }

    /**
     * Sorts four corners into the clockwise top-left..bottom-left cycle [Crop] expects, starting at
     * the corner nearest the image's top-left.
     *
     * The cycle is what protects width/height: because the points are now true corners, the start
     * corner only decides the output's quarter-turn, never its aspect ratio. The previous min/max
     * (x+y) rule picked extreme points that are not corners once a page is turned past ~45 degrees.
     */
    private fun orderClockwise(points: List<Corner>): List<Corner>? {
        if (points.size != 4) return null
        if (points.zipWithNext().any { (a, b) -> a.x == b.x && a.y == b.y }) return null
        val centreX = points.sumOf { it.x.toDouble() }.toFloat() / 4f
        val centreY = points.sumOf { it.y.toDouble() }.toFloat() / 4f
        // atan2 with y growing downward yields a clockwise cycle in image coordinates.
        val cycle = points.sortedBy { atan2((it.y - centreY).toDouble(), (it.x - centreX).toDouble()) }
        val start = cycle.indices.minByOrNull { cycle[it].x + cycle[it].y } ?: return null
        return List(4) { cycle[(start + it) % 4] }
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

    /** Bounds the outline buffer so a pathological mask cannot allocate without limit. */
    private const val MAX_BOUNDARY = 200_000
}
