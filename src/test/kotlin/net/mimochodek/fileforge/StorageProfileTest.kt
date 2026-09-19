package net.mimochodek.fileforge

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.UnknownStorageProfileException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.naming.DefaultObjectKeyGenerator
import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.spi.FileCandidate
import net.mimochodek.fileforge.spi.FileValidator
import net.mimochodek.fileforge.testsupport.Fixtures
import net.mimochodek.fileforge.testsupport.InMemoryStorageBackend
import net.mimochodek.fileforge.testsupport.storeBytes
import net.mimochodek.fileforge.validation.DefaultFileValidator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Profiles replace the per-category folder concatenation and limit checks that
 * would otherwise be rewritten in every consuming application.
 */
class StorageProfileTest {

    private val backend = InMemoryStorageBackend()

    /** Records what it was asked to validate, so delegation can be asserted. */
    private class RecordingValidator : FileValidator {
        var calls: Int = 0
        override fun validate(candidate: FileCandidate) {
            calls++
        }
    }

    private val globalValidator = RecordingValidator()

    private fun storage(profiles: Map<String, StorageProfile>) = DefaultFileStorage(
        backend = backend,
        keyGenerator = DefaultObjectKeyGenerator(NamingStrategy.UUID),
        validator = globalValidator,
        maxFileSize = 1024 * 1024,
        profiles = profiles,
    )

    @Test
    fun `should apply the profile folder`() {
        val storage = storage(mapOf("documents" to StorageProfile(folder = "documents")))

        val stored = storage.store(FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), profile = "documents"))

        assertTrue(stored.key.startsWith("documents/"), stored.key)
    }

    @Test
    fun `should let an explicit folder win over the profile folder`() {
        val storage = storage(mapOf("documents" to StorageProfile(folder = "documents")))

        val stored = storage.store(
            FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), folder = "override", profile = "documents"),
        )

        assertTrue(stored.key.startsWith("override/"), stored.key)
    }

    @Test
    fun `should keep the global validator for a profile that only sets a folder`() {
        val storage = storage(mapOf("documents" to StorageProfile(folder = "documents")))

        storage.store(FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), profile = "documents"))

        assertEquals(1, globalValidator.calls, "a folder-only profile must not replace a custom validator")
    }

    @Test
    fun `should apply the profile validator instead of the global one`() {
        val storage = storage(
            mapOf(
                "avatars" to StorageProfile(
                    folder = "avatars",
                    validator = DefaultFileValidator(2_048, listOf("image/jpeg", "image/png")),
                    maxFileSize = 2_048,
                ),
            ),
        )

        storage.store(FileUpload("photo.jpg", ContentSource.Bytes(Fixtures.JPEG), profile = "avatars"))

        assertEquals(0, globalValidator.calls, "the profile validator must take over")
    }

    @Test
    fun `should enforce the profile whitelist`() {
        val storage = storage(
            mapOf(
                "avatars" to StorageProfile(
                    folder = "avatars",
                    validator = DefaultFileValidator(2_048, listOf("image/jpeg")),
                    maxFileSize = 2_048,
                ),
            ),
        )

        assertThrows<UnsupportedFileTypeException> {
            storage.store(FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), profile = "avatars"))
        }
        assertTrue(backend.objects.isEmpty())
    }

    @Test
    fun `should enforce the profile size cap while reading, not just when validating`() {
        val storage = storage(
            mapOf(
                "avatars" to StorageProfile(
                    folder = "avatars",
                    validator = DefaultFileValidator(64, listOf("application/pdf")),
                    maxFileSize = 64,
                ),
            ),
        )

        // Streamed, so the cap has to bound the read itself - the global 1 MiB
        // limit would otherwise let the whole payload be buffered first.
        val payload = Fixtures.PDF + ByteArray(4_096)

        assertThrows<FileSizeExceededException> {
            storage.store(
                FileUpload(
                    "report.pdf",
                    ContentSource.Stream(java.io.ByteArrayInputStream(payload)),
                    profile = "avatars",
                ),
            )
        }
        assertTrue(backend.objects.isEmpty())
    }

    @Test
    fun `should still verify content against its declared type under a profile`() {
        val storage = storage(
            mapOf(
                "avatars" to StorageProfile(
                    folder = "avatars",
                    validator = DefaultFileValidator(4_096, listOf("image/png")),
                    maxFileSize = 4_096,
                ),
            ),
        )

        assertThrows<ContentMismatchException> {
            storage.store(
                FileUpload("fake.png", ContentSource.Bytes(Fixtures.JPEG), "image/png", profile = "avatars"),
            )
        }
    }

    @Test
    fun `should reject an unknown profile and name the ones that exist`() {
        val storage = storage(
            mapOf(
                "documents" to StorageProfile(folder = "documents"),
                "avatars" to StorageProfile(folder = "avatars"),
            ),
        )

        val thrown = assertThrows<UnknownStorageProfileException> {
            storage.store(FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), profile = "document"))
        }

        assertEquals("document", thrown.profile)
        assertEquals(setOf("documents", "avatars"), thrown.knownProfiles)
        assertTrue(thrown.message!!.contains("avatars, documents"))
        assertTrue(backend.objects.isEmpty())
    }

    @Test
    fun `should report that no profiles are configured`() {
        val thrown = assertThrows<UnknownStorageProfileException> {
            storage(emptyMap()).store(
                FileUpload("report.pdf", ContentSource.Bytes(Fixtures.PDF), profile = "documents"),
            )
        }

        assertTrue(thrown.message!!.endsWith("configured profiles are none"), thrown.message)
    }

    @Test
    fun `should ignore profiles entirely when none is named`() {
        val storage = storage(mapOf("documents" to StorageProfile(folder = "documents")))

        val stored = storage.storeBytes("report.pdf", Fixtures.PDF, folder = "loose")

        assertTrue(stored.key.startsWith("loose/"), stored.key)
        assertEquals(1, globalValidator.calls)
    }

}
