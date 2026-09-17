package net.mimochodek.fileforge.internal.s3

import java.io.InputStream

/**
 * A view over the next [limit] bytes of [delegate].
 *
 * Used for multipart uploads, where each part must be handed to the SDK as its
 * own stream of a declared length while the underlying stream stays open for the
 * parts that follow - so [close] deliberately does nothing.
 */
internal class BoundedInputStream(
    private val delegate: InputStream,
    private val limit: Long,
) : InputStream() {

    private var consumed = 0L

    override fun read(): Int {
        if (consumed >= limit) return -1
        val byte = delegate.read()
        if (byte >= 0) consumed++
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val allowed = minOf(len.toLong(), limit - consumed).toInt()
        val read = delegate.read(b, off, allowed)
        if (read > 0) consumed += read
        return read
    }

    override fun available(): Int = minOf(delegate.available().toLong(), limit - consumed).toInt()

    /** No-op: the delegate is still needed for the remaining parts. */
    override fun close() = Unit
}
