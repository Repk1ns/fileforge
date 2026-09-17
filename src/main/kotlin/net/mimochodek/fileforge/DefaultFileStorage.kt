package net.mimochodek.fileforge

import net.mimochodek.fileforge.internal.resolveContent
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileContent
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.model.FilePage
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.model.StoredFile
import net.mimochodek.fileforge.spi.BackendPutRequest
import net.mimochodek.fileforge.spi.FileCandidate
import net.mimochodek.fileforge.spi.FileValidator
import net.mimochodek.fileforge.spi.ObjectKeyGenerator
import net.mimochodek.fileforge.spi.ObjectKeyRequest
import net.mimochodek.fileforge.spi.StorageBackend
import net.mimochodek.fileforge.validation.ContentTypeDetector
import net.mimochodek.fileforge.validation.FileNames
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * The storage-agnostic pipeline between [FileStorage] and a [StorageBackend]:
 * **sanitize -> resolve -> validate -> generate key -> delegate**.
 *
 * Every step above the backend is shared by all backends, which is what makes
 * two things true at once: a new backend is a handful of trivial methods, and
 * validation can never be skipped. No path writes unvalidated content, and no
 * path trusts a caller's claimed size - the content is resolved to a known
 * length first, and that length is what gets enforced and recorded.
 */
public class DefaultFileStorage(
    private val backend: StorageBackend,
    private val keyGenerator: ObjectKeyGenerator,
    private val validator: FileValidator,
    private val maxFileSize: Long,
    private val inMemoryThreshold: Long = DEFAULT_IN_MEMORY_THRESHOLD,
    private val tempDir: Path? = null,
    private val clock: Clock = Clock.systemUTC(),
) : FileStorage {

    override fun store(upload: FileUpload): StoredFile {
        val fileName = FileNames.sanitize(upload.fileName)

        return resolveContent(upload.content, maxFileSize, inMemoryThreshold, tempDir).use { content ->
            val contentType = upload.contentType
                ?.let { ContentTypeDetector.normalize(it) }
                ?: ContentTypeDetector.detect(content.head, fileName)

            validator.validate(
                FileCandidate(
                    fileName = fileName,
                    contentType = contentType,
                    size = content.size,
                    head = content.head,
                ),
            )

            val key = keyGenerator.generate(ObjectKeyRequest(fileName, upload.folder, contentType))
            val result = content.openStream().use { stream ->
                backend.put(
                    BackendPutRequest(
                        key = key,
                        content = stream,
                        contentLength = content.size,
                        contentType = contentType,
                        fileName = fileName,
                        metadata = upload.metadata,
                    ),
                )
            }

            log.debug(
                "Stored '{}' as {} ({}, {} bytes) via {}",
                fileName,
                key,
                contentType,
                result.size,
                backend.providerName,
            )

            StoredFile(
                key = key,
                fileName = fileName,
                contentType = contentType,
                size = result.size,
                checksum = result.checksum,
                storedAt = clock.instant(),
            )
        }
    }

    override fun store(fileName: String, bytes: ByteArray, contentType: String?, folder: String): StoredFile =
        store(FileUpload(fileName, ContentSource.Bytes(bytes), contentType, folder))

    override fun read(key: String): FileContent {
        log.debug("Reading {}", key)
        return backend.get(key)
    }

    override fun <T> read(key: String, block: (FileContent) -> T): T = read(key).use(block)

    override fun readBytes(key: String): ByteArray = read(key) { it.stream.readBytes() }

    override fun metadata(key: String): FileMetadata = backend.head(key)

    override fun exists(key: String): Boolean = backend.exists(key)

    override fun delete(key: String): Boolean {
        val existed = backend.delete(key)
        log.debug("Deleted {} (existed: {})", key, existed)
        return existed
    }

    override fun deleteAll(keys: Collection<String>) {
        backend.deleteAll(keys)
        log.debug("Deleted {} objects", keys.size)
    }

    override fun copy(from: String, to: String) {
        backend.copy(from, to)
        log.debug("Copied {} to {}", from, to)
    }

    override fun move(from: String, to: String) {
        backend.copy(from, to)
        try {
            backend.delete(from)
        } catch (e: RuntimeException) {
            // The copy succeeded, so the object is not lost - but both keys now
            // exist, and the caller believes only the target does.
            log.warn(
                "Moved {} to {} but could not delete the source; both keys now exist",
                from,
                to,
                e,
            )
            throw e
        }
        log.debug("Moved {} to {}", from, to)
    }

    override fun list(prefix: String, limit: Int, pageToken: String?): FilePage =
        backend.list(prefix, limit, pageToken)

    override fun downloadUrl(key: String, ttl: Duration, downloadAs: String?): URI =
        backend.presignGet(key, ttl, downloadAs)

    override fun uploadUrl(key: String, contentType: String, ttl: Duration): URI =
        backend.presignPut(key, contentType, ttl)

    public companion object {
        /** Streams up to this size are buffered in memory; larger ones spill to disk. */
        public const val DEFAULT_IN_MEMORY_THRESHOLD: Long = 1024L * 1024

        private val log = LoggerFactory.getLogger(DefaultFileStorage::class.java)
    }
}
