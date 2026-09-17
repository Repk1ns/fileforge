package net.mimochodek.fileforge.web

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.FileStorageException
import net.mimochodek.fileforge.exception.InvalidFileNameException
import net.mimochodek.fileforge.exception.StorageUnavailableException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileForgeProblemDetailsHandlerTest {

    private val handler = FileForgeProblemDetailsHandler()

    @Test
    fun `should map a missing object to 404 and name the key`() {
        val problem = handler.handle(FileNotFoundStorageException("docs/missing.pdf"))

        assertEquals(404, problem.status)
        assertEquals("docs/missing.pdf", problem.properties?.get("key"))
        assertTrue(problem.detail!!.contains("docs/missing.pdf"))
    }

    @Test
    fun `should map an oversized file to 413 with both sizes`() {
        val problem = handler.handle(FileSizeExceededException(actualSize = 9_437_184, maxSize = 5_242_880))

        assertEquals(413, problem.status)
        assertEquals(5_242_880L, problem.properties?.get("maxSize"))
        assertEquals(9_437_184L, problem.properties?.get("actualSize"))
    }

    @Test
    fun `should omit the actual size when the upload was aborted before it was known`() {
        val problem = handler.handle(FileSizeExceededException(actualSize = null, maxSize = 5_242_880))

        assertEquals(413, problem.status)
        assertEquals(5_242_880L, problem.properties?.get("maxSize"))
        assertNull(problem.properties?.get("actualSize"))
    }

    @Test
    fun `should map a disallowed type to 415 and list what is allowed`() {
        val problem = handler.handle(
            UnsupportedFileTypeException("application/zip", setOf("application/pdf", "image/png")),
        )

        assertEquals(415, problem.status)
        assertEquals("application/zip", problem.properties?.get("contentType"))
        assertEquals(listOf("application/pdf", "image/png"), problem.properties?.get("allowedTypes"))
    }

    @Test
    fun `should map a content mismatch to 415 and report what the bytes look like`() {
        val problem = handler.handle(ContentMismatchException("application/pdf", "image/jpeg"))

        assertEquals(415, problem.status)
        assertEquals("application/pdf", problem.properties?.get("declaredContentType"))
        assertEquals("image/jpeg", problem.properties?.get("detectedContentType"))
    }

    @Test
    fun `should map an invalid file name to 400`() {
        val problem = handler.handle(InvalidFileNameException(".."))

        assertEquals(400, problem.status)
        assertEquals("..", problem.properties?.get("fileName"))
    }

    @Test
    fun `should map an unreachable backend to 503 without leaking internals`() {
        val problem = handler.handle(
            StorageUnavailableException("Storage is unreachable while trying to upload object 'k': nas:7070 refused"),
        )

        assertEquals(503, problem.status)
        assertEquals("File storage is temporarily unavailable", problem.detail)
        assertTrue(problem.detail!!.none { it == ':' }, "internal host details must not reach the client")
    }

    @Test
    fun `should map anything unclassified to 500 without leaking internals`() {
        val problem = handler.handle(FileStorageException("Failed to upload object 'k' to bucket internal-prod"))

        assertEquals(500, problem.status)
        assertEquals("The file storage operation failed", problem.detail)
    }
}
