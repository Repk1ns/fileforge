package net.mimochodek.fileforge.validation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The library takes nothing from a caller-supplied name except its extension -
 * the key is generated and the name is not stored - so this is the whole
 * surface, and the extension has to be safe on its own.
 */
class FileNamesTest {

    @Test
    fun `should lowercase the extension`() {
        assertEquals("pdf", FileNames.extensionOf("Report.PDF"))
    }

    @Test
    fun `should take the last extension`() {
        assertEquals("gz", FileNames.extensionOf("archive.tar.gz"))
    }

    @Test
    fun `should accept a long but real extension`() {
        assertEquals("webmanifest", FileNames.extensionOf("site.webmanifest"))
    }

    @Test
    fun `should report no extension`() {
        assertNull(FileNames.extensionOf("README"))
        assertNull(FileNames.extensionOf(""))
    }

    @Test
    fun `should refuse anything that could not be an extension`() {
        // This is the only part of a name that reaches the object key, and the
        // name arrives exactly as the caller supplied it - nothing sanitizes it
        // first, so these must not slip through.
        assertNull(FileNames.extensionOf("a.pdf/../../../evil"))
        assertNull(FileNames.extensionOf("x.jpg?acl=public"))
        assertNull(FileNames.extensionOf("y.png/../secret"))
        assertNull(FileNames.extensionOf("w." + "a".repeat(40)))
    }

    @Test
    fun `should keep a hostile name out of the key entirely`() {
        // Belt and braces: whatever the name, only the extension can travel.
        assertNull(FileNames.extensionOf("../../etc/passwd"))
    }
}
