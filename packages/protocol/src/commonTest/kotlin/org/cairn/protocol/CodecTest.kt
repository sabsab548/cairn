package org.cairn.protocol

import kotlin.random.Random
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

    private fun sampleMessage() = MessagePacket(
        id = id(10, ObjectType.Message),
        senderId = 7,
        timestampSec = 12_345,
        referencedObject = id(1),
        text = "meet at the ridge",
    )

    private fun sampleTombstone() = TombstonePacket(
        id = id(1, ObjectType.Message),
        targetId = id(42),
        timestampSec = 98_765,
    )

    private fun sampleDigest() = DigestPacket(
        id = id(99, ObjectType.Message),
        filterBytes = byteArrayOf(1, 2, 3),
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
        val rnd = Random(42)
        repeat(20_000) {
            val data = ByteArray(rnd.nextInt(0, Codec.MAX_PACKET_BYTES + 2)) { rnd.nextInt().toByte() }
            // Valid version, then every known kind plus 0x05 (unknown) so the
            // sub-decoders are actually reached. (The previous version built
            // kind = version + 13 and never decoded anything at all.)
            if (data.isNotEmpty()) data[0] = Codec.VERSION
            if (data.size > 1) data[1] = (1 + rnd.nextInt(5)).toByte()
            assertDecodeFailsCleanly(data)
        }
    }

    @Test
    fun every_truncation_of_a_valid_packet_fails_cleanly() {
        listOf(
            samplePoint(),
            samplePoint(name = null),
            samplePoint(name = "camp").copy(flags = Flags(hasExpiry = true), expirySec = 1_000),
            sampleMessage(),
            sampleTombstone(),
            sampleDigest(),
        ).map(Codec::encode).forEach { bytes ->
            for (n in 0 until bytes.size) assertDecodeFailsCleanly(bytes.copyOf(n))
        }
    }

    private fun assertDecodeFailsCleanly(bytes: ByteArray) {
        runCatching { Codec.decode(bytes) }.exceptionOrNull()?.let {
            assertTrue(
                it is IllegalArgumentException,
                "unexpected exception: $it (input: ${bytes.contentToString()})",
            )
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

    @Test
    fun flags_byte_matches_protocol_layout() {
        assertEquals(0x00.toByte(), Flags().toByte())
        assertEquals(0x01.toByte(), Flags(closed = true).toByte())
        assertEquals(0x02.toByte(), Flags(deleted = true).toByte())
        assertEquals(0x04.toByte(), Flags(emergency = true).toByte())
        assertEquals(0x08.toByte(), Flags(hasExpiry = true).toByte())   // PROTOCOL.md bit 3
    }

    @Test
    fun oversized_name_on_wire_rejected() {
        val base = Codec.encode(samplePoint(name = "x".repeat(Codec.MAX_NAME_BYTES)))
        val bytes = base.copyOf(base.size + 1)
        bytes[19] = (Codec.MAX_NAME_BYTES + 1).toByte()   // nameLen
        assertFailsWith<IllegalArgumentException> { Codec.decode(bytes) }
    }

    @Test
    fun oversized_text_on_wire_rejected() {
        val base = Codec.encode(
            MessagePacket(id(99, ObjectType.Message), 1, 0, null, "y".repeat(Codec.MAX_TEXT_BYTES)),
        )
        val bytes = base.copyOf(base.size + 1)
        bytes[15] = (Codec.MAX_TEXT_BYTES + 1).toByte()   // textLen
        assertFailsWith<IllegalArgumentException> { Codec.decode(bytes) }
    }
}
