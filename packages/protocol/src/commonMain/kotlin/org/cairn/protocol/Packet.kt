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
 * [version] is the edit stamp used by latest-wins (F-81, ADR-006). It is
 * deliberately **not** defaulted: a defaulted `Hlc.ZERO` would make every
 * edit look stale and silently drop it (the same trap the old defaulted
 * receive timestamp had).
 *
 * [expirySec] is null when unset (F-40). When non-null, the wire includes
 * a 4-byte expiry field after the name.
 */
data class PointPacket(
    override val id: ObjectId,
    val predecessor: ObjectId?,
    val flags: Flags,
    val version: Hlc,
    val latE7: Int,
    val lonE7: Int,
    val name: String?, // UTF-8, <= 40 bytes on the wire
    val expirySec: Int? = null, // F-40 optional
) : Packet() {
    override val size: Int
        get() = 26 + (name?.encodeToByteArray()?.size ?: 0) +
                (if (flags.hasExpiry) 4 else 0)
}

/**
 * A short text message (F-50).
 *
 * [version] carries the send time as well: `version.sec` is the `ts` used by
 * the message list (F-51). Messages are never edited, so the logical counter
 * stays 0 in practice.
 */
data class MessagePacket(
    override val id: ObjectId,
    val senderId: Byte,
    val version: Hlc,
    val referencedObject: ObjectId?, // F-52 optional map-object ref
    val text: String,             // UTF-8, <= 180 bytes
) : Packet() {
    /** Seconds since 2026-01-01T00:00:00Z. Display only; ordering uses [version]. */
    val timestampSec: Int get() = version.sec

    override val size: Int
        get() = 18 + text.encodeToByteArray().size
}

/**
 * Tombstone (F-41): signals that [targetId] is deleted as of [version].
 *
 * The tombstone is **not** an object of its own: [version] competes with the
 * target's own version, so a stale copy cannot resurrect the target and a
 * newer edit can still undelete it (ADR-006).
 */
data class TombstonePacket(
    override val id: ObjectId,
    val targetId: ObjectId,
    val version: Hlc,
) : Packet() {
    override val size: Int = 16 // 2 + 4 + 4 + 6
}

/**
 * Digest (F-83): "here is what I have" — compact filter over known IDs.
 *
 * Not a `data class`: the generated `equals`/`hashCode` would compare the
 * [filterBytes] array by reference, which breaks dedupe and round-trip tests.
 */
class DigestPacket(
    override val id: ObjectId,
    val filterBytes: ByteArray,
) : Packet() {
    override val size: Int = 2 + ObjectId.SIZE + 1 + filterBytes.size

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DigestPacket && id == other.id && filterBytes.contentEquals(other.filterBytes))

    override fun hashCode(): Int = 31 * id.hashCode() + filterBytes.contentHashCode()

    override fun toString(): String =
        "DigestPacket(id=$id, filterBytes=${filterBytes.contentToString()})"
}
