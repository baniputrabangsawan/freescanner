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
            val corners = if (touchesBorder) null else componentQuad(pixels, width, height, mask, queue, size)
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
    private fun componentQuad(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: ByteArray,
        component: IntArray,
        size: Int,
    ): List<Corner>? {
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
        var luminance = 0L
        for (i in 0 until size) luminance += ImageProcessor.luminance(pixels[component[i]])
        val insideLuminance = (luminance / size).toInt()

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
        val pixelCorners = listOf(first, positive, second, negative).map { index ->
            floatArrayOf((index % width).toFloat(), (index / width).toFloat())
        }
        val ordered = orderCorners(pixelCorners) ?: return null
        return refineEdges(pixels, width, height, ordered, insideLuminance)
            .map { Corner(it[0] / width, it[1] / height) }
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

    /** Clockwise cycle in pixel space, starting at the corner nearest the image's top-left. */
    private fun orderCorners(points: List<FloatArray>): List<FloatArray>? {
        if (points.size != 4) return null
        for (i in 1..3) for (j in 0 until i) {
            if (points[i][0] == points[j][0] && points[i][1] == points[j][1]) return null
        }
        val centreX = points.sumOf { it[0].toDouble() }.toFloat() / 4f
        val centreY = points.sumOf { it[1].toDouble() }.toFloat() / 4f
        // atan2 with y growing downward yields a clockwise cycle in image coordinates.
        val cycle = points.sortedBy { atan2((it[1] - centreY).toDouble(), (it[0] - centreX).toDouble()) }
        val start = cycle.indices.minByOrNull { cycle[it][0] + cycle[it][1] } ?: return null
        return List(4) { cycle[(start + it) % 4] }
    }

    /**
     * Moves each corner onto the intersection of its two photometric edges, then inside them.
     *
     * The component outline is quantised to whole pixels and sits just outside the real paper edge,
     * so a crop taken straight from it resamples a sliver of background along the edges. Each edge is
     * therefore re-located from the image itself: along the edge normal the 50% luminance crossing
     * between paper and background is found, subpixel edge points are fitted with a least-squares
     * line, the line is shifted inward by [EDGE_INSET_PIXELS], and neighbouring lines are
     * intersected. Any edge with too few confident crossings keeps its mask position, and a corner
     * that would move implausibly far keeps its original point, so this can only sharpen a valid quad.
     */
    private fun refineEdges(
        pixels: IntArray,
        width: Int,
        height: Int,
        corners: List<FloatArray>,
        insideLuminance: Int,
    ): List<FloatArray> {
        fun value(x: Float, y: Float): Float {
            val clampedX = x.coerceIn(0f, width - 1.001f)
            val clampedY = y.coerceIn(0f, height - 1.001f)
            val x0 = clampedX.toInt()
            val y0 = clampedY.toInt()
            val fx = clampedX - x0
            val fy = clampedY - y0
            val top = ImageProcessor.luminance(pixels[y0 * width + x0]) * (1 - fx) +
                ImageProcessor.luminance(pixels[y0 * width + x0 + 1]) * fx
            val bottom = ImageProcessor.luminance(pixels[(y0 + 1) * width + x0]) * (1 - fx) +
                ImageProcessor.luminance(pixels[(y0 + 1) * width + x0 + 1]) * fx
            return top * (1 - fy) + bottom * fy
        }
        val centreX = corners.sumOf { it[0].toDouble() }.toFloat() / 4f
        val centreY = corners.sumOf { it[1].toDouble() }.toFloat() / 4f
        val lines = Array(4) { edge ->
            photometricEdgeLine({ x, y -> value(x, y) }, corners, edge, centreX, centreY, insideLuminance)
        }
        // Shift every fitted edge perpendicularly towards the paper centre. The 50% luminance crossing
        // lands on the antialiased runway pixel, so a crop exactly on it can still resample background.
        val inward = Array(4) { edge ->
            val line = lines[edge]
            var nx = -line[3]
            var ny = line[2]
            if ((centreX - line[0]) * nx + (centreY - line[1]) * ny < 0f) {
                nx = -nx
                ny = -ny
            }
            floatArrayOf(line[0] + nx * EDGE_INSET_PIXELS, line[1] + ny * EDGE_INSET_PIXELS, line[2], line[3])
        }
        val refined = ArrayList<FloatArray>(4)
        for (i in 0..3) {
            val incoming = inward[(i + 3) % 4]
            val outgoing = inward[i]
            val denominator = incoming[2] * outgoing[3] - incoming[3] * outgoing[2]
            if (abs(denominator) < 1e-3f) {
                refined.add(corners[i])
                continue
            }
            val t = ((outgoing[0] - incoming[0]) * outgoing[3] - (outgoing[1] - incoming[1]) * outgoing[2]) / denominator
            val fittedX = incoming[0] + t * incoming[2]
            val fittedY = incoming[1] + t * incoming[3]
            // Never let refinement (or its inset) move a corner implausibly far from the outline.
            val moved = hypot(fittedX - corners[i][0], fittedY - corners[i][1])
            refined.add(if (moved <= MAX_REFINEMENT_PIXELS) floatArrayOf(fittedX, fittedY) else corners[i])
        }
        return refined
    }

    /**
     * Least-squares line (a point and a unit direction) through the subpixel edge crossings of one
     * quad edge; falls back to the mask edge when fewer than [MIN_EDGE_SAMPLES] crossings are found.
     */
    private fun photometricEdgeLine(
        value: (Float, Float) -> Float,
        corners: List<FloatArray>,
        edge: Int,
        centreX: Float,
        centreY: Float,
        insideLuminance: Int,
    ): FloatArray {
        val ax = corners[edge][0]
        val ay = corners[edge][1]
        val bx = corners[(edge + 1) % 4][0]
        val by = corners[(edge + 1) % 4][1]
        val dx = bx - ax
        val dy = by - ay
        val length = hypot(dx, dy)
        val fallback = floatArrayOf(ax, ay, 1f, 0f)
        if (length < 16f) return fallback
        val ux = dx / length
        val uy = dy / length
        // Outward normal: perpendicular to the edge, pointing away from the quad centre.
        var nx = -uy
        var ny = ux
        val midX = (ax + bx) / 2f
        val midY = (ay + by) / 2f
        if ((midX - centreX) * nx + (midY - centreY) * ny < 0f) {
            nx = -nx
            ny = -ny
        }
        val threshold = insideLuminance * .5f
        val crossings = ArrayList<FloatArray>(MIN_EDGE_SAMPLES)
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        var sumYY = 0.0
        for (sample in 1..EDGE_SAMPLES) {
            val t = sample / (EDGE_SAMPLES + 1f)
            val px = ax + dx * t
            val py = ay + dy * t
            // Start inside the paper and walk outward to the paper-to-background crossing.
            var previous = value(px - nx, py - ny)
            var crossing = Float.NaN
            var step = -0.5f
            while (step <= 4f) {
                val current = value(px + nx * step, py + ny * step)
                if (previous > threshold && current <= threshold) {
                    val ratio = (previous - threshold) / (previous - current)
                    crossing = step - .25f + ratio * .25f
                    break
                }
                previous = current
                step += .25f
            }
            if (crossing.isNaN()) continue
            val ex = px + nx * crossing
            val ey = py + ny * crossing
            crossings.add(floatArrayOf(ex, ey))
            sumX += ex
            sumY += ey
            sumXX += ex.toDouble() * ex
            sumXY += ex.toDouble() * ey
            sumYY += ey.toDouble() * ey
        }
        if (crossings.size < MIN_EDGE_SAMPLES) return floatArrayOf(ax, ay, ux, uy)
        val count = crossings.size
        val meanX = (sumX / count).toFloat()
        val meanY = (sumY / count).toFloat()
        val sxx = sumXX - count * meanX.toDouble() * meanX
        val sxy = sumXY - count * meanX.toDouble() * meanY
        val syy = sumYY - count * meanY.toDouble() * meanY
        if (abs(sxx) + abs(sxy) + abs(syy) < 1e-6) return floatArrayOf(ax, ay, ux, uy)
        val theta = .5f * atan2((2.0 * sxy).toFloat(), (sxx - syy).toFloat())
        return floatArrayOf(meanX, meanY, kotlin.math.cos(theta), kotlin.math.sin(theta))
    }

    /** Kept just inside the paper edge so the rendered crop cannot resample background. */
    private const val EDGE_INSET_PIXELS = 1.2f

    /** Bounds the outline buffer so a pathological mask cannot allocate without limit. */
    private const val MAX_BOUNDARY = 200_000

    /** A refined corner further than this from its outline extreme is treated as a bad fit. */
    private const val MAX_REFINEMENT_PIXELS = 6f

    /** Edge crossings sampled per quad edge, and the least needed to trust a photometric fit. */
    private const val EDGE_SAMPLES = 40
    private const val MIN_EDGE_SAMPLES = 16
}
