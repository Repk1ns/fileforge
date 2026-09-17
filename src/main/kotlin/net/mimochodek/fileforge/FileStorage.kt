package net.mimochodek.fileforge

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.InvalidFileNameException
import net.mimochodek.fileforge.exception.UnknownStorageProfileException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.model.FileContent
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.model.FilePage
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.model.StoredFile
import java.net.URI
import java.time.Duration

/**
 * The file storage API - the one bean a consuming application injects.
 *
 * Deliberately separate from [net.mimochodek.fileforge.spi.StorageBackend]: this
 * is what consumers call, that is what a backend implements. Keeping them apart
 * is what makes two things true at the same time - the backend is selectable by
 * configuration without touching consumer code, and validation is applied
 * unconditionally on the way in.
 *
 * Using it takes three steps: add the dependency, configure `fileforge` in
 * `application.yaml`, inject this.
 *
 * ```kotlin
 * @Service
 * class DocumentService(private val storage: FileStorage) {
 *     fun upload(file: MultipartFile) = storage.store(file, folder = "documents")
 *     fun link(key: String) = storage.downloadUrl(key)
 * }
 * ```
 *
 * Every `store` call sanitizes the file name, measures the real content length,
 * validates against the configured policy and generates the object key. There is
 * no path through this interface that writes unvalidated content, and no
 * parameter whose value is taken on trust.
 */
public interface FileStorage {

    /**
     * Stores [upload] and returns everything worth persisting about it.
     *
     * @throws InvalidFileNameException when the file name is unusable.
     * @throws FileSizeExceededException when the content exceeds the configured limit.
     * @throws UnsupportedFileTypeException when the content type is not whitelisted.
     * @throws ContentMismatchException when the content does not match its type.
     * @throws UnknownStorageProfileException when [FileUpload.profile] names a
     *   profile that is not configured.
     */
    public fun store(upload: FileUpload): StoredFile

    /** Stores [bytes] under [fileName]; detects the content type when none is given. */
    public fun store(
        fileName: String,
        bytes: ByteArray,
        contentType: String? = null,
        folder: String = "",
        profile: String? = null,
    ): StoredFile

    /**
     * Opens the object under [key].
     *
     * The result holds a backend resource (for S3, a pooled HTTP connection) and
     * **must** be closed. Prefer the scoped overload, which cannot leak.
     *
     * @throws FileNotFoundStorageException when the object does not exist.
     */
    public fun read(key: String): FileContent

    /** Opens the object under [key], hands it to [block], and always closes it. */
    public fun <T> read(key: String, block: (FileContent) -> T): T

    /** Reads the whole object under [key] into memory. */
    public fun readBytes(key: String): ByteArray

    /**
     * Reads metadata - including the original file name - without transferring
     * the content, which is what an HTTP response needs to set its headers.
     *
     * @throws FileNotFoundStorageException when the object does not exist.
     */
    public fun metadata(key: String): FileMetadata

    /** Returns `true` when an object exists under [key]. */
    public fun exists(key: String): Boolean

    /** Deletes [key]. Idempotent; returns `true` when the object existed. */
    public fun delete(key: String): Boolean

    /**
     * Deletes every key in [keys], batched by the backend (S3 takes 1000 per
     * request) - which matters when deleting an entity with many attachments.
     */
    public fun deleteAll(keys: Collection<String>)

    /** Copies [from] to [to], leaving the source in place. */
    public fun copy(from: String, to: String)

    /**
     * Moves [from] to [to].
     *
     * Not atomic: copy and delete are separate backend calls, so a failure
     * between them leaves both keys present. The source is only deleted once the
     * copy has succeeded, so the object is never lost.
     */
    public fun move(from: String, to: String)

    /**
     * Returns one page of at most [limit] keys under [prefix].
     *
     * Always paginated, so a large bucket cannot be pulled into memory by
     * accident. Pass [FilePage.nextPageToken] back for the following page.
     *
     * @param delimiter set it to `"/"` for a directory-style listing: keys below
     *   the next `/` are rolled up into [FilePage.commonPrefixes] rather than
     *   listed individually.
     */
    public fun list(
        prefix: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        pageToken: String? = null,
        delimiter: String? = null,
    ): FilePage

    /**
     * A temporary URL for reading [key].
     *
     * @param downloadAs when set, the browser saves the file under this name
     *   instead of the opaque generated key - typically
     *   [net.mimochodek.fileforge.model.FileMetadata.fileName].
     */
    public fun downloadUrl(key: String, ttl: Duration = DEFAULT_TTL, downloadAs: String? = null): URI

    /**
     * A temporary URL for writing [key], so a browser can upload straight to
     * storage without proxying the bytes through the application.
     */
    public fun uploadUrl(key: String, contentType: String, ttl: Duration = DEFAULT_TTL): URI

    public companion object {
        /** Default validity of presigned URLs. */
        public val DEFAULT_TTL: Duration = Duration.ofMinutes(15)

        /** Default page size for [list]. */
        public const val DEFAULT_PAGE_SIZE: Int = 1000
    }
}
