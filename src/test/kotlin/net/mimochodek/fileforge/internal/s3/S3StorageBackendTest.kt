package net.mimochodek.fileforge.internal.s3

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileStorageException
import net.mimochodek.fileforge.spi.BackendPutRequest
import net.mimochodek.fileforge.testsupport.Fixtures
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CommonPrefix
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3StorageBackendTest {

    private val s3 = mockk<S3Client>(relaxed = false)
    private val presigner = mockk<S3Presigner>()
    private val backend = S3StorageBackend(s3, presigner, BUCKET)

    @Nested
    inner class Putting {

        @Test
        fun `should build the put request from the resolved content`() {
            val requestSlot = slot<PutObjectRequest>()
            every { s3.putObject(capture(requestSlot), any<RequestBody>()) } returns
                PutObjectResponse.builder().eTag("\"abc123\"").build()

            val result = backend.put(
                BackendPutRequest(
                    key = "docs/key.pdf",
                    content = ByteArrayInputStream(Fixtures.PDF),
                    contentLength = Fixtures.PDF.size.toLong(),
                    contentType = "application/pdf",
                    fileName = "report.pdf",
                    metadata = mapOf("owner" to "42"),
                ),
            )

            val request = requestSlot.captured
            assertEquals(BUCKET, request.bucket())
            assertEquals("docs/key.pdf", request.key())
            assertEquals("application/pdf", request.contentType())
            assertEquals(Fixtures.PDF.size.toLong(), request.contentLength())
            assertEquals("42", request.metadata()["owner"])
            assertEquals(Fixtures.PDF.size.toLong(), result.size)
            assertEquals("abc123", result.checksum, "the ETag must be unquoted")
        }

        @Test
        fun `should url-encode the original file name into object metadata`() {
            val requestSlot = slot<PutObjectRequest>()
            every { s3.putObject(capture(requestSlot), any<RequestBody>()) } returns
                PutObjectResponse.builder().build()

            backend.put(
                BackendPutRequest(
                    key = "docs/key.pdf",
                    content = ByteArrayInputStream(Fixtures.PDF),
                    contentLength = Fixtures.PDF.size.toLong(),
                    contentType = "application/pdf",
                    // S3 user metadata travels in HTTP headers and cannot carry raw non-ASCII.
                    fileName = "Ctvrtletni zprava.pdf",
                ),
            )

            assertEquals(
                "Ctvrtletni+zprava.pdf",
                requestSlot.captured.metadata()["fileforge-filename"],
            )
        }

        @Test
        fun `should upload in parts above the single-PUT limit`() {
            // S3 rejects a single PutObject above 5 GiB; the threshold is injected
            // here so the multipart path is exercised without a 5 GiB fixture.
            val large = S3StorageBackend(s3, presigner, BUCKET, multipartThreshold = 10, uploadPartSize = 4)
            val payload = ByteArray(11) { it.toByte() }
            val uploaded = mutableListOf<Pair<UploadPartRequest, ByteArray>>()

            every { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns
                CreateMultipartUploadResponse.builder().uploadId("upload-1").build()
            every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } answers {
                val request = firstArg<UploadPartRequest>()
                val body = secondArg<RequestBody>()
                uploaded += request to body.contentStreamProvider().newStream().readBytes()
                UploadPartResponse.builder().eTag("etag-${request.partNumber()}").build()
            }
            val completeSlot = slot<CompleteMultipartUploadRequest>()
            every { s3.completeMultipartUpload(capture(completeSlot)) } returns
                CompleteMultipartUploadResponse.builder().eTag("\"final\"").build()

            val result = large.put(
                BackendPutRequest(
                    key = "docs/big.bin",
                    content = ByteArrayInputStream(payload),
                    contentLength = payload.size.toLong(),
                    contentType = "application/octet-stream",
                    fileName = "big.bin",
                ),
            )

            assertEquals(listOf(4L, 4L, 3L), uploaded.map { it.first.contentLength() })
            assertEquals(listOf(1, 2, 3), uploaded.map { it.first.partNumber() })
            assertContentEquals(payload, uploaded.fold(ByteArray(0)) { all, part -> all + part.second })
            assertEquals(
                listOf(1 to "etag-1", 2 to "etag-2", 3 to "etag-3"),
                completeSlot.captured.multipartUpload().parts().map { it.partNumber() to it.eTag() },
            )
            assertEquals(payload.size.toLong(), result.size)
            assertEquals("final", result.checksum)
            verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        }

        @Test
        fun `should abort a multipart upload that fails partway`() {
            // Otherwise the orphaned parts keep costing storage indefinitely.
            val large = S3StorageBackend(s3, presigner, BUCKET, multipartThreshold = 10, uploadPartSize = 4)
            every { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns
                CreateMultipartUploadResponse.builder().uploadId("upload-1").build()
            every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } throws s3Exception(500)
            every { s3.abortMultipartUpload(any<java.util.function.Consumer<AbortMultipartUploadRequest.Builder>>()) } returns
                AbortMultipartUploadResponse.builder().build()

            assertThrows<FileStorageException> {
                large.put(
                    BackendPutRequest(
                        key = "docs/big.bin",
                        content = ByteArrayInputStream(ByteArray(11)),
                        contentLength = 11,
                        contentType = "application/octet-stream",
                    ),
                )
            }

            verify(exactly = 1) {
                s3.abortMultipartUpload(any<java.util.function.Consumer<AbortMultipartUploadRequest.Builder>>())
            }
        }

        @Test
        fun `should use a single request at or below the threshold`() {
            val large = S3StorageBackend(s3, presigner, BUCKET, multipartThreshold = 10, uploadPartSize = 4)
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
                PutObjectResponse.builder().eTag("\"single\"").build()

            large.put(
                BackendPutRequest("k", ByteArrayInputStream(ByteArray(10)), 10, "application/octet-stream"),
            )

            verify(exactly = 0) { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) }
        }

        @Test
        fun `should map an sdk failure to FileStorageException`() {
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws s3Exception(500)

            assertThrows<FileStorageException> {
                backend.put(
                    BackendPutRequest("k", ByteArrayInputStream(ByteArray(0)), 0, "application/pdf"),
                )
            }
        }

        @Test
        fun `should map a transport failure to FileStorageException, not leak the sdk type`() {
            // 1.x caught only S3Exception, so connection refused / DNS failure /
            // socket timeout propagated raw AWS types straight through the abstraction.
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws
                SdkClientException.create("Unable to execute HTTP request: Connection refused")

            val thrown = assertThrows<FileStorageException> {
                backend.put(BackendPutRequest("k", ByteArrayInputStream(ByteArray(0)), 0, "application/pdf"))
            }
            assertTrue(thrown.cause is SdkClientException)
        }
    }

    @Nested
    inner class Reading {

        @Test
        fun `should return content with metadata`() {
            val requestSlot = slot<GetObjectRequest>()
            every { s3.getObject(capture(requestSlot)) } returns responseStream(
                GetObjectResponse.builder()
                    .contentType("application/pdf")
                    .contentLength(Fixtures.PDF.size.toLong())
                    .eTag("\"etag\"")
                    .lastModified(Instant.EPOCH)
                    .metadata(mapOf("fileforge-filename" to "Ctvrtletni+zprava.pdf", "owner" to "42"))
                    .build(),
                Fixtures.PDF,
            )

            val content = backend.get("docs/key.pdf")

            assertEquals(BUCKET, requestSlot.captured.bucket())
            assertEquals("docs/key.pdf", requestSlot.captured.key())
            assertEquals("Ctvrtletni zprava.pdf", content.metadata.fileName, "the file name must round-trip")
            assertEquals("application/pdf", content.metadata.contentType)
            assertEquals("etag", content.metadata.checksum)
            assertEquals(mapOf("owner" to "42"), content.metadata.metadata, "reserved entries must be hidden")
            assertEquals(Fixtures.PDF.size, content.use { it.stream.readBytes() }.size)
        }

        @Test
        fun `should map a missing key to FileNotFoundStorageException`() {
            every { s3.getObject(any<GetObjectRequest>()) } throws NoSuchKeyException.builder().statusCode(404).build()

            assertThrows<FileNotFoundStorageException> { backend.get("missing.pdf") }
        }

        @Test
        fun `should map a 404 without a typed exception to FileNotFoundStorageException`() {
            every { s3.getObject(any<GetObjectRequest>()) } throws s3Exception(404)

            assertThrows<FileNotFoundStorageException> { backend.get("missing.pdf") }
        }

        @Test
        fun `should read head metadata`() {
            every { s3.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder()
                .contentType("application/pdf")
                .contentLength(123)
                .eTag("\"etag\"")
                .build()

            val metadata = backend.head("docs/key.pdf")

            assertEquals(123, metadata.size)
            assertNull(metadata.fileName)
        }
    }

    @Nested
    inner class Existence {

        @Test
        fun `should return true when head succeeds`() {
            every { s3.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder().build()

            assertTrue(backend.exists("docs/key.pdf"))
        }

        @Test
        fun `should return false for a typed missing key`() {
            every { s3.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder().statusCode(404).build()

            assertFalse(backend.exists("missing.pdf"))
        }

        @Test
        fun `should return false for a bare 404`() {
            every { s3.headObject(any<HeadObjectRequest>()) } throws s3Exception(404)

            assertFalse(backend.exists("missing.pdf"))
        }

        @Test
        fun `should propagate other failures`() {
            every { s3.headObject(any<HeadObjectRequest>()) } throws s3Exception(500)

            assertThrows<FileStorageException> { backend.exists("docs/key.pdf") }
        }
    }

    @Nested
    inner class Deleting {

        @Test
        fun `should report that an existing object was deleted`() {
            every { s3.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder().build()
            val deleteSlot = slot<DeleteObjectRequest>()
            every { s3.deleteObject(capture(deleteSlot)) } returns DeleteObjectResponse.builder().build()

            assertTrue(backend.delete("docs/key.pdf"))
            assertEquals(BUCKET, deleteSlot.captured.bucket())
            assertEquals("docs/key.pdf", deleteSlot.captured.key())
        }

        @Test
        fun `should report false for an absent object but still be idempotent`() {
            every { s3.headObject(any<HeadObjectRequest>()) } throws s3Exception(404)
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

            assertFalse(backend.delete("missing.pdf"))
        }

        @Test
        fun `should batch deletes at the S3 limit of 1000 keys`() {
            val requests = mutableListOf<DeleteObjectsRequest>()
            every { s3.deleteObjects(capture(requests)) } returns DeleteObjectsResponse.builder().build()

            backend.deleteAll((1..2_500).map { "docs/$it.pdf" })

            assertEquals(3, requests.size)
            assertEquals(listOf(1000, 1000, 500), requests.map { it.delete().objects().size })
        }

        @Test
        fun `should do nothing for an empty batch`() {
            backend.deleteAll(emptyList())

            verify(exactly = 0) { s3.deleteObjects(any<DeleteObjectsRequest>()) }
        }

        @Test
        fun `should surface per-key failures from a batch delete`() {
            every { s3.deleteObjects(any<DeleteObjectsRequest>()) } returns DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("docs/a.pdf").code("AccessDenied").message("nope").build())
                .build()

            val thrown = assertThrows<FileStorageException> { backend.deleteAll(listOf("docs/a.pdf")) }

            assertTrue(thrown.message!!.contains("docs/a.pdf"))
            assertTrue(thrown.message!!.contains("AccessDenied"))
        }
    }

    @Nested
    inner class Listing {

        @Test
        fun `should request a single bounded page and return the continuation token`() {
            val requestSlot = slot<ListObjectsV2Request>()
            every { s3.listObjectsV2(capture(requestSlot)) } returns ListObjectsV2Response.builder()
                .contents(
                    S3Object.builder().key("docs/a.pdf").build(),
                    S3Object.builder().key("docs/b.pdf").build(),
                )
                .nextContinuationToken("token-2")
                .build()

            val page = backend.list("docs/", limit = 2, pageToken = "token-1", delimiter = null)

            assertEquals(listOf("docs/a.pdf", "docs/b.pdf"), page.keys)
            assertEquals("token-2", page.nextPageToken)
            assertTrue(page.hasMore)
            assertEquals(BUCKET, requestSlot.captured.bucket())
            assertEquals("docs/", requestSlot.captured.prefix())
            assertEquals(2, requestSlot.captured.maxKeys())
            assertEquals("token-1", requestSlot.captured.continuationToken())
        }

        @Test
        fun `should report the last page`() {
            every { s3.listObjectsV2(any<ListObjectsV2Request>()) } returns
                ListObjectsV2Response.builder().contents(S3Object.builder().key("docs/a.pdf").build()).build()

            val page = backend.list("docs/", limit = 10, pageToken = null, delimiter = null)

            assertNull(page.nextPageToken)
            assertFalse(page.hasMore)
        }

        @Test
        fun `should roll nested keys into common prefixes when a delimiter is given`() {
            val requestSlot = slot<ListObjectsV2Request>()
            every { s3.listObjectsV2(capture(requestSlot)) } returns ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("docs/loose.pdf").build())
                .commonPrefixes(
                    CommonPrefix.builder().prefix("docs/2026/").build(),
                    CommonPrefix.builder().prefix("docs/2025/").build(),
                )
                .build()

            val page = backend.list("docs/", limit = 100, pageToken = null, delimiter = "/")

            assertEquals("/", requestSlot.captured.delimiter())
            assertEquals(listOf("docs/loose.pdf"), page.keys)
            assertEquals(listOf("docs/2026/", "docs/2025/"), page.commonPrefixes)
        }

        @Test
        fun `should report no common prefixes without a delimiter`() {
            val requestSlot = slot<ListObjectsV2Request>()
            every { s3.listObjectsV2(capture(requestSlot)) } returns
                ListObjectsV2Response.builder().contents(S3Object.builder().key("docs/a.pdf").build()).build()

            val page = backend.list("docs/", limit = 100, pageToken = null, delimiter = null)

            assertNull(requestSlot.captured.delimiter())
            assertTrue(page.commonPrefixes.isEmpty())
        }

        @Test
        fun `should reject a non-positive limit`() {
            assertThrows<IllegalArgumentException> { backend.list("docs/", limit = 0, pageToken = null, delimiter = null) }
        }
    }

    @Nested
    inner class Copying {

        @Test
        fun `should copy a small object in one request`() {
            every { s3.headObject(any<HeadObjectRequest>()) } returns
                HeadObjectResponse.builder().contentLength(1_024).contentType("application/pdf").build()
            val copySlot = slot<CopyObjectRequest>()
            every { s3.copyObject(capture(copySlot)) } returns CopyObjectResponse.builder().build()

            backend.copy("active/file.pdf", "archive/file.pdf")

            val copy = copySlot.captured
            assertEquals(BUCKET, copy.sourceBucket())
            assertEquals("active/file.pdf", copy.sourceKey())
            assertEquals(BUCKET, copy.destinationBucket())
            assertEquals("archive/file.pdf", copy.destinationKey())
            verify(exactly = 0) { s3.deleteObject(any<DeleteObjectRequest>()) }
        }

        @Test
        fun `should map a missing source to FileNotFoundStorageException`() {
            every { s3.headObject(any<HeadObjectRequest>()) } throws
                NoSuchKeyException.builder().statusCode(404).build()

            assertThrows<FileNotFoundStorageException> { backend.copy("missing.pdf", "archive/file.pdf") }
        }
    }

    @Nested
    inner class Presigning {

        @Test
        fun `should presign a get url with the requested ttl`() {
            val presignSlot = slot<GetObjectPresignRequest>()
            val presigned = mockk<PresignedGetObjectRequest>()
            every { presigned.url() } returns URI.create("http://localhost/$BUCKET/docs/key.pdf?sig=x").toURL()
            every { presigner.presignGetObject(capture(presignSlot)) } returns presigned

            val uri = backend.presignGet("docs/key.pdf", Duration.ofMinutes(5), downloadAs = null)

            assertEquals("http://localhost/$BUCKET/docs/key.pdf?sig=x", uri.toString())
            assertEquals(Duration.ofMinutes(5), presignSlot.captured.signatureDuration())
            assertEquals("docs/key.pdf", presignSlot.captured.getObjectRequest().key())
            assertEquals(BUCKET, presignSlot.captured.getObjectRequest().bucket())
            assertNull(presignSlot.captured.getObjectRequest().responseContentDisposition())
        }

        @Test
        fun `should set Content-Disposition when a download name is requested`() {
            val presignSlot = slot<GetObjectPresignRequest>()
            val presigned = mockk<PresignedGetObjectRequest>()
            every { presigned.url() } returns URI.create("http://localhost/x").toURL()
            every { presigner.presignGetObject(capture(presignSlot)) } returns presigned

            backend.presignGet("docs/key.pdf", Duration.ofMinutes(5), downloadAs = "report.pdf")

            assertEquals(
                """attachment; filename="report.pdf"; filename*=UTF-8''report.pdf""",
                presignSlot.captured.getObjectRequest().responseContentDisposition(),
            )
        }

        @Test
        fun `should encode a non-ascii download name per RFC 5987`() {
            val presignSlot = slot<GetObjectPresignRequest>()
            val presigned = mockk<PresignedGetObjectRequest>()
            every { presigned.url() } returns URI.create("http://localhost/x").toURL()
            every { presigner.presignGetObject(capture(presignSlot)) } returns presigned

            backend.presignGet("docs/key.pdf", Duration.ofMinutes(5), downloadAs = "zpráva.pdf")

            val disposition = presignSlot.captured.getObjectRequest().responseContentDisposition()!!
            assertTrue(disposition.contains("""filename="zpr_va.pdf""""), disposition)
            assertTrue(disposition.contains("filename*=UTF-8''zpr%C3%A1va.pdf"), disposition)
        }

        @Test
        fun `should presign a put url`() {
            val presignSlot = slot<PutObjectPresignRequest>()
            val presigned = mockk<PresignedPutObjectRequest>()
            every { presigned.url() } returns URI.create("http://localhost/put").toURL()
            every { presigner.presignPutObject(capture(presignSlot)) } returns presigned

            val uri = backend.presignPut("docs/key.pdf", "application/pdf", Duration.ofMinutes(2))

            assertEquals("http://localhost/put", uri.toString())
            assertEquals(Duration.ofMinutes(2), presignSlot.captured.signatureDuration())
            assertEquals("application/pdf", presignSlot.captured.putObjectRequest().contentType())
            assertEquals("docs/key.pdf", presignSlot.captured.putObjectRequest().key())
        }
    }

    @Test
    fun `should identify itself as the s3 provider`() {
        assertEquals("s3", backend.providerName)
    }

    private fun responseStream(response: GetObjectResponse, bytes: ByteArray) =
        ResponseInputStream(response, AbortableInputStream.create(ByteArrayInputStream(bytes)))

    private fun s3Exception(statusCode: Int): S3Exception =
        S3Exception.builder()
            .statusCode(statusCode)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("TestError").build())
            .build() as S3Exception

    private companion object {
        const val BUCKET = "test-bucket"
    }
}
