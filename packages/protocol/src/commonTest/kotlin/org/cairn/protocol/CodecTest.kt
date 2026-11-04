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
        val decoded = Codec.decode(Codec.encode(p))!! as PointPacket
        assertNull(decoded.predecessor)
    }

    @Test
    fun point_with_emergency_flag_round_trip() {
        val p = samplePoint().copy(flags = Flags(emergency = true))
        val decoded = Codec.decode(Codec.encode(p))!! as PointPacket
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

    @Test
    fun unknown_kind_dropped_silently() {
        val bytes = Codec.encode(samplePoint()).toMutableList().toByteArray()
        bytes[1] = 0xFF.toByte() // unknown kind
        assertNull(Codec.decode(bytes))
    }

    @Test
    fun fuzz_decode_only_ila_error() {
        val seed = 12345L
        for (i in 0 until 10_000) {
            val len = (seed + i).toInt() and 0xFF
            val data = ByteArray(len) { ((seed + i * 7 + it * 13) and 0xFF).toByte() }
            val result = runCatching { Codec.decode(data) }
            // must either succeed or throw IllegalArgumentException
            if (result.isFailure) {
                assertTrue(
                    result.exceptionOrNull() is IllegalArgumentException,
                    "unexpected exception for len=$len: ${result.exceptionOrNull()!!.javaClass}",
                )
            }
        }
    }

    @Test
    fun oversized_packet_rejected() {
        val bytes = ByteArray(Codec.MAX_PACKET_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { Codec.decode(bytes) }
    }

    @Test
    fun worst_case_utf8_point_under_230_bytes() {
        // 10 emoji = 40 UTF-8 bytes, but only 10 characters.
        // Verifies the byte-size assertion (not char-count) is used.
        val p = samplePoint(name = "😀".repeat(10))
            .copy(latE7 = Int.MIN_VALUE, lonE7 = Int.MAX_VALUE)
        assertTrue(Codec.encode(p).size <= Codec.MAX_PACKET_BYTES)
    }

    @Test
    fun utf8_name_over_40_bytes_rejected() {
        // 11 emoji = 44 UTF-8 bytes (> MAX_NAME_BYTES), but only 11 chars.
        val p = samplePoint(name = "😀".repeat(11))
        assertFailsWith<IllegalArgumentException> { Codec.encode(p) }
    }

    @Test
    fun tombstone_round_trip() {
        val t = TombstonePacket(
            id = id(1, ObjectType.Message),
            targetId = id(42),
            timestampSec = 98765,
        )
        assertEquals(t, Codec.decode(Codec.encode(t)))
    }

    @Test
    fun point_expiry_round_trip() {
        val p = samplePoint().copy(
            flags = Flags(hasExpiry = true),
            expirySec = 100_000,
        )
        val decoded = Codec.decode(Codec.encode(p))!! as PointPacket
        assertTrue(decoded.flags.hasExpiry)
        assertEquals(100_000, decoded.expirySec)
    }

    @Test
    fun point_with_name_and_expiry_round_trip() {
        val p = samplePoint(name = "camp").copy(
            flags = Flags(hasExpiry = true),
            expirySec = 100_000,
        )
        // Full equality: catches the expiry field overwriting the name.
        assertEquals(p, Codec.decode(Codec.encode(p)))
    }

    @Test
    fun has_expiry_flag_without_field_rejected() {
        val p = samplePoint().copy(flags = Flags(hasExpiry = true), expirySec = null)
        assertFailsWith<IllegalArgumentException> { Codec.encode(p) }
    }

    @Test
    fun digest_round_trip() {
        val d = DigestPacket(
            id = id(1, ObjectType.Message),
            filterBytes = byteArrayOf(0x01, 0x02, 0x03),
        )
        assertEquals(d, Codec.decode(Codec.encode(d)))
    }

    @Test
    fun digest_max_size_under_230_bytes() {
        val d = DigestPacket(
            id = id(99, ObjectType.Message),
            filterBytes = ByteArray(Codec.MAX_DIGEST_BYTES) { 0.toByte() },
        )
        assertTrue(Codec.encode(d).size <= Codec.MAX_PACKET_BYTES)
    }

    @Test
    fun digest_oversized_filter_rejected() {
        val d = DigestPacket(
            id = id(99, ObjectType.Message),
            filterBytes = ByteArray(Codec.MAX_DIGEST_BYTES + 1) { 0.toByte() },
        )
        assertFailsWith<IllegalArgumentException> { Codec.encode(d) }
    }
}
