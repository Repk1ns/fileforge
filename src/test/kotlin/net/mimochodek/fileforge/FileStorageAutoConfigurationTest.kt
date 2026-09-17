package net.mimochodek.fileforge

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the starter contract itself: the `AutoConfiguration.imports` entry,
 * the activation conditions, property binding and bean back-off.
 */
class FileStorageAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileStorageAutoConfiguration::class.java))

    private fun AssertableApplicationContext.hasSingle(type: Class<*>): Boolean =
        getBeanNamesForType(type).size == 1

    @Test
    fun `should register every bean when the endpoint is configured`() {
        runner.withPropertyValues(*FULL_CONFIG).run { context ->
            assertNotNull(context.startupFailure?.let { throw it } ?: Unit)
            assertTrue(context.hasSingle(S3Client::class.java))
            assertTrue(context.hasSingle(S3Presigner::class.java))
            assertTrue(context.hasSingle(FileValidationService::class.java))
            assertTrue(context.hasSingle(FileStorageService::class.java))
            assertTrue(context.getBean(FileStorageService::class.java) is S3FileStorageService)
        }
    }

    @Test
    fun `should back off entirely when the endpoint is not configured`() {
        runner.withPropertyValues(
            "fileforge.s3.bucket=my-bucket",
            "fileforge.s3.access-key=access",
            "fileforge.s3.secret-key=secret",
        ).run { context ->
            assertFalse(context.containsBean("fileStorageService"))
            assertTrue(context.getBeanNamesForType(FileStorageService::class.java).isEmpty())
            assertTrue(context.getBeanNamesForType(S3Client::class.java).isEmpty())
        }
    }

    @Test
    fun `should bind the configuration properties`() {
        runner.withPropertyValues(
            *FULL_CONFIG,
            "fileforge.s3.region=eu-central-1",
            "fileforge.s3.max-file-size-bytes=1024",
            "fileforge.s3.allowed-mime-types=application/pdf,image/png",
        ).run { context ->
            val properties = context.getBean(FileStorageProperties::class.java)

            assertEquals("http://localhost:7070", properties.endpoint)
            assertEquals("eu-central-1", properties.region)
            assertEquals("my-bucket", properties.bucket)
            assertEquals("access", properties.accessKey)
            assertEquals("secret", properties.secretKey)
            assertEquals(1024, properties.maxFileSizeBytes)
            assertEquals(listOf("application/pdf", "image/png"), properties.allowedMimeTypes)
        }
    }

    @Test
    fun `should apply property defaults`() {
        runner.withPropertyValues(*FULL_CONFIG).run { context ->
            val properties = context.getBean(FileStorageProperties::class.java)

            assertEquals("us-east-1", properties.region)
            assertEquals(52_428_800, properties.maxFileSizeBytes)
            assertEquals(
                listOf("image/jpeg", "image/png", "image/webp", "application/pdf"),
                properties.allowedMimeTypes,
            )
        }
    }

    @Test
    fun `should fail fast when a required property is missing`() {
        runner.withPropertyValues(
            "fileforge.s3.endpoint=http://localhost:7070",
            "fileforge.s3.access-key=access",
            "fileforge.s3.secret-key=secret",
        ).run { context ->
            assertNotNull(context.startupFailure, "a missing bucket must fail the context")
        }
    }

    @Test
    fun `should be registered in the AutoConfiguration imports file`() {
        val imports = javaClass
            .getResourceAsStream("/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            ?.use { it.readBytes().decodeToString() }

        assertNotNull(imports, "the AutoConfiguration.imports resource is missing")
        assertEquals(
            listOf(FileStorageAutoConfiguration::class.java.name),
            imports.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") },
        )
    }

    @Test
    fun `should back off from every bean the consumer defines`() {
        runner.withPropertyValues(*FULL_CONFIG)
            .withUserConfiguration(CustomBeans::class.java)
            .run { context ->
                assertSame(CustomBeans.storageService, context.getBean(FileStorageService::class.java))
                assertSame(CustomBeans.validationService, context.getBean(FileValidationService::class.java))
                assertTrue(context.hasSingle(FileStorageService::class.java))
                assertTrue(context.hasSingle(FileValidationService::class.java))
            }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomBeans {

        @Bean
        fun fileStorageService(): FileStorageService = storageService

        @Bean
        fun fileValidationService(): FileValidationService = validationService

        companion object {
            val storageService: FileStorageService = io.mockk.mockk()
            val validationService: FileValidationService = io.mockk.mockk()
        }
    }

    private companion object {
        val FULL_CONFIG = arrayOf(
            "fileforge.s3.endpoint=http://localhost:7070",
            "fileforge.s3.bucket=my-bucket",
            "fileforge.s3.access-key=access",
            "fileforge.s3.secret-key=secret",
        )
    }
}
