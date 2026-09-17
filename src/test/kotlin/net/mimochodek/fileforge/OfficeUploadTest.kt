package net.mimochodek.fileforge

import net.mimochodek.fileforge.naming.DefaultObjectKeyGenerator
import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.validation.DefaultFileValidator
import net.mimochodek.fileforge.web.store
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import kotlin.test.assertEquals

/**
 * The exact upload mix a consuming project stores: PDF, photos and Office files.
 *
 * Exercised through the MultipartFile entry point, because that is how the files
 * actually arrive, and with the content type both declared and omitted - a
 * browser sends it, an API client often does not.
 */
class OfficeUploadTest {

    private val backend = InMemoryStorageBackend()

    private val storage: FileStorage = DefaultFileStorage(
        backend = backend,
        keyGenerator = DefaultObjectKeyGenerator(NamingStrategy.DATE_UUID),
        validator = DefaultFileValidator(
            maxFileSize = 20 * 1024 * 1024,
            allowedMimeTypes = listOf(
                "image/jpeg", "image/png", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ),
        ),
        maxFileSize = 20 * 1024 * 1024,
    )

    @Test
    fun `should store the whole documented upload mix when the client declares the type`() {
        val cases = listOf(
            Triple("photo.jpg", "image/jpeg", Fixtures.JPEG),
            Triple("logo.png", "image/png", Fixtures.PNG),
            Triple("invoice.pdf", "application/pdf", Fixtures.PDF),
            Triple("report.docx", DOCX, Fixtures.ZIP),
            Triple("budget.xlsx", XLSX, Fixtures.ZIP),
        )

        cases.forEach { (name, type, bytes) ->
            val stored = storage.store(MockMultipartFile("f", name, type, bytes), folder = "attachments")
            assertEquals(type, stored.contentType, "declared type must be kept for $name")
        }
    }

    @Test
    fun `should store the same mix when the client declares no type at all`() {
        val cases = listOf(
            "photo.jpg" to "image/jpeg",
            "invoice.pdf" to "application/pdf",
            "report.docx" to DOCX,
            "budget.xlsx" to XLSX,
        )
        val bytes = mapOf(
            "photo.jpg" to Fixtures.JPEG,
            "invoice.pdf" to Fixtures.PDF,
            "report.docx" to Fixtures.ZIP,
            "budget.xlsx" to Fixtures.ZIP,
        )

        cases.forEach { (name, expected) ->
            val stored = storage.store(MockMultipartFile("f", name, null, bytes.getValue(name)))
            assertEquals(expected, stored.contentType, "detection must identify $name")
        }
    }

    private companion object {
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
