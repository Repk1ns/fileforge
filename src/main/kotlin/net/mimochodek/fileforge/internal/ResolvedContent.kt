package net.mimochodek.fileforge.internal

import net.mimochodek.fileforge.exception.FileSizeExceededException
import net.mimochodek.fileforge.exception.FileStorageException
import net.mimochodek.fileforge.model.ContentSource
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min

/**
 * Content whose true [size] is known and whose bytes can be opened again.
 *
 * Resolving to this shape is what lets the library validate and size-check the
 * *actual* content. A caller-supplied content length is only ever a hint:
 * trusting it (as 1.x did) let a caller declare `5` and bypass the size limit
 * entirely, because `RequestBody.fromInputStream` reads exactly that many bytes.
 */
internal interface ResolvedContent : Closeable {

    /** The real number of bytes. */
    val size: Long

    /** The leading bytes, for magic-byte checks. */
    val head: ByteArray

    /** Opens a fresh stream over the whole content. */
    fun openStream(): InputStream
}

/** The number of leading bytes kept for magic-byte verification. */
private const val HEAD_BYTES = 64

private const val COPY_BUFFER = 8 * 1024

/**
 * Turns a [ContentSource] into [ResolvedContent], enforcing [maxSize] as it goes.
 *
 * Bytes and local files are used in place - no copying. A stream is buffered in
 * memory while it fits within [inMemoryThreshold] and spilled to a temporary
 * file in [tempDir] beyond it, which is what makes unknown-length streams work
 * and guarantees a single-pass stream is never read twice.
 *
 * The returned value owns any temporary file and must be closed.
 */
internal fun resolveContent(
    source: ContentSource,
    maxSize: Long,
    inMemoryThreshold: Long,
    tempDir: Path?,
): ResolvedContent = when (source) {
    is ContentSource.Bytes -> {
        val size = source.value.size.toLong()
        if (size > maxSize) throw FileSizeExceededException(size, maxSize)
        ByteArrayContent(source.value)
    }

    is ContentSource.LocalFile -> {
        val size = try {
            Files.size(source.path)
        } catch (e: IOException) {
            throw FileStorageException("Cannot read '${source.path}'", e)
        }
        if (size > maxSize) throw FileSizeExceededException(size, maxSize)
        FileBackedContent(source.path, size, ownsFile = false)
    }

    is ContentSource.Stream -> source.stream.use { bufferStream(it, maxSize, inMemoryThreshold, tempDir) }
}

/** In-memory content. */
private class ByteArrayContent(private val bytes: ByteArray) : ResolvedContent {

    override val size: Long get() = bytes.size.toLong()

    override val head: ByteArray = bytes.copyOf(min(HEAD_BYTES, bytes.size))

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() = Unit
}

/** Content backed by a file. [ownsFile] marks a spill file this object must delete. */
private class FileBackedContent(
    private val path: Path,
    override val size: Long,
    private val ownsFile: Boolean,
) : ResolvedContent {

    override val head: ByteArray = try {
        Files.newInputStream(path).use { it.readNBytes(HEAD_BYTES) }
    } catch (e: IOException) {
        throw FileStorageException("Cannot read '$path'", e)
    }

    override fun openStream(): InputStream = try {
        Files.newInputStream(path)
    } catch (e: IOException) {
        throw FileStorageException("Cannot read '$path'", e)
    }

    override fun close() {
        if (ownsFile) runCatching { Files.deleteIfExists(path) }
    }
}

/**
 * Reads [stream] to its end, keeping it in memory while it fits and spilling to
 * a temporary file afterwards. Aborts as soon as [maxSize] is exceeded rather
 * than reading a hostile upload to completion.
 */
private fun bufferStream(
    stream: InputStream,
    maxSize: Long,
    inMemoryThreshold: Long,
    tempDir: Path?,
): ResolvedContent {
    // One byte past the threshold: reading that byte is exactly what tells us the
    // content does not fit in memory and has to be spilled.
    val cap = (min(inMemoryThreshold, maxSize) + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val buffered = try {
        stream.readNBytes(cap)
    } catch (e: IOException) {
        throw FileStorageException("Failed to read the upload content", e)
    }

    if (buffered.size <= inMemoryThreshold) {
        if (buffered.size > maxSize) throw FileSizeExceededException(buffered.size.toLong(), maxSize)
        return ByteArrayContent(buffered)
    }

    val spill = createTempFile(tempDir)
    var written = 0L
    try {
        Files.newOutputStream(spill).use { out ->
            out.write(buffered)
            written = buffered.size.toLong()
            val chunk = ByteArray(COPY_BUFFER)
            while (written <= maxSize) {
                val read = stream.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
                written += read
            }
        }
        if (written > maxSize) throw FileSizeExceededException(written, maxSize)
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(spill) }
        throw when (e) {
            is IOException -> FileStorageException("Failed to buffer the upload content", e)
            else -> e
        }
    }
    return FileBackedContent(spill, written, ownsFile = true)
}

private fun createTempFile(tempDir: Path?): Path = try {
    if (tempDir == null) {
        Files.createTempFile("fileforge-", ".upload")
    } else {
        Files.createDirectories(tempDir)
        Files.createTempFile(tempDir, "fileforge-", ".upload")
    }
} catch (e: IOException) {
    throw FileStorageException("Cannot create a temporary file to buffer the upload", e)
}
