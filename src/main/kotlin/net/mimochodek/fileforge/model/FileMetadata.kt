package net.mimochodek.fileforge.model

import java.time.Instant

/**
 * Metadata about a stored object, without transferring its content.
 *
 * @property fileName the original file name, when it was recorded at upload time.
 */
public data class FileMetadata(
    val key: String,
    val fileName: String?,
    val contentType: String,
    val size: Long,
    val checksum: String?,
    val lastModified: Instant?,
    val metadata: Map<String, String> = emptyMap(),
)
