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
    fun `should normalize parameters and case`() {
        assertEquals("application/pdf", ContentTypeDetector.normalize("  APPLICATION/PDF; charset=UTF-8 "))
    }
}
