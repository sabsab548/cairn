package org.cairn.meshtastic

/**
 * One Meshtastic link, as seen by the shared code: a message pipe, nothing
 * more.
 *
 * A link moves **unframed** `ToRadio`/`FromRadio` protobufs. Stream links
 * (serial, TCP) do their own `0x94 0xC3` framing with [StreamFramer]; BLE
 * delivers one message per GATT write/notification and needs none.
 *
 * Implementations live with the platform, because that is where the only
 * genuinely platform-specific part is: opening a socket, a tty or a GATT
 * connection. Everything above this interface is testable without hardware.
 */
interface MeshtasticLink {
    /** Human-readable, e.g. `tcp:192.168.0.7:4403` or `serial:/dev/ttyACM0`. */
    val description: String

    /**
     * Receives one complete, unframed message. Invoked on the link's own
     * thread; must not block for long.
     */
    var onMessage: (ByteArray) -> Unit

    /** Invoked once when the link drops. `null` means a clean local close. */
    var onClosed: (Throwable?) -> Unit

    /** Opens the underlying connection. Throws if it cannot. */
    fun open()

    /** Sends one unframed message. May be called from any thread. */
    fun write(message: ByteArray)

    fun close()
}
