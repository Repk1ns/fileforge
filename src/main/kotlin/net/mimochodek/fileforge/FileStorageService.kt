package net.mimochodek.fileforge

import java.io.InputStream
import java.net.URL
import java.time.Duration

/**
 * Domain-agnostic file storage abstraction over an S3-compatible backend.
 *
 * The service works purely with object keys, streams/bytes and content types.
 * Metadata persistence and authorization are the responsibility of the
 * consuming application.
 */
interface FileStorageService {

    /**
     * Uploads [content] under a newly generated unique object key and returns it.
     *
     * The generated key keeps the path prefix of [objectKey] (if any) and replaces
     * the file name with `<year>_<uuid>.<extension>`, e.g. `docs/2026_1f7a...c3.jpeg`.
     * The returned key is what the consuming application should persist (e.g. in its database).
     */
    fun upload(objectKey: String, content: InputStream, contentLength: Long, contentType: String): String

    /** Downloads the object stored under [objectKey]. */
    fun download(objectKey: String): InputStream

    /** Deletes the object stored under [objectKey]. */
    fun delete(objectKey: String)

    /** Returns `true` if an object exists under [objectKey]. */
    fun exists(objectKey: String): Boolean

    /** Lists all object keys starting with [prefix]. */
    fun list(prefix: String): List<String>

    /** Archive = generic move: copy to targetKey then delete sourceKey. */
    fun archive(sourceKey: String, targetKey: String)

    /** Generates a presigned GET URL for [objectKey] valid for [ttl]. */
    fun presignGetUrl(objectKey: String, ttl: Duration = Duration.ofMinutes(15)): URL
}
