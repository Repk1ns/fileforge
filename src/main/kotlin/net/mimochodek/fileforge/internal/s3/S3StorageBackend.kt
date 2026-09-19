package net.mimochodek.fileforge.internal.s3

import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileStorageException
import net.mimochodek.fileforge.exception.StorageUnavailableException
import net.mimochodek.fileforge.model.FileContent
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.model.FilePage
import net.mimochodek.fileforge.spi.BackendPutRequest
import net.mimochodek.fileforge.spi.BackendPutResult
import net.mimochodek.fileforge.spi.StorageBackend
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * [StorageBackend] backed by the AWS SDK v2 [S3Client].
 *
 * Works on exact keys only - naming, validation and policy live above it. The
 * client and presigner are owned by this backend and closed with it, so the
 * library never contributes generically-typed `S3Client` / `S3Presigner` beans
 * to the consumer's context.
 */
internal class S3StorageBackend(
    private val s3: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
    /**
     * Uploads at or below this size go in a single PutObject. S3 rejects a
     * single PUT above 5 GiB, so anything larger must be multipart. Injectable
     * so the multipart path is testable without a 5 GiB fixture.
     */
    private val multipartThreshold: Long = MAX_SINGLE_PART_PUT,
    private val uploadPartSize: Long = UPLOAD_PART_SIZE,
) : StorageBackend, AutoCloseable {

    override val providerName: String get() = "s3"

    override fun put(request: BackendPutRequest): BackendPutResult {
        if (request.contentLength > multipartThreshold) {
            log.debug(
                "Uploading {} ({} bytes) via multipart; a single PutObject is capped at 5 GiB",
                request.key,
                request.contentLength,
            )
            return multipartPut(request)
        }
        val put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(request.key)
            .contentType(request.contentType)
            .contentLength(request.contentLength)
            .metadata(request.metadata)
            .build()
        val response = call("upload object '${request.key}'") {
            s3.putObject(put, RequestBody.fromInputStream(request.content, request.contentLength))
        }
        return BackendPutResult(size = request.contentLength, checksum = response.eTag().unquote())
    }

    /**
     * Uploads in parts, because S3 rejects a single PutObject above 5 GiB.
     *
     * The content length is already known by the time it reaches here, so each
     * part's length can be declared up front and the stream read exactly once.
     */
    private fun multipartPut(request: BackendPutRequest): BackendPutResult {
        val uploadId = call("start multipart upload of '${request.key}'") {
            s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(request.key)
                    .contentType(request.contentType)
                    .metadata(request.metadata)
                    .build(),
            )
        }.uploadId()

        try {
            val parts = ArrayList<CompletedPart>()
            var remaining = request.contentLength
            var partNumber = 1
            while (remaining > 0) {
                val partLength = minOf(uploadPartSize, remaining)
                // Bounded and non-closing: the SDK reads exactly partLength bytes
                // and must not close the stream we still need for later parts.
                val part = BoundedInputStream(request.content, partLength)
                val response = call("upload part $partNumber of '${request.key}'") {
                    s3.uploadPart(
                        UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(request.key)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .contentLength(partLength)
                            .build(),
                        RequestBody.fromInputStream(part, partLength),
                    )
                }
                parts += CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build()
                remaining -= partLength
                partNumber++
            }
            val completed = call("complete multipart upload of '${request.key}'") {
                s3.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(request.key)
                        .uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                        .build(),
                )
            }
            return BackendPutResult(size = request.contentLength, checksum = completed.eTag().unquote())
        } catch (e: Throwable) {
            // Without this the incomplete parts keep costing storage indefinitely.
            runCatching { s3.abortMultipartUpload { it.bucket(bucket).key(request.key).uploadId(uploadId) } }
            throw e
        }
    }

    override fun get(key: String): FileContent {
        val stream = call("download object '$key'", notFoundKey = key) {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
        }
        val response = stream.response()
        val metadata = FileMetadata(
            key = key,
            contentType = response.contentType() ?: DEFAULT_CONTENT_TYPE,
            size = response.contentLength() ?: -1L,
            checksum = response.eTag().unquote(),
            lastModified = response.lastModified(),
            metadata = response.metadata().orEmpty(),
        )
        return FileContent(metadata, stream)
    }

    override fun head(key: String): FileMetadata = headObject(key).toMetadata(key)

    override fun exists(key: String): Boolean =
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: SdkException) {
            if (e.isNotFound()) false else throw mapped("check existence of object '$key'", e)
        }

    override fun delete(key: String): Boolean {
        // S3 DeleteObject succeeds for absent keys and reports nothing about
        // whether the object was there, so the existence check is a separate call.
        val existed = exists(key)
        call("delete object '$key'") {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        }
        return existed
    }

    override fun deleteAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        keys.distinct().chunked(MAX_KEYS_PER_DELETE).forEach { batch ->
            val delete = Delete.builder()
                .objects(batch.map { ObjectIdentifier.builder().key(it).build() })
                .quiet(true)
                .build()
            val response = call("delete ${batch.size} objects") {
                s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket).delete(delete).build())
            }
            val errors = response.errors().orEmpty()
            if (errors.isNotEmpty()) {
                throw FileStorageException(
                    "Failed to delete ${errors.size} of ${batch.size} objects; first failure: " +
                        "'${errors.first().key()}' (${errors.first().code()}: ${errors.first().message()})",
                )
            }
        }
    }

    override fun list(prefix: String, limit: Int, pageToken: String?, delimiter: String?): FilePage {
        require(limit > 0) { "limit must be positive but was $limit" }
        val request = ListObjectsV2Request.builder()
            .bucket(bucket)
            .prefix(prefix)
            .maxKeys(limit)
            .apply {
                if (pageToken != null) continuationToken(pageToken)
                if (delimiter != null) delimiter(delimiter)
            }
            .build()
        // Deliberately a single page rather than the paginator: `.contents()` on the
        // paginator is eager, so a large bucket would materialize every key.
        val response = call("list objects with prefix '$prefix'") { s3.listObjectsV2(request) }
        return FilePage(
            keys = response.contents().orEmpty().map { it.key() },
            nextPageToken = response.nextContinuationToken(),
            commonPrefixes = response.commonPrefixes().orEmpty().mapNotNull { it.prefix() },
        )
    }

    override fun presignGet(key: String, ttl: Duration, downloadAs: String?): URI {
        val get = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .apply { if (downloadAs != null) responseContentDisposition(contentDisposition(downloadAs)) }
            .build()
        return call("presign GET URL for object '$key'") {
            presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(get)
                    .build(),
            )
        }.url().toURI()
    }

    override fun close() {
        runCatching { s3.close() }
        runCatching { presigner.close() }
    }

    private fun headObject(key: String): HeadObjectResponse =
        call("read metadata of object '$key'", notFoundKey = key) {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        }

    private fun HeadObjectResponse.toMetadata(key: String) = FileMetadata(
        key = key,
        contentType = contentType() ?: DEFAULT_CONTENT_TYPE,
        size = contentLength() ?: -1L,
        checksum = eTag().unquote(),
        lastModified = lastModified(),
        metadata = metadata().orEmpty(),
    )

    /**
     * Runs an SDK call, translating every SDK failure into the library's
     * hierarchy so no AWS type escapes the abstraction. [SdkException] rather
     * than [S3Exception] is caught, so connection refused, DNS failures and
     * socket timeouts are mapped too.
     */
    private inline fun <T> call(action: String, notFoundKey: String? = null, block: () -> T): T =
        try {
            block()
        } catch (e: NoSuchKeyException) {
            if (notFoundKey != null) throw FileNotFoundStorageException(notFoundKey, e) else throw mapped(action, e)
        } catch (e: SdkException) {
            if (notFoundKey != null && e.isNotFound()) {
                throw FileNotFoundStorageException(notFoundKey, e)
            }
            throw mapped(action, e)
        }

    /**
     * A failure with no HTTP response behind it - connection refused, DNS
     * failure, socket timeout - is the backend being unreachable rather than a
     * storage error, and is worth a distinct type: it is retryable and maps to a
     * different HTTP status.
     */
    private fun mapped(action: String, e: SdkException): FileStorageException {
        log.debug("S3 call failed: {}", action, e)
        return if (e is SdkClientException) {
            StorageUnavailableException("Storage is unreachable while trying to $action", e)
        } else {
            FileStorageException("Failed to $action", e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(S3StorageBackend::class.java)

        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /** S3 accepts at most 1000 keys per DeleteObjects request. */
        const val MAX_KEYS_PER_DELETE = 1000

        /** S3 rejects a single PutObject above 5 GiB. */
        const val MAX_SINGLE_PART_PUT = 5L * 1024 * 1024 * 1024

        /**
         * Part size for multipart uploads. Comfortably over S3's 5 MiB minimum
         * part size, and 512 MiB x 10 000 parts covers any object S3 accepts.
         */
        const val UPLOAD_PART_SIZE = 512L * 1024 * 1024

        fun String?.unquote(): String? = this?.trim('"')

        fun SdkException.isNotFound(): Boolean = this is S3Exception && statusCode() == 404

        /**
         * RFC 6266 / RFC 5987 `Content-Disposition`: the plain `filename` keeps
         * legacy clients working, `filename*` carries the exact UTF-8 name.
         */
        fun contentDisposition(fileName: String): String {
            val ascii = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
                .joinToString("")
            val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
            return """attachment; filename="$ascii"; filename*=UTF-8''$encoded"""
        }
    }
}
