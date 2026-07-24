package net.mimochodek.fileforge

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.InputStream
import java.net.URL
import java.time.Duration

/**
 * [FileStorageService] implementation backed by the AWS SDK v2 [S3Client].
 */
class S3FileStorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: FileStorageProperties,
) : FileStorageService {

    override fun upload(objectKey: String, content: InputStream, contentLength: Long, contentType: String) {
        val request = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .contentLength(contentLength)
            .build()
        try {
            s3Client.putObject(request, RequestBody.fromInputStream(content, contentLength))
        } catch (e: S3Exception) {
            throw FileStorageException("Failed to upload object '$objectKey'", e)
        }
    }

    override fun download(objectKey: String): InputStream {
        val request = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .build()
        return try {
            s3Client.getObject(request)
        } catch (e: NoSuchKeyException) {
            throw FileNotFoundStorageException(objectKey, e)
        } catch (e: S3Exception) {
            throw FileStorageException("Failed to download object '$objectKey'", e)
        }
    }

    override fun delete(objectKey: String) {
        val request = DeleteObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .build()
        try {
            s3Client.deleteObject(request)
        } catch (e: S3Exception) {
            throw FileStorageException("Failed to delete object '$objectKey'", e)
        }
    }

    override fun exists(objectKey: String): Boolean {
        val request = HeadObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .build()
        return try {
            s3Client.headObject(request)
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false
            else throw FileStorageException("Failed to check existence of object '$objectKey'", e)
        }
    }

    override fun list(prefix: String): List<String> {
        val request = ListObjectsV2Request.builder()
            .bucket(properties.bucket)
            .prefix(prefix)
            .build()
        return try {
            s3Client.listObjectsV2Paginator(request)
                .contents()
                .map { it.key() }
        } catch (e: S3Exception) {
            throw FileStorageException("Failed to list objects with prefix '$prefix'", e)
        }
    }

    override fun archive(sourceKey: String, targetKey: String) {
        val copyRequest = CopyObjectRequest.builder()
            .sourceBucket(properties.bucket)
            .sourceKey(sourceKey)
            .destinationBucket(properties.bucket)
            .destinationKey(targetKey)
            .build()
        try {
            s3Client.copyObject(copyRequest)
        } catch (e: NoSuchKeyException) {
            throw FileNotFoundStorageException(sourceKey, e)
        } catch (e: S3Exception) {
            throw FileStorageException("Failed to archive object '$sourceKey' to '$targetKey'", e)
        }
        delete(sourceKey)
    }

    override fun presignGetUrl(objectKey: String, ttl: Duration): URL {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getObjectRequest)
            .build()
        return try {
            s3Presigner.presignGetObject(presignRequest).url()
        } catch (e: S3Exception) {
            throw FileStorageException("Failed to presign GET URL for object '$objectKey'", e)
        }
    }
}
