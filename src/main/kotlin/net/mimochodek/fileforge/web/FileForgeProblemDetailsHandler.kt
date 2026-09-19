package net.mimochodek.fileforge.web

import net.mimochodek.fileforge.exception.ContentMismatchException
import net.mimochodek.fileforge.exception.FileNotFoundStorageException
import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.FileStorageException
import net.mimochodek.fileforge.exception.StorageUnavailableException
import net.mimochodek.fileforge.exception.UnsupportedFileTypeException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps the library's exceptions to RFC 7807 `ProblemDetail` responses.
 *
 * Opt in with `fileforge.web.problem-details.enabled: true`. It is off by
 * default because a library should not silently take over a consumer's error
 * handling, but when it is on, one line of configuration replaces the
 * `@RestControllerAdvice` every consuming application was otherwise writing.
 *
 * Each problem carries the facts the exception exposes as extension members, so
 * a client can react to them instead of parsing the message:
 *
 * ```json
 * {
 *   "type": "about:blank",
 *   "title": "Payload Too Large",
 *   "status": 413,
 *   "detail": "File size 9437184 bytes exceeds the maximum allowed size of 5242880 bytes",
 *   "maxSize": 5242880,
 *   "actualSize": 9437184
 * }
 * ```
 */
@RestControllerAdvice
public class FileForgeProblemDetailsHandler {

    @ExceptionHandler(FileNotFoundStorageException::class)
    public fun handle(e: FileNotFoundStorageException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, e).apply { setProperty("key", e.key) }

    @ExceptionHandler(FileSizeExceededException::class)
    public fun handle(e: FileSizeExceededException): ProblemDetail =
        problem(HttpStatus.PAYLOAD_TOO_LARGE, e).apply {
            setProperty("maxSize", e.maxSize)
            e.actualSize?.let { setProperty("actualSize", it) }
        }

    @ExceptionHandler(UnsupportedFileTypeException::class)
    public fun handle(e: UnsupportedFileTypeException): ProblemDetail =
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e).apply {
            setProperty("contentType", e.contentType)
            if (e.allowedTypes.isNotEmpty()) setProperty("allowedTypes", e.allowedTypes.sorted())
        }

    @ExceptionHandler(ContentMismatchException::class)
    public fun handle(e: ContentMismatchException): ProblemDetail =
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e).apply {
            setProperty("declaredContentType", e.declaredContentType)
            e.detectedContentType?.let { setProperty("detectedContentType", it) }
        }

    @ExceptionHandler(StorageUnavailableException::class)
    public fun handle(e: StorageUnavailableException): ProblemDetail {
        // The consumer did nothing wrong and the detail may name internal hosts,
        // so this one is logged in full and reported generically.
        log.error("File storage is unreachable", e)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "File storage is temporarily unavailable",
        )
    }

    /** Anything the library did not classify more precisely. */
    @ExceptionHandler(FileStorageException::class)
    public fun handle(e: FileStorageException): ProblemDetail {
        log.error("File storage operation failed", e)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "The file storage operation failed",
        )
    }

    private fun problem(status: HttpStatus, e: FileStorageException): ProblemDetail {
        log.debug("Mapping {} to {}", e.javaClass.simpleName, status.value())
        return ProblemDetail.forStatusAndDetail(status, e.message ?: status.reasonPhrase)
    }

    private companion object {
        val log = LoggerFactory.getLogger(FileForgeProblemDetailsHandler::class.java)
    }
}
