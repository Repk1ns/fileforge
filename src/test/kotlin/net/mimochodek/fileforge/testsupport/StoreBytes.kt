package net.mimochodek.fileforge.testsupport

import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.model.StoredFile

/**
 * Stores a byte array in one line.
 *
 * The library deliberately no longer ships a `ByteArray` overload of `store()`:
 * it invites loading a whole upload into the heap, which is exactly what the
 * streaming path exists to avoid. Tests work with tiny fixtures where that does
 * not matter, so the convenience lives here instead of in the public API.
 */
fun FileStorage.storeBytes(
    fileName: String,
    bytes: ByteArray,
    contentType: String? = null,
    folder: String = "",
    profile: String? = null,
): StoredFile = store(
    FileUpload(
        fileName = fileName,
        content = ContentSource.Bytes(bytes),
        contentType = contentType,
        folder = folder,
        profile = profile,
    ),
)
