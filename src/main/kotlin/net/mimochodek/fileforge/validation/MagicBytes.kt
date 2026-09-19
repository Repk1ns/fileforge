package net.mimochodek.fileforge.validation

/**
 * The magic-byte signature table used for content verification and content-type
 * detection.
 */
public object MagicBytes {

    /**
     * A signature: one or more byte sequences that must **all** match at their
     * respective offsets.
     */
    private class Signature(private vararg val parts: Part) {

        fun matches(head: ByteArray): Boolean = parts.all { part ->
            head.size >= part.offset + part.bytes.size &&
                part.bytes.indices.all { head[part.offset + it] == part.bytes[it] }
        }

        class Part(val bytes: ByteArray, val offset: Int = 0)
    }

    private fun part(vararg bytes: Byte) = Signature.Part(bytes)

    private fun part(ascii: String) = Signature.Part(ascii.toByteArray())

    private fun part(offset: Int, ascii: String) = Signature.Part(ascii.toByteArray(), offset)

    /** Media type of an OLE2 compound file, the container of legacy Office formats. */
    public const val OLE2: String = "application/x-ole-storage"

    private val ZIP_SIGNATURES = listOf(
        Signature(part(0x50, 0x4B, 0x03, 0x04)),
        Signature(part(0x50, 0x4B, 0x05, 0x06)),
        Signature(part(0x50, 0x4B, 0x07, 0x08)),
    )

    private val OLE2_SIGNATURES = listOf(
        Signature(part(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())),
    )

    private val SIGNATURES: Map<String, List<Signature>> = mapOf(
        "image/jpeg" to listOf(
            Signature(part(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        ),
        "image/png" to listOf(
            Signature(part(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        ),
        "image/webp" to listOf(
            // "RIFF" <4 byte little-endian size> "WEBP" - both parts are required,
            // otherwise any RIFF container (e.g. a WAV file) would pass as a webp image.
            Signature(part("RIFF"), part(8, "WEBP")),
        ),
        "image/gif" to listOf(
            Signature(part("GIF87a")),
            Signature(part("GIF89a")),
        ),
        "image/tiff" to listOf(
            Signature(part(0x49, 0x49, 0x2A, 0x00)),
            Signature(part(0x4D, 0x4D, 0x00, 0x2A)),
        ),
        "image/bmp" to listOf(
            Signature(part("BM")),
        ),
        "application/pdf" to listOf(
            Signature(part("%PDF-")),
        ),
        "application/zip" to ZIP_SIGNATURES,
        "application/gzip" to listOf(
            Signature(part(0x1F, 0x8B.toByte())),
        ),
        // OLE2 compound file - the container of the legacy Office formats.
        // Registered before the formats themselves so that detect() reports the
        // container; the concrete format is then refined by extension, because
        // the bytes alone genuinely cannot tell .doc from .xls.
        OLE2 to OLE2_SIGNATURES,
        // OOXML (.docx / .xlsx / .pptx) is a ZIP archive, so it carries the same
        // signature as application/zip. Registering the concrete types means a
        // declared .docx is at least verified to BE a zip container - without
        // this, an executable declared as .docx passed unchecked, because a type
        // with no known signature skips verification entirely.
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ZIP_SIGNATURES,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ZIP_SIGNATURES,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ZIP_SIGNATURES,
        "application/msword" to OLE2_SIGNATURES,
        "application/vnd.ms-excel" to OLE2_SIGNATURES,
        "application/vnd.ms-powerpoint" to OLE2_SIGNATURES,
    )

    /** `true` when a signature is known for [contentType]. */
    public fun isKnown(contentType: String): Boolean = contentType in SIGNATURES

    /** `true` when [head] matches any signature registered for [contentType]. */
    public fun matches(contentType: String, head: ByteArray): Boolean =
        SIGNATURES[contentType]?.any { it.matches(head) } ?: false

    /** The content type whose signature [head] matches, or `null` when unrecognized. */
    public fun detect(head: ByteArray): String? =
        SIGNATURES.entries.firstOrNull { (_, signatures) -> signatures.any { it.matches(head) } }?.key
}
