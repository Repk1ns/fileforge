package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.testsupport.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ContentTypeDetectorTest {

    @Test
    fun `should detect from magic bytes`() {
        assertEquals("image/jpeg", ContentTypeDetector.detect(Fixtures.JPEG, "whatever.bin"))
        assertEquals("image/png", ContentTypeDetector.detect(Fixtures.PNG, "whatever.bin"))
        assertEquals("application/pdf", ContentTypeDetector.detect(Fixtures.PDF, "whatever.bin"))
        assertEquals("image/webp", ContentTypeDetector.detect(Fixtures.WEBP, "whatever.bin"))
    }

    @Test
    fun `should prefer magic bytes over a lying extension`() {
        assertEquals("application/pdf", ContentTypeDetector.detect(Fixtures.PDF, "invoice.png"))
    }

    @Test
    fun `should fall back to the extension when the content is unrecognized`() {
        assertEquals("text/csv", ContentTypeDetector.detect(byteArrayOf(1, 2, 3), "export.CSV"))
    }

    @Test
    fun `should fall back to octet-stream when nothing identifies the content`() {
        assertEquals(ContentTypeDetector.FALLBACK, ContentTypeDetector.detect(byteArrayOf(1, 2, 3), "blob"))
    }

    @Test
    fun `should refine a zip container to the Office type the extension names`() {
        // .docx and .xlsx ARE zip archives, so the magic bytes can never say more
        // than "zip". Without refinement every Office upload that arrived without a
        // declared content type was detected as application/zip and then rejected.
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ContentTypeDetector.detect(Fixtures.ZIP, "report.docx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ContentTypeDetector.detect(Fixtures.ZIP, "budget.XLSX"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ContentTypeDetector.detect(Fixtures.ZIP, "deck.pptx"),
        )
    }

    @Test
    fun `should refine an OLE2 container to the legacy Office type`() {
        assertEquals("application/msword", ContentTypeDetector.detect(Fixtures.OLE2, "old.doc"))
        assertEquals("application/vnd.ms-excel", ContentTypeDetector.detect(Fixtures.OLE2, "old.xls"))
    }

    @Test
    fun `should leave a plain zip as a zip`() {
        assertEquals("application/zip", ContentTypeDetector.detect(Fixtures.ZIP, "archive.zip"))
        assertEquals("application/zip", ContentTypeDetector.detect(Fixtures.ZIP, "no-extension"))
    }

    @Test
    fun `should not let refinement become a way past the magic bytes`() {
        // Refinement is only for a container that really does carry the extension's
        // type. A PDF renamed to .docx is still a PDF, so it must stay detected as one.
        assertEquals("application/pdf", ContentTypeDetector.detect(Fixtures.PDF, "invoice.docx"))
        assertEquals("image/jpeg", ContentTypeDetector.detect(Fixtures.JPEG, "sheet.xlsx"))
    }

    @Test
    fun `should normalize parameters and case`() {
        assertEquals("application/pdf", ContentTypeDetector.normalize("  APPLICATION/PDF; charset=UTF-8 "))
    }
}
