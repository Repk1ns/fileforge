package net.mimochodek.fileforge.internal.s3

import net.mimochodek.fileforge.FileNotFoundStorageException
import net.mimochodek.fileforge.FileStorageException
import net.mimochodek.fileforge.model.FileContent
import net.mimochodek.fileforge.model.FileMetadata
import net.mimochodek.fileforge.model.FilePage
import net.mimochodek.fileforge.spi.BackendPutRequest
import net.mimochodek.fileforge.spi.BackendPutResult
import net.mimochodek.fileforge.spi.StorageBackend
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
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
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.net.URLDecoder
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
) : StorageBackend, AutoCloseable {

    override val providerName: String get() = "s3"

    override fun put(request: BackendPutRequest): BackendPutResult {
        val put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(request.key)
            .contentType(request.contentType)
            .contentLength(request.contentLength)
            .metadata(request.s3Metadata())
            .build()
        val response = call("upload object '${request.key}'") {
            s3.putObject(put, RequestBody.fromInputStream(request.content, request.contentLength))
        }
        return BackendPutResult(size = request.contentLength, checksum = response.eTag().unquote())
    }

    override fun get(key: String): FileContent {
        val stream = call("download object '$key'", notFoundKey = key) {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
        }
        val response = stream.response()
        val metadata = FileMetadata(
            key = key,
            fileName = decodeFileName(response.metadata()),
            contentType = response.contentType() ?: DEFAULT_CONTENT_TYPE,
            size = response.contentLength() ?: -1L,
            checksum = response.eTag().unquote(),
            lastModified = response.lastModified(),
            metadata = response.metadata().userMetadata(),
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

    override fun list(prefix: String, limit: Int, pageToken: String?): FilePage {
        require(limit > 0) { "limit must be positive but was $limit" }
        val request = ListObjectsV2Request.builder()
            .bucket(bucket)
            .prefix(prefix)
            .maxKeys(limit)
            .apply { if (pageToken != null) continuationToken(pageToken) }
            .build()
        // Deliberately a single page rather than the paginator: `.contents()` on the
        // paginator is eager, so a large bucket would materialize every key.
        val response = call("list objects with prefix '$prefix'") { s3.listObjectsV2(request) }
        return FilePage(
            keys = response.contents().orEmpty().map { it.key() },
            nextPageToken = response.nextContinuationToken(),
        )
    }

    override fun copy(sourceKey: String, targetKey: String) {
        val source = headObject(sourceKey)
        val size = source.contentLength() ?: 0L
        if (size > MAX_SINGLE_PART_COPY) {
            multipartCopy(sourceKey, targetKey, size, source.contentType())
            return
        }
        call("copy object '$sourceKey' to '$targetKey'", notFoundKey = sourceKey) {
            s3.copyObject(
                CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(sourceKey)
                    .destinationBucket(bucket)
                    .destinationKey(targetKey)
                    .build(),
            )
        }
    }

    /**
     * Copies via multipart, because a single-part `CopyObject` is capped at 5 GiB
     * by S3.
     */
    private fun multipartCopy(sourceKey: String, targetKey: String, size: Long, contentType: String?) {
        val uploadId = call("start multipart copy of '$sourceKey'") {
            s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(targetKey)
                    .apply { if (contentType != null) contentType(contentType) }
                    .build(),
            )
        }.uploadId()

        try {
            val parts = ArrayList<CompletedPart>()
            var offset = 0L
            var partNumber = 1
            while (offset < size) {
                val last = minOf(offset + COPY_PART_SIZE, size) - 1
                val response = call("copy part $partNumber of '$sourceKey'") {
                    s3.uploadPartCopy(
                        UploadPartCopyRequest.builder()
                            .sourceBucket(bucket)
                            .sourceKey(sourceKey)
                            .destinationBucket(bucket)
                            .destinationKey(targetKey)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .copySourceRange("bytes=$offset-$last")
                            .build(),
                    )
                }
                parts += CompletedPart.builder()
                    .partNumber(partNumber)
                    .eTag(response.copyPartResult().eTag())
                    .build()
                offset = last + 1
                partNumber++
            }
            call("complete multipart copy of '$sourceKey'") {
                s3.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(targetKey)
                        .uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                        .build(),
                )
            }
        } catch (e: Throwable) {
            runCatching {
                s3.abortMultipartUpload { it.bucket(bucket).key(targetKey).uploadId(uploadId) }
            }
            throw e
        }
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

    override fun presignPut(key: String, contentType: String, ttl: Duration): URI =
        call("presign PUT URL for object '$key'") {
            presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(
                        PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    )
                    .build(),
            )
        }.url().toURI()

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
        fileName = decodeFileName(metadata()),
        contentType = contentType() ?: DEFAULT_CONTENT_TYPE,
        size = contentLength() ?: -1L,
        checksum = eTag().unquote(),
        lastModified = lastModified(),
        metadata = metadata().userMetadata(),
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

    private fun mapped(action: String, e: SdkException) = FileStorageException("Failed to $action", e)

    private companion object {
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /**
         * Object-metadata entry holding the original file name, URL-encoded:
         * S3 user metadata is sent as HTTP headers and cannot carry raw
         * non-ASCII characters.
         */
        const val FILE_NAME_METADATA = "fileforge-filename"

        fun BackendPutRequest.s3Metadata(): Map<String, String> = buildMap {
            putAll(metadata)
            fileName?.let { put(FILE_NAME_METADATA, URLEncoder.encode(it, StandardCharsets.UTF_8)) }
        }

        fun decodeFileName(metadata: Map<String, String>?): String? =
            metadata?.get(FILE_NAME_METADATA)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

        /** The caller's own metadata, without the library's reserved entries. */
        fun Map<String, String>?.userMetadata(): Map<String, String> =
            this.orEmpty().filterKeys { it != FILE_NAME_METADATA }

        /** S3 accepts at most 1000 keys per DeleteObjects request. */
        const val MAX_KEYS_PER_DELETE = 1000

        /** S3 rejects a single-part CopyObject above 5 GiB. */
        const val MAX_SINGLE_PART_COPY = 5L * 1024 * 1024 * 1024

        /** Part size for multipart copies; well within the 10 000 part limit. */
        const val COPY_PART_SIZE = 1024L * 1024 * 1024

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
