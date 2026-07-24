package net.mimochodek.fileforge

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class FileValidationServiceTest {

    private val properties = FileStorageProperties(
        endpoint = "http://localhost:7070",
        region = "us-east-1",
        bucket = "test-bucket",
        accessKey = "access",
        secretKey = "secret",
        maxFileSizeBytes = 1_000,
        allowedMimeTypes = listOf("image/jpeg", "image/png", "application/pdf"),
    )

    private val service = FileValidationService(properties)

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val pdfBytes = "%PDF-1.7".toByteArray()

    @Nested
    inner class SizeLimit {

        @Test
        fun `should accept file within size limit`() {
            assertDoesNotThrow { service.validateSize(1_000) }
        }

        @Test
        fun `should reject file exceeding size limit`() {
            assertThrows<FileSizeExceededException> { service.validateSize(1_001) }
        }
    }

    @Nested
    inner class MimeWhitelist {

        @Test
        fun `should accept whitelisted mime type`() {
            assertDoesNotThrow { service.validateMimeType("image/jpeg") }
        }

        @Test
        fun `should accept mime type with charset parameter`() {
            assertDoesNotThrow { service.validateMimeType("application/pdf; charset=UTF-8") }
        }

        @Test
        fun `should reject mime type outside whitelist`() {
            assertThrows<UnsupportedFileTypeException> { service.validateMimeType("application/zip") }
        }
    }

    @Nested
    inner class MagicBytes {

        @Test
        fun `should accept jpeg content declared as jpeg`() {
            assertDoesNotThrow { service.validateMagicBytes("image/jpeg", jpegBytes) }
        }

        @Test
        fun `should accept png content declared as png`() {
            assertDoesNotThrow { service.validateMagicBytes("image/png", pngBytes) }
        }

        @Test
        fun `should accept pdf content declared as pdf`() {
            assertDoesNotThrow { service.validateMagicBytes("application/pdf", pdfBytes) }
        }

        @Test
        fun `should reject png content declared as jpeg`() {
            assertThrows<ContentMismatchException> { service.validateMagicBytes("image/jpeg", pngBytes) }
        }

        @Test
        fun `should reject truncated content`() {
            assertThrows<ContentMismatchException> { service.validateMagicBytes("image/png", byteArrayOf(0x89.toByte())) }
        }
    }

    @Nested
    inner class FullValidation {

        @Test
        fun `should pass valid file`() {
            assertDoesNotThrow { service.validate(500, "image/jpeg", jpegBytes) }
        }

        @Test
        fun `should fail on size before mime check`() {
            assertThrows<FileSizeExceededException> { service.validate(2_000, "image/jpeg", jpegBytes) }
        }
    }

    @Nested
    inner class NameSanitization {

        @Test
        fun `should keep safe file name unchanged`() {
            assertEquals("report_2024.pdf", service.sanitizeFileName("report_2024.pdf"))
        }

        @Test
        fun `should strip path traversal components`() {
            assertEquals("passwd", service.sanitizeFileName("../../etc/passwd"))
        }

        @Test
        fun `should strip windows path components`() {
            assertEquals("photo.jpg", service.sanitizeFileName("""C:\Users\me\photo.jpg"""))
        }

        @Test
        fun `should replace whitespace with underscores`() {
            assertEquals("my_holiday_photo.png", service.sanitizeFileName("my holiday photo.png"))
        }

        @Test
        fun `should remove unsafe characters`() {
            assertEquals("filename.txt", service.sanitizeFileName("file<>:name?.txt"))
        }

        @Test
        fun `should reject name that sanitizes to nothing`() {
            assertThrows<InvalidFileNameException> { service.sanitizeFileName("///") }
        }

        @Test
        fun `should reject dot-only name`() {
            assertThrows<InvalidFileNameException> { service.sanitizeFileName("..") }
        }
    }
}
