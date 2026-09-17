package net.mimochodek.fileforge

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
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
import java.time.Year
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full round-trip integration test against a real S3-compatible endpoint
 * (`versity/versitygw`) with `forcePathStyle(true)`.
 *
 * Every test is self-contained: it uploads its own fixture into its own folder,
 * so tests can run in isolation and in any order.
 *
 * Requires a running Docker daemon; the test is skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3FileStorageServiceIntegrationTest {

    private lateinit var s3Client: S3Client
    private lateinit var s3Presigner: S3Presigner
    private lateinit var service: S3FileStorageService

    private val fileContent = "%PDF-1.7 integration test payload".toByteArray()

    @BeforeAll
    fun setUp() {
        container.start()
        val endpoint = "http://${container.host}:${container.getMappedPort(S3_PORT)}"
        val properties = FileStorageProperties(
            endpoint = endpoint,
            region = REGION,
            bucket = BUCKET,
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
        )
        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY),
        )
        s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build()
        s3Presigner = S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build(),
            )
            .build()
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())
        service = S3FileStorageService(s3Client, s3Presigner, properties)
    }

    @AfterAll
    fun tearDown() {
        if (::s3Client.isInitialized) s3Client.close()
        if (::s3Presigner.isInitialized) s3Presigner.close()
        container.stop()
    }

    /** Uploads the fixture into a folder unique to the calling test. */
    private fun upload(folder: String, fileName: String = "report.pdf"): String =
        service.upload(
            "$folder/$fileName",
            ByteArrayInputStream(fileContent),
            fileContent.size.toLong(),
            "application/pdf",
        )

    private fun uniqueFolder(name: String) = "$name-${UUID.randomUUID()}"

    @Test
    fun `should upload object and return generated key`() {
        val folder = uniqueFolder("upload")
        val key = upload(folder)

        val year = Year.now().value
        assertTrue(
            Regex("$folder/${year}_$UUID_PATTERN\\.pdf").matches(key),
            "Unexpected generated key: $key",
        )
    }

    @Test
    fun `should generate a unique key per upload`() {
        val folder = uniqueFolder("unique")

        assertTrue(upload(folder) != upload(folder))
    }

    @Test
    fun `should report uploaded object as existing`() {
        val folder = uniqueFolder("exists")
        val key = upload(folder)

        assertTrue(service.exists(key))
        assertFalse(service.exists("$folder/missing.pdf"))
    }

    @Test
    fun `should download uploaded object`() {
        val key = upload(uniqueFolder("download"))

        val downloaded = service.download(key).use { it.readBytes() }

        assertEquals(fileContent.toList(), downloaded.toList())
    }

    @Test
    fun `should list objects by prefix`() {
        val folder = uniqueFolder("list")
        val key = upload(folder)

        assertEquals(listOf(key), service.list("$folder/"))
        assertTrue(service.list("${uniqueFolder("empty")}/").isEmpty())
    }

    @Test
    fun `should presign get url`() {
        val key = upload(uniqueFolder("presign"))

        val url = service.presignGetUrl(key, Duration.ofMinutes(5))

        val downloaded = url.openStream().use { it.readBytes() }
        assertEquals(fileContent.toList(), downloaded.toList())
    }

    @Test
    fun `should archive object`() {
        val folder = uniqueFolder("archive")
        val key = upload(folder)
        val archivedKey = "$folder/archived/report.pdf"

        service.archive(key, archivedKey)

        assertFalse(service.exists(key))
        assertTrue(service.exists(archivedKey))
        assertEquals(fileContent.toList(), service.download(archivedKey).use { it.readBytes() }.toList())
    }

    @Test
    fun `should delete object`() {
        val key = upload(uniqueFolder("delete"))

        service.delete(key)

        assertFalse(service.exists(key))
    }

    @Test
    fun `should throw when downloading missing object`() {
        assertThrows<FileNotFoundStorageException> { service.download("${uniqueFolder("missing")}/nope.pdf") }
    }

    private companion object {
        const val S3_PORT = 7070
        const val ACCESS_KEY = "testAccessKey"
        const val SECRET_KEY = "testSecretKey"
        const val BUCKET = "integration-test-bucket"
        const val REGION = "us-east-1"
        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        /**
         * Pinned image tag - `latest` would make the build non-reproducible.
         *
         * Readiness is probed over HTTP: an anonymous request to `/` is answered by
         * the gateway with 403 once the S3 API is actually serving, which a plain
         * listening-port check does not guarantee.
         */
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("versity/versitygw:v1.8.0"))
            .withExposedPorts(S3_PORT)
            .withEnv("ROOT_ACCESS_KEY", ACCESS_KEY)
            .withEnv("ROOT_SECRET_KEY", SECRET_KEY)
            .withCommand("posix", "/tmp/vgw")
            .waitingFor(
                HttpWaitStrategy()
                    .forPort(S3_PORT)
                    .forPath("/")
                    .forStatusCodeMatching { it in 200..499 }
                    .withStartupTimeout(Duration.ofSeconds(60)),
            )
    }
}
