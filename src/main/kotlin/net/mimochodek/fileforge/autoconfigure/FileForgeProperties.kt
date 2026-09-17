package net.mimochodek.fileforge.autoconfigure

import net.mimochodek.fileforge.naming.NamingStrategy
import net.mimochodek.fileforge.validation.UnknownTypePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Storage-agnostic configuration, bound to `fileforge`.
 *
 * Naming and validation are deliberately **not** nested under a provider
 * prefix: they are policy that applies to every backend, so a future filesystem
 * backend must not have to read `fileforge.s3.max-file-size-bytes`.
 *
 * @property provider selects the backend implementation. `s3` is the only one
 *   shipped today; any other value leaves the S3 auto-configuration inactive,
 *   which is also how you disable the library while keeping the dependency.
 */
@ConfigurationProperties(prefix = "fileforge")
public class FileForgeProperties(
    public val provider: String = PROVIDER_S3,
    public val naming: NamingProperties = NamingProperties(),
    public val validation: ValidationProperties = ValidationProperties(),
    public val buffering: BufferingProperties = BufferingProperties(),
) {
    public companion object {
        public const val PROVIDER_S3: String = "s3"
    }
}

/** How object keys are built - `fileforge.naming`. */
public class NamingProperties(
    public val strategy: NamingStrategy = NamingStrategy.DATE_UUID,
    public val keepExtension: Boolean = true,
    /**
     * Time zone the date path is derived in. Pinned to UTC by default rather
     * than following the system default, so keys are reproducible across hosts.
     */
    public val zone: ZoneId = ZoneOffset.UTC,
)

/** Upload policy - `fileforge.validation`. Applied to every store, always. */
public class ValidationProperties(
    public val maxFileSize: DataSize = DataSize.ofMegabytes(50),
    public val allowedMimeTypes: List<String> = DEFAULT_ALLOWED_MIME_TYPES,
    public val verifyMagicBytes: Boolean = true,
    public val unknownTypePolicy: UnknownTypePolicy = UnknownTypePolicy.ALLOW,
) {
    init {
        require(maxFileSize.toBytes() > 0) {
            "fileforge.validation.max-file-size must be positive but was $maxFileSize"
        }
        require(allowedMimeTypes.isNotEmpty()) {
            "fileforge.validation.allowed-mime-types must not be empty; " +
                "an empty whitelist rejects every upload"
        }
    }

    public companion object {
        public val DEFAULT_ALLOWED_MIME_TYPES: List<String> = listOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf",
        )
    }
}

/**
 * How upload content is buffered - `fileforge.buffering`.
 *
 * Streams are buffered so that their real length is known before anything is
 * written; that is what lets the size limit apply to the content rather than to
 * the caller's claim about it.
 */
public class BufferingProperties(
    public val inMemoryThreshold: DataSize = DataSize.ofMegabytes(1),
    /** Where to spill larger uploads. `null` uses the JVM temp directory. */
    public val tempDir: Path? = null,
) {
    init {
        require(inMemoryThreshold.toBytes() > 0) {
            "fileforge.buffering.in-memory-threshold must be positive but was $inMemoryThreshold"
        }
    }
}
