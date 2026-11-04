package org.cairn.protocol

/**
 * Hybrid logical clock stamp (F-81, see docs/DECISIONS.md ADR-006).
 *
 * Two parts:
 *  - [sec]   the sender's physical time in **seconds since 2026-01-01T00:00:00Z**
 *            (the same epoch a message's `ts` uses),
 *  - [count] a 16-bit logical counter that breaks same-second ties.
 *
 * Ordering is lexicographic on `(sec, count)`. That is what makes latest-wins
 * converge: every stamp is comparable with every other stamp, so two peers
 * that receive the same set of versions always agree on the winner, no matter
 * in which order the packets arrived. A wall clock alone is not enough — it
 * ties whenever two edits happen in the same second.
 *
 * Wire: `[sec 4 B BE][count 2 B BE]` = [SIZE] bytes.
 */
data class Hlc(val sec: Int, val count: Int = 0) : Comparable<Hlc> {

    init {
        require(count in 0..MAX_COUNT) { "HLC counter must fit in 2 bytes (got $count)" }
    }

    override fun compareTo(other: Hlc): Int {
        val bySec = sec.compareTo(other.sec)
        return if (bySec != 0) bySec else count.compareTo(other.count)
    }

    fun toBytes(): ByteArray = byteArrayOf(
        (sec ushr 24).toByte(),
        (sec ushr 16).toByte(),
        (sec ushr 8).toByte(),
        sec.toByte(),
        (count ushr 8).toByte(),
        count.toByte(),
    )

    companion object {
        const val SIZE = 6
        const val MAX_COUNT = 0xFFFF

        /** Lowest possible stamp. Never used by a real edit; losing to it is intentional. */
        val ZERO = Hlc(0, 0)

        fun fromBytes(buf: ByteArray, offset: Int = 0): Hlc {
            require(offset >= 0 && buf.size - offset >= SIZE) {
                "buffer too short: need $SIZE bytes at offset $offset, have ${buf.size}"
            }
            val sec = ((buf[offset].toInt() and 0xFF) shl 24) or
                      ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                      ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                      (buf[offset + 3].toInt() and 0xFF)
            val count = ((buf[offset + 4].toInt() and 0xFF) shl 8) or
                        (buf[offset + 5].toInt() and 0xFF)
            return Hlc(sec, count)
        }
    }
}

/**
 * The writing half of [Hlc]: turns "now" into a stamp that is strictly greater
 * than every stamp this device has produced *or seen*.
 *
 * Rules (Kulkarni et al., simplified to second resolution):
 *  - on a local edit, take `max(own, physical now)`; bump [Hlc.count] if that
 *    landed on the same second as the previous stamp,
 *  - on receiving a packet, also take the sender's stamp into account, so a
 *    device whose physical clock is *behind* still produces stamps that sort
 *    after what it has already seen.
 *
 * This does **not** correct a wrong clock. A phone that is five minutes fast
 * still wins concurrent edits inside that window; HLC only guarantees that
 * the order is total, monotone and causally consistent. Callers should treat
 * a stamp far ahead of [physicalNowSec] as an advisory (bad clock), never
 * clamp it — clamping is non-monotonic and would break convergence.
 *
 * Call [observe] for every accepted inbound packet, then [now] for local edits.
 */
class HybridClock(private val physicalNowSec: () -> Int) {

    var last: Hlc = Hlc.ZERO
        private set

    fun now(): Hlc {
        val l = maxOf(last.sec, physicalNowSec())
        last = if (l == last.sec) increment(last) else Hlc(l, 0)
        return last
    }

    fun observe(remote: Hlc): Hlc {
        val l = maxOf(last.sec, remote.sec, physicalNowSec())
        last = when {
            l == last.sec && l == remote.sec ->
                increment(if (last.count >= remote.count) last else remote)
            l == last.sec -> increment(last)
            l == remote.sec -> increment(remote)
            else -> Hlc(l, 0)
        }
        return last
    }

    /** Keeps the stamp monotone even if the counter runs out within one second. */
    private fun increment(h: Hlc): Hlc =
        if (h.count >= Hlc.MAX_COUNT) Hlc(h.sec + 1, 0) else Hlc(h.sec, h.count + 1)
}
