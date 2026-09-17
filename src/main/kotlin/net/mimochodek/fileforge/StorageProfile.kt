package net.mimochodek.fileforge

import net.mimochodek.fileforge.spi.FileValidator

/**
 * A named category of uploads: where it goes, and what is allowed in it.
 *
 * Profiles exist to remove the per-category string concatenation and limit
 * juggling that otherwise gets rewritten in every consuming application -
 * `"documents/$name"` in one service, a 2 MB avatar check in another. Declared
 * once under `fileforge.profiles`, they are referenced by name:
 *
 * ```kotlin
 * storage.store(file, profile = "documents")
 * ```
 *
 * @property folder key prefix applied when the upload does not name its own.
 * @property validator policy for this profile, or `null` to use the global one -
 *   which is also what preserves a consumer's custom [FileValidator] bean for
 *   profiles that only set a folder.
 * @property maxFileSize the profile's size cap. Needed separately from
 *   [validator] because it also bounds how much of an upload is read before it
 *   is abandoned.
 */
public class StorageProfile(
    public val folder: String = "",
    public val validator: FileValidator? = null,
    public val maxFileSize: Long? = null,
)
