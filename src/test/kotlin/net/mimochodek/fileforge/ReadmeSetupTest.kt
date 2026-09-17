package net.mimochodek.fileforge

import net.mimochodek.fileforge.autoconfigure.FileForgeAutoConfiguration
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.spi.StorageBackend
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.web.store
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockMultipartFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the exact configuration the README advertises under
 * "Ready-made setup: documents and photos" and stores the exact upload mix it
 * promises to handle.
 *
 * The point is to keep the README honest. A copy-paste block that nobody
 * executes drifts from the code silently, and the reader has no way to tell -
 * they only find out when their own upload is rejected.
 */
class ReadmeSetupTest {

    /**
     * Property-for-property the README's `application.yaml`, minus the S3
     * connection settings, which the in-memory backend replaces.
     */
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileForgeAutoConfiguration::class.java))
        .withUserConfiguration(Backend::class.java)
        .withPropertyValues(
            "fileforge.validation.max-file-size=20MB",
            "fileforge.validation.allowed-mime-types=" + listOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "application/pdf",
                DOCX,
                XLSX,
            ).joinToString(","),
            "fileforge.profiles.photos.folder=photos",
            "fileforge.profiles.photos.validation.max-file-size=10MB",
            "fileforge.profiles.photos.validation.allowed-mime-types=image/jpeg,image/png,image/webp",
            "fileforge.profiles.attachments.folder=attachments",
            "fileforge.profiles.attachments.validation.max-file-size=20MB",
        )

    @Test
    fun `should start and publish FileStorage`() {
        runner.run { context ->
            context.startupFailure?.let { throw it }

            assertEquals(1, context.getBeanNamesForType(FileStorage::class.java).size)
        }
    }

    @Test
    fun `should accept every file type the README lists, via the attachments profile`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val cases = listOf(
                Triple("photo.jpg", "image/jpeg", Fixtures.JPEG),
                Triple("logo.png", "image/png", Fixtures.PNG),
                Triple("invoice.pdf", "application/pdf", Fixtures.PDF),
                Triple("report.docx", DOCX, Fixtures.ZIP),
                Triple("budget.xlsx", XLSX, Fixtures.ZIP),
            )

            cases.forEach { (name, type, bytes) ->
                val stored = storage.store(MockMultipartFile("file", name, type, bytes), profile = "attachments")

                assertTrue(stored.key.startsWith("attachments/"), "$name landed at ${stored.key}")
                assertEquals(type, stored.contentType, "content type of $name")
            }
        }
    }

    @Test
    fun `should route photos into their own folder`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val stored = storage.store(
                MockMultipartFile("file", "holiday photo.jpg", "image/jpeg", Fixtures.JPEG),
                profile = "photos",
            )

            assertTrue(stored.key.startsWith("photos/"), stored.key)
            assertEquals("holiday_photo.jpg", stored.fileName)
        }
    }

    @Test
    fun `should keep documents out of the photos profile`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            // The photos profile narrows the whitelist to images only.
            assertThrows<UnsupportedFileTypeException> {
                storage.store(MockMultipartFile("file", "invoice.pdf", "application/pdf", Fixtures.PDF), profile = "photos")
            }
        }
    }

    @Test
    fun `should apply the per-profile size limits the README advertises`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)
            val twelveMegabytes = Fixtures.JPEG + ByteArray(12 * 1024 * 1024)

            // 12 MB is within the global and attachments limit of 20MB...
            val stored = storage.store(
                MockMultipartFile("file", "huge.jpg", "image/jpeg", twelveMegabytes),
                profile = "attachments",
            )
            assertTrue(stored.key.startsWith("attachments/"))

            // ...but over the photos limit of 10MB.
            val thrown = assertThrows<FileSizeExceededException> {
                storage.store(MockMultipartFile("file", "huge.jpg", "image/jpeg", twelveMegabytes), profile = "photos")
            }
            assertEquals(10 * 1024 * 1024L, thrown.maxSize)
        }
    }

    @Test
    fun `should let the attachments profile inherit the global whitelist`() {
        // The README claims the attachments profile sets only a size and therefore
        // "inherits the MIME whitelist above". If inheritance broke, this would fail.
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val stored = storage.store(MockMultipartFile("file", "report.docx", DOCX, Fixtures.ZIP), profile = "attachments")

            assertEquals(DOCX, stored.contentType)
        }
    }

    @Test
    fun `should detect Office types even when the client declares none`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)

            val docx = storage.store(MockMultipartFile("file", "report.docx", null, Fixtures.ZIP), profile = "attachments")
            val xlsx = storage.store(MockMultipartFile("file", "budget.xlsx", null, Fixtures.ZIP), profile = "attachments")

            assertEquals(DOCX, docx.contentType)
            assertEquals(XLSX, xlsx.contentType)
        }
    }

    @Test
    fun `should read back the original file name without a database column for it`() {
        runner.run { context ->
            val storage = context.getBean(FileStorage::class.java)
            val stored = storage.store(
                MockMultipartFile("file", "Quarterly Report.pdf", "application/pdf", Fixtures.PDF),
                profile = "attachments",
            )

            // This is what lets a consumer persist only the key.
            val metadata = storage.metadata(stored.key)

            assertEquals("Quarterly_Report.pdf", metadata.fileName)
            assertEquals("application/pdf", metadata.contentType)
            assertEquals(Fixtures.PDF.size.toLong(), metadata.size)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Backend {
        @Bean
        fun storageBackend(): StorageBackend = InMemoryStorageBackend()
    }

    private companion object {
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
