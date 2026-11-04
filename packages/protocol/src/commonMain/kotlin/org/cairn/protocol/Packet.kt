package org.cairn.protocol

/** Sealed hierarchy of wire packets. Each `Packet` == one LoRa payload. */
sealed class Packet {
    /** Object identity on the wire; every packet carries exactly one. */
    abstract val id: ObjectId

    /** Estimated wire size in bytes. Computed before encoding. */
    abstract val size: Int
}

/**
 * A point on the map: user position (F-10), pin (F-20) or route point (F-30).
 *
 * [predecessor] is null for standalone pins and for the first point of a
 * route (F-30 / F-35). Otherwise it points at the previous point's ID.
 *
 * [expirySec] is null when unset (F-40). When non-null, the wire includes
 * a 4-byte expiry field after the name.
 */
data class PointPacket(
    override val id: ObjectId,
    val predecessor: ObjectId?,
    val flags: Flags,
    val latE7: Int,
    val lonE7: Int,
    val name: String?, // UTF-8, <= 40 bytes on the wire
    val expirySec: Int? = null, // F-40 optional
) : Packet() {
    override val size: Int
        get() = 20 + (name?.toByteArray()?.size ?: 0) +
                (if (flags.hasExpiry) 4 else 0)
}

/** A short text message (F-50). */
data class MessagePacket(
    override val id: ObjectId,
    val senderId: Byte,
    val timestampSec: Int,        // seconds since 2026-01-01T00:00:00Z
    val referencedObject: ObjectId?, // F-52 optional map-object ref
    val text: String,             // UTF-8, <= 180 bytes
) : Packet() {
    override val size: Int
        get() = 16 + text.toByteArray().size
}

/** Tombstone (F-41): signals that [targetId] is deleted. */
data class TombstonePacket(
    override val id: ObjectId,
    val targetId: ObjectId,
    val timestampSec: Int,
) : Packet() {
    override val size: Int = 2 + ObjectId.SIZE * 2 + 4 // 14
}

/** Digest (F-83): "here is what I have" — compact filter over known IDs. */
data class DigestPacket(
    override val id: ObjectId,
    val filterBytes: ByteArray,
) : Packet() {
    override val size: Int = 2 + ObjectId.SIZE + 1 + filterBytes.size
}

