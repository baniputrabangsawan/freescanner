package com.freescanner.pdf

import kotlin.math.floor
import kotlin.math.sqrt

internal data class LongImageLayout(val width: Int, val height: Int, val heights: List<Int>) {
    companion object {
        const val MAX_SIDE = 16_000
        const val MAX_PIXELS = 16_000_000L

        fun calculate(dimensions: List<Pair<Int, Int>>, requestedWidth: Int): LongImageLayout {
            require(dimensions.isNotEmpty() && dimensions.all { it.first > 0 && it.second > 0 }) { "Pilih halaman yang valid untuk gambar panjang." }
            val ratios = dimensions.map { it.second.toDouble() / it.first }
            val totalRatio = ratios.sum()
            val width = floor(minOf(requestedWidth.toDouble(), dimensions.maxOf { it.first }.toDouble(),
                MAX_SIDE.toDouble() / totalRatio, sqrt(MAX_PIXELS / totalRatio))).toInt()
            require(width >= 96) { "Halaman terlalu banyak atau terlalu tinggi untuk gambar panjang yang terbaca. Pilih lebih sedikit halaman atau ekspor JPEG terpisah." }
            val heights = ratios.map { floor(width * it).toInt().coerceAtLeast(1) }
            val height = heights.sumOf { it.toLong() }
            require(height <= MAX_SIDE && width.toLong() * height <= MAX_PIXELS) {
                "Gambar panjang ini terlalu besar. Pilih lebih sedikit halaman atau ekspor JPEG terpisah."
            }
            return LongImageLayout(width, height.toInt(), heights)
        }
    }
}
