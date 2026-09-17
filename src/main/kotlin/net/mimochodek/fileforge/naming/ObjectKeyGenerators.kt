package net.mimochodek.fileforge.naming

import net.mimochodek.fileforge.spi.ObjectKeyGenerator
import net.mimochodek.fileforge.spi.ObjectKeyRequest
import net.mimochodek.fileforge.validation.FileNames
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The built-in key layouts, selectable with `fileforge.naming.strategy`. */
public enum class NamingStrategy {

    /** `documents/2026/09/17/<uuid>.pdf` - the default; keeps prefixes browsable. */
    DATE_UUID,

    /** `documents/<uuid>.pdf` - flat. */
    UUID,

    /** `documents/2026_<uuid>.pdf` - the 1.x layout, kept for compatibility. */
    YEAR_UUID,
}

/**
 * Builds keys from a UUID, optionally prefixed by a date path and suffixed with
 * the original extension.
 *
 * The [clock] and [zone] are injected rather than read from the system default,
 * so key generation is pinned and testable.
 */
public class DefaultObjectKeyGenerator(
    private val strategy: NamingStrategy = NamingStrategy.DATE_UUID,
    private val keepExtension: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneOffset.UTC,
) : ObjectKeyGenerator {

    override fun generate(request: ObjectKeyRequest): String {
        val uuid = UUID.randomUUID().toString()
        val extension = if (keepExtension) FileNames.extensionOf(request.fileName) else null
        val name = if (extension == null) uuid else "$uuid.$extension"

        val now = clock.instant().atZone(zone)
        val leaf = when (strategy) {
            NamingStrategy.DATE_UUID -> "${DATE_PATH.format(now)}/$name"
            NamingStrategy.UUID -> name
            NamingStrategy.YEAR_UUID -> "${now.year}_$name"
        }

        val folder = request.folder.trim('/')
        return if (folder.isEmpty()) leaf else "$folder/$leaf"
    }

    private companion object {
        val DATE_PATH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    }
}
