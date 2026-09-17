package net.mimochodek.fileforge.model

import java.io.Closeable
import java.io.InputStream

/**
 * An open object: its [metadata] together with a [stream] over its bytes.
 *
 * The stream holds a backend resource (for S3, a pooled HTTP connection) and
 * **must** be closed. Prefer the scoped
 * [net.mimochodek.fileforge.FileStorage.read] overload that takes a lambda,
 * which closes it for you.
 */
public class FileContent(
    public val metadata: FileMetadata,
    public val stream: InputStream,
) : Closeable {

    override fun close() {
        stream.close()
    }
}
