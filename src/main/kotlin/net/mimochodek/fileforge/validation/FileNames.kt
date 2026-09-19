package net.mimochodek.fileforge.validation


/**
 * What the library takes from a caller-supplied file name.
 *
 * Which is only the extension. The object key is generated, and the name itself
 * is not stored - so there is nothing to sanitize and no sanitizer to configure.
 * If your application wants to show users the name they uploaded, keep it in
 * your own database next to the key.
 */
public object FileNames {

    /** Longest plausible extension; `webmanifest` is 11. */
    private const val MAX_EXTENSION_LENGTH = 16

    /**
     * The lowercase extension of [fileName] without the dot, or `null` when it
     * has none or what follows the dot is not a plausible extension.
     *
     * This is the **only part of a file name that reaches the object key**, and
     * the name arrives exactly as the caller supplied it, so the check happens
     * here. The naive version returns things like `/evil` for
     * `a.pdf/../../../evil` or `jpg?acl=public` for `x.jpg?acl=public`, which
     * would land straight in the key.
     */
    public fun extensionOf(fileName: String): String? {
        val candidate = fileName.substringAfterLast('.', "").lowercase()
        return candidate.takeIf {
            it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit)
        }
    }
}
