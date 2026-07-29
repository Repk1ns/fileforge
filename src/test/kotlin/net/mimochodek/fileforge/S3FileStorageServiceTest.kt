package net.mimochodek.fileforge

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3FileStorageServiceTest {

    private val s3Client = mockk<S3Client>()
    private val s3Presigner = mockk<S3Presigner>()

    private val properties = FileStorageProperties(
        endpoint = "http://localhost:7070",
        region = "us-east-1",
        bucket = "test-bucket",
        accessKey = "access",
        secretKey = "secret",
    )

    private val service = S3FileStorageService(s3Client, s3Presigner, properties)

    @Test
    fun `should build correct put request on upload`() {
        val requestSlot = slot<PutObjectRequest>()
        every { s3Client.putObject(capture(requestSlot), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()

        val content = ByteArrayInputStream("hello".toByteArray())
        val generatedKey = service.upload("docs/file.pdf", content, 5, "application/pdf")

        val request = requestSlot.captured
        assertEquals("test-bucket", request.bucket())
        assertEquals(generatedKey, request.key())
        assertEquals("application/pdf", request.contentType())
        assertEquals(5L, request.contentLength())
    }

    @Test
    fun `should generate object key as year underscore uuid with original extension`() {
        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()

        val generatedKey = service.upload(
            "docs/photo.jpeg",
            ByteArrayInputStream("hello".toByteArray()),
            5,
            "image/jpeg",
        )

        val year = Year.now().value
        val uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        assertTrue(
            Regex("docs/${year}_$uuidPattern\\.jpeg").matches(generatedKey),
            "Unexpected generated key: $generatedKey",
        )
    }

    @Test
    fun `should generate unique object keys for repeated uploads`() {
        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()

        val first = service.upload("photo.png", ByteArrayInputStream(ByteArray(0)), 0, "image/png")
        val second = service.upload("photo.png", ByteArrayInputStream(ByteArray(0)), 0, "image/png")

        assertTrue(first != second)
    }

    @Test
    fun `should map s3 error on upload to FileStorageException`() {
        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws s3Exception(500)

        assertThrows<FileStorageException> {
            service.upload("docs/file.pdf", ByteArrayInputStream(ByteArray(0)), 0, "application/pdf")
        }
    }

    @Test
    fun `should build correct get request on download`() {
        val requestSlot = slot<GetObjectRequest>()
        val responseStream = ResponseInputStream(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(ByteArrayInputStream("data".toByteArray())),
        )
        every { s3Client.getObject(capture(requestSlot)) } returns responseStream

        val result = service.download("docs/file.pdf")

        assertEquals("data", result.readBytes().decodeToString())
        assertEquals("test-bucket", requestSlot.captured.bucket())
        assertEquals("docs/file.pdf", requestSlot.captured.key())
    }

    @Test
    fun `should map missing key on download to FileNotFoundStorageException`() {
        every { s3Client.getObject(any<GetObjectRequest>()) } throws
            NoSuchKeyException.builder().statusCode(404).build()

        assertThrows<FileNotFoundStorageException> { service.download("missing.pdf") }
    }

    @Test
    fun `should build correct delete request`() {
        val requestSlot = slot<DeleteObjectRequest>()
        every { s3Client.deleteObject(capture(requestSlot)) } returns DeleteObjectResponse.builder().build()

        service.delete("docs/file.pdf")

        assertEquals("test-bucket", requestSlot.captured.bucket())
        assertEquals("docs/file.pdf", requestSlot.captured.key())
    }

    @Test
    fun `should return true when head object succeeds`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder().build()

        assertTrue(service.exists("docs/file.pdf"))
    }

    @Test
    fun `should return false when head object reports missing key`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws
            NoSuchKeyException.builder().statusCode(404).build()

        assertFalse(service.exists("missing.pdf"))
    }

    @Test
    fun `should return false when head object returns 404 status`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws s3Exception(404)

        assertFalse(service.exists("missing.pdf"))
    }

    @Test
    fun `should map other head errors to FileStorageException`() {
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws s3Exception(500)

        assertThrows<FileStorageException> { service.exists("docs/file.pdf") }
    }

    @Test
    fun `should list keys with prefix`() {
        val requestSlot = slot<ListObjectsV2Request>()
        val iterable = mockk<ListObjectsV2Iterable>()
        every { s3Client.listObjectsV2Paginator(capture(requestSlot)) } returns iterable
        every { iterable.contents() } returns software.amazon.awssdk.core.pagination.sync.SdkIterable {
            mutableListOf(
                software.amazon.awssdk.services.s3.model.S3Object.builder().key("docs/a.pdf").build(),
                software.amazon.awssdk.services.s3.model.S3Object.builder().key("docs/b.pdf").build(),
            ).iterator()
        }

        val keys = service.list("docs/")

        assertEquals(listOf("docs/a.pdf", "docs/b.pdf"), keys)
        assertEquals("test-bucket", requestSlot.captured.bucket())
        assertEquals("docs/", requestSlot.captured.prefix())
    }

    @Test
    fun `should copy then delete on archive`() {
        val copySlot = slot<CopyObjectRequest>()
        val deleteSlot = slot<DeleteObjectRequest>()
        every { s3Client.copyObject(capture(copySlot)) } returns CopyObjectResponse.builder().build()
        every { s3Client.deleteObject(capture(deleteSlot)) } returns DeleteObjectResponse.builder().build()

        service.archive("active/file.pdf", "archive/file.pdf")

        val copy = copySlot.captured
        assertEquals("test-bucket", copy.sourceBucket())
        assertEquals("active/file.pdf", copy.sourceKey())
        assertEquals("test-bucket", copy.destinationBucket())
        assertEquals("archive/file.pdf", copy.destinationKey())
        assertEquals("active/file.pdf", deleteSlot.captured.key())
        verify(exactly = 1) { s3Client.copyObject(any<CopyObjectRequest>()) }
        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }

    @Test
    fun `should not delete source when copy fails`() {
        every { s3Client.copyObject(any<CopyObjectRequest>()) } throws s3Exception(500)

        assertThrows<FileStorageException> { service.archive("active/file.pdf", "archive/file.pdf") }

        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }

    @Test
    fun `should presign get url with requested ttl`() {
        val presignSlot = slot<GetObjectPresignRequest>()
        val presigned = mockk<PresignedGetObjectRequest>()
        every { presigned.url() } returns URI.create("http://localhost:7070/test-bucket/docs/file.pdf?sig=x").toURL()
        every { s3Presigner.presignGetObject(capture(presignSlot)) } returns presigned

        val url = service.presignGetUrl("docs/file.pdf", Duration.ofMinutes(5))

        assertEquals("http", url.protocol)
        assertEquals(Duration.ofMinutes(5), presignSlot.captured.signatureDuration())
        assertEquals("docs/file.pdf", presignSlot.captured.getObjectRequest().key())
        assertEquals("test-bucket", presignSlot.captured.getObjectRequest().bucket())
    }

    private fun s3Exception(statusCode: Int): S3Exception =
        S3Exception.builder()
            .statusCode(statusCode)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("TestError").build())
            .build() as S3Exception
}
