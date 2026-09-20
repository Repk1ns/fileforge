package net.mimochodek.fileforge.naming

import net.mimochodek.fileforge.spi.ObjectKeyGenerator
import net.mimochodek.fileforge.spi.ObjectKeyRequest
import net.mimochodek.fileforge.validation.FileNames
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The built-in key layout: `<folder>/<year>_<uuid>.<extension>`, for example
 * `documents/2026_1f7a8b2c-....pdf`.
 *
 * The name the file arrived under never appears — only its extension, and only
 * after [FileNames.extensionOf] has confirmed it could be one. A key therefore
 * cannot be influenced by what a user uploads, and two uploads of the same file
 * never collide.
 *
 * The year is a coarse grouping that keeps a bucket browsable without inviting
 * anyone to parse meaning out of a key. If you want a different layout,
 * implement [ObjectKeyGenerator] and publish it as a bean; there is one layout
 * here because one is enough.
 *
 * [clock] and [zone] are injected rather than read from the system default, so
 * generation is deterministic and the year boundary is testable.
 */
public class YearUuidKeyGenerator(
    private val keepExtension: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneOffset.UTC,
) : ObjectKeyGenerator {

    override fun generate(request: ObjectKeyRequest): String {
        val uuid = UUID.randomUUID()
        val extension = if (keepExtension) FileNames.extensionOf(request.fileName) else null
        val year = clock.instant().atZone(zone).year

        val leaf = if (extension == null) "${year}_$uuid" else "${year}_$uuid.$extension"
        val folder = request.folder.trim('/')
        return if (folder.isEmpty()) leaf else "$folder/$leaf"
    }
}
