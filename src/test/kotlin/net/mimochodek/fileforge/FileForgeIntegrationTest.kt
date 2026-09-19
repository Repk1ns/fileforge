package net.mimochodek.fileforge

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.internal.s3.S3StorageBackend
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.naming.DefaultObjectKeyGenerator
import net.mimochodek.fileforge.spi.BackendPutRequest
import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.validation.DefaultFileValidator
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
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full round-trip against a real S3-compatible endpoint (`versity/versitygw`).
 *
 * Every test is self-contained in its own folder, so tests run in isolation and
 * in any order - a failure no longer cascades through the rest of the class.
 *
 * Requires a running Docker daemon; skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileForgeIntegrationTest {

    private lateinit var client: S3Client
    private lateinit var presigner: S3Presigner
    private lateinit var backend: S3StorageBackend
    private lateinit var storage: DefaultFileStorage

    @BeforeAll
    fun setUp() {
        container.start()
        val endpoint = URI.create("http://${container.host}:${container.getMappedPort(S3_PORT)}")
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY))

        client = S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build()
        presigner = S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())

        backend = S3StorageBackend(client, presigner, BUCKET)
        storage = DefaultFileStorage(
            backend = backend,
            keyGenerator = DefaultObjectKeyGenerator(NamingStrategy.DATE_UUID),
            validator = DefaultFileValidator(MAX_SIZE, listOf("application/pdf", "image/jpeg")),
            maxFileSize = MAX_SIZE,
            inMemoryThreshold = 128,
        )
    }

    @AfterAll
    fun tearDown() {
        if (::backend.isInitialized) backend.close()
        container.stop()
    }

    private fun folder(name: String) = "$name-${UUID.randomUUID()}"

    private fun store(folder: String, fileName: String = "report.pdf") =
        storage.store(FileUpload(fileName, ContentSource.Bytes(Fixtures.PDF), folder = folder))

    @Test
    fun `should store and report everything the consumer persists`() {
        val folder = folder("store")

        val stored = store(folder)

        assertTrue(Regex("$folder/\\d{4}/\\d{2}/\\d{2}/$UUID_PATTERN\\.pdf").matches(stored.key), stored.key)
        assertEquals("report.pdf", stored.fileName)
        assertEquals("application/pdf", stored.contentType)
        assertEquals(Fixtures.PDF.size.toLong(), stored.size)
        assertTrue(!stored.checksum.isNullOrBlank(), "the backend should report a checksum")
    }

    @Test
    fun `should round-trip content and metadata`() {
        val stored = store(folder("read"))

        val metadata = storage.metadata(stored.key)

        assertEquals("report.pdf", metadata.fileName, "the original file name must survive the round trip")
        assertEquals("application/pdf", metadata.contentType)
        assertEquals(Fixtures.PDF.size.toLong(), metadata.size)
        assertContentEquals(Fixtures.PDF, storage.readBytes(stored.key))
    }

    @Test
    fun `should carry a name with diacritics all the way there and back`() {
        // The whole chain has to agree: sanitization must keep the characters, S3
        // object metadata must URL-encode them to survive an HTTP header, and
        // Content-Disposition must encode them per RFC 5987.
        val stored = storage.store(
            FileUpload("Čtvrtletní zpráva.pdf", ContentSource.Bytes(Fixtures.PDF), folder = folder("diacritics")),
        )

        assertEquals("Čtvrtletní_zpráva.pdf", stored.fileName)
        assertEquals("Čtvrtletní_zpráva.pdf", storage.metadata(stored.key).fileName)

        val url = storage.downloadUrl(stored.key, Duration.ofMinutes(5), downloadAs = stored.fileName)
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            val disposition = connection.getHeaderField("Content-Disposition")!!
            assertTrue(
                disposition.contains("filename*=UTF-8''%C4%8Ctvrtletn%C3%AD_zpr%C3%A1va.pdf"),
                "got: $disposition",
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `should store a stream that spills to disk`() {
        val payload = Fixtures.PDF + ByteArray(4_096) { (it % 251).toByte() }

        val stored = storage.store(
            FileUpload("big.pdf", ContentSource.Stream(ByteArrayInputStream(payload), length = 1)),
        )

        assertEquals(payload.size.toLong(), stored.size)
        assertContentEquals(payload, storage.readBytes(stored.key))
    }

    @Test
    fun `should report existence`() {
        val folder = folder("exists")
        val stored = store(folder)

        assertTrue(storage.exists(stored.key))
        assertFalse(storage.exists("$folder/missing.pdf"))
    }

    @Test
    fun `should paginate a listing`() {
        val folder = folder("list")
        repeat(3) { store(folder) }

        val first = storage.list("$folder/", limit = 2)
        val second = storage.list("$folder/", limit = 2, pageToken = first.nextPageToken)

        assertEquals(2, first.keys.size)
        assertTrue(first.hasMore)
        assertEquals(1, second.keys.size)
        assertFalse(second.hasMore)
        assertEquals(3, (first.keys + second.keys).distinct().size)
    }

    @Test
    fun `should list one level with a delimiter`() {
        // Exact keys, because delimiter listing is about key shape rather than
        // about whichever layout the key generator happens to produce.
        val folder = folder("delimiter")
        putExactly("$folder/loose.pdf")
        putExactly("$folder/nested/deep.pdf")
        putExactly("$folder/other/deep.pdf")

        val page = storage.list("$folder/", delimiter = "/")

        assertEquals(listOf("$folder/loose.pdf"), page.keys, "only keys at this level are listed")
        assertEquals(
            listOf("$folder/nested/", "$folder/other/"),
            page.commonPrefixes.sorted(),
            "deeper keys roll up into prefixes",
        )
    }

    /** Writes straight to the backend, bypassing key generation. */
    private fun putExactly(key: String) {
        backend.put(
            BackendPutRequest(
                key = key,
                content = ByteArrayInputStream(Fixtures.PDF),
                contentLength = Fixtures.PDF.size.toLong(),
                contentType = "application/pdf",
            ),
        )
    }

    @Test
    fun `should upload a large object in parts`() {
        // S3 rejects a single PutObject above 5 GiB. Exercised here with the real
        // 5 MiB minimum part size against a real endpoint, rather than a 5 GiB fixture.
        val partSize = 5L * 1024 * 1024
        val multipart = S3StorageBackend(
            s3 = client,
            presigner = presigner,
            bucket = BUCKET,
            multipartThreshold = partSize,
            uploadPartSize = partSize,
        )
        val payload = Fixtures.PDF + ByteArray((partSize + 1_024).toInt()) { (it % 251).toByte() }
        val key = "${folder("multipart")}/big.pdf"

        val result = multipart.put(
            BackendPutRequest(
                key = key,
                content = ByteArrayInputStream(payload),
                contentLength = payload.size.toLong(),
                contentType = "application/pdf",
                fileName = "big.pdf",
            ),
        )

        assertEquals(payload.size.toLong(), result.size)
        assertEquals(payload.size.toLong(), storage.metadata(key).size)
        assertContentEquals(payload, storage.readBytes(key))
    }

    @Test
    fun `should serve a presigned download url`() {
        val stored = store(folder("presign"))

        val url = storage.downloadUrl(stored.key, Duration.ofMinutes(5))

        assertContentEquals(Fixtures.PDF, url.toURL().openStream().use { it.readBytes() })
    }

    @Test
    fun `should make a presigned url download under the original file name`() {
        val stored = store(folder("disposition"))

        val url = storage.downloadUrl(stored.key, Duration.ofMinutes(5), downloadAs = stored.fileName)

        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            val disposition = connection.getHeaderField("Content-Disposition")
            assertTrue(disposition != null && disposition.contains("report.pdf"), "got: $disposition")
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `should delete idempotently and report whether the object existed`() {
        val stored = store(folder("delete"))

        assertTrue(storage.delete(stored.key))
        assertFalse(storage.delete(stored.key))
    }

    @Test
    fun `should delete many objects in one batch`() {
        val folder = folder("delete-all")
        val keys = (1..3).map { store(folder).key }

        storage.deleteAll(keys)

        assertTrue(keys.none { storage.exists(it) })
        assertTrue(storage.list("$folder/").keys.isEmpty())
    }

    @Test
    fun `should reject an upload that violates the policy`() {
        assertThrows<UnsupportedFileTypeException> {
            storage.store(FileUpload("notes.txt", ContentSource.Bytes("plain text".toByteArray())))
        }
        assertThrows<ContentMismatchException> {
            storage.store(FileUpload("fake.pdf", ContentSource.Bytes(Fixtures.JPEG), "application/pdf"))
        }
        assertThrows<FileSizeExceededException> {
            storage.store(FileUpload("huge.pdf", ContentSource.Bytes(Fixtures.PDF + ByteArray(MAX_SIZE.toInt()))))
        }
    }

    @Test
    fun `should throw for a missing key`() {
        assertThrows<FileNotFoundStorageException> { storage.readBytes("${folder("missing")}/nope.pdf") }
        assertThrows<FileNotFoundStorageException> { storage.metadata("${folder("missing")}/nope.pdf") }
    }

    private companion object {
        const val S3_PORT = 7070
        const val ACCESS_KEY = "testAccessKey"
        const val SECRET_KEY = "testSecretKey"
        const val BUCKET = "integration-test-bucket"
        const val REGION = "us-east-1"
        const val MAX_SIZE = 64L * 1024
        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        /**
         * Pinned tag - `latest` would make the build non-reproducible.
         *
         * Readiness is probed over HTTP: the gateway answers an anonymous request
         * once the S3 API is actually serving, which an open port does not imply.
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
