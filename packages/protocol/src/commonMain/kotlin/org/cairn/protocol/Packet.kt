package org.cairn.protocol

/** Sealed hierarchy of wire packets. Each `Packet` == one LoRa payload. */
sealed class Packet

/**
 * A point on the map: user position (F-10), pin (F-20) or route point (F-30).
 *
 * [predecessor] is null for standalone pins and for the first point of a
 * route (F-30 / F-35). Otherwise it points at the previous point's ID.
 */
data class PointPacket(
    val id: ObjectId,
    val predecessor: ObjectId?,
    val flags: Flags,
    val latE7: Int,
    val lonE7: Int,
    val name: String?, // UTF-8, <= 40 bytes on the wire
) : Packet()

/** A short text message (F-50). */
data class MessagePacket(
    val id: ObjectId,
    val senderId: Byte,
    val timestampSec: Int,        // seconds since 2026-01-01T00:00:00Z
    val referencedObject: ObjectId?, // F-52 optional map-object ref
    val text: String,             // UTF-8, <= 180 bytes
) : Packet()
