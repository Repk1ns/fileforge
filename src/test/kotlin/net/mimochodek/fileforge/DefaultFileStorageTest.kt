package net.mimochodek.fileforge

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.InvalidFileNameException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.naming.DefaultObjectKeyGenerator
import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.spi.FileValidator
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.validation.DefaultFileValidator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultFileStorageTest {

    private val backend = InMemoryStorageBackend()
    private val clock = Clock.fixed(Instant.parse("2026-09-17T10:15:30Z"), ZoneOffset.UTC)

    private fun storage(
        validator: FileValidator = DefaultFileValidator(MAX_SIZE, listOf("image/jpeg", "application/pdf")),
        maxFileSize: Long = MAX_SIZE,
        inMemoryThreshold: Long = 1024,
    ) = DefaultFileStorage(
        backend = backend,
        keyGenerator = DefaultObjectKeyGenerator(NamingStrategy.UUID, clock = clock),
        validator = validator,
        maxFileSize = maxFileSize,
        inMemoryThreshold = inMemoryThreshold,
        clock = clock,
    )

    @Nested
    inner class Storing {

        @Test
        fun `should return everything the consumer needs to persist`() {
            val stored = storage().store(FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), folder = "docs"))

            assertTrue(stored.key.startsWith("docs/"))
            assertTrue(stored.key.endsWith(".pdf"))
            assertEquals("report.pdf", stored.fileName)
            assertEquals("application/pdf", stored.contentType)
            assertEquals(Fixtures.PDF.size.toLong(), stored.size)
            assertEquals("etag-${Fixtures.PDF.size}", stored.checksum)
            assertEquals(clock.instant(), stored.storedAt)
        }

        @Test
        fun `should preserve the original file name as backend metadata`() {
            val stored = storage().store(FileUpload("Quarterly Report.pdf", ContentSource.Bytes(Fixtures.PDF)))

            assertEquals("Quarterly_Report.pdf", stored.fileName)
            assertEquals("Quarterly_Report.pdf", backend.head(stored.key).fileName)
        }

        @Test
        fun `should sanitize a traversing file name before it reaches the backend`() {
            val stored = storage().store(FileUpload("../../etc/passwd.pdf", ContentSource.Bytes(Fixtures.PDF)))

            assertEquals("passwd.pdf", stored.fileName)
            assertFalse(stored.key.contains(".."))
        }

        @Test
        fun `should pass caller metadata down without the reserved entries`() {
            val stored = storage().store(
                FileUpload(
                    fileName = "report.pdf",
                    content = ContentSource.Bytes(Fixtures.PDF),
                    metadata = mapOf("owner" to "42"),
                ),
            )

            assertEquals(mapOf("owner" to "42"), backend.head(stored.key).metadata)
        }

        @Test
        fun `should detect the content type when the caller declares none`() {
            val stored = storage().store(FileUpload("mystery.bin", ContentSource.Bytes(Fixtures.PDF)))

            assertEquals("application/pdf", stored.contentType)
        }

        @Test
        fun `should accept a byte array through the convenience overload`() {
            val stored = storage().store("photo.jpg", Fixtures.JPEG, folder = "images")

            assertEquals("image/jpeg", stored.contentType)
            assertTrue(stored.key.startsWith("images/"))
        }
    }

    @Nested
    inner class ValidationIsUnconditional {

        @Test
        fun `should reject a type outside the whitelist`() {
            assertThrows<UnsupportedFileTypeException> {
                storage().store(FileUpload("archive.zip", ContentSource.Bytes("PKdata".toByteArray())))
            }
            assertTrue(backend.objects.isEmpty(), "nothing may be written when validation fails")
        }

        @Test
        fun `should reject content that does not match its declared type`() {
            assertThrows<ContentMismatchException> {
                storage().store(FileUpload("fake.pdf", ContentSource.Bytes(Fixtures.JPEG), "application/pdf"))
            }
            assertTrue(backend.objects.isEmpty())
        }

        @Test
        fun `should reject an unusable file name`() {
            assertThrows<InvalidFileNameException> {
                storage().store(FileUpload("..", ContentSource.Bytes(Fixtures.PDF)))
            }
        }
    }

    @Nested
    inner class SizeIsMeasuredNotTrusted {

        @Test
        fun `should reject oversized bytes`() {
            assertThrows<FileSizeExceededException> {
                storage(maxFileSize = 4).store(FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF)))
            }
            assertTrue(backend.objects.isEmpty())
        }

        @Test
        fun `should ignore a stream length that understates the content`() {
            // The 1.x hole: contentLength went straight to the SDK, so a caller could
            // declare 5 bytes for a much larger upload and bypass the limit entirely.
            val payload = Fixtures.PDF + ByteArray(2_000)

            assertThrows<FileSizeExceededException> {
                storage(maxFileSize = 500).store(
                    FileUpload("report.pdf", ContentSource.Stream(ByteArrayInputStream(payload), length = 5)),
                )
            }
            assertTrue(backend.objects.isEmpty())
        }

        @Test
        fun `should record the measured size, not the declared one`() {
            val payload = Fixtures.PDF

            val stored = storage().store(
                FileUpload("report.pdf", ContentSource.Stream(ByteArrayInputStream(payload), length = 5)),
            )

            assertEquals(payload.size.toLong(), stored.size)
            assertEquals(payload.size.toLong(), backend.puts.single().contentLength)
        }

        @Test
        fun `should store a stream of unknown length`() {
            val stored = storage().store(
                FileUpload("report.pdf", ContentSource.Stream(ByteArrayInputStream(Fixtures.PDF), length = null)),
            )

            assertEquals(Fixtures.PDF.size.toLong(), stored.size)
            assertContentEquals(Fixtures.PDF, backend.objects.getValue(stored.key).bytes)
        }

        @Test
        fun `should store a stream larger than the in-memory threshold by spilling to disk`() {
            val payload = Fixtures.PDF + ByteArray(8_000) { (it % 251).toByte() }

            val stored = storage(inMemoryThreshold = 64).store(
                FileUpload("report.pdf", ContentSource.Stream(ByteArrayInputStream(payload))),
            )

            assertEquals(payload.size.toLong(), stored.size)
            assertContentEquals(payload, backend.objects.getValue(stored.key).bytes)
        }

        @Test
        fun `should consume a single-pass stream exactly once`() {
            val source = SinglePassStream(Fixtures.PDF)

            storage().store(FileUpload("report.pdf", ContentSource.Stream(source)))

            assertEquals(1, source.reads.coerceAtMost(1), "the content must not be read twice")
            assertTrue(source.closed, "the caller's stream must be closed")
        }

        @Test
        fun `should store a local file`(@TempDir dir: Path) {
            val file = dir.resolve("report.pdf").also { Files.write(it, Fixtures.PDF) }

            val stored = storage().store(FileUpload("report.pdf", ContentSource.LocalFile(file)))

            assertEquals(Fixtures.PDF.size.toLong(), stored.size)
            assertContentEquals(Fixtures.PDF, backend.objects.getValue(stored.key).bytes)
        }
    }

    @Nested
    inner class Reading {

        @Test
        fun `should read bytes back`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            assertContentEquals(Fixtures.PDF, storage.readBytes(key))
        }

        @Test
        fun `should expose metadata alongside the stream`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            val contentType = storage.read(key) { it.metadata.contentType }

            assertEquals("application/pdf", contentType)
        }

        @Test
        fun `should close the stream after the scoped read`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            val captured = storage.read(key) { it.stream }

            assertThrows<IOException> { captured.read() }
                .let { assertEquals("stream is closed", it.message) }
        }

        @Test
        fun `should report metadata without transferring content`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            val metadata = storage.metadata(key)

            assertEquals("report.pdf", metadata.fileName)
            assertEquals(Fixtures.PDF.size.toLong(), metadata.size)
        }

        @Test
        fun `should throw for a missing key`() {
            assertThrows<FileNotFoundStorageException> { storage().readBytes("nope") }
        }
    }

    @Nested
    inner class Managing {

        @Test
        fun `should report whether the object existed on delete`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            assertTrue(storage.delete(key))
            assertFalse(storage.delete(key), "delete must be idempotent")
        }

        @Test
        fun `should delete many keys at once`() {
            val storage = storage()
            val keys = (1..3).map { storage.store("report.pdf", Fixtures.PDF).key }

            storage.deleteAll(keys)

            assertTrue(keys.none { storage.exists(it) })
        }

        @Test
        fun `should copy without removing the source`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            storage.copy(key, "copies/report.pdf")

            assertTrue(storage.exists(key))
            assertTrue(storage.exists("copies/report.pdf"))
        }

        @Test
        fun `should move by copying then deleting the source`() {
            val storage = storage()
            val key = storage.store("report.pdf", Fixtures.PDF).key

            storage.move(key, "archive/report.pdf")

            assertFalse(storage.exists(key))
            assertContentEquals(Fixtures.PDF, storage.readBytes("archive/report.pdf"))
        }

        @Test
        fun `should paginate listings`() {
            val storage = storage()
            repeat(5) { storage.store("report.pdf", Fixtures.PDF, folder = "docs") }

            val first = storage.list("docs/", limit = 2)
            val second = storage.list("docs/", limit = 2, pageToken = first.nextPageToken)

            assertEquals(2, first.keys.size)
            assertTrue(first.hasMore)
            assertEquals(2, second.keys.size)
            assertEquals(4, (first.keys + second.keys).distinct().size)
        }

        @Test
        fun `should report the last page`() {
            val storage = storage()
            storage.store("report.pdf", Fixtures.PDF, folder = "docs")

            val page = storage.list("docs/", limit = 10)

            assertNull(page.nextPageToken)
            assertFalse(page.hasMore)
        }
    }

    @Nested
    inner class PresignedUrls {

        @Test
        fun `should pass the download name through to the backend`() {
            val uri = storage().downloadUrl("docs/key.pdf", Duration.ofMinutes(5), downloadAs = "report.pdf")

            assertEquals("memory://get/docs/key.pdf?ttl=300&downloadAs=report.pdf", uri.toString())
        }

        @Test
        fun `should default the ttl to fifteen minutes`() {
            val uri = storage().downloadUrl("docs/key.pdf")

            assertTrue(uri.toString().contains("ttl=900"), "unexpected uri: $uri")
        }

        @Test
        fun `should build an upload url`() {
            val uri = storage().uploadUrl("docs/key.pdf", "application/pdf", Duration.ofMinutes(1))

            assertEquals("memory://put/docs/key.pdf?ttl=60&contentType=application/pdf", uri.toString())
        }
    }

    /** A stream that may only be consumed once, like a socket or a servlet body. */
    private class SinglePassStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var reads = 0
        var closed = false

        override fun read(): Int {
            if (closed) error("the stream was read after being closed")
            reads++
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) error("the stream was read after being closed")
            reads++
            return delegate.read(b, off, len)
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val MAX_SIZE = 1024L * 1024
    }
}
