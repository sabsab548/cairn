package org.cairn.meshtastic

/**
 * Minimal protobuf (proto3) writer.
 *
 * Meshtastic's link protocol is protobuf. Rather than pulling in the protobuf
 * runtime, the code generator and a `.proto` build step, this module
 * hand-rolls the handful of wire primitives the link needs. That keeps the
 * module dependency-free and testable on every platform's JVM/CI, which is
 * what ADR-003 asks for: the encoding lives *above* the transport line.
 *
 * Only the primitives Meshtastic actually uses are implemented:
 * varint (uint32/int32/enum/bool), fixed32 (fixed32/sfixed32/float) and
 * length-delimited (string/bytes/embedded message).
 */
class ProtoWriter(initialCapacity: Int = 64) {

    private var buf = ByteArray(initialCapacity)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = if (buf.isEmpty()) 16 else buf.size
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    private fun writeByte(value: Int) {
        ensure(1)
        buf[size++] = value.toByte()
    }

    fun varint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                writeByte(b)
                return
            }
            writeByte(b or 0x80)
        }
    }

    private fun tag(field: Int, wireType: Int) {
        require(field > 0) { "field number must be > 0 (got $field)" }
        varint(((field shl 3) or wireType).toLong())
    }

    fun uint32(field: Int, value: Int) {
        tag(field, WIRE_VARINT)
        varint(value.toLong() and 0xFFFF_FFFFL)
    }

    fun bool(field: Int, value: Boolean) {
        tag(field, WIRE_VARINT)
        varint(if (value) 1L else 0L)
    }

    private fun writeFixed32(value: Int) {
        ensure(4)
        buf[size++] = value.toByte()
        buf[size++] = (value ushr 8).toByte()
        buf[size++] = (value ushr 16).toByte()
        buf[size++] = (value ushr 24).toByte()
    }

    fun fixed32(field: Int, value: Int) {
        tag(field, WIRE_FIXED32)
        writeFixed32(value)
    }

    fun float(field: Int, value: Float) = fixed32(field, value.toRawBits())

    fun bytes(field: Int, value: ByteArray) {
        tag(field, WIRE_LEN)
        varint(value.size.toLong())
        ensure(value.size)
        value.copyInto(buf, size)
        size += value.size
    }

    fun string(field: Int, value: String) = bytes(field, value.encodeToByteArray())

    fun message(field: Int, body: ProtoWriter) = bytes(field, body.toByteArray())

    /** Embeds an already-encoded message (see [MeshProto]). */
    fun message(field: Int, body: ByteArray) = bytes(field, body)

    fun toByteArray(): ByteArray = buf.copyOf(size)

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5
    }
}

/** Thrown when a message is not valid protobuf, or not the subset we support. */
class ProtoException(message: String) : Exception(message)

/**
 * Minimal protobuf reader. Iterate with [nextField], then read the value that
 * belongs to the reported [wireType].
 *
 * Unknown fields are safe: [nextField] followed by [skipValue] steps over any
 * wire type, which is what keeps this decoder working when Meshtastic adds
 * fields in a future firmware version.
 */
class ProtoReader private constructor(
    private val buf: ByteArray,
    private var pos: Int,
    private val limit: Int,
) {
    constructor(buf: ByteArray) : this(buf, 0, buf.size)

    var wireType: Int = -1
        private set

    val atEnd: Boolean get() = pos >= limit

    /** Field number of the next field, or null at the end of the message. */
    fun nextField(): Int? {
        if (atEnd) return null
        val key = readVarint()
        if (key == 0L) return null
        val field = (key ushr 3).toInt()
        val wt = (key and 0x7).toInt()
        if (field == 0) throw ProtoException("field number 0 is not valid")
        wireType = wt
        return field
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= limit) throw ProtoException("truncated varint")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw ProtoException("varint longer than 10 bytes")
        }
    }

    fun readUInt32(): Int = readVarint().toInt()

    fun readBool(): Boolean = readVarint() != 0L

    fun readFixed32(): Int {
        if (pos + 4 > limit) throw ProtoException("truncated fixed32")
        val v = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readFloat(): Float = Float.fromBits(readFixed32())

    private fun readLengthPrefixed(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > limit) throw ProtoException("truncated length-delimited field ($len B)")
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readBytes(): ByteArray = readLengthPrefixed()

    fun readString(): String = readLengthPrefixed().decodeToString()

    /** Reads an embedded message as its own reader. */
    fun readMessage(): ProtoReader {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > limit) throw ProtoException("truncated embedded message ($len B)")
        val sub = ProtoReader(buf, pos, pos + len)
        pos += len
        return sub
    }

    /** Steps over the current field's value. */
    fun skipValue() {
        when (wireType) {
            ProtoWriter.WIRE_VARINT -> readVarint()
            ProtoWriter.WIRE_FIXED64 -> {
                if (pos + 8 > limit) throw ProtoException("truncated fixed64")
                pos += 8
            }
            ProtoWriter.WIRE_LEN -> {
                val len = readVarint().toInt()
                if (len < 0 || pos + len > limit) throw ProtoException("truncated length-delimited field")
                pos += len
            }
            ProtoWriter.WIRE_FIXED32 -> {
                if (pos + 4 > limit) throw ProtoException("truncated fixed32")
                pos += 4
            }
            else -> throw ProtoException("unsupported wire type $wireType")
        }
    }
}
