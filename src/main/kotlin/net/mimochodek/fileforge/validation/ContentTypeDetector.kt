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
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    /**
     * Container formats whose concrete type the extension is allowed to refine.
     *
     * `.docx` and `.xlsx` *are* ZIP archives and `.doc` and `.xls` *are* OLE2
     * compound files, so the magic bytes can never say more than "zip" or
     * "ole2" - there is no conflict for the extension to lose. Without this,
     * every Office upload without a declared content type was detected as
     * `application/zip` and rejected by any sane whitelist.
     *
     * This is narrowly scoped on purpose: refinement only happens when the
     * extension's type really is carried by the detected container, so a PDF
     * renamed to `.docx` is still detected as a PDF.
     */
    private val CONTAINER_REFINEMENTS: Map<String, Set<String>> = mapOf(
        "application/zip" to setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ),
        MagicBytes.OLE2 to setOf(
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
        ),
    )

    /** Normalizes a content type: drops parameters, trims and lowercases. */
    public fun normalize(contentType: String): String = contentType.substringBefore(';').trim().lowercase()

    /**
     * Detects the content type of [head], using the [fileName] extension as the
     * fallback - and, for container formats, as a refinement.
     *
     * Magic bytes win over the extension, because the extension is
     * attacker-controlled. The one exception is a detected *container*: when the
     * bytes say `application/zip` and the extension says `.docx`, the extension
     * is more specific and both are true, so the specific type wins. See
     * [CONTAINER_REFINEMENTS].
     */
    public fun detect(head: ByteArray, fileName: String): String {
        val byExtension = FileNames.extensionOf(fileName)?.let { BY_EXTENSION[it] }
        val byMagic = MagicBytes.detect(head) ?: return byExtension ?: FALLBACK
        val refinable = CONTAINER_REFINEMENTS[byMagic].orEmpty()
        return if (byExtension != null && byExtension in refinable) byExtension else byMagic
    }
}
