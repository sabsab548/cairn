package org.cairn.meshtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramingTest {

    private fun message(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun frame_has_magic_and_big_endian_length() {
        val framed = StreamFraming.frame(message(0x01, 0x02, 0x03))
        assertEquals(7, framed.size)
        assertEquals(StreamFraming.START1, framed[0].toInt() and 0xFF)
        assertEquals(StreamFraming.START2, framed[1].toInt() and 0xFF)
        assertEquals(0x00, framed[2].toInt())
        assertEquals(0x03, framed[3].toInt())
        assertTrue(framed.copyOfRange(4, 7).contentEquals(message(0x01, 0x02, 0x03)))
    }

    @Test
    fun frame_rejects_oversized_message() {
        val tooBig = ByteArray(StreamFraming.MAX_MESSAGE + 1)
        assertTrue(runCatching { StreamFraming.frame(tooBig) }.isFailure)
    }

    @Test
    fun parses_one_frame_in_one_chunk() {
        val framer = StreamFramer()
        val framed = StreamFraming.frame(message(0xAA, 0xBB))
        val out = framer.feed(framed)
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(message(0xAA, 0xBB)))
    }

    @Test
    fun parses_frame_delivered_one_byte_at_a_time() {
        val framer = StreamFramer()
        val framed = StreamFraming.frame(message(1, 2, 3, 4, 5))
        val out = mutableListOf<ByteArray>()
        for (b in framed) {
            out += framer.feed(byteArrayOf(b))
        }
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(message(1, 2, 3, 4, 5)))
    }

    @Test
    fun parses_two_frames_in_one_chunk() {
        val framer = StreamFramer()
        val chunk = StreamFraming.frame(message(1)) + StreamFraming.frame(message(2, 2))
        val out = framer.feed(chunk)
        assertEquals(2, out.size)
        assertTrue(out[0].contentEquals(message(1)))
        assertTrue(out[1].contentEquals(message(2, 2)))
    }

    @Test
    fun skips_serial_log_text_before_a_frame() {
        val framer = StreamFramer()
        val log = "INFO | booting...\r\n".encodeToByteArray()
        val framed = StreamFraming.frame(message(0x42))
        val out = framer.feed(log + framed)
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(message(0x42)))
    }

    @Test
    fun skips_log_text_that_contains_a_stray_magic_byte() {
        val framer = StreamFramer()
        // 0x94 is printable-ish noise here: it must not swallow the real frame.
        val log = byteArrayOf(0x94.toByte(), 0x41, 0x42)
        val framed = StreamFraming.frame(message(0x42))
        val out = framer.feed(log + framed)
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(message(0x42)))
    }

    @Test
    fun resynchronises_after_an_implausible_length() {
        val framer = StreamFramer()
        // Magic bytes, then a length far beyond MAX_MESSAGE.
        val bogus = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val framed = StreamFraming.frame(message(0x07))
        val out = framer.feed(bogus + framed)
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(message(0x07)))
    }

    @Test
    fun reports_discarded_bytes() {
        val discarded = mutableListOf<Int>()
        val framer = StreamFramer()
        framer.onDiscardedByte = { discarded += it }
        framer.feed("AB".encodeToByteArray() + StreamFraming.frame(message(0x00)))
        assertEquals(listOf(0x41, 0x42), discarded)
    }

    @Test
    fun empty_frame_is_delivered() {
        val framer = StreamFramer()
        val out = framer.feed(StreamFraming.frame(ByteArray(0)))
        assertEquals(1, out.size)
        assertEquals(0, out[0].size)
    }
}
