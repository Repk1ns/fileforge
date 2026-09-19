package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.spi.StorageBackend
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.testsupport.storeBytes
import net.mimochodek.fileforge.validation.UnknownTypePolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Profiles end to end: bound from configuration, then actually enforced.
 */
class ProfilePropertiesTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileForgeAutoConfiguration::class.java))
        .withUserConfiguration(Backend::class.java)
        .withPropertyValues(
            "fileforge.validation.max-file-size=50MB",
            "fileforge.validation.allowed-mime-types=application/pdf,image/jpeg,image/png",
            "fileforge.profiles.documents.folder=documents",
            "fileforge.profiles.documents.validation.max-file-size=2KB",
            "fileforge.profiles.documents.validation.allowed-mime-types=application/pdf",
            "fileforge.profiles.avatars.folder=avatars",
            "fileforge.profiles.thumbnails.folder=thumbnails",
        )

    @Test
    fun `should bind the profile map`() {
        runner.run { context ->
            context.startupFailure?.let { throw it }
            val profiles = context.getBean(FileForgeProperties::class.java).profiles

            assertEquals(setOf("documents", "avatars", "thumbnails"), profiles.keys)
            assertEquals("documents", profiles.getValue("documents").folder)
            assertEquals(2048, profiles.getValue("documents").validation.maxFileSize?.toBytes())
            assertEquals(listOf("application/pdf"), profiles.getValue("documents").validation.allowedMimeTypes)
            assertTrue(profiles.getValue("thumbnails").validation.isEmpty())
        }
    }

    @Test
    fun `should route an upload into the profile folder`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val stored = storage.storeBytes("report.pdf", Fixtures.PDF, profile = "documents")

            assertTrue(stored.key.startsWith("documents/"), stored.key)
        }
    }

    @Test
    fun `should enforce the profile size limit, not the global one`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)
            val payload = Fixtures.PDF + ByteArray(4_096)

            // Well within the global 50MB, over the profile's 2KB.
            val thrown = assertThrows<FileSizeExceededException> {
                storage.storeBytes("report.pdf", payload, profile = "documents")
            }

            assertEquals(2048, thrown.maxSize)
        }
    }

    @Test
    fun `should enforce the profile whitelist, not the global one`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            // image/jpeg is globally allowed but not allowed in the documents profile.
            val thrown = assertThrows<UnsupportedFileTypeException> {
                storage.storeBytes("photo.jpg", Fixtures.JPEG, profile = "documents")
            }

            assertEquals(setOf("application/pdf"), thrown.allowedTypes)
        }
    }

    @Test
    fun `should inherit unset fields from the global policy rather than the library defaults`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileForgeAutoConfiguration::class.java))
            .withUserConfiguration(Backend::class.java)
            .withPropertyValues(
                // Globally permissive, and a profile that narrows only the size.
                "fileforge.validation.max-file-size=50MB",
                "fileforge.validation.allowed-mime-types=application/pdf,image/jpeg",
                "fileforge.validation.unknown-type-policy=reject",
                "fileforge.profiles.small.folder=small",
                "fileforge.profiles.small.validation.max-file-size=2KB",
            )
            .run { context ->
                context.startupFailure?.let { throw it }
                val storage = context.getBean(FileStorage::class.java)
                val properties = context.getBean(FileForgeProperties::class.java)
                val merged = properties.profiles.getValue("small").validation.mergedWith(properties.validation)

                assertEquals(2048, merged.maxFileSize.toBytes())
                assertEquals(listOf("application/pdf", "image/jpeg"), merged.allowedMimeTypes)
                assertEquals(UnknownTypePolicy.REJECT, merged.unknownTypePolicy)

                // image/jpeg is inherited from the global whitelist, so this passes.
                val stored = storage.storeBytes("photo.jpg", Fixtures.JPEG, profile = "small")
                assertTrue(stored.key.startsWith("small/"), stored.key)
            }
    }

    @Test
    fun `should apply the global policy to a folder-only profile`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val stored = storage.store(
                FileUpload("photo.jpg", ContentSource.Bytes(Fixtures.JPEG), profile = "avatars"),
            )

            assertTrue(stored.key.startsWith("avatars/"), stored.key)
        }
    }

    @Test
    fun `should default to no profiles`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileForgeAutoConfiguration::class.java))
            .withUserConfiguration(Backend::class.java)
            .run { context ->
                assertTrue(context.getBean(FileForgeProperties::class.java).profiles.isEmpty())
            }
    }

    @Configuration(proxyBeanMethods = false)
    class Backend {
        @Bean
        fun storageBackend(): StorageBackend = InMemoryStorageBackend()
    }
}
