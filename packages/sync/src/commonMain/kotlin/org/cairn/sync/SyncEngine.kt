package org.cairn.sync

import org.cairn.protocol.ObjectId
import org.cairn.protocol.Packet
import org.cairn.protocol.PointPacket
import org.cairn.protocol.MessagePacket

/**
 * Pure, platform-independent state machine.
 *
 * Guarantees (once complete, see packages/sync/TODO.md):
 *   F-80  duplicate packets are ignored
 *   F-81  latest-wins on ID conflicts (by receive timestamp)
 *   F-82  references to unseen predecessors are queued and resolved later
 *   F-40  expiry greys then removes
 *   F-41  deletion propagates as a tombstone
 *
 * Non-goals (handled elsewhere):
 *   - BLE transport
 *   - persistence (SQLite)
 *   - airtime throttling (see F-84 / Priority.kt, TODO)
 */
class SyncEngine(
    private val now: () -> Long = { 0L },
) {

    /** What we know about one object. */
    data class Stored(
        val packet: Packet,
        val receivedAtMs: Long,
    )

    private val objects = mutableMapOf<ObjectId, Stored>()

    /**
     * IDs waiting for a predecessor that we have not seen yet.
     * Map: missing-predecessor-ID -> list of object-IDs that depend on it.
     */
    private val pendingRefs = mutableMapOf<ObjectId, MutableList<ObjectId>>()

    /**
     * Apply an inbound packet. Returns the events the UI should react to.
     *
     * Both the wire-level and the app-level path go through this method.
     */
    fun ingest(packet: Packet, receivedAtMs: Long = now()): List<Event> {
        val id = idOf(packet)

        // F-80: exact duplicate
        val existing = objects[id]
        if (existing != null && existing.packet == packet) return emptyList()

        // F-81: latest-wins. Older or equal-timestamp packet loses.
        if (existing != null && existing.receivedAtMs >= receivedAtMs) return emptyList()

        objects[id] = Stored(packet, receivedAtMs)
        val events = mutableListOf<Event>()
        events += Event.ObjectUpserted(id, packet)

        // Register pending references BEFORE resolving, so out-of-order
        // arrival (point first, predecessor second) is also handled.
        if (packet is PointPacket && packet.predecessor != null) {
            if (packet.predecessor !in objects) {
                pendingRefs.getOrPut(packet.predecessor) { mutableListOf() }.add(id)
            }
        }

        // F-82: anything waiting on THIS id can now be resolved.
        pendingRefs.remove(id)?.let { waiting ->
            // TODO(F-82): emit ObjectReady events for each waiting object.
            // For now: just clear the queue so repeated arrivals don't grow it.
            events += waiting.map { Event.PendingRefResolved(it) }
        }

        return events
    }

    fun snapshot(): List<Stored> = objects.values.toList()

    fun get(id: ObjectId): Stored? = objects[id]

    // TODO(F-40): fun sweep(nowMs: Long): List<Event> — grey then remove expired.
    // TODO(F-41): ingest tombstone packets; re-broadcast to catch-up peers.
    // TODO(F-83): fun digest(): ByteArray — "here is what I have".
    // TODO(F-84): send queue with priority; see Priority.kt.

    private fun idOf(p: Packet): ObjectId = when (p) {
        is PointPacket   -> p.id
        is MessagePacket -> p.id
    }
}

/** Events emitted by the sync engine; the UI subscribes to these. */
sealed class Event {
    data class ObjectUpserted(val id: ObjectId, val packet: Packet) : Event()
    data class PendingRefResolved(val waitingId: ObjectId) : Event()
    // TODO: ObjectExpired, ObjectDeleted, EmergencyReceived
}
