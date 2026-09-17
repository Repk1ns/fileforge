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
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * The storage-agnostic pipeline that sits between consumers and a
 * [StorageBackend]: **sanitize -> resolve -> validate -> generate key -> delegate**.
 *
 * Every step above the backend is shared by all backends, which is what makes
 * two things true at once: a new backend is a handful of trivial methods, and
 * validation can never be skipped. There is no code path that writes
 * unvalidated content, and no path that trusts a caller's claimed size - the
 * content is resolved to a known length first, and that length is what gets
 * enforced and recorded.
 */
public class DefaultFileStorage(
    private val backend: StorageBackend,
    private val keyGenerator: ObjectKeyGenerator,
    private val validator: FileValidator,
    private val maxFileSize: Long,
    private val inMemoryThreshold: Long = DEFAULT_IN_MEMORY_THRESHOLD,
    private val tempDir: Path? = null,
    private val defaultTtl: Duration = DEFAULT_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Stores [upload], validating it first. */
    public fun store(upload: FileUpload): StoredFile {
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

    /** Stores [bytes] under [fileName]. */
    @JvmOverloads
    public fun store(
        fileName: String,
        bytes: ByteArray,
        contentType: String? = null,
        folder: String = "",
    ): StoredFile = store(FileUpload(fileName, ContentSource.Bytes(bytes), contentType, folder))

    /**
     * Opens the object under [key].
     *
     * The returned value holds a backend connection and must be closed; prefer
     * the scoped overload below.
     */
    public fun read(key: String): FileContent = backend.get(key)

    /** Opens the object under [key], passes it to [block] and always closes it. */
    public fun <T> read(key: String, block: (FileContent) -> T): T = backend.get(key).use(block)

    /** Reads the whole object under [key] into memory. */
    public fun readBytes(key: String): ByteArray = read(key) { it.stream.readBytes() }

    /** Reads the metadata of [key] without transferring content. */
    public fun metadata(key: String): FileMetadata = backend.head(key)

    /** Returns `true` when an object exists under [key]. */
    public fun exists(key: String): Boolean = backend.exists(key)

    /** Deletes [key]. Idempotent; returns `true` when the object existed. */
    public fun delete(key: String): Boolean = backend.delete(key)

    /** Deletes every key in [keys], batched by the backend. */
    public fun deleteAll(keys: Collection<String>) {
        backend.deleteAll(keys)
    }

    /** Copies [from] to [to], leaving the source in place. */
    public fun copy(from: String, to: String) {
        backend.copy(from, to)
    }

    /**
     * Moves [from] to [to] (copy, then delete the source).
     *
     * Not atomic: the copy and the delete are separate backend calls, so a
     * failure between them leaves both keys present. The source is only deleted
     * after the copy succeeds, so the object is never lost.
     */
    public fun move(from: String, to: String) {
        backend.copy(from, to)
        backend.delete(from)
    }

    /** Returns one page of at most [limit] keys under [prefix]. */
    @JvmOverloads
    public fun list(prefix: String, limit: Int = DEFAULT_PAGE_SIZE, pageToken: String? = null): FilePage =
        backend.list(prefix, limit, pageToken)

    /**
     * A temporary read URL for [key].
     *
     * @param downloadAs when set, the browser downloads the object under this
     *   file name instead of the opaque generated key.
     */
    @JvmOverloads
    public fun downloadUrl(key: String, ttl: Duration = defaultTtl, downloadAs: String? = null): URI =
        backend.presignGet(key, ttl, downloadAs)

    /** A temporary write URL for [key], for direct browser-to-storage uploads. */
    @JvmOverloads
    public fun uploadUrl(key: String, contentType: String, ttl: Duration = defaultTtl): URI =
        backend.presignPut(key, contentType, ttl)

    public companion object {
        /** Streams up to this size are buffered in memory; larger ones spill to disk. */
        public const val DEFAULT_IN_MEMORY_THRESHOLD: Long = 1024L * 1024

        /** Default validity of presigned URLs. */
        public val DEFAULT_TTL: Duration = Duration.ofMinutes(15)

        /** Default page size for [list]. */
        public const val DEFAULT_PAGE_SIZE: Int = 1000
    }
}
