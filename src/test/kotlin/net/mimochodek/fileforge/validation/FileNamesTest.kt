package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.exception.InvalidFileNameException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileNamesTest {

    @Test
    fun `should keep a safe name unchanged`() {
        assertEquals("report_2024.pdf", FileNames.sanitize("report_2024.pdf"))
    }

    @Test
    fun `should strip path traversal components`() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
    }

    @Test
    fun `should strip windows path components`() {
        assertEquals("photo.jpg", FileNames.sanitize("""C:\Users\me\photo.jpg"""))
    }

    @Test
    fun `should replace whitespace with underscores`() {
        assertEquals("my_holiday_photo.png", FileNames.sanitize("my holiday photo.png"))
    }

    @Test
    fun `should remove unsafe characters`() {
        assertEquals("filename.txt", FileNames.sanitize("file<>:name?.txt"))
    }

    @Test
    fun `should keep diacritics instead of mangling them`() {
        // The name never reaches the object key (that is generated) and both
        // places it does reach are encoded for transport, so stripping it down to
        // ASCII destroyed information for no safety gain. "Ctvrtletni zprava.pdf"
        // used to come out as "tvrtletn_zprva.pdf".
        assertEquals("Čtvrtletní_zpráva.pdf", FileNames.sanitize("Čtvrtletní zpráva.pdf"))
        assertEquals("účetnictví_2026.xlsx", FileNames.sanitize("účetnictví_2026.xlsx"))
        assertEquals("Pöschl_Söhne.pdf", FileNames.sanitize("Pöschl & Söhne.pdf"))
        assertEquals("発注書.pdf", FileNames.sanitize("発注書.pdf"))
    }

    @Test
    fun `should still drop punctuation that is not a letter, digit, dot, dash or underscore`() {
        assertEquals("Smlouva_o_dílo_příloha_č.3.docx", FileNames.sanitize("Smlouva o dílo — příloha č.3.docx"))
    }

    @Test
    fun `should cap the length`() {
        // The name travels in an HTTP header as S3 object metadata.
        val long = "a".repeat(400) + ".pdf"

        assertEquals(255, FileNames.sanitize(long).length)
    }

    @Test
    fun `should reject a name that sanitizes to nothing`() {
        assertThrows<InvalidFileNameException> { FileNames.sanitize("///") }
    }

    @Test
    fun `should reject a dot-only name`() {
        assertThrows<InvalidFileNameException> { FileNames.sanitize("..") }
    }

    @Test
    fun `should lowercase the extension`() {
        assertEquals("pdf", FileNames.extensionOf("Report.PDF"))
    }

    @Test
    fun `should report no extension`() {
        assertNull(FileNames.extensionOf("README"))
    }
}
