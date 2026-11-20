package com.freescanner.export

import com.freescanner.model.OcrText
import com.freescanner.model.ScanDocument
import com.freescanner.model.ScanPage
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class TextDocumentWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun docxIsARealPackageWithUnicodeWhitespaceAndPageBreaks() {
        val output = temporary.newFile("unicode.docx")
        val original = "  R&D <scan> café — Bahasa Indonesia 😀\tend\r\nnext\n"
        TextDocumentWriter.write(document(original, "", "Last page"), output, docx = true)
        ZipFile(output).use { zip ->
            assertTrue(zip.getEntry("[Content_Types].xml") != null)
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val relationships = zip.getInputStream(zip.getEntry("_rels/.rels")).use {
                factory.newDocumentBuilder().parse(it)
            }
            val relation = relationships.getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/package/2006/relationships", "Relationship",
            ).item(0) as Element
            assertEquals("word/document.xml", relation.getAttribute("Target"))
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).use {
                factory.newDocumentBuilder().parse(it)
            }
            val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            val texts = xml.getElementsByTagNameNS(namespace, "t")
            assertEquals("  R&D <scan> café — Bahasa Indonesia 😀", texts.item(0).textContent)
            assertEquals("preserve", (texts.item(0) as Element).getAttributeNS("http://www.w3.org/XML/1998/namespace", "space"))
            assertEquals(1, xml.getElementsByTagNameNS(namespace, "tab").length)
            val breaks = xml.getElementsByTagNameNS(namespace, "br")
            var pages = 0
            var lineBreaks = 0
            for (index in 0 until breaks.length) {
                if ((breaks.item(index) as Element).getAttributeNS(namespace, "type") == "page") pages++ else lineBreaks++
            }
            assertEquals(2, pages)
            assertEquals(2, lineBreaks)
            assertEquals("Last page", texts.item(texts.length - 1).textContent)
        }
    }

    @Test fun txtPreservesUnicodeAndBlankPagesWithoutInventingText() {
        val output = temporary.newFile("exact.txt")
        val first = "  café 😀\r\n\tR&D <scan>  "
        TextDocumentWriter.write(document(first, "", "last"), output, docx = false)
        assertEquals(first + "\n\n\u000c\n\n\n\n\u000c\n\nlast", output.readText(Charsets.UTF_8))
    }

    @Test fun unrecognizedOrEntirelyBlankSelectionNeverTruncatesExistingOutput() {
        val output = temporary.newFile("unchanged.txt").apply { writeText("keep") }
        val missing = document("real", "later").let {
            it.copy(pages = it.pages.mapIndexed { index, page -> if (index == 1) page.copy(text = null) else page })
        }
        for (document in listOf(missing, document(" ", "\n"))) {
            val failure = runCatching { TextDocumentWriter.write(document, output, docx = false) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("keep", output.readText())
        }
    }

    @Test fun malformedUnicodeAndXmlControlsAreVisibleErrorsNotCorruptedExports() {
        val output = temporary.root.resolve("invalid.docx")
        for (text in listOf("bad\uD800", "bad\uDC00", "control\u0001", "invalid\uFFFF")) {
            assertTrue(runCatching { TextDocumentWriter.write(document(text), output, docx = true) }.exceptionOrNull() is IllegalArgumentException)
            assertFalse(output.exists())
        }
        TextDocumentWriter.write(document("control\u0001"), output, docx = false)
        assertEquals("control\u0001", output.readText(Charsets.UTF_8))
    }

    private fun document(vararg texts: String) = ScanDocument(
        id = "owned-test", title = "Owned text fixture", createdAt = 0L, updatedAt = 0L,
        pages = texts.mapIndexed { index, text -> ScanPage("page-$index", "unused-$index", text = OcrText(text)) },
    )
}
