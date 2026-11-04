package org.cairn.protocol

/**
 * Point/route flags, one byte on the wire.
 * Layout documented in docs/PROTOCOL.md.
 */
data class Flags(
    val closed: Boolean = false,
    val deleted: Boolean = false,
    val emergency: Boolean = false,
    val hasExpiry: Boolean = false, // F-40
) {
    fun toByte(): Byte {
        var b = 0
        if (closed) b = b or MASK_CLOSED
        if (deleted) b = b or MASK_DELETED
        if (emergency) b = b or MASK_EMERGENCY
        if (hasExpiry) b = b or MASK_HAS_EXPIRY
        return b.toByte()
    }

    companion object {
        const val MASK_CLOSED: Int = 0x01
        const val MASK_DELETED: Int = 0x02
        const val MASK_EMERGENCY: Int = 0x04
        const val MASK_HAS_EXPIRY: Int = 0x08

        fun fromByte(b: Byte): Flags {
            val v = b.toInt() and 0xFF
            return Flags(
                closed = (v and MASK_CLOSED) != 0,
                deleted = (v and MASK_DELETED) != 0,
                emergency = (v and MASK_EMERGENCY) != 0,
                hasExpiry = (v and MASK_HAS_EXPIRY) != 0,
            )
        }
    }
}
