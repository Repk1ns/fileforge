package net.mimochodek.fileforge

/**
 * Validates files before they are stored: size limit, MIME whitelist,
 * magic-byte content verification and file-name sanitization.
 */
class FileValidationService(
    private val properties: FileStorageProperties,
) {

    /** Whitelist normalized once, not on every [validateMimeType] call. */
    private val allowedMimeTypes: Set<String> =
        properties.allowedMimeTypes.mapTo(HashSet()) { it.substringBefore(';').trim().lowercase() }

    /**
     * Validates [contentLength] and [contentType] against the configured limits
     * and verifies that the first bytes of [headBytes] match the declared type.
     *
     * @throws FileSizeExceededException when the file is too large
     * @throws UnsupportedFileTypeException when the MIME type is not whitelisted
     * @throws ContentMismatchException when the magic bytes do not match the declared type
     */
    fun validate(contentLength: Long, contentType: String, headBytes: ByteArray) {
        validateSize(contentLength)
        validateMimeType(contentType)
        validateMagicBytes(contentType, headBytes)
    }

    /** Enforces the configured maximum file size. */
    fun validateSize(contentLength: Long) {
        if (contentLength > properties.maxFileSizeBytes) {
            throw FileSizeExceededException(contentLength, properties.maxFileSizeBytes)
        }
    }

    /** Enforces the configured MIME whitelist. */
    fun validateMimeType(contentType: String) {
        if (normalize(contentType) !in allowedMimeTypes) {
            throw UnsupportedFileTypeException(contentType)
        }
    }

    /**
     * Verifies the actual content (magic bytes) matches the declared [contentType].
     *
     * Content types without a known signature are not checked.
     */
    fun validateMagicBytes(contentType: String, headBytes: ByteArray) {
        val signatures = MAGIC_BYTES[normalize(contentType)] ?: return
        if (signatures.none { it.matches(headBytes) }) {
            throw ContentMismatchException(contentType)
        }
    }

    /**
     * Sanitizes a file name: strips any path components, removes unsafe
     * characters and normalizes whitespace to prevent path traversal and
     * collisions.
     *
     * @throws InvalidFileNameException when nothing usable remains
     */
    fun sanitizeFileName(fileName: String): String {
        val baseName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val sanitized = baseName
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^A-Za-z0-9._-]"""), "")
            .trimStart('.')
        if (sanitized.isEmpty() || sanitized.all { it == '.' }) {
            throw InvalidFileNameException(fileName)
        }
        return sanitized
    }

    private fun normalize(contentType: String): String = contentType.substringBefore(';').trim().lowercase()

    /**
     * A magic-byte signature: one or more byte sequences that must **all** match
     * at their respective offsets for the signature to apply.
     */
    private class MagicSignature(private vararg val parts: Part) {

        fun matches(head: ByteArray): Boolean = parts.all { part ->
            head.size >= part.offset + part.bytes.size &&
                part.bytes.indices.all { head[part.offset + it] == part.bytes[it] }
        }

        class Part(val bytes: ByteArray, val offset: Int = 0)
    }

    private companion object {

        private fun part(vararg bytes: Byte) = MagicSignature.Part(bytes)

        private fun part(offset: Int, ascii: String) = MagicSignature.Part(ascii.toByteArray(), offset)

        private fun part(ascii: String) = MagicSignature.Part(ascii.toByteArray())

        val MAGIC_BYTES: Map<String, List<MagicSignature>> = mapOf(
            "image/jpeg" to listOf(
                MagicSignature(part(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            ),
            "image/png" to listOf(
                MagicSignature(part(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            ),
            "image/webp" to listOf(
                // "RIFF" <4 byte little-endian size> "WEBP" - both parts are required,
                // otherwise any RIFF container (e.g. a WAV file) would pass as a webp image.
                MagicSignature(part("RIFF"), part(8, "WEBP")),
            ),
            "image/gif" to listOf(
                MagicSignature(part("GIF87a")),
                MagicSignature(part("GIF89a")),
            ),
            "application/pdf" to listOf(
                MagicSignature(part("%PDF-")),
            ),
        )
    }
}
