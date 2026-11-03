package org.cairn.protocol

/**
 * Wire codec for Cairn packets. See docs/PROTOCOL.md for the byte layout.
 *
 * v0.1 scope:
 *   - single-point packets (point, pin, user position) — F-23
 *   - single message packets — F-50
 *   - nothing else
 *
 * Explicitly NOT in v0.1:
 *   - F-32 multi-point route packing   → packages/protocol/TODO.md
 *   - F-40 optional expiry field       → packages/protocol/TODO.md
 *   - F-41 tombstone packet kind       → packages/protocol/TODO.md
 *   - F-83 digest packet kind          → packages/protocol/TODO.md
 */
object Codec {
    const val VERSION: Byte = 0x01
    const val MAX_PACKET_BYTES: Int = 230
    const val MAX_NAME_BYTES: Int = 40
    const val MAX_TEXT_BYTES: Int = 180

    private const val KIND_POINT: Byte = 0x01
    private const val KIND_MESSAGE: Byte = 0x02

    fun encode(packet: Packet): ByteArray = when (packet) {
        is PointPacket   -> encodePoint(packet)
        is MessagePacket -> encodeMessage(packet)
    }

    fun decode(bytes: ByteArray): Packet {
        require(bytes.size in 2..MAX_PACKET_BYTES) {
            "packet size ${bytes.size} outside [2, $MAX_PACKET_BYTES]"
        }
        require(bytes[0] == VERSION) { "unsupported version ${bytes[0]}" }
        return when (bytes[1]) {
            KIND_POINT   -> decodePoint(bytes)
            KIND_MESSAGE -> decodeMessage(bytes)
            else         -> error("unknown packet kind ${bytes[1]}")
        }
    }

    // ------------------------------------------------------------ point

    private fun encodePoint(p: PointPacket): ByteArray {
        val name = p.name?.encodeToByteArray() ?: ByteArray(0)
        require(name.size <= MAX_NAME_BYTES) {
            "name too long: ${name.size} > $MAX_NAME_BYTES bytes"
        }
        // 2 (version+kind) + 4 (id) + 4 (pred) + 1 (flags) + 4 + 4 + 1 + name
        val out = ByteArray(2 + ObjectId.SIZE + ObjectId.SIZE + 1 + 8 + 1 + name.size)
        var o = 0
        out[o++] = VERSION
        out[o++] = KIND_POINT
        p.id.toBytes().copyInto(out, o); o += ObjectId.SIZE

        // TODO(F-41): sentinel 0x00000000 for "no predecessor" collides with
        // the legitimate ID author=0,counter=0. See packages/protocol/TODO.md.
        val predBytes = p.predecessor?.toBytes() ?: ByteArray(ObjectId.SIZE)
        predBytes.copyInto(out, o); o += ObjectId.SIZE

        out[o++] = p.flags.toByte()
        writeInt32(out, o, p.latE7); o += 4
        writeInt32(out, o, p.lonE7); o += 4
        out[o++] = name.size.toByte()
        name.copyInto(out, o)
        return out
    }

    private fun decodePoint(b: ByteArray): PointPacket {
        var o = 2
        val id = ObjectId.fromBytes(b, o); o += ObjectId.SIZE

        val predRaw = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val pred = if (predRaw == ObjectId.NONE) null else predRaw

        val flags = Flags.fromByte(b[o++])
        val lat = readInt32(b, o); o += 4
        val lon = readInt32(b, o); o += 4
        require(o < b.size) { "point packet truncated before nameLen" }
        val nameLen = b[o++].toInt() and 0xFF
        require(o + nameLen <= b.size) { "point name truncated" }
        val name = if (nameLen == 0) null else b.copyOfRange(o, o + nameLen).decodeToString()
        return PointPacket(id, pred, flags, lat, lon, name)
    }

    // ------------------------------------------------------------ message

    private fun encodeMessage(m: MessagePacket): ByteArray {
        val text = m.text.encodeToByteArray()
        require(text.isNotEmpty()) { "message text must not be empty" }
        require(text.size <= MAX_TEXT_BYTES) {
            "message too long: ${text.size} > $MAX_TEXT_BYTES bytes"
        }
        // 2 + 4 (id) + 1 (sender) + 4 (ts) + 4 (ref) + 1 (len) + text
        val out = ByteArray(2 + ObjectId.SIZE + 1 + 4 + 4 + 1 + text.size)
        var o = 0
        out[o++] = VERSION
        out[o++] = KIND_MESSAGE
        m.id.toBytes().copyInto(out, o); o += ObjectId.SIZE
        out[o++] = m.senderId
        writeInt32(out, o, m.timestampSec); o += 4
        (m.referencedObject?.toBytes() ?: ByteArray(ObjectId.SIZE)).copyInto(out, o)
        o += ObjectId.SIZE
        out[o++] = text.size.toByte()
        text.copyInto(out, o)
        return out
    }

    private fun decodeMessage(b: ByteArray): MessagePacket {
        var o = 2
        val id = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val sender = b[o++]
        val ts = readInt32(b, o); o += 4
        val refRaw = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val ref = if (refRaw == ObjectId.NONE) null else refRaw
        require(o < b.size) { "message packet truncated before textLen" }
        val len = b[o++].toInt() and 0xFF
        require(o + len <= b.size) { "message text truncated" }
        val text = b.copyOfRange(o, o + len).decodeToString()
        require(text.isNotEmpty()) { "empty message text" }
        return MessagePacket(id, sender, ts, ref, text)
    }

    // ------------------------------------------------------------ helpers

    private fun writeInt32(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = (v ushr 24).toByte()
        buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    private fun readInt32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
        ((buf[off + 1].toInt() and 0xFF) shl 16) or
        ((buf[off + 2].toInt() and 0xFF) shl 8) or
        (buf[off + 3].toInt() and 0xFF)
}
