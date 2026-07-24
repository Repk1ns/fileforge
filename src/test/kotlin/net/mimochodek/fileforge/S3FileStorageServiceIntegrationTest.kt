package net.mimochodek.fileforge

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full round-trip integration test against a real S3-compatible endpoint
 * (`versity/versitygw`) with `forcePathStyle(true)`.
 *
 * Requires a running Docker daemon; the test is skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class S3FileStorageServiceIntegrationTest {

    private val accessKey = "testAccessKey"
    private val secretKey = "testSecretKey"
    private val bucket = "integration-test-bucket"

    private val container = GenericContainer(DockerImageName.parse("versity/versitygw:latest"))
        .withExposedPorts(7070)
        .withEnv("ROOT_ACCESS_KEY", accessKey)
        .withEnv("ROOT_SECRET_KEY", secretKey)
        .withCommand("posix", "/tmp/vgw")
        .waitingFor(Wait.forListeningPort())

    private lateinit var s3Client: S3Client
    private lateinit var s3Presigner: S3Presigner
    private lateinit var service: S3FileStorageService

    private val objectKey = "docs/report.pdf"
    private val archivedKey = "archive/report.pdf"
    private val fileContent = "%PDF-1.7 integration test payload".toByteArray()

    @BeforeAll
    fun setUp() {
        container.start()
        val endpoint = "http://${container.host}:${container.getMappedPort(7070)}"
        val properties = FileStorageProperties(
            endpoint = endpoint,
            region = "us-east-1",
            bucket = bucket,
            accessKey = accessKey,
            secretKey = secretKey,
        )
        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey),
        )
        s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build()
        s3Presigner = S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(credentials)
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build(),
            )
            .build()
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        service = S3FileStorageService(s3Client, s3Presigner, properties)
    }

    @AfterAll
    fun tearDown() {
        if (::s3Client.isInitialized) s3Client.close()
        if (::s3Presigner.isInitialized) s3Presigner.close()
        container.stop()
    }

    @Test
    @Order(1)
    fun `should upload object`() {
        service.upload(objectKey, ByteArrayInputStream(fileContent), fileContent.size.toLong(), "application/pdf")
    }

    @Test
    @Order(2)
    fun `should report uploaded object as existing`() {
        assertTrue(service.exists(objectKey))
        assertFalse(service.exists("docs/missing.pdf"))
    }

    @Test
    @Order(3)
    fun `should download uploaded object`() {
        val downloaded = service.download(objectKey).use { it.readBytes() }
        assertEquals(fileContent.toList(), downloaded.toList())
    }

    @Test
    @Order(4)
    fun `should list objects by prefix`() {
        assertEquals(listOf(objectKey), service.list("docs/"))
        assertTrue(service.list("nonexistent/").isEmpty())
    }

    @Test
    @Order(5)
    fun `should presign get url`() {
        val url = service.presignGetUrl(objectKey, Duration.ofMinutes(5))
        val downloaded = url.openStream().use { it.readBytes() }
        assertEquals(fileContent.toList(), downloaded.toList())
    }

    @Test
    @Order(6)
    fun `should archive object`() {
        service.archive(objectKey, archivedKey)
        assertFalse(service.exists(objectKey))
        assertTrue(service.exists(archivedKey))
        val downloaded = service.download(archivedKey).use { it.readBytes() }
        assertEquals(fileContent.toList(), downloaded.toList())
    }

    @Test
    @Order(7)
    fun `should delete archived object`() {
        service.delete(archivedKey)
        assertFalse(service.exists(archivedKey))
    }

    @Test
    @Order(8)
    fun `should throw when downloading missing object`() {
        assertThrows<FileNotFoundStorageException> { service.download("does/not/exist.pdf") }
    }
}
