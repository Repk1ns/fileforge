package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.InvalidFileNameException

/** File-name sanitization, shared by every backend. */
public object FileNames {

    private val WHITESPACE = Regex("""\s+""")
    private val UNSAFE = Regex("""[^A-Za-z0-9._-]""")

    /**
     * Strips path components, collapses whitespace to `_` and removes unsafe
     * characters, so the result can never escape its prefix.
     *
     * @throws InvalidFileNameException when nothing usable remains.
     */
    public fun sanitize(fileName: String): String {
        val baseName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val sanitized = baseName
            .replace(WHITESPACE, "_")
            .replace(UNSAFE, "")
            .trimStart('.')
        if (sanitized.isEmpty() || sanitized.all { it == '.' }) {
            throw InvalidFileNameException(fileName)
        }
        return sanitized
    }

    /** The lowercase extension of [fileName] without the dot, or `null` when it has none. */
    public fun extensionOf(fileName: String): String? =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
}
