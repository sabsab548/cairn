package org.cairn.sync

import org.cairn.protocol.DigestPacket
import org.cairn.protocol.Flags
import org.cairn.protocol.Hlc
import org.cairn.protocol.ObjectId
import org.cairn.protocol.ObjectType
import org.cairn.protocol.Packet
import org.cairn.protocol.PointPacket
import org.cairn.protocol.TombstonePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private fun engine() = SyncEngine { 1_000L }

    private fun point(
        counter: Int,
        pred: ObjectId? = null,
        version: Hlc = Hlc(1_000, 0),
        author: Byte = 1,
        name: String = "p$counter",
    ) = PointPacket(
        id = ObjectId(ObjectType.Pin, authorId = author, counter = counter),
        predecessor = pred,
        flags = Flags(),
        version = version,
        latE7 = 47_000_000,
        lonE7 = 8_000_000,
        name = name,
    )

    private fun tombstone(
        target: ObjectId,
        version: Hlc,
        author: Byte = 1,
    ) = TombstonePacket(
        id = ObjectId(ObjectType.Message, authorId = author, counter = 900),
        targetId = target,
        version = version,
    )

    @Test
    fun first_ingest_emits_upsert() {
        val events = engine().ingest(point(1))
        assertEquals(1, events.size)
        assertTrue(events[0] is Event.ObjectUpserted)
    }

    @Test
    fun duplicate_packet_is_ignored() {   // F-80
        val e = engine()
        val p = point(1)
        e.ingest(p)
        assertEquals(0, e.ingest(p).size)
    }

    @Test
    fun latest_wins() {   // F-81
        val e = engine()
        val p = point(1, version = Hlc(1_000, 0))
        e.ingest(p)
        e.ingest(p.copy(name = "renamed", version = Hlc(2_000, 0)))

        val stored = e.get(p.id)
        assertNotNull(stored)
        assertEquals("renamed", (stored.packet as PointPacket).name)
    }

    @Test
    fun older_packet_does_not_override_newer() {   // F-81
        val e = engine()
        val p = point(1, version = Hlc(5_000, 0))
        e.ingest(p)
        e.ingest(p.copy(name = "stale", version = Hlc(4_000, 0)))

        assertEquals("p1", (e.get(p.id)!!.packet as PointPacket).name)
    }

    @Test
    fun same_second_edits_are_ordered_by_the_logical_counter() {
        // A wall clock alone would tie here and silently drop the second edit.
        val e = engine()
        val p = point(1, version = Hlc(1_000, 0))
        e.ingest(p)
        e.ingest(p.copy(name = "second", version = Hlc(1_000, 1)))

        assertEquals("second", (e.get(p.id)!!.packet as PointPacket).name)
    }

    @Test
    fun receive_order_does_not_decide_the_winner() {
        // The regression that started ADR-006: two peers hear the same two
        // versions in opposite order and must still agree.
        val old = point(1, version = Hlc(1_000, 0), name = "old")
        val new = point(1, version = Hlc(2_000, 0), name = "new")

        val peerA = engine().apply { ingest(old, receivedAtMs = 1); ingest(new, receivedAtMs = 2) }
        val peerB = engine().apply { ingest(new, receivedAtMs = 1); ingest(old, receivedAtMs = 2) }

        assertEquals("new", (peerA.get(old.id)!!.packet as PointPacket).name)
        assertEquals("new", (peerB.get(old.id)!!.packet as PointPacket).name)
    }

    @Test
    fun any_delivery_order_converges_to_the_same_state() {
        val id = ObjectId(ObjectType.Pin, authorId = 1, counter = 7)
        val versions: List<Packet> = listOf(
            point(7, version = Hlc(1_000, 0), name = "v1"),
            point(7, version = Hlc(1_000, 3), name = "v2"),
            point(7, version = Hlc(2_000, 0), name = "v3"),
            // Same stamp, same author, different content: only reachable with
            // a buggy writer, but it must still not split the group.
            point(7, version = Hlc(2_000, 0), name = "v3bis"),
            tombstone(id, version = Hlc(2_500, 0)),
            point(7, version = Hlc(3_000, 0), name = "v5-after-delete"),
        )

        val outcomes = permutations(versions).map { order ->
            val e = engine()
            order.forEachIndexed { i, packet -> e.ingest(packet, receivedAtMs = i.toLong()) }
            val stored = e.get(id)!!
            stored.version to (stored.packet as? PointPacket)?.name
        }.toSet()

        assertEquals(1, outcomes.size, "peers diverged: $outcomes")
        assertEquals(Hlc(3_000, 0) to "v5-after-delete", outcomes.single())
    }

    @Test
    fun a_delete_edit_tie_is_broken_by_author_in_every_order() {
        // Both editors reuse the target's ObjectId, so a tie between two edits
        // always has the same author byte. A tombstone carries its own author
        // byte, which is where the tiebreak actually matters.
        val owner = point(1, version = Hlc(2_000, 0), author = 7, name = "mine")
        val id = owner.id

        val quieter = tombstone(id, version = Hlc(2_000, 0), author = 3)   // 3 < 7
        val louder = tombstone(id, version = Hlc(2_000, 0), author = 9)    // 9 > 7

        listOf(
            engine().apply { ingest(owner); ingest(quieter) },
            engine().apply { ingest(quieter); ingest(owner) },
        ).forEach { assertTrue(!it.get(id)!!.deleted, "lower-author tombstone won a tie") }

        listOf(
            engine().apply { ingest(owner); ingest(louder) },
            engine().apply { ingest(louder); ingest(owner) },
        ).forEach { assertTrue(it.get(id)!!.deleted, "higher-author tombstone lost a tie") }
    }

    @Test
    fun equal_content_ties_still_converge() {
        val a = point(1, version = Hlc(2_000, 0), name = "alpha")
        val b = point(1, version = Hlc(2_000, 0), name = "beta")

        val first = engine().apply { ingest(a); ingest(b) }
        val second = engine().apply { ingest(b); ingest(a) }

        assertEquals(
            (first.get(a.id)!!.packet as PointPacket).name,
            (second.get(a.id)!!.packet as PointPacket).name,
            "content tiebreak must be order independent",
        )
    }

    @Test
    fun delete_drops_the_payload_and_keeps_a_floor() {   // F-41
        val e = engine()
        val p = point(1, version = Hlc(1_000, 0))
        e.ingest(p)

        val events = e.ingest(tombstone(p.id, version = Hlc(2_000, 0)))

        assertEquals(1, events.size)
        assertTrue(events[0] is Event.ObjectDeleted)
        val stored = e.get(p.id)
        assertNotNull(stored)
        assertTrue(stored.deleted)
        assertNull(stored.packet, "deleted payload must not be kept")
        assertEquals(Hlc(2_000, 0), stored.version)
    }

    @Test
    fun a_stale_copy_cannot_resurrect_a_deleted_object() {
        // This is the offline peer coming back: it only has the old version.
        val e = engine()
        val p = point(1, version = Hlc(1_000, 0))
        e.ingest(p)
        e.ingest(tombstone(p.id, version = Hlc(2_000, 0)))

        assertEquals(0, e.ingest(p).size)
        assertTrue(e.get(p.id)!!.deleted)
    }

    @Test
    fun a_tombstone_for_an_unseen_object_still_blocks_a_later_create() {
        val e = engine()
        val unseen = point(1, version = Hlc(1_000, 0))
        e.ingest(tombstone(unseen.id, version = Hlc(2_000, 0)))

        assertEquals(0, e.ingest(unseen).size)
        assertTrue(e.get(unseen.id)!!.deleted)
    }

    @Test
    fun a_newer_edit_undeletes() {
        val e = engine()
        val p = point(1, version = Hlc(1_000, 0))
        e.ingest(p)
        e.ingest(tombstone(p.id, version = Hlc(2_000, 0)))

        val events = e.ingest(p.copy(name = "back", version = Hlc(3_000, 0)))

        assertEquals(1, events.size)
        assertTrue(events[0] is Event.ObjectUpserted)
        val stored = e.get(p.id)!!
        assertTrue(!stored.deleted)
        assertEquals("back", (stored.packet as PointPacket).name)
    }

    @Test
    fun an_older_tombstone_does_not_delete_a_newer_object() {
        val e = engine()
        val p = point(1, version = Hlc(2_000, 0))
        e.ingest(p)

        assertEquals(0, e.ingest(tombstone(p.id, version = Hlc(1_000, 0))).size)
        assertTrue(!e.get(p.id)!!.deleted)
    }

    @Test
    fun tombstone_targeting_the_nil_id_is_ignored() {
        assertEquals(0, engine().ingest(tombstone(ObjectId.NONE, version = Hlc(1, 0))).size)
    }

    @Test
    fun out_of_order_arrival_registers_pending_ref_and_resolves() {   // F-82
        val e = engine()
        val first = point(1)                 // predecessor = null (first of route)
        val second = point(2, pred = first.id)

        // Deliver the second point FIRST — it references an unseen predecessor.
        e.ingest(second)
        // Now deliver the predecessor.
        val events = e.ingest(first)

        val resolved = events.filterIsInstance<Event.PendingRefResolved>()
        assertEquals(listOf(second.id), resolved.map { it.waitingId })
        assertTrue(resolved.none { it.orphaned })
    }

    @Test
    fun deleting_a_waited_on_predecessor_orphans_the_waiters() {
        // The chain H -> A -> B: delete A and B is no longer waiting, it is the
        // head of a new segment (the route splits in two, see ADR-006).
        val e = engine()
        val a = point(1)
        val b = point(2, pred = a.id)

        e.ingest(b)                       // A not seen yet -> B is pending
        val events = e.ingest(tombstone(a.id, version = Hlc(2_000, 0)))

        val resolved = events.filterIsInstance<Event.PendingRefResolved>()
        assertEquals(listOf(b.id), resolved.map { it.waitingId })
        assertTrue(resolved.single().orphaned)
    }

    @Test
    fun a_point_whose_predecessor_is_already_deleted_is_not_queued() {
        val e = engine()
        val a = point(1)
        e.ingest(a)
        e.ingest(tombstone(a.id, version = Hlc(2_000, 0)))

        // No PendingRefResolved and nothing left waiting: the point is simply
        // the head of its own segment now.
        val events = e.ingest(point(2, pred = a.id, version = Hlc(3_000, 0)))
        assertTrue(events.none { it is Event.PendingRefResolved })
    }

    @Test
    fun a_deleted_object_stops_waiting_for_its_own_predecessor() {
        val e = engine()
        val head = point(1)
        val middle = point(2, pred = head.id)
        e.ingest(middle)                                   // head unseen -> pending
        e.ingest(tombstone(middle.id, version = Hlc(2_000, 0)))

        // The deleted point must not report as resolved when `head` arrives.
        val events = e.ingest(head.copy(version = Hlc(3_000, 0)))
        assertTrue(events.none { it is Event.PendingRefResolved })
    }

    @Test
    fun re_pointed_edit_drops_its_stale_pending_entry() {
        val e = engine()
        val missing = ObjectId(ObjectType.Pin, authorId = 9, counter = 1)
        val p = point(2, pred = missing, version = Hlc(1_000, 0))
        e.ingest(p)

        // A later version of the same point points somewhere else (a link).
        val head = point(3, version = Hlc(500, 0))
        e.ingest(head)
        e.ingest(p.copy(predecessor = head.id, version = Hlc(2_000, 0)))

        // The stale registration must not fire when `missing` finally appears.
        val events = e.ingest(point(1, author = 9, version = Hlc(3_000, 0)))
        assertTrue(events.none { it is Event.PendingRefResolved })
    }

    @Test
    fun duplicate_digest_is_ignored() {   // F-80
        val e = engine()
        e.ingest(sampleDigest())
        // Same content, different array instance: must still be a duplicate.
        assertEquals(0, e.ingest(sampleDigest()).size)
    }

    @Test
    fun a_digest_does_not_shadow_a_real_object_with_the_same_id() {
        val e = engine()
        val p = point(1, version = Hlc(1_000, 0))
        e.ingest(p)

        val clashing = DigestPacket(id = p.id, filterBytes = byteArrayOf(9))
        assertEquals(0, e.ingest(clashing).size)
        assertEquals("p1", (e.get(p.id)!!.packet as PointPacket).name)
    }

    private fun sampleDigest() = DigestPacket(
        id = ObjectId(ObjectType.Message, authorId = 1, counter = 5),
        filterBytes = byteArrayOf(1, 2, 3),
    )

    private fun permutations(items: List<Packet>): List<List<Packet>> {
        if (items.size <= 1) return listOf(items)
        val out = mutableListOf<List<Packet>>()
        items.forEachIndexed { i, item ->
            val rest = items.toMutableList().apply { removeAt(i) }
            permutations(rest).forEach { out += listOf(item) + it }
        }
        return out
    }
}
