package net.mimochodek.fileforge

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties bound to the `fileforge.s3` prefix.
 */
@ConfigurationProperties(prefix = "fileforge.s3")
data class FileStorageProperties(
    /** S3-compatible endpoint, e.g. `http://nas:7070`. */
    val endpoint: String,
    /** S3 region, e.g. `us-east-1`. */
    val region: String = "us-east-1",
    /** Bucket used by the consuming application. */
    val bucket: String,
    /** Access key for the S3 endpoint. */
    val accessKey: String,
    /** Secret key for the S3 endpoint. */
    val secretKey: String,
    /** Maximum allowed file size in bytes (default 50 MiB). */
    val maxFileSizeBytes: Long = 52_428_800,
    /** Whitelist of allowed MIME types. */
    val allowedMimeTypes: List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "application/pdf",
    ),
)
