package net.mimochodek.fileforge.model

import java.time.Instant

/**
 * The result of storing a file - everything a consuming application needs to
 * persist, without re-deriving any of it by hand.
 *
 * @property key the generated object key; this is the value to persist.
 * @property contentType the effective content type (declared or detected).
 * @property size the number of bytes actually written, not the caller's claim.
 * @property checksum backend checksum (the S3 ETag), when the backend reports one.
 * @property storedAt when the object was written.
 */
public data class StoredFile(
    val key: String,
    val contentType: String,
    val size: Long,
    val checksum: String?,
    val storedAt: Instant,
)
