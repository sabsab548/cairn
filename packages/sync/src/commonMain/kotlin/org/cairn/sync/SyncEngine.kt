package org.cairn.sync

import org.cairn.protocol.Codec
import org.cairn.protocol.DigestPacket
import org.cairn.protocol.Hlc
import org.cairn.protocol.MessagePacket
import org.cairn.protocol.ObjectId
import org.cairn.protocol.Packet
import org.cairn.protocol.PointPacket
import org.cairn.protocol.TombstonePacket

/**
 * Pure, platform-independent state machine.
 *
 * Guarantees (see packages/sync/TODO.md):
 *   F-80  duplicate packets are ignored
 *   F-81  latest-wins on ID conflicts, ordered by the packet's own [Hlc] stamp
 *         (docs/DECISIONS.md ADR-006)
 *   F-82  references to unseen predecessors are queued and resolved later
 *   F-41  deletion is a tombstone whose version competes with the target's
 *   F-40  expiry greys then removes
 *
 * Non-goals (handled elsewhere):
 *   - BLE transport
 *   - persistence (SQLite)
 *   - airtime throttling (see F-84 / Priority.kt, TODO)
 */
class SyncEngine(
    // Only used for "when did we last hear about this" (F-40 staleness,
    // F-84 priority). Conflict resolution NEVER uses this clock: it uses the
    // sender's stamp carried in the packet. A receive-time clock made
    // latest-wins depend on delivery order, so peers diverged.
    private val now: () -> Long,
) {

    /**
     * What we know about one object.
     *
     * [packet] is null when the object is deleted: the payload is dropped, only
     * the id, the winning [version] and [authorId] are kept as a floor. That
     * floor is what stops an offline peer from resurrecting a deleted object.
     */
    data class Stored(
        val packet: Packet?,
        val version: Hlc,
        val authorId: Byte,
        val receivedAtMs: Long,
    ) {
        val deleted: Boolean get() = packet == null
    }

    private val objects = mutableMapOf<ObjectId, Stored>()

    /**
     * IDs waiting for a predecessor that we have not seen yet.
     * Map: missing-predecessor-ID -> list of object-IDs that depend on it.
     */
    private val pendingRefs = mutableMapOf<ObjectId, MutableList<ObjectId>>()

    /** Reverse of [pendingRefs], so a re-pointed edit can drop its stale entry. */
    private val waitingOn = mutableMapOf<ObjectId, ObjectId>()

    /**
     * Apply an inbound packet. Returns the events the UI should react to.
     *
     * Both the wire-level and the app-level path go through this method.
     */
    fun ingest(packet: Packet, receivedAtMs: Long = now()): List<Event> = when (packet) {
        is TombstonePacket -> applyTombstone(packet, receivedAtMs)
        is DigestPacket -> ingestDigest(packet, receivedAtMs)
        is PointPacket -> upsert(packet.id, packet, packet.version, packet.id.authorId, receivedAtMs)
        is MessagePacket -> upsert(packet.id, packet, packet.version, packet.id.authorId, receivedAtMs)
    }

    /** F-80 + F-81 for objects that exist in their own right. */
    private fun upsert(
        id: ObjectId,
        packet: Packet,
        version: Hlc,
        authorId: Byte,
        receivedAtMs: Long,
    ): List<Event> {
        // F-81: only a strictly greater version wins. Ties are broken on
        // author, then on content, so every peer picks the same winner.
        if (!beats(version, authorId, packet, objects[id])) return emptyList()

        objects[id] = Stored(packet, version, authorId, receivedAtMs)
        val events = mutableListOf<Event>()
        events += Event.ObjectUpserted(id, packet)

        // Register pending references BEFORE resolving, so out-of-order
        // arrival (point first, predecessor second) is also handled.
        registerPending(id, packet)

        // F-82: anything waiting on THIS id can now be resolved.
        pendingRefs.remove(id)?.let { waiting ->
            waiting.forEach { waitingOn.remove(it) }
            events += waiting.map { Event.PendingRefResolved(it, orphaned = false) }
        }

        return events
    }

    /**
     * F-41: a tombstone is a *version of the target*, not an object of its own.
     * If it wins, the payload is dropped and the version floor is raised.
     */
    private fun applyTombstone(tombstone: TombstonePacket, receivedAtMs: Long): List<Event> {
        val target = tombstone.targetId
        // ObjectId.NONE is the "no reference" sentinel, never a real object.
        if (target == ObjectId.NONE) return emptyList()

        if (!beats(tombstone.version, tombstone.id.authorId, null, objects[target])) {
            return emptyList()
        }

        objects[target] = Stored(
            packet = null,
            version = tombstone.version,
            authorId = tombstone.id.authorId,
            receivedAtMs = receivedAtMs,
        )
        val events = mutableListOf<Event>()
        events += Event.ObjectDeleted(target)

        // A deleted object is not waiting for its own predecessor any more.
        unregisterPending(target)

        // A deleted predecessor can never arrive, so its waiters are not
        // pending any more: they are the heads of split-off route segments.
        pendingRefs.remove(target)?.let { waiting ->
            waiting.forEach { waitingOn.remove(it) }
            events += waiting.map { Event.PendingRefResolved(it, orphaned = true) }
        }

        return events
    }

    /** F-83 is not implemented yet; digests are not map objects. */
    private fun ingestDigest(packet: DigestPacket, receivedAtMs: Long): List<Event> {
        val previous = objects[packet.id]?.packet
        // A digest must never shadow a real object that happens to share its ID.
        if (previous != null && previous !is DigestPacket) return emptyList()
        if (previous is DigestPacket && previous == packet) return emptyList()

        objects[packet.id] = Stored(packet, Hlc.ZERO, packet.id.authorId, receivedAtMs)
        return listOf(Event.ObjectUpserted(packet.id, packet))
    }

    /**
     * Total order for latest-wins, in this order:
     *   1. [Hlc] stamp (physical seconds, then logical counter),
     *   2. author ID — deterministic when two peers edit concurrently,
     *   3. content — only reached if one author issued two different versions
     *      with the same stamp (a bug or a collision, ADR-004). Keeping the
     *      greater content hash still makes all peers agree.
     */
    private fun beats(
        version: Hlc,
        authorId: Byte,
        packet: Packet?,
        existing: Stored?,
    ): Boolean {
        if (existing == null) return true

        val byVersion = version.compareTo(existing.version)
        if (byVersion != 0) return byVersion > 0

        val byAuthor = (authorId.toInt() and 0xFF).compareTo(existing.authorId.toInt() and 0xFF)
        if (byAuthor != 0) return byAuthor > 0

        return rank(packet) > rank(existing.packet)
    }

    /** Content rank; a deleted object ranks below any live content. */
    private fun rank(packet: Packet?): Int =
        if (packet == null) 0
        else runCatching { Codec.encode(packet).contentHashCode() }.getOrDefault(0)

    /** Queue a point until its predecessor is known; drop stale entries on re-parent. */
    private fun registerPending(id: ObjectId, packet: Packet) {
        if (packet !is PointPacket) return
        val pred = packet.predecessor ?: return

        // A re-parented edit must not leave the old queue entry behind: it
        // would fire a bogus PendingRefResolved when the old target lands.
        unregisterPending(id)

        // Known (alive or deleted) -> not pending. If the predecessor is
        // deleted this point is a segment head; the caller can tell from
        // get(pred).deleted. Unseen -> queue it.
        if (pred in objects) return

        pendingRefs.getOrPut(pred) { mutableListOf() }.add(id)
        waitingOn[id] = pred
    }

    private fun unregisterPending(id: ObjectId) {
        waitingOn.remove(id)?.let { previous ->
            pendingRefs[previous]?.let { list ->
                list.remove(id)
                if (list.isEmpty()) pendingRefs.remove(previous)
            }
        }
    }

    /**
     * Everything we know, including deleted objects (their [Stored.packet] is
     * null). Deleted entries are kept on purpose: they are the version floor.
     */
    fun snapshot(): List<Stored> = objects.values.toList()

    fun get(id: ObjectId): Stored? = objects[id]

    // TODO(F-40): fun sweep(nowMs: Long): List<Event> — grey then remove expired.
    // TODO(F-83): fun digest(): ByteArray — "here is what I have".
    // TODO(F-84): send queue with priority; see Priority.kt.
}

/** Events emitted by the sync engine; the UI subscribes to these. */
sealed class Event {
    data class ObjectUpserted(val id: ObjectId, val packet: Packet) : Event()

    /** F-41: the object is deleted; its payload is gone. */
    data class ObjectDeleted(val id: ObjectId) : Event()

    /**
     * F-82: [waitingId] no longer waits on a missing predecessor.
     *
     * [orphaned] = true when the predecessor was *deleted* rather than
     * received, i.e. the route chain has split and [waitingId] is now the
     * head of a new segment.
     */
    data class PendingRefResolved(val waitingId: ObjectId, val orphaned: Boolean = false) : Event()

    // TODO: ObjectExpired, EmergencyReceived
}
