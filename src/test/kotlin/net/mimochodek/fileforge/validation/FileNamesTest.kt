package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.InvalidFileNameException
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
