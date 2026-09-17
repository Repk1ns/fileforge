package net.mimochodek.fileforge.naming

import net.mimochodek.fileforge.spi.ObjectKeyRequest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultObjectKeyGeneratorTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-17T23:30:00Z"), ZoneOffset.UTC)

    private fun generator(
        strategy: NamingStrategy = NamingStrategy.DATE_UUID,
        keepExtension: Boolean = true,
        zone: ZoneId = ZoneOffset.UTC,
    ) = DefaultObjectKeyGenerator(strategy, keepExtension, clock, zone)

    private fun request(fileName: String = "report.pdf", folder: String = "documents") =
        ObjectKeyRequest(fileName, folder, "application/pdf")

    @Test
    fun `should build a date path by default`() {
        val key = generator().generate(request())

        assertTrue(Regex("documents/2026/09/17/$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should build a flat key`() {
        val key = generator(NamingStrategy.UUID).generate(request())

        assertTrue(Regex("documents/$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should build the legacy 1x layout`() {
        val key = generator(NamingStrategy.YEAR_UUID).generate(request())

        assertTrue(Regex("documents/2026_$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should drop the extension when configured to`() {
        val key = generator(keepExtension = false).generate(request())

        assertTrue(Regex("documents/2026/09/17/$UUID").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should place keys at the root when no folder is given`() {
        val key = generator().generate(request(folder = ""))

        assertTrue(Regex("2026/09/17/$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should normalize surrounding slashes in the folder`() {
        val key = generator(NamingStrategy.UUID).generate(request(folder = "/documents/"))

        assertTrue(Regex("documents/$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should derive the date in the configured zone, not the system default`() {
        // 23:30 UTC on the 17th is already the 18th in Tokyo; pinning the zone is
        // what makes this deterministic instead of host-dependent.
        val key = generator(zone = ZoneId.of("Asia/Tokyo")).generate(request())

        assertTrue(Regex("documents/2026/09/18/$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should generate a unique key per call`() {
        val generator = generator()

        val keys = (1..100).map { generator.generate(request()) }.toSet()

        assertEquals(100, keys.size)
    }

    private companion object {
        const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }
}
