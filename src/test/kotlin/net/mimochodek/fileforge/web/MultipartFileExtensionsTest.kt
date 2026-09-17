package net.mimochodek.fileforge.web

import net.mimochodek.fileforge.DefaultFileStorage
import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.naming.DefaultObjectKeyGenerator
import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.validation.DefaultFileValidator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole point of the extension: what took five lines of ceremony at every
 * upload site in 1.x is now one call, and it is safe by construction.
 */
class MultipartFileExtensionsTest {

    private val backend = InMemoryStorageBackend()

    private val storage: FileStorage = DefaultFileStorage(
        backend = backend,
        keyGenerator = DefaultObjectKeyGenerator(NamingStrategy.UUID),
        validator = DefaultFileValidator(MAX_SIZE, listOf("application/pdf", "image/jpeg")),
        maxFileSize = MAX_SIZE,
    )

    @Test
    fun `should store an uploaded file in one call`() {
        val file = MockMultipartFile("document", "Quarterly Report.pdf", "application/pdf", Fixtures.PDF)

        val stored = storage.store(file, folder = "documents")

        assertTrue(stored.key.startsWith("documents/"))
        assertEquals("Quarterly_Report.pdf", stored.fileName)
        assertEquals("application/pdf", stored.contentType)
        assertEquals(Fixtures.PDF.size.toLong(), stored.size)
        assertContentEquals(Fixtures.PDF, backend.objects.getValue(stored.key).bytes)
    }

    @Test
    fun `should pass extra metadata through`() {
        val file = MockMultipartFile("document", "report.pdf", "application/pdf", Fixtures.PDF)

        val stored = storage.store(file, folder = "documents", metadata = mapOf("owner" to "42"))

        assertEquals(mapOf("owner" to "42"), backend.head(stored.key).metadata)
    }

    @Test
    fun `should fall back to the form field name when the browser sends no file name`() {
        val file = MockMultipartFile("upload.pdf", "", "application/pdf", Fixtures.PDF)

        val stored = storage.store(file)

        assertEquals("upload.pdf", stored.fileName)
    }

    @Test
    fun `should detect the content type when the browser declares none`() {
        val file = MockMultipartFile("document", "report.pdf", null, Fixtures.PDF)

        val stored = storage.store(file)

        assertEquals("application/pdf", stored.contentType)
    }

    @Test
    fun `should verify the declared content type against the bytes`() {
        // A browser-declared content type is attacker-controlled, so it is checked
        // rather than believed.
        val file = MockMultipartFile("document", "report.pdf", "application/pdf", Fixtures.JPEG)

        assertThrows<ContentMismatchException> { storage.store(file) }
        assertTrue(backend.objects.isEmpty())
    }

    @Test
    fun `should enforce the size limit on the real content`() {
        val file = MockMultipartFile("document", "big.pdf", "application/pdf", Fixtures.PDF + ByteArray(MAX_SIZE.toInt()))

        assertThrows<FileSizeExceededException> { storage.store(file) }
        assertTrue(backend.objects.isEmpty())
    }

    private companion object {
        const val MAX_SIZE = 4_096L
    }
}
