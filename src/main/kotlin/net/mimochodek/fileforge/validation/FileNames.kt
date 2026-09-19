package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.exception.InvalidFileNameException

/** File-name sanitization, shared by every backend. */
public object FileNames {

    private val WHITESPACE = Regex("""\s+""")

    /** A dropped character between two spaces would otherwise leave `__`. */
    private val REPEATED_UNDERSCORE = Regex("_{2,}")

    /** Punctuation kept alongside letters and digits. */
    private const val KEPT_PUNCTUATION = "._-"

    /**
     * Longest name kept. Filesystems stop at 255, and the name also travels in
     * an HTTP header as S3 object metadata, so an unbounded one is a liability.
     */
    private const val MAX_LENGTH = 255

    /** Longest plausible extension; `webmanifest` is 11. */
    private const val MAX_EXTENSION_LENGTH = 16

    /**
     * Strips path components, collapses whitespace to `_` and removes anything
     * that is not a letter, a digit or `._-`, so the result can never escape its
     * prefix.
     *
     * **Letters and digits are Unicode-aware**, so `Čtvrtletní zpráva.pdf`
     * survives as `Čtvrtletní_zpráva.pdf`. Restricting this to ASCII would
     * mangle every name in most of the world's languages - and pointlessly,
     * because the name never appears in the object key (that is generated) and
     * both places it does reach are encoded for transport: S3 object metadata is
     * URL-encoded and `Content-Disposition` follows RFC 5987.
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
            .filter { it.isLetterOrDigit() || it in KEPT_PUNCTUATION }
            .replace(REPEATED_UNDERSCORE, "_")
            .trimStart('.')
            .take(MAX_LENGTH)
        if (sanitized.isEmpty() || sanitized.all { it == '.' }) {
            throw InvalidFileNameException(fileName)
        }
        return sanitized
    }

    /**
     * The lowercase extension of [fileName] without the dot, or `null` when it
     * has none or what follows the dot is not a plausible extension.
     *
     * This is the **only part of a file name that reaches the object key**, so
     * it is validated here rather than relying on the name having been
     * sanitized first. On a raw name, the naive version of this returns things
     * like `/evil` for `a.pdf/../../../evil` or `jpg?acl=public` for
     * `x.jpg?acl=public` - and an [net.mimochodek.fileforge.spi.ObjectKeyGenerator]
     * written by someone else has no way to know it had to guard against that.
     */
    public fun extensionOf(fileName: String): String? {
        val candidate = fileName.substringAfterLast('.', "").lowercase()
        return candidate.takeIf {
            it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit)
        }
    }
}
