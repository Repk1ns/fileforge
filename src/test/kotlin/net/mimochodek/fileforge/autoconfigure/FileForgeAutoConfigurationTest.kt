package net.mimochodek.fileforge.autoconfigure

import io.mockk.mockk
import net.mimochodek.fileforge.DefaultFileStorage
import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.spi.FileValidator
import net.mimochodek.fileforge.spi.ObjectKeyGenerator
import net.mimochodek.fileforge.spi.StorageBackend
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.validation.UnknownTypePolicy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the starter contract: the `AutoConfiguration.imports` entries, the
 * activation conditions, property binding and bean back-off.
 *
 * This is the most valuable test a starter can have - it is the only thing that
 * checks what consumers actually depend on, and in 1.0.0 its absence hid a
 * `S3Client` bean that could never be constructed.
 */
class FileForgeAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                S3StorageAutoConfiguration::class.java,
                FileForgeAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `should wire the whole pipeline from a bucket alone`() {
        runner.withPropertyValues("fileforge.s3.bucket=my-bucket").run { context ->
            context.startupFailure?.let { throw it }

            assertEquals(1, context.getBeanNamesForType(StorageBackend::class.java).size)
            assertEquals(1, context.getBeanNamesForType(FileStorage::class.java).size)
            assertEquals(1, context.getBeanNamesForType(ObjectKeyGenerator::class.java).size)
            assertEquals(1, context.getBeanNamesForType(FileValidator::class.java).size)
            assertEquals("s3", context.getBean(StorageBackend::class.java).providerName)
        }
    }

    @Test
    fun `should not publish S3Client or S3Presigner beans`() {
        // A library must not contribute generically-typed infrastructure it owns:
        // in 1.x that silently crossed wires with a consumer's own S3 client.
        runner.withPropertyValues("fileforge.s3.bucket=my-bucket").run { context ->
            assertTrue(context.getBeanNamesForType(S3Client::class.java).isEmpty())
            assertTrue(context.getBeanNamesForType(S3Presigner::class.java).isEmpty())
        }
    }

    @Test
    fun `should work against real AWS without an endpoint or static credentials`() {
        // 1.x made `endpoint` the activation trigger and always used static
        // credentials, so AWS needed a fake endpoint and IAM roles were impossible.
        runner.withPropertyValues(
            "fileforge.s3.bucket=my-bucket",
            "fileforge.s3.region=eu-central-1",
            "fileforge.s3.path-style-access=false",
        ).run { context ->
            context.startupFailure?.let { throw it }

            val properties = context.getBean(S3Properties::class.java)
            assertNull(properties.endpoint)
            assertTrue(!properties.hasStaticCredentials)
            assertEquals(1, context.getBeanNamesForType(StorageBackend::class.java).size)
        }
    }

    @Test
    fun `should accept a self-hosted endpoint with static credentials`() {
        runner.withPropertyValues(
            "fileforge.s3.bucket=my-bucket",
            "fileforge.s3.endpoint=http://nas:7070",
            "fileforge.s3.access-key=access",
            "fileforge.s3.secret-key=secret",
        ).run { context ->
            context.startupFailure?.let { throw it }

            val properties = context.getBean(S3Properties::class.java)
            assertEquals("http://nas:7070", properties.endpoint.toString())
            assertTrue(properties.hasStaticCredentials)
        }
    }

    @Test
    fun `should back off when the provider is not s3`() {
        runner.withPropertyValues(
            "fileforge.provider=none",
            "fileforge.s3.bucket=my-bucket",
        ).run { context ->
            assertTrue(context.getBeanNamesForType(StorageBackend::class.java).isEmpty())
            assertTrue(
                context.getBeanNamesForType(FileStorage::class.java).isEmpty(),
                "the pipeline must not be published without a backend",
            )
        }
    }

    @Test
    fun `should fail fast with a readable message when the bucket is missing`() {
        runner.run { context ->
            val failure = assertNotNull(context.startupFailure, "a missing bucket must fail the context")

            assertTrue(
                failure.stackTraceToString().contains("fileforge.s3.bucket must be configured"),
                "unexpected failure: ${failure.stackTraceToString().take(500)}",
            )
        }
    }

    @Test
    fun `should reject access key without secret key`() {
        runner.withPropertyValues(
            "fileforge.s3.bucket=my-bucket",
            "fileforge.s3.access-key=access",
        ).run { context ->
            val failure = assertNotNull(context.startupFailure)

            assertTrue(
                failure.stackTraceToString().contains("must be set together"),
                "unexpected failure: ${failure.stackTraceToString().take(500)}",
            )
        }
    }

    @Test
    fun `should bind storage-agnostic policy outside the provider prefix`() {
        runner.withPropertyValues(
            "fileforge.s3.bucket=my-bucket",
            "fileforge.naming.strategy=year-uuid",
            "fileforge.naming.keep-extension=false",
            "fileforge.naming.zone=Europe/Prague",
            "fileforge.validation.max-file-size=2MB",
            "fileforge.validation.allowed-mime-types=application/pdf,image/png",
            "fileforge.validation.verify-magic-bytes=false",
            "fileforge.validation.unknown-type-policy=reject",
            "fileforge.buffering.in-memory-threshold=512KB",
        ).run { context ->
            context.startupFailure?.let { throw it }
            val properties = context.getBean(FileForgeProperties::class.java)

            assertEquals(NamingStrategy.YEAR_UUID, properties.naming.strategy)
            assertEquals(false, properties.naming.keepExtension)
            assertEquals(ZoneId.of("Europe/Prague"), properties.naming.zone)
            assertEquals(2 * 1024 * 1024L, properties.validation.maxFileSize.toBytes())
            assertEquals(listOf("application/pdf", "image/png"), properties.validation.allowedMimeTypes)
            assertEquals(false, properties.validation.verifyMagicBytes)
            assertEquals(UnknownTypePolicy.REJECT, properties.validation.unknownTypePolicy)
            assertEquals(512 * 1024L, properties.buffering.inMemoryThreshold.toBytes())
        }
    }

    @Test
    fun `should apply policy defaults`() {
        runner.withPropertyValues("fileforge.s3.bucket=my-bucket").run { context ->
            context.startupFailure?.let { throw it }
            val properties = context.getBean(FileForgeProperties::class.java)
            val s3 = context.getBean(S3Properties::class.java)

            assertEquals("s3", properties.provider)
            assertEquals(NamingStrategy.DATE_UUID, properties.naming.strategy)
            assertEquals(true, properties.naming.keepExtension)
            assertEquals(52_428_800, properties.validation.maxFileSize.toBytes())
            assertEquals(
                listOf("image/jpeg", "image/png", "image/webp", "application/pdf"),
                properties.validation.allowedMimeTypes,
            )
            assertEquals(UnknownTypePolicy.ALLOW, properties.validation.unknownTypePolicy)
            assertEquals("us-east-1", s3.region)
            assertEquals(true, s3.pathStyleAccess)
        }
    }

    @Test
    fun `should reject an empty mime whitelist`() {
        runner.withPropertyValues(
            "fileforge.s3.bucket=my-bucket",
            "fileforge.validation.allowed-mime-types=",
        ).run { context ->
            val failure = assertNotNull(context.startupFailure)

            assertTrue(
                failure.stackTraceToString().contains("must not be empty"),
                "unexpected failure: ${failure.stackTraceToString().take(500)}",
            )
        }
    }

    @Test
    fun `should let a consumer replace the backend and keep the pipeline`() {
        runner.withUserConfiguration(CustomBackend::class.java).run { context ->
            context.startupFailure?.let { throw it }

            assertSame(CustomBackend.backend, context.getBean(StorageBackend::class.java))
            assertEquals(1, context.getBeanNamesForType(FileStorage::class.java).size)
            // No fileforge.s3.bucket needed - the S3 backend never gets built.
            assertTrue(context.getBeanNamesForType(S3Properties::class.java).isEmpty())
        }
    }

    @Test
    fun `should back off from every replaceable bean`() {
        runner.withPropertyValues("fileforge.s3.bucket=my-bucket")
            .withUserConfiguration(CustomPolicy::class.java)
            .run { context ->
                context.startupFailure?.let { throw it }

                assertSame(CustomPolicy.keyGenerator, context.getBean(ObjectKeyGenerator::class.java))
                assertSame(CustomPolicy.validator, context.getBean(FileValidator::class.java))
                assertSame(CustomPolicy.storage, context.getBean(FileStorage::class.java))
            }
    }

    @Test
    fun `should register every auto-configuration in the imports file`() {
        val imports = javaClass
            .getResourceAsStream("/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            ?.use { it.readBytes().decodeToString() }

        assertNotNull(imports, "the AutoConfiguration.imports resource is missing")
        assertEquals(
            listOf(
                S3StorageAutoConfiguration::class.java.name,
                FileForgeAutoConfiguration::class.java.name,
                FileForgeWebAutoConfiguration::class.java.name,
            ),
            imports.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") },
        )
    }

    @Configuration(proxyBeanMethods = false)
    class CustomBackend {

        @Bean
        fun storageBackend(): StorageBackend = backend

        companion object {
            val backend: StorageBackend = InMemoryStorageBackend()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomPolicy {

        @Bean
        fun objectKeyGenerator(): ObjectKeyGenerator = keyGenerator

        @Bean
        fun fileValidator(): FileValidator = validator

        @Bean
        fun fileStorage(): FileStorage = storage

        companion object {
            val keyGenerator: ObjectKeyGenerator = ObjectKeyGenerator { "fixed-key" }
            val validator: FileValidator = FileValidator { }
            val storage: FileStorage = mockk()
        }
    }
}
