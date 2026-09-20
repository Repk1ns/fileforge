package net.mimochodek.fileforge.naming

import net.mimochodek.fileforge.spi.ObjectKeyRequest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YearUuidKeyGeneratorTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-17T23:30:00Z"), ZoneOffset.UTC)

    private fun generator(keepExtension: Boolean = true, zone: ZoneId = ZoneOffset.UTC) =
        YearUuidKeyGenerator(keepExtension, clock, zone)

    private fun request(fileName: String = "report.pdf", folder: String = "documents") =
        ObjectKeyRequest(fileName, folder, "application/pdf")

    @Test
    fun `should build year underscore uuid with the extension`() {
        val key = generator().generate(request())

        assertTrue(Regex("documents/2026_$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should drop the extension when configured to`() {
        val key = generator(keepExtension = false).generate(request())

        assertTrue(Regex("documents/2026_$UUID").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should place keys at the root when no folder is given`() {
        val key = generator().generate(request(folder = ""))

        assertTrue(Regex("2026_$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should normalize surrounding slashes in the folder`() {
        val key = generator().generate(request(folder = "/documents/"))

        assertTrue(Regex("documents/2026_$UUID\\.pdf").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should never let the uploaded name into the key`() {
        // Only the extension travels, and only if it could be one.
        val key = generator().generate(request(fileName = "../../etc/passwd"))

        assertTrue(Regex("documents/2026_$UUID").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should refuse an implausible extension rather than put it in the key`() {
        val key = generator().generate(request(fileName = "a.pdf/../../../evil"))

        assertTrue(Regex("documents/2026_$UUID").matches(key), "unexpected key: $key")
    }

    @Test
    fun `should take the year from the configured zone, not the system default`() {
        // 2026-12-31 23:30 UTC is already 2027 in Tokyo.
        val newYearsEve = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneOffset.UTC)

        val utc = YearUuidKeyGenerator(clock = newYearsEve).generate(request())
        val tokyo = YearUuidKeyGenerator(clock = newYearsEve, zone = ZoneId.of("Asia/Tokyo")).generate(request())

        assertTrue(utc.startsWith("documents/2026_"), utc)
        assertTrue(tokyo.startsWith("documents/2027_"), tokyo)
    }

    @Test
    fun `should generate a unique key per call`() {
        val generator = generator()

        assertEquals(100, (1..100).map { generator.generate(request()) }.toSet().size)
    }

    private companion object {
        const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }
}
