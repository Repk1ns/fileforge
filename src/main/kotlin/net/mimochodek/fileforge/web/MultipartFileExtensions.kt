package net.mimochodek.fileforge.web

import net.mimochodek.fileforge.FileStorage
import net.mimochodek.fileforge.model.ContentSource
import net.mimochodek.fileforge.model.FileUpload
import net.mimochodek.fileforge.model.StoredFile
import org.springframework.web.multipart.MultipartFile

/**
 * Stores an uploaded [MultipartFile].
 *
 * Every consuming application is a Spring Boot web app and every one of them was
 * writing this adapter by hand, so it ships here. `spring-web` is `compileOnly`,
 * making this a zero-cost extension function: nothing is added to the runtime
 * classpath, and the library stays usable outside a web application.
 *
 * ```kotlin
 * fun upload(file: MultipartFile) = storage.store(file, folder = "documents")
 * ```
 *
 * The content type is passed as the browser declared it, which means it is
 * verified against the magic bytes rather than believed; when the browser sends
 * nothing, the type is detected from the content. `file.size` is likewise only a
 * hint - the real length is measured.
 */
@JvmOverloads
public fun FileStorage.store(
    file: MultipartFile,
    folder: String = "",
    metadata: Map<String, String> = emptyMap(),
): StoredFile = store(
    FileUpload(
        fileName = file.originalFilename?.takeIf { it.isNotBlank() } ?: file.name,
        content = ContentSource.Stream(file.inputStream, file.size),
        contentType = file.contentType?.takeIf { it.isNotBlank() },
        folder = folder,
        metadata = metadata,
    ),
)
