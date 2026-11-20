package com.freescanner.export

import com.freescanner.model.ScanDocument
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes selected snapshots; the export owner handles staging, rename and retention. */
object TextDocumentWriter {
    fun write(document: ScanDocument, output: File, docx: Boolean) {
        require(document.pages.isNotEmpty()) { "Pilih setidaknya satu halaman untuk diekspor." }
        document.pages.forEachIndexed { index, page ->
            val text = requireNotNull(page.text) {
                "Halaman ${index + 1} belum memiliki teks. Kenali teks atau edit setiap halaman yang dipilih sebelum mengekspor."
            }
            requireValidUnicode(text.text, docx)
        }
        require(document.pages.any { !it.text?.text.isNullOrBlank() }) {
            "Tidak ada teks untuk diekspor. Kenali atau masukkan teks terlebih dahulu."
        }
        FileOutputStream(output).use { file ->
            if (docx) {
                writeDocx(document, file)
            } else {
                OutputStreamWriter(file, Charsets.UTF_8).buffered().use { writer ->
                    document.pages.forEachIndexed { index, page ->
                        if (index > 0) writer.write("\n\n\u000c\n\n")
                        writer.write(requireNotNull(page.text).text)
                    }
                    writer.flush()
                    file.fd.sync()
                }
            }
        }
    }

    private fun writeDocx(document: ScanDocument, file: FileOutputStream) {
        ZipOutputStream(file).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "</Types>").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            // Do not close this writer separately: it shares the ZIP stream across entries.
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            writer.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
            document.pages.forEachIndexed { index, page ->
                if (index > 0) writer.write("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                writer.write("<w:p><w:r><w:t xml:space=\"preserve\">")
                writeWordText(writer, requireNotNull(page.text).text)
                writer.write("</w:t></w:r></w:p>")
            }
            writer.write("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>")
            writer.write("</w:body></w:document>")
            writer.flush()
            zip.closeEntry()
            zip.finish()
            zip.flush()
            file.fd.sync()
        }
    }

    private fun writeWordText(writer: Writer, text: String) {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when (character) {
                '&' -> writer.write("&amp;")
                '<' -> writer.write("&lt;")
                '>' -> writer.write("&gt;")
                '\t' -> writer.write("</w:t><w:tab/><w:t xml:space=\"preserve\">")
                '\r', '\n' -> {
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    writer.write("</w:t><w:br/><w:t xml:space=\"preserve\">")
                }
                else -> writer.write(character.code)
            }
            index++
        }
    }

    /** Reject malformed input before opening/truncating output instead of corrupting Unicode. */
    private fun requireValidUnicode(text: String, xml: Boolean) {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character.isHighSurrogate()) {
                require(index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    "Teks berisi karakter Unicode yang tidak lengkap. Perbaiki sebelum mengekspor."
                }
                index += 2
                continue
            }
            require(!character.isLowSurrogate()) {
                "Teks berisi karakter Unicode yang tidak lengkap. Perbaiki sebelum mengekspor."
            }
            require(!xml || character == '\t' || character == '\n' || character == '\r' ||
                character.code in 0x20..0xD7FF || character.code in 0xE000..0xFFFD) {
                "Teks berisi karakter kontrol yang tidak dapat ditampilkan oleh Word. Hapus karakter tersebut atau ekspor TXT."
            }
            index++
        }
    }
}
