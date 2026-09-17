package net.mimochodek.fileforge.exception

/**
 * Base exception for every failure the library reports.
 *
 * Backends translate their own failures into this hierarchy, so no
 * backend-specific type (an AWS `S3Exception`, an `SdkClientException`) ever
 * escapes the abstraction.
 *
 * Subtypes expose the facts that caused them as properties rather than burying
 * them in the message, so a consumer can build a precise error response without
 * parsing strings.
 */
public open class FileStorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The requested object does not exist. */
public class FileNotFoundStorageException(
    public val key: String,
    cause: Throwable? = null,
) : FileStorageException("Object not found: $key", cause)

/**
 * The file is larger than the configured limit.
 *
 * @property actualSize the measured size, or `null` when the upload was aborted
 *   as soon as it passed the limit rather than read to its end.
 */
public class FileSizeExceededException(
    public val actualSize: Long?,
    public val maxSize: Long,
) : FileStorageException(
    if (actualSize == null) {
        "File exceeds the maximum allowed size of $maxSize bytes"
    } else {
        "File size $actualSize bytes exceeds the maximum allowed size of $maxSize bytes"
    },
)

/** The content type is not in the configured whitelist. */
public class UnsupportedFileTypeException(
    public val contentType: String,
    public val allowedTypes: Set<String> = emptySet(),
) : FileStorageException(
    buildString {
        append("Content type '")
        append(contentType)
        append("' is not allowed")
        if (allowedTypes.isNotEmpty()) {
            append("; allowed types are ")
            append(allowedTypes.sorted().joinToString(", "))
        }
    },
)

/**
 * The content does not match the type it was declared as - a jpeg renamed to
 * `.pdf`, or a type that cannot be verified at all under a strict policy.
 *
 * @property detectedContentType what the magic bytes actually say, when they
 *   identify anything.
 */
public class ContentMismatchException(
    public val declaredContentType: String,
    public val detectedContentType: String? = null,
) : FileStorageException(
    buildString {
        append("File content does not match the declared content type '")
        append(declaredContentType)
        append("'")
        if (detectedContentType != null) {
            append("; the content looks like '")
            append(detectedContentType)
            append("'")
        }
    },
)

/** The file name is unusable, or would allow path traversal. */
public class InvalidFileNameException(
    public val fileName: String,
) : FileStorageException("Invalid file name: '$fileName'")

/**
 * The storage backend could not be reached at all - connection refused, DNS
 * failure, socket timeout.
 *
 * Distinguished from [FileStorageException] because it is retryable and maps to
 * a different HTTP status than a genuine storage error.
 */
public class StorageUnavailableException(
    message: String,
    cause: Throwable? = null,
) : FileStorageException(message, cause)

/**
 * A `store` call named a profile that is not configured.
 *
 * Reported eagerly with the list of known profiles, because the cause is always
 * a typo or a missing `fileforge.profiles` entry rather than anything the end
 * user did.
 */
public class UnknownStorageProfileException(
    public val profile: String,
    public val knownProfiles: Set<String>,
) : FileStorageException(
    "Unknown storage profile '$profile'; configured profiles are " +
        if (knownProfiles.isEmpty()) "none" else knownProfiles.sorted().joinToString(", "),
)
