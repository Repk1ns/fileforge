package net.mimochodek.fileforge.spi

/**
 * Validates a file before it is written.
 *
 * Applied unconditionally by [net.mimochodek.fileforge.FileStorage] on every
 * store, so there is no code path that writes unvalidated content. Validation is
 * storage-agnostic and therefore shared by every backend.
 */
public fun interface FileValidator {

    /**
     * @throws net.mimochodek.fileforge.exception.FileStorageException when [candidate]
     *   violates the configured policy.
     */
    public fun validate(candidate: FileCandidate)
}

/**
 * The file about to be written, as the library actually sees it.
 *
 * @property size the true byte count, established by reading the content.
 * @property head the leading bytes, for magic-byte verification.
 */
public class FileCandidate(
    public val fileName: String,
    public val contentType: String,
    public val size: Long,
    public val head: ByteArray,
)
