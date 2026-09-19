package net.mimochodek.fileforge.model

import java.time.Instant

/**
 * Metadata about a stored object, without transferring its content.

 */
public data class FileMetadata(
    val key: String,
    val contentType: String,
    val size: Long,
    val checksum: String?,
    val lastModified: Instant?,
    val metadata: Map<String, String> = emptyMap(),
)
