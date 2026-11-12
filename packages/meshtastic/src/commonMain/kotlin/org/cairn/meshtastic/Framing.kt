package org.cairn.meshtastic

/**
 * Framing for Meshtastic's stream transports (USB serial and TCP/4403).
 *
 * Every `ToRadio`/`FromRadio` protobuf is preceded by
 * `0x94 0xC3 <len hi> <len lo>`. The device also prints plain-text log lines
 * on the same serial port, so a client must treat anything that does not start
 * with `0x94 0xC3` as noise and resynchronise on the next magic byte. This is
 * exactly what the reference Python client does (`stream_interface.py`).
 *
 * BLE needs no framing: one GATT write / notification carries one message.
 */
object StreamFraming {
    const val START1 = 0x94
    const val START2 = 0xC3
    const val HEADER_LEN = 4

    /** Reference client's `MAX_TO_FROM_RADIO_SIZE`. */
    const val MAX_MESSAGE = 512

    fun frame(message: ByteArray): ByteArray {
        require(message.size <= MAX_MESSAGE) { "message too large: ${message.size} > $MAX_MESSAGE" }
        val out = ByteArray(HEADER_LEN + message.size)
        out[0] = START1.toByte()
        out[1] = START2.toByte()
        out[2] = ((message.size ushr 8) and 0xFF).toByte()
        out[3] = (message.size and 0xFF).toByte()
        message.copyInto(out, HEADER_LEN)
        return out
    }
}

/**
 * Incremental stream parser: feed arbitrary chunks, get complete messages.
 *
 * Stateful on purpose — a serial read can split a frame anywhere, including
 * between the two magic bytes. Not thread-safe; use one framer per link and
 * one thread per link.
 */
class StreamFramer(
    private val maxMessage: Int = StreamFraming.MAX_MESSAGE,
    /** Called for every byte dropped while resynchronising (log lines etc.). */
    var onDiscardedByte: ((Int) -> Unit)? = null,
) {
    private var buf = ByteArray(1024)
    private var size = 0

    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): List<ByteArray> {
        append(chunk, offset, length)
        val messages = mutableListOf<ByteArray>()
        while (true) {
            // Drop noise until a magic byte starts the buffer.
            while (size > 0 && (buf[0].toInt() and 0xFF) != StreamFraming.START1) {
                onDiscardedByte?.invoke(buf[0].toInt() and 0xFF)
                consume(1)
            }
            if (size < 2) return messages
            if ((buf[1].toInt() and 0xFF) != StreamFraming.START2) {
                // A stray 0x94 (e.g. a log byte); resync one byte further.
                consume(1)
                continue
            }
            if (size < StreamFraming.HEADER_LEN) return messages
            val len = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (len > maxMessage) {
                // Implausible length: not a header. Resync past this byte.
                consume(1)
                continue
            }
            val total = StreamFraming.HEADER_LEN + len
            if (size < total) return messages
            messages += buf.copyOfRange(StreamFraming.HEADER_LEN, total)
            consume(total)
        }
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        if (size + length > buf.size) {
            var cap = buf.size
            while (cap < size + length) cap *= 2
            buf = buf.copyOf(cap)
        }
        chunk.copyInto(buf, size, offset, offset + length)
        size += length
    }

    private fun consume(count: Int) {
        require(count <= size)
        buf.copyInto(buf, 0, count, size)
        size -= count
    }
}
