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
 * eight methods; no consumer code changes.
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
     * @throws net.mimochodek.fileforge.exception.FileNotFoundStorageException when absent.
     */
    public fun get(key: String): FileContent

    /**
     * Reads the metadata of [key] without transferring content.
     *
     * @throws net.mimochodek.fileforge.exception.FileNotFoundStorageException when absent.
     */
    public fun head(key: String): FileMetadata

    /** Returns `true` when an object exists under [key]. */
    public fun exists(key: String): Boolean

    /** Deletes [key]. Idempotent; returns `true` when the object existed. */
    public fun delete(key: String): Boolean

    /** Deletes every key in [keys], batching where the backend supports it. */
    public fun deleteAll(keys: Collection<String>)

    /**
     * Returns one page of at most [limit] keys under [prefix], starting at [pageToken].
     *
     * @param delimiter when set, keys below it are rolled up into
     *   [FilePage.commonPrefixes] instead of being listed, which is how a
     *   directory-style listing of one level is obtained.
     */
    public fun list(prefix: String, limit: Int, pageToken: String?, delimiter: String?): FilePage

    /**
     * Builds a temporary read URL for [key], valid for [ttl].
     *
     * @param downloadAs when set, the URL must make the object download under
     *   this file name (for S3, `response-content-disposition`).
     */
    public fun presignGet(key: String, ttl: Duration, downloadAs: String?): URI
}

/**
 * A resolved write request: [contentLength] is the true length of [content],
 * already established by the layer above.

 */
public class BackendPutRequest(
    public val key: String,
    public val content: InputStream,
    public val contentLength: Long,
    public val contentType: String,
    public val metadata: Map<String, String> = emptyMap(),
)

/** What the backend actually wrote. */
public class BackendPutResult(
    public val size: Long,
    public val checksum: String?,
)
