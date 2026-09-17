package net.mimochodek.fileforge.validation

/**
 * Determines the content type of an upload when the caller did not declare one.
 *
 * Magic bytes win over the file extension, because the extension is attacker-controlled.
 */
public object ContentTypeDetector {

    /** Used when neither the magic bytes nor the extension identify the content. */
    public const val FALLBACK: String = "application/octet-stream"

    private val BY_EXTENSION: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )

    /** Normalizes a content type: drops parameters, trims and lowercases. */
    public fun normalize(contentType: String): String = contentType.substringBefore(';').trim().lowercase()

    /** Detects the content type from [head], falling back to the [fileName] extension. */
    public fun detect(head: ByteArray, fileName: String): String =
        MagicBytes.detect(head)
            ?: FileNames.extensionOf(fileName)?.let { BY_EXTENSION[it] }
            ?: FALLBACK
}
