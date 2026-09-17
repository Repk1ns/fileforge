package net.mimochodek.fileforge.model

import java.io.InputStream
import java.nio.file.Path

/**
 * The bytes to be stored, in whichever form the caller already has them.
 *
 * The library resolves the source into a replayable stream with a known length
 * before anything is written, which is what allows validation and size limits to
 * be enforced against the *actual* content rather than a caller's claim.
 */
public sealed interface ContentSource {

    /** Content already fully in memory. */
    public class Bytes(public val value: ByteArray) : ContentSource

    /**
     * A single-pass stream. [length] is a hint only - it is never trusted for
     * size enforcement and may be `null` when the length is unknown.
     *
     * The stream is consumed once and closed by the library.
     */
    public class Stream(
        public val stream: InputStream,
        public val length: Long? = null,
    ) : ContentSource

    /** Content read from a local file. */
    public class LocalFile(public val path: Path) : ContentSource

    public companion object {
        public fun of(bytes: ByteArray): ContentSource = Bytes(bytes)

        @JvmOverloads
        public fun of(stream: InputStream, length: Long? = null): ContentSource = Stream(stream, length)

        public fun of(path: Path): ContentSource = LocalFile(path)
    }
}
