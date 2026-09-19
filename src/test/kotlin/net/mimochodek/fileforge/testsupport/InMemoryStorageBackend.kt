package net.mimochodek.fileforge.testsupport

import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.model.FileContent
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.model.FilePage
import net.mimochodek.fileforge.spi.BackendPutRequest
import net.mimochodek.fileforge.spi.BackendPutResult
import net.mimochodek.fileforge.spi.StorageBackend
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * A complete second backend, in memory.
 *
 * It exists to hold the [StorageBackend] SPI to its promise: adding a backend
 * must be a handful of trivial methods with no validation, naming or policy
 * duplicated from the layer above. If this class ever needs to grow one of
 * those, the seam is in the wrong place.
 */
class InMemoryStorageBackend : StorageBackend {

    class Stored(
        val bytes: ByteArray,
        val contentType: String,
        val fileName: String?,
        val metadata: Map<String, String>,
        val storedAt: Instant = Instant.EPOCH,
    )

    val objects: MutableMap<String, Stored> = LinkedHashMap()

    /** Records every put, so tests can assert what the pipeline handed down. */
    val puts: MutableList<BackendPutRequest> = mutableListOf()

    override val providerName: String get() = "in-memory"

    override fun put(request: BackendPutRequest): BackendPutResult {
        val bytes = request.content.readBytes()
        puts += request
        objects[request.key] = Stored(bytes, request.contentType, request.fileName, request.metadata)
        return BackendPutResult(size = bytes.size.toLong(), checksum = "etag-${bytes.size}")
    }

    override fun get(key: String): FileContent {
        val stored = objects[key] ?: throw FileNotFoundStorageException(key)
        // A closable stream rather than a bare ByteArrayInputStream, whose close()
        // is a no-op and would let a leak test pass vacuously.
        return FileContent(metadataOf(key, stored), ClosingStream(stored.bytes))
    }

    /** Mimics a real backend stream: reading after close fails. */
    class ClosingStream(bytes: ByteArray) : java.io.InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var closed: Boolean = false
            private set

        override fun read(): Int = checkOpen().read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = checkOpen().read(b, off, len)

        override fun available(): Int = checkOpen().available()

        override fun close() {
            closed = true
        }

        private fun checkOpen(): ByteArrayInputStream {
            if (closed) throw java.io.IOException("stream is closed")
            return delegate
        }
    }

    override fun head(key: String): FileMetadata {
        val stored = objects[key] ?: throw FileNotFoundStorageException(key)
        return metadataOf(key, stored)
    }

    override fun exists(key: String): Boolean = key in objects

    override fun delete(key: String): Boolean = objects.remove(key) != null

    override fun deleteAll(keys: Collection<String>) {
        keys.forEach { objects.remove(it) }
    }

    override fun list(prefix: String, limit: Int, pageToken: String?, delimiter: String?): FilePage {
        val matching = objects.keys.filter { it.startsWith(prefix) }.sorted()
        if (delimiter == null) {
            val from = pageToken?.let { token -> matching.indexOfFirst { it == token } } ?: 0
            val page = matching.drop(from).take(limit)
            return FilePage(page, matching.drop(from + limit).firstOrNull())
        }
        // Keys containing the delimiter past the prefix roll up into a common prefix.
        val (nested, flat) = matching.partition { it.removePrefix(prefix).contains(delimiter) }
        val prefixes = nested
            .map { prefix + it.removePrefix(prefix).substringBefore(delimiter) + delimiter }
            .distinct()
        return FilePage(flat.take(limit), null, prefixes)
    }

    override fun presignGet(key: String, ttl: Duration, downloadAs: String?): URI =
        URI.create("memory://get/$key?ttl=${ttl.seconds}&downloadAs=${downloadAs ?: ""}")

    private fun metadataOf(key: String, stored: Stored) = FileMetadata(
        key = key,
        fileName = stored.fileName,
        contentType = stored.contentType,
        size = stored.bytes.size.toLong(),
        checksum = "etag-${stored.bytes.size}",
        lastModified = stored.storedAt,
        metadata = stored.metadata,
    )
}
