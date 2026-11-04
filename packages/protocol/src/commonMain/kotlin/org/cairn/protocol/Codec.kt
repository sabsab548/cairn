package org.cairn.protocol

/**
 * Wire codec for Cairn packets. See docs/PROTOCOL.md for the byte layout.
 *
 * v0.1 scope:
 *   - single-point packets (point, pin, user position) — F-23
 *   - single message packets — F-50
 *   - tombstone packets — F-41
 *   - digest packets — F-83
 *
 * Explicitly NOT in v0.1:
 *   - F-32 multi-point route packing   → packages/protocol/TODO.md
 */
object Codec {
    const val VERSION: Byte = 0x01
    const val MAX_PACKET_BYTES: Int = 230
    const val MAX_NAME_BYTES: Int = 40
    const val MAX_TEXT_BYTES: Int = 180
    const val MAX_DIGEST_BYTES: Int = 40

    private const val KIND_POINT: Byte = 0x01
    private const val KIND_MESSAGE: Byte = 0x02
    private const val KIND_TOMBSTONE: Byte = 0x03
    private const val KIND_DIGEST: Byte = 0x04

    fun encode(packet: Packet): ByteArray = when (packet) {
        is PointPacket      -> encodePoint(packet)
        is MessagePacket    -> encodeMessage(packet)
        is TombstonePacket  -> encodeTombstone(packet)
        is DigestPacket     -> encodeDigest(packet)
    }

    fun decode(bytes: ByteArray): Packet? {
        require(bytes.size in 2..MAX_PACKET_BYTES) {
            "packet size ${bytes.size} outside [2, $MAX_PACKET_BYTES]"
        }
        require(bytes[0] == VERSION) { "unsupported version ${bytes[0]}" }
        return when (bytes[1]) {
            KIND_POINT      -> decodePoint(bytes)
            KIND_MESSAGE    -> decodeMessage(bytes)
            KIND_TOMBSTONE  -> decodeTombstone(bytes)
            KIND_DIGEST     -> decodeDigest(bytes)
            else            -> null // drop unknown kind silently
        }
    }

    // ------------------------------------------------------------ point

    private fun encodePoint(p: PointPacket): ByteArray {
        val name = p.name?.encodeToByteArray() ?: ByteArray(0)
        require(name.size <= MAX_NAME_BYTES) {
            "name too long: ${name.size} > $MAX_NAME_BYTES bytes"
        }
        // F-40: the flag and the field must agree, otherwise decode reads
        // either past the end of the buffer or into the name bytes.
        require(p.flags.hasExpiry == (p.expirySec != null)) {
            "hasExpiry flag (${p.flags.hasExpiry}) disagrees with expirySec (${p.expirySec})"
        }
        // 2 + id + predecessor + flags + version + lat + lon + nameLen + name + (expiry?)
        val expirySize = if (p.expirySec != null) 4 else 0
        val out = ByteArray(
            2 + ObjectId.SIZE + ObjectId.SIZE + 1 + Hlc.SIZE + 8 + 1 + name.size + expirySize,
        )
        var o = 0
        out[o++] = VERSION
        out[o++] = KIND_POINT
        p.id.toBytes().copyInto(out, o); o += ObjectId.SIZE

        val predBytes = p.predecessor?.toBytes() ?: ObjectId.NONE.toBytes()
        predBytes.copyInto(out, o); o += ObjectId.SIZE

        out[o++] = p.flags.toByte()
        p.version.toBytes().copyInto(out, o); o += Hlc.SIZE
        writeInt32(out, o, p.latE7); o += 4
        writeInt32(out, o, p.lonE7); o += 4
        out[o++] = name.size.toByte()
        name.copyInto(out, o); o += name.size

        // F-40: optional expiry field
        if (expirySize == 4) {
            writeInt32(out, o, p.expirySec!!); o += 4
        }
        return out
    }

    private fun decodePoint(b: ByteArray): PointPacket {
        // 2 + id + predecessor + flags + version + lat + lon + nameLen
        require(b.size >= 2 + ObjectId.SIZE * 2 + 1 + Hlc.SIZE + 4 + 4 + 1) {
            "point packet truncated"
        }
        var o = 2
        val id = ObjectId.fromBytes(b, o); o += ObjectId.SIZE

        val predRaw = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val pred = if (predRaw == ObjectId.NONE) null else predRaw

        val flags = Flags.fromByte(b[o++])
        val version = Hlc.fromBytes(b, o); o += Hlc.SIZE
        val lat = readInt32(b, o); o += 4
        val lon = readInt32(b, o); o += 4
        val nameLen = b[o++].toInt() and 0xFF
        require(nameLen <= MAX_NAME_BYTES) { "point name too long: $nameLen > $MAX_NAME_BYTES" }
        require(o + nameLen <= b.size) { "point name truncated" }
        val name = if (nameLen == 0) null else b.copyOfRange(o, o + nameLen).decodeToString()
        o += nameLen

        // F-40: optional expiry field
        var expirySec: Int? = null
        if (flags.hasExpiry) {
            require(o + 4 <= b.size) { "point expiry truncated" }
            expirySec = readInt32(b, o); o += 4
        }
        return PointPacket(id, pred, flags, version, lat, lon, name, expirySec)
    }

    // ------------------------------------------------------------ message

    private fun encodeMessage(m: MessagePacket): ByteArray {
        val text = m.text.encodeToByteArray()
        require(text.isNotEmpty()) { "message text must not be empty" }
        require(text.size <= MAX_TEXT_BYTES) {
            "message too long: ${text.size} > $MAX_TEXT_BYTES bytes"
        }
        // 2 + id + sender + version (ts + counter) + ref + len + text
        val out = ByteArray(2 + ObjectId.SIZE + 1 + Hlc.SIZE + 4 + 1 + text.size)
        var o = 0
        out[o++] = VERSION
        out[o++] = KIND_MESSAGE
        m.id.toBytes().copyInto(out, o); o += ObjectId.SIZE
        out[o++] = m.senderId
        m.version.toBytes().copyInto(out, o); o += Hlc.SIZE
        (m.referencedObject?.toBytes() ?: ByteArray(ObjectId.SIZE)).copyInto(out, o)
        o += ObjectId.SIZE
        out[o++] = text.size.toByte()
        text.copyInto(out, o)
        return out
    }

    private fun decodeMessage(b: ByteArray): MessagePacket {
        // 2 + id + sender + version + ref + textLen
        require(b.size >= 2 + ObjectId.SIZE + 1 + Hlc.SIZE + ObjectId.SIZE + 1) {
            "message packet truncated"
        }
        var o = 2
        val id = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val sender = b[o++]
        val version = Hlc.fromBytes(b, o); o += Hlc.SIZE
        val refRaw = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val ref = if (refRaw == ObjectId.NONE) null else refRaw
        val len = b[o++].toInt() and 0xFF
        require(len <= MAX_TEXT_BYTES) { "message too long: $len > $MAX_TEXT_BYTES" }
        require(o + len <= b.size) { "message text truncated" }
        val text = b.copyOfRange(o, o + len).decodeToString()
        require(text.isNotEmpty()) { "empty message text" }
        return MessagePacket(id, sender, version, ref, text)
    }

    // ------------------------------------------------------------ digest

    private fun encodeDigest(d: DigestPacket): ByteArray {
        require(d.filterBytes.size <= MAX_DIGEST_BYTES) {
            "digest filter too large: ${d.filterBytes.size} > $MAX_DIGEST_BYTES"
        }
        val out = ByteArray(2 + ObjectId.SIZE + 1 + d.filterBytes.size)
        var o = 0
        out[o++] = VERSION
        out[o++] = KIND_DIGEST
        d.id.toBytes().copyInto(out, o); o += ObjectId.SIZE
        out[o++] = d.filterBytes.size.toByte()
        d.filterBytes.copyInto(out, o)
        return out
    }

    private fun decodeDigest(b: ByteArray): DigestPacket {
        // 2 + id + filterLen
        require(b.size >= 2 + ObjectId.SIZE + 1) { "digest packet truncated" }
        var o = 2
        val id = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val filterLen = b[o++].toInt() and 0xFF
        require(filterLen <= MAX_DIGEST_BYTES) {
            "digest filter too large: $filterLen > $MAX_DIGEST_BYTES"
        }
        require(o + filterLen <= b.size) { "digest filter truncated" }
        val filterBytes = b.copyOfRange(o, o + filterLen)
        return DigestPacket(id, filterBytes)
    }

    // ------------------------------------------------------------ tombstone

    private fun encodeTombstone(t: TombstonePacket): ByteArray {
        val out = ByteArray(2 + ObjectId.SIZE * 2 + Hlc.SIZE) // 16
        var o = 0
        out[o++] = VERSION
        out[o++] = KIND_TOMBSTONE
        t.id.toBytes().copyInto(out, o); o += ObjectId.SIZE
        t.targetId.toBytes().copyInto(out, o); o += ObjectId.SIZE
        t.version.toBytes().copyInto(out, o); o += Hlc.SIZE
        return out
    }

    private fun decodeTombstone(b: ByteArray): TombstonePacket {
        require(b.size >= 2 + ObjectId.SIZE * 2 + Hlc.SIZE) { "tombstone packet truncated" }
        var o = 2
        val id = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val targetId = ObjectId.fromBytes(b, o); o += ObjectId.SIZE
        val version = Hlc.fromBytes(b, o); o += Hlc.SIZE
        return TombstonePacket(id, targetId, version)
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
