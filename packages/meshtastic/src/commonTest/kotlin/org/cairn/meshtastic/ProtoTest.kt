package org.cairn.meshtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoTest {

    @Test
    fun varint_round_trips() {
        val values = listOf(0L, 1L, 127L, 128L, 300L, 0xFFFFL, 0xFFFF_FFFFL, Long.MAX_VALUE)
        for (v in values) {
            val w = ProtoWriter()
            w.varint(v)
            val r = ProtoReader(w.toByteArray())
            assertEquals(v, r.readVarint(), "varint $v")
        }
    }

    @Test
    fun varint_matches_protobuf_reference_encoding() {
        // 300 = 0b1_0010_1100 -> 0xAC 0x02
        val w = ProtoWriter()
        w.varint(300)
        assertTrue(w.toByteArray().contentEquals(byteArrayOf(0xAC.toByte(), 0x02)))
    }

    @Test
    fun uint32_encodes_negative_int_as_unsigned() {
        val w = ProtoWriter()
        w.uint32(1, -1) // 0xFFFFFFFF
        val bytes = w.toByteArray()
        // tag 0x08, then five-byte varint 0xFF FF FF FF 0F
        assertTrue(bytes.contentEquals(byteArrayOf(0x08, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)))
        val r = ProtoReader(bytes)
        assertEquals(1, r.nextField())
        assertEquals(-1, r.readUInt32())
    }

    @Test
    fun fixed32_is_little_endian() {
        val w = ProtoWriter()
        w.fixed32(2, 0x11223344)
        val bytes = w.toByteArray()
        assertTrue(bytes.contentEquals(byteArrayOf(0x15, 0x44, 0x33, 0x22, 0x11)))
        val r = ProtoReader(bytes)
        assertEquals(2, r.nextField())
        assertEquals(0x11223344, r.readFixed32())
    }

    @Test
    fun float_and_string_round_trip() {
        val w = ProtoWriter()
        w.float(1, -3.5f)
        w.string(2, "Grüße")
        val r = ProtoReader(w.toByteArray())
        assertEquals(1, r.nextField())
        assertEquals(-3.5f, r.readFloat())
        assertEquals(2, r.nextField())
        assertEquals("Grüße", r.readString())
    }

    @Test
    fun embedded_message_round_trips() {
        val inner = ProtoWriter().apply { uint32(1, 42) }
        val outer = ProtoWriter().apply { message(3, inner) }
        val r = ProtoReader(outer.toByteArray())
        assertEquals(3, r.nextField())
        val sub = r.readMessage()
        assertEquals(1, sub.nextField())
        assertEquals(42, sub.readUInt32())
        assertNull(sub.nextField())
    }

    @Test
    fun unknown_fields_are_skipped() {
        val w = ProtoWriter()
        w.uint32(1, 7) // known
        w.fixed32(2, 99) // unknown fixed32
        w.string(3, "junk") // unknown length-delimited
        w.uint32(127, 5) // unknown varint
        w.uint32(4, 8) // known

        val r = ProtoReader(w.toByteArray())
        val seen = mutableListOf<Pair<Int, Int>>()
        while (true) {
            val field = r.nextField() ?: break
            when (field) {
                1, 4 -> seen += field to r.readUInt32()
                else -> r.skipValue()
            }
        }
        assertEquals(listOf(1 to 7, 4 to 8), seen)
    }

    @Test
    fun truncated_input_throws() {
        val w = ProtoWriter().apply { string(1, "hello") }
        val bytes = w.toByteArray()
        val truncated = bytes.copyOfRange(0, bytes.size - 2)
        val r = ProtoReader(truncated)
        assertEquals(1, r.nextField())
        val error = runCatching { r.readString() }.exceptionOrNull()
        assertNotNull(error)
    }
}
