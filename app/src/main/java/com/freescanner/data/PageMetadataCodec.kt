package com.freescanner.data

import com.freescanner.model.Corner
import com.freescanner.model.OcrLine
import com.freescanner.model.OcrText
import com.freescanner.model.PageAnnotation
import com.freescanner.model.isValid
import org.json.JSONArray
import org.json.JSONObject

/** Versioned by the Room schema; unknown recipes fail instead of silently losing edits. */
internal object PageMetadataCodec {
    fun encodeAnnotations(annotations: List<PageAnnotation>): String {
        if (annotations.isEmpty()) return "[]"
        val values = JSONArray()
        for (annotation in annotations) {
            require(annotation.isValid()) { "Anotasi halaman tidak valid." }
            val value = JSONObject()
            when (annotation) {
                is PageAnnotation.Ink -> {
                    val points = JSONArray()
                    for (point in annotation.points) { points.put(point.x.toDouble()); points.put(point.y.toDouble()) }
                    value.put("type", "ink").put("points", points).put("color", annotation.color)
                        .put("width", annotation.width.toDouble()).put("highlight", annotation.highlight)
                }
                is PageAnnotation.Label -> value.put("type", "label").put("text", annotation.text)
                    .put("x", annotation.x.toDouble()).put("y", annotation.y.toDouble())
                    .put("size", annotation.size.toDouble()).put("color", annotation.color)
                    .put("rotation", annotation.rotation.toDouble()).put("opacity", annotation.opacity.toDouble())
                    .put("tiled", annotation.tiled).put("spacing", annotation.spacing.toDouble())
            }
            values.put(value)
        }
        return values.toString()
    }

    fun decodeAnnotations(json: String): List<PageAnnotation> {
        require(json.length <= MAX_JSON_CHARACTERS) { "Anotasi halaman tersimpan terlalu besar." }
        if (json == "[]") return emptyList()
        val values = JSONArray(json)
        require(values.length() <= 1_000) { "Anotasi halaman tersimpan terlalu banyak." }
        return List(values.length()) { index ->
            val value = values.getJSONObject(index)
            val annotation = when (value.getString("type")) {
                "ink" -> {
                    val points = value.getJSONArray("points")
                    require(points.length() in 2..200_000 && points.length() % 2 == 0) { "Goresan tersimpan tidak valid." }
                    PageAnnotation.Ink(
                        List(points.length() / 2) { i -> Corner(points.getDouble(i * 2).toFloat(), points.getDouble(i * 2 + 1).toFloat()) },
                        value.getInt("color"), value.getDouble("width").toFloat(), value.getBoolean("highlight"),
                    )
                }
                "label" -> PageAnnotation.Label(
                    value.getString("text"), value.getDouble("x").toFloat(), value.getDouble("y").toFloat(),
                    value.getDouble("size").toFloat(), value.getInt("color"), value.getDouble("rotation").toFloat(),
                    value.getDouble("opacity").toFloat(), value.getBoolean("tiled"), value.getDouble("spacing").toFloat(),
                )
                else -> throw IllegalArgumentException("Halaman ini menggunakan jenis anotasi yang tidak didukung.")
            }
            require(annotation.isValid()) { "Anotasi halaman tersimpan tidak valid." }
            annotation
        }
    }

    fun encodeText(text: OcrText?): String? {
        if (text == null) return null
        require(text.isValid()) { "Teks yang dikenali terlalu besar atau memiliki batas yang tidak valid." }
        val lines = JSONArray()
        for (line in text.lines) lines.put(JSONObject().put("text", line.text)
            .put("left", line.left.toDouble()).put("top", line.top.toDouble())
            .put("right", line.right.toDouble()).put("bottom", line.bottom.toDouble()))
        return JSONObject().put("text", text.text).put("lines", lines).toString()
    }

    fun decodeText(json: String?): OcrText? {
        if (json == null) return null
        require(json.length <= MAX_JSON_CHARACTERS) { "Teks hasil pengenalan tersimpan terlalu besar." }
        val value = JSONObject(json)
        val lines = value.getJSONArray("lines")
        require(lines.length() <= 10_000) { "Baris teks tersimpan terlalu banyak." }
        val text = OcrText(value.getString("text"), List(lines.length()) { index ->
            val line = lines.getJSONObject(index)
            OcrLine(line.getString("text"), line.getDouble("left").toFloat(), line.getDouble("top").toFloat(),
                line.getDouble("right").toFloat(), line.getDouble("bottom").toFloat())
        })
        require(text.isValid()) { "Teks hasil pengenalan tersimpan tidak valid." }
        return text
    }

    private const val MAX_JSON_CHARACTERS = 8 * 1024 * 1024
}
