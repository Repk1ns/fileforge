package net.mimochodek.fileforge

/**
 * Validates files before they are stored: size limit, MIME whitelist,
 * magic-byte content verification and file-name sanitization.
 */
class FileValidationService(
    private val properties: FileStorageProperties,
) {

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
        val normalized = contentType.substringBefore(';').trim().lowercase()
        if (normalized !in properties.allowedMimeTypes.map { it.lowercase() }) {
            throw UnsupportedFileTypeException(contentType)
        }
    }

    /** Verifies the actual content (magic bytes) matches the declared [contentType]. */
    fun validateMagicBytes(contentType: String, headBytes: ByteArray) {
        val normalized = contentType.substringBefore(';').trim().lowercase()
        val signatures = MAGIC_BYTES[normalized] ?: return
        val matches = signatures.any { signature -> headBytes.startsWith(signature.bytes, signature.offset) }
        if (!matches) {
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

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int): Boolean {
        if (size < offset + prefix.size) return false
        return prefix.indices.all { this[offset + it] == prefix[it] }
    }

    private data class MagicSignature(val bytes: ByteArray, val offset: Int = 0)

    private companion object {
        val MAGIC_BYTES: Map<String, List<MagicSignature>> = mapOf(
            "image/jpeg" to listOf(
                MagicSignature(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            ),
            "image/png" to listOf(
                MagicSignature(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            ),
            "image/webp" to listOf(
                // "RIFF" .... "WEBP"
                MagicSignature("RIFF".toByteArray()),
            ),
            "image/gif" to listOf(
                MagicSignature("GIF87a".toByteArray()),
                MagicSignature("GIF89a".toByteArray()),
            ),
            "application/pdf" to listOf(
                MagicSignature("%PDF-".toByteArray()),
            ),
        )
    }
}
