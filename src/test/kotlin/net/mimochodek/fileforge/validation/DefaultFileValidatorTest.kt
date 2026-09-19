package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.spi.FileCandidate
import net.mimochodek.fileforge.testsupport.Fixtures
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DefaultFileValidatorTest {

    private val validator = DefaultFileValidator(
        maxFileSize = 1_000,
        allowedMimeTypes = listOf("image/jpeg", "image/PNG", "application/pdf", "image/webp"),
    )

    @Nested
    inner class SizeLimit {

        @Test
        fun `should accept a file at the limit`() {
            assertDoesNotThrow { validator.validateSize(1_000) }
        }

        @Test
        fun `should reject a file over the limit`() {
            assertThrows<FileSizeExceededException> { validator.validateSize(1_001) }
        }
    }

    @Nested
    inner class MimeWhitelist {

        @Test
        fun `should accept a whitelisted type`() {
            assertDoesNotThrow { validator.validateMimeType("image/jpeg") }
        }

        @Test
        fun `should accept a type carrying parameters`() {
            assertDoesNotThrow { validator.validateMimeType("application/pdf; charset=UTF-8") }
        }

        @Test
        fun `should match the whitelist case-insensitively in both directions`() {
            assertDoesNotThrow { validator.validateMimeType("IMAGE/png") }
        }

        @Test
        fun `should reject a type outside the whitelist`() {
            assertThrows<UnsupportedFileTypeException> { validator.validateMimeType("application/zip") }
        }

        @Test
        fun `should expose the normalized whitelist`() {
            assertEquals(
                setOf("image/jpeg", "image/png", "application/pdf", "image/webp"),
                validator.allowedMimeTypes,
            )
        }
    }

    @Nested
    inner class MagicByteVerification {

        @Test
        fun `should accept content matching its declared type`() {
            assertDoesNotThrow { validator.validateMagicBytes("image/jpeg", Fixtures.JPEG) }
            assertDoesNotThrow { validator.validateMagicBytes("image/png", Fixtures.PNG) }
            assertDoesNotThrow { validator.validateMagicBytes("application/pdf", Fixtures.PDF) }
            assertDoesNotThrow { validator.validateMagicBytes("image/webp", Fixtures.WEBP) }
        }

        @Test
        fun `should reject png content declared as jpeg`() {
            assertThrows<ContentMismatchException> { validator.validateMagicBytes("image/jpeg", Fixtures.PNG) }
        }

        @Test
        fun `should reject a wav file declared as webp`() {
            assertThrows<ContentMismatchException> { validator.validateMagicBytes("image/webp", Fixtures.WAV) }
        }

        @Test
        fun `should reject webp truncated before the WEBP marker`() {
            assertThrows<ContentMismatchException> {
                validator.validateMagicBytes("image/webp", "RIFF".toByteArray())
            }
        }

        @Test
        fun `should reject truncated content`() {
            assertThrows<ContentMismatchException> {
                validator.validateMagicBytes("image/png", byteArrayOf(0x89.toByte()))
            }
        }

        @Test
        fun `should skip verification when magic bytes are disabled`() {
            val lenient = DefaultFileValidator(1_000, listOf("image/jpeg"), verifyMagicBytes = false)

            assertDoesNotThrow { lenient.validate(candidate("image/jpeg", Fixtures.PNG)) }
        }
    }

    @Nested
    inner class OfficeDocuments {

        private val office = DefaultFileValidator(
            maxFileSize = 20 * 1024 * 1024,
            allowedMimeTypes = listOf(DOCX, XLSX, "application/pdf"),
        )

        @Test
        fun `should accept a docx that really is a zip container`() {
            assertDoesNotThrow { office.validate(candidate(DOCX, Fixtures.ZIP)) }
            assertDoesNotThrow { office.validate(candidate(XLSX, Fixtures.ZIP)) }
        }

        @Test
        fun `should reject content declared as docx that is not even a zip`() {
            // Before the OOXML signatures existed, these types had no signature at
            // all, so verification was skipped and an executable declared as .docx
            // went straight into the bucket unchecked.
            assertThrows<ContentMismatchException> { office.validate(candidate(DOCX, Fixtures.PDF)) }
            assertThrows<ContentMismatchException> { office.validate(candidate(XLSX, Fixtures.JPEG)) }
        }

        @Test
        fun `should reject an office type that is not whitelisted`() {
            assertThrows<UnsupportedFileTypeException> { office.validate(candidate(PPTX, Fixtures.ZIP)) }
        }
    }

    @Nested
    inner class UnknownTypes {

        @Test
        fun `should allow an unverifiable type by default`() {
            val permissive = DefaultFileValidator(1_000, listOf("text/plain"))

            assertDoesNotThrow { permissive.validate(candidate("text/plain", byteArrayOf(1, 2, 3))) }
        }

        @Test
        fun `should reject an unverifiable type when the policy says so`() {
            val strict = DefaultFileValidator(
                maxFileSize = 1_000,
                allowedMimeTypes = listOf("text/plain"),
                unknownTypePolicy = UnknownTypePolicy.REJECT,
            )

            assertThrows<ContentMismatchException> { strict.validate(candidate("text/plain", byteArrayOf(1, 2, 3))) }
        }
    }

    @Nested
    inner class FullValidation {

        @Test
        fun `should pass a valid file`() {
            assertDoesNotThrow { validator.validate(candidate("image/jpeg", Fixtures.JPEG, size = 500)) }
        }

        @Test
        fun `should check size before type`() {
            assertThrows<FileSizeExceededException> {
                validator.validate(candidate("application/zip", Fixtures.JPEG, size = 2_000))
            }
        }
    }

    private fun candidate(contentType: String, head: ByteArray, size: Long = 100) =
        FileCandidate(contentType, size, head)

    private companion object {
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
