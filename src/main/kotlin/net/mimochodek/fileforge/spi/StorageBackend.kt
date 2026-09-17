package net.mimochodek.fileforge.spi

import net.mimochodek.fileforge.model.FileContent
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.model.FilePage
import java.io.InputStream
import java.net.URI
import java.time.Duration

/**
 * The narrow service-provider interface a storage backend implements.
 *
 * Deliberately dumb: every method works on an **exact** key that has already
 * been generated, and performs no validation, naming or policy of any kind -
 * all of that lives in [net.mimochodek.fileforge.FileStorage] above it and is
 * therefore shared by every backend.
 *
 * Adding a backend (filesystem, Azure Blob, GCS, ...) means implementing these
 * methods; no consumer code changes.
 *
 * Implementations must translate backend-specific failures into the library's
 * exception hierarchy, and must not leak backend types through this interface.
 * No method has a default argument, so implementing this interface in Java or
 * Kotlin costs nothing extra.
 */
public interface StorageBackend {

    /** Identifies the backend in logs and error messages, e.g. `s3`. */
    public val providerName: String

    /** Writes [request] and reports what was actually written. */
    public fun put(request: BackendPutRequest): BackendPutResult

    /**
     * Opens the object stored under [key].
     *
     * @throws net.mimochodek.fileforge.FileNotFoundStorageException when absent.
     */
    public fun get(key: String): FileContent

    /**
     * Reads the metadata of [key] without transferring content.
     *
     * @throws net.mimochodek.fileforge.FileNotFoundStorageException when absent.
     */
    public fun head(key: String): FileMetadata

    /** Returns `true` when an object exists under [key]. */
    public fun exists(key: String): Boolean

    /** Deletes [key]. Idempotent; returns `true` when the object existed. */
    public fun delete(key: String): Boolean

    /** Deletes every key in [keys], batching where the backend supports it. */
    public fun deleteAll(keys: Collection<String>)

    /** Returns one page of at most [limit] keys under [prefix], starting at [pageToken]. */
    public fun list(prefix: String, limit: Int, pageToken: String?): FilePage

    /** Copies [sourceKey] to [targetKey], leaving the source in place. */
    public fun copy(sourceKey: String, targetKey: String)

    /**
     * Builds a temporary read URL for [key], valid for [ttl].
     *
     * @param downloadAs when set, the URL must make the object download under
     *   this file name (for S3, `response-content-disposition`).
     */
    public fun presignGet(key: String, ttl: Duration, downloadAs: String?): URI

    /** Builds a temporary write URL for [key], valid for [ttl]. */
    public fun presignPut(key: String, contentType: String, ttl: Duration): URI
}

/**
 * A resolved write request: [contentLength] is the true length of [content],
 * already established by the layer above.
 *
 * @property fileName the sanitized original file name. The backend is
 *   responsible for persisting it (and for any encoding that needs - S3 object
 *   metadata travels in HTTP headers and cannot carry raw non-ASCII) so that
 *   [net.mimochodek.fileforge.model.FileMetadata.fileName] reads it back
 *   unchanged. Consumers no longer have to keep it in their own database.
 */
public class BackendPutRequest(
    public val key: String,
    public val content: InputStream,
    public val contentLength: Long,
    public val contentType: String,
    public val fileName: String? = null,
    public val metadata: Map<String, String> = emptyMap(),
)

/** What the backend actually wrote. */
public class BackendPutResult(
    public val size: Long,
    public val checksum: String?,
)
