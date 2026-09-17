package net.mimochodek.fileforge.model

/**
 * Everything needed to store one file.
 *
 * @property fileName the original file name; it is sanitized, used for the key
 *   extension and preserved as backend metadata so downloads can be served
 *   under the name the user uploaded.
 * @property content the bytes to store.
 * @property contentType the declared content type, or `null` to let the library
 *   detect it from the magic bytes and the file extension.
 * @property folder key prefix, e.g. `documents`. Empty means the bucket root,
 *   or the folder of [profile] when one is named.
 * @property metadata additional backend metadata to attach to the object.
 * @property profile a configured `fileforge.profiles` entry supplying the folder
 *   and the validation policy for this category of upload.
 */
public data class FileUpload(
    val fileName: String,
    val content: ContentSource,
    val contentType: String? = null,
    val folder: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val profile: String? = null,
)
