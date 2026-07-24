package net.mimochodek.fileforge

/**
 * Base exception for all file storage errors thrown by the library.
 */
open class FileStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thrown when the requested object does not exist in the storage backend. */
class FileNotFoundStorageException(objectKey: String, cause: Throwable? = null) :
    FileStorageException("Object not found: $objectKey", cause)

/** Thrown when a file exceeds the configured maximum size. */
class FileSizeExceededException(actualSize: Long, maxSize: Long) :
    FileStorageException("File size $actualSize bytes exceeds the maximum allowed size of $maxSize bytes")

/** Thrown when a file's declared or detected content type is not in the configured whitelist. */
class UnsupportedFileTypeException(contentType: String) :
    FileStorageException("Content type '$contentType' is not allowed")

/** Thrown when the actual file content (magic bytes) does not match the declared content type. */
class ContentMismatchException(declaredContentType: String) :
    FileStorageException("File content does not match the declared content type '$declaredContentType'")

/** Thrown when a file name is invalid or would allow path traversal. */
class InvalidFileNameException(fileName: String) :
    FileStorageException("Invalid file name: '$fileName'")
