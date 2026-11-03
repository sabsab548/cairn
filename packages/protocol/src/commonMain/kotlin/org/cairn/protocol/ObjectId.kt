package org.cairn.protocol

/**
 * Wire-format object identifier. 4 bytes: [type 1B][author 1B][counter 2B].
 * See docs/PROTOCOL.md.
 */
enum class ObjectType(val tag: Byte) {
    UserPosition(0x00),
    RoutePoint(0x01),
    Pin(0x02),
    Message(0x03),
    Unknown(0xFF.toByte());

    companion object {
        fun fromTag(tag: Byte): ObjectType =
            entries.firstOrNull { it.tag == tag } ?: Unknown
    }
}

data class ObjectId(
    val type: ObjectType,
    val authorId: Byte,
    val counter: Int,
) {
    init {
        require(counter in 0..0xFFFF) { "counter must fit in 2 bytes (got $counter)" }
    }

    fun toBytes(): ByteArray = byteArrayOf(
        type.tag,
        authorId,
        (counter ushr 8).toByte(),
        counter.toByte(),
    )

    companion object {
        const val SIZE = 4

        fun fromBytes(buf: ByteArray, offset: Int = 0): ObjectId {
            require(offset >= 0 && buf.size - offset >= SIZE) {
                "buffer too short: need $SIZE bytes at offset $offset, have ${buf.size}"
            }
            return ObjectId(
                type = ObjectType.fromTag(buf[offset]),
                authorId = buf[offset + 1],
                counter = ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                          (buf[offset + 3].toInt() and 0xFF),
            )
        }

        /** Sentinel used in the wire format for "no predecessor". */
        val NONE = ObjectId(ObjectType.Unknown, 0, 0)
    }
}
