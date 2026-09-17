package net.mimochodek.fileforge.testsupport

/** Minimal byte fixtures whose magic bytes are genuinely correct. */
object Fixtures {
    val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(12)
    val PNG: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
    val PDF: ByteArray = "%PDF-1.7 payload".toByteArray()
    val WEBP: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x20, 0, 0, 0) + "WEBP".toByteArray()

    /** A RIFF container that is not a webp image - the 1.x false positive. */
    val WAV: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x20, 0, 0, 0) + "WAVE".toByteArray()
}
