package org.cairn.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

    private fun id(counter: Int, type: ObjectType = ObjectType.Pin, author: Byte = 1) =
        ObjectId(type, author, counter)

    private fun samplePoint(name: String? = "camp") = PointPacket(
        id = id(1),
        predecessor = id(0, ObjectType.RoutePoint, author = 1),
        flags = Flags(closed = false, deleted = false, emergency = false),
        latE7 = 470_123_456,
        lonE7 = 8_123_456,
        name = name,
    )

    @Test
    fun point_round_trip() {
        val p = samplePoint()
        val bytes = Codec.encode(p)
        assertEquals(p, Codec.decode(bytes))
    }

    @Test
    fun point_without_name_round_trip() {
        val p = samplePoint(name = null)
        assertEquals(p, Codec.decode(Codec.encode(p)))
    }

    @Test
    fun point_without_predecessor_round_trip() {
        val p = samplePoint().copy(predecessor = null)
        val decoded = Codec.decode(Codec.encode(p)) as PointPacket
        assertNull(decoded.predecessor)
    }

    @Test
    fun point_with_emergency_flag_round_trip() {
        val p = samplePoint().copy(flags = Flags(emergency = true))
        val decoded = Codec.decode(Codec.encode(p)) as PointPacket
        assertTrue(decoded.flags.emergency)
    }

    @Test
    fun message_round_trip() {
        val m = MessagePacket(
            id = id(10, ObjectType.Message),
            senderId = 7,
            timestampSec = 12345,
            referencedObject = id(1),
            text = "meet at the ridge",
        )
        assertEquals(m, Codec.decode(Codec.encode(m)))
    }

    @Test
    fun message_without_ref_round_trip() {
        val m = MessagePacket(
            id = id(11, ObjectType.Message),
            senderId = 7,
            timestampSec = 0,
            referencedObject = null,
            text = "hi",
        )
        assertEquals(m, Codec.decode(Codec.encode(m)))
    }

    @Test
    fun worst_case_point_under_230_bytes() {
        // 40-byte name, extreme coords
        val p = samplePoint(name = "x".repeat(Codec.MAX_NAME_BYTES))
            .copy(latE7 = Int.MIN_VALUE, lonE7 = Int.MAX_VALUE)
        assertTrue(Codec.encode(p).size <= Codec.MAX_PACKET_BYTES)
    }

    @Test
    fun worst_case_message_under_230_bytes() {
        val m = MessagePacket(
            id = id(12, ObjectType.Message),
            senderId = 7,
            timestampSec = Int.MAX_VALUE,
            referencedObject = id(1),
            text = "y".repeat(Codec.MAX_TEXT_BYTES),
        )
        assertTrue(Codec.encode(m).size <= Codec.MAX_PACKET_BYTES)
    }

    @Test
    fun oversized_name_rejected() {
        assertFailsWith<IllegalArgumentException> {
            Codec.encode(samplePoint(name = "z".repeat(Codec.MAX_NAME_BYTES + 1)))
        }
    }

    @Test
    fun empty_message_text_rejected() {
        val m = MessagePacket(
            id = id(13, ObjectType.Message),
            senderId = 1,
            timestampSec = 0,
            referencedObject = null,
            text = "",
        )
        assertFailsWith<IllegalArgumentException> { Codec.encode(m) }
    }

    @Test
    fun wrong_version_rejected() {
        val bytes = Codec.encode(samplePoint())
        bytes[0] = 0x09
        assertFailsWith<IllegalArgumentException> { Codec.decode(bytes) }
    }

    @Test
    fun truncated_packet_rejected() {
        val bytes = Codec.encode(samplePoint())
        assertFailsWith<IllegalArgumentException> {
            Codec.decode(bytes.copyOfRange(0, bytes.size - 1))
        }
    }
}
