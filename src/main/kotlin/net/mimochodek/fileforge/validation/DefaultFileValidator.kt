package net.mimochodek.fileforge.validation

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import net.mimochodek.fileforge.spi.FileCandidate
import net.mimochodek.fileforge.spi.FileValidator

/**
 * The built-in validator: size limit, MIME whitelist and magic-byte verification.
 *
 * @param maxFileSize maximum size in bytes.
 * @param allowedMimeTypes the whitelist; normalized once at construction rather
 *   than on every call.
 * @param verifyMagicBytes whether the content must match its declared type.
 * @param unknownTypePolicy what to do with types that have no known signature.
 */
public class DefaultFileValidator(
    private val maxFileSize: Long,
    allowedMimeTypes: Collection<String>,
    private val verifyMagicBytes: Boolean = true,
    private val unknownTypePolicy: UnknownTypePolicy = UnknownTypePolicy.ALLOW,
) : FileValidator {

    private val allowed: Set<String> = allowedMimeTypes.mapTo(HashSet()) { ContentTypeDetector.normalize(it) }

    /** The effective whitelist, normalized. */
    public val allowedMimeTypes: Set<String> get() = allowed

    override fun validate(candidate: FileCandidate) {
        validateSize(candidate.size)
        val contentType = ContentTypeDetector.normalize(candidate.contentType)
        validateMimeType(contentType)
        if (verifyMagicBytes) {
            validateMagicBytes(contentType, candidate.head)
        }
    }

    /** @throws FileSizeExceededException when [size] exceeds the configured limit. */
    public fun validateSize(size: Long) {
        if (size > maxFileSize) {
            throw FileSizeExceededException(size, maxFileSize)
        }
    }

    /** @throws UnsupportedFileTypeException when [contentType] is not whitelisted. */
    public fun validateMimeType(contentType: String) {
        if (ContentTypeDetector.normalize(contentType) !in allowed) {
            throw UnsupportedFileTypeException(contentType, allowed)
        }
    }

    /**
     * @throws ContentMismatchException when [head] does not match [contentType],
     *   or when the type has no known signature and the policy is
     *   [UnknownTypePolicy.REJECT].
     */
    public fun validateMagicBytes(contentType: String, head: ByteArray) {
        val normalized = ContentTypeDetector.normalize(contentType)
        if (!MagicBytes.isKnown(normalized)) {
            if (unknownTypePolicy == UnknownTypePolicy.REJECT) {
                throw ContentMismatchException(contentType, MagicBytes.detect(head))
            }
            return
        }
        if (!MagicBytes.matches(normalized, head)) {
            throw ContentMismatchException(contentType, MagicBytes.detect(head))
        }
    }
}
