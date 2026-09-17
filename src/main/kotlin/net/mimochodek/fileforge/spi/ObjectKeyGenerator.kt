package net.mimochodek.fileforge.spi

/**
 * Generates the object key a file is stored under.
 *
 * Naming is a storage-agnostic policy, so it lives here rather than inside a
 * backend - every backend gets the same layout, and a consumer can replace the
 * policy by publishing their own bean.
 */
public fun interface ObjectKeyGenerator {

    /** Returns the full key, including any folder prefix. */
    public fun generate(request: ObjectKeyRequest): String
}

/**
 * @property fileName the already-sanitized original file name.
 * @property folder the requested key prefix; empty for the bucket root.
 * @property contentType the effective content type, when known.
 */
public class ObjectKeyRequest(
    public val fileName: String,
    public val folder: String,
    public val contentType: String? = null,
)
