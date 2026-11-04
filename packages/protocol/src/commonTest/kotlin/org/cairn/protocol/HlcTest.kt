package org.cairn.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun orders_by_seconds_then_counter() {
        assertTrue(Hlc(100, 0) < Hlc(100, 1))
        assertTrue(Hlc(99, 65_535) < Hlc(100, 0))
        assertEquals(Hlc(100, 7), Hlc(100, 7))
    }

    @Test
    fun wire_round_trip() {
        listOf(Hlc.ZERO, Hlc(1, 0), Hlc(1_700_000_000, 65_535), Hlc(Int.MAX_VALUE, 1))
            .forEach { h ->
                assertEquals(h, Hlc.fromBytes(h.toBytes()))
                assertEquals(Hlc.SIZE, h.toBytes().size)
            }
    }

    @Test
    fun counter_rejects_values_that_do_not_fit_in_two_bytes() {
        assertFailsWith<IllegalArgumentException> { Hlc(1, -1) }
        assertFailsWith<IllegalArgumentException> { Hlc(1, Hlc.MAX_COUNT + 1) }
    }

    @Test
    fun clock_bumps_counter_within_the_same_second() {
        val clock = HybridClock { 1_000 }
        assertEquals(Hlc(1_000, 0), clock.now())
        assertEquals(Hlc(1_000, 1), clock.now())
        // Two edits in the same second must not tie: that was the whole
        // reason for the counter (ADR-006).
        val previous = clock.last
        assertTrue(clock.now() > previous)
    }

    @Test
    fun clock_resets_counter_when_physical_time_advances() {
        val t = intArrayOf(1_000)
        val clock = HybridClock { t[0] }
        assertEquals(Hlc(1_000, 0), clock.now())
        assertEquals(Hlc(1_000, 1), clock.now())
        t[0] = 1_001
        assertEquals(Hlc(1_001, 0), clock.now())
    }

    @Test
    fun clock_never_goes_backwards_when_physical_time_does() {
        val t = intArrayOf(1_000)
        val clock = HybridClock { t[0] }
        clock.now()
        t[0] = 900 // clock stepped backwards
        val next = clock.now()
        assertEquals(1_000, next.sec)
        assertTrue(next > Hlc(1_000, 0))
    }

    @Test
    fun observe_jumps_ahead_of_a_peer() {
        val clock = HybridClock { 1_000 }
        clock.now()
        val after = clock.observe(Hlc(5_000, 3))
        assertEquals(Hlc(5_000, 4), after)
        assertTrue(clock.now() > after)
    }

    @Test
    fun observe_lets_a_slow_clock_still_sort_after_what_it_saw() {
        // This is the case a plain wall clock gets wrong: a phone whose clock
        // is behind would otherwise keep minting stale stamps.
        val clock = HybridClock { 100 }
        clock.observe(Hlc(500, 0))
        val stamp = clock.now()
        assertTrue(stamp > Hlc(500, 0), "slow device must sort after what it has seen")
    }

    @Test
    fun counter_overflow_carries_into_seconds() {
        val clock = HybridClock { 1_000 }
        // The counter cannot grow past 2 bytes, so the stamp moves to the next
        // second instead of wrapping around and losing the ordering.
        assertEquals(Hlc(1_001, 0), clock.observe(Hlc(1_000, Hlc.MAX_COUNT)))
        assertEquals(Hlc(1_001, 1), clock.now())
    }

    @Test
    fun stamps_are_strictly_monotone_under_a_random_script() {
        val rnd = Random(7)
        val t = intArrayOf(1_000)
        val clock = HybridClock { t[0] }
        var previous = clock.now()
        repeat(2_000) {
            when (rnd.nextInt(3)) {
                0 -> t[0] += rnd.nextInt(0, 3)          // time moves forward
                1 -> t[0] -= rnd.nextInt(0, 3)          // or backwards
                else -> {
                    val observed = clock.observe(Hlc(t[0] + rnd.nextInt(0, 5), rnd.nextInt(0, 3)))
                    assertTrue(observed > previous, "$observed must be greater than $previous")
                    previous = observed
                }
            }
            val next = clock.now()
            assertTrue(next > previous, "$next must be greater than $previous")
            previous = next
        }
    }
}
