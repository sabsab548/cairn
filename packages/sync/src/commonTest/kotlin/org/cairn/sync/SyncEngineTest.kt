package org.cairn.sync

import org.cairn.protocol.Flags
import org.cairn.protocol.ObjectId
import org.cairn.protocol.ObjectType
import org.cairn.protocol.PointPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private fun point(counter: Int, pred: ObjectId? = null) = PointPacket(
        id = ObjectId(ObjectType.Pin, authorId = 1, counter = counter),
        predecessor = pred,
        flags = Flags(),
        latE7 = 47_000_000,
        lonE7 = 8_000_000,
        name = "p$counter",
    )

    @Test
    fun first_ingest_emits_upsert() {
        val e = SyncEngine { 1_000L }
        val events = e.ingest(point(1), receivedAtMs = 1_000L)
        assertEquals(1, events.size)
        assertTrue(events[0] is Event.ObjectUpserted)
    }

    @Test
    fun duplicate_packet_is_ignored() {
        val e = SyncEngine { 1_000L }
        val p = point(1)
        e.ingest(p, receivedAtMs = 1_000L)
        val second = e.ingest(p, receivedAtMs = 2_000L)
        assertEquals(0, second.size)   // F-80
    }

    @Test
    fun latest_wins() {
        val e = SyncEngine { 1_000L }
        val p = point(1)
        e.ingest(p, receivedAtMs = 1_000L)

        val renamed = p.copy(name = "renamed")
        e.ingest(renamed, receivedAtMs = 2_000L)

        val stored = e.get(p.id)
        assertNotNull(stored)
        assertEquals("renamed", (stored.packet as PointPacket).name)
    }

    @Test
    fun older_packet_does_not_override_newer() {
        val e = SyncEngine { 1_000L }
        val p = point(1)
        e.ingest(p, receivedAtMs = 5_000L)

        val stale = p.copy(name = "stale")
        e.ingest(stale, receivedAtMs = 4_000L)

        val stored = e.get(p.id)
        assertNotNull(stored)
        assertEquals("p1", (stored.packet as PointPacket).name)
    }

    @Test
    fun out_of_order_arrival_registers_pending_ref_and_resolves() {
        val e = SyncEngine { 1_000L }
        val first = point(1)                 // predecessor = null (first of route)
        val second = point(2, pred = first.id)

        // Deliver the second point FIRST — it references an unseen predecessor.
        e.ingest(second, receivedAtMs = 1_000L)
        // Now deliver the predecessor.
        val events = e.ingest(first, receivedAtMs = 1_001L)

        // The predecessor delivery should have produced a PendingRefResolved
        // for `second`. (The upsert for `first` is also in the list.)
        assertTrue(events.any { it is Event.PendingRefResolved })
    }
}
