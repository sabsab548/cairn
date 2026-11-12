package org.cairn.sync

import kotlinx.coroutines.flow.StateFlow
import org.cairn.protocol.Packet

/**
 * The only seam between the sync engine and the radio. Implemented per
 * platform and per link type (Android GATT, iOS CoreBluetooth, desktop
 * TCP/serial) and deliberately tiny.
 *
 * Everything above this interface is pure logic and CI-tested; everything
 * below it is validated on a real node (ADR-003).
 */
interface RadioTransport {
    val state: StateFlow<State>

    /** Called for every Cairn packet that arrived over the radio. */
    val onReceive: (Packet) -> Unit

    /** Called on every state transition. */
    val onStateChanged: (State) -> Unit

    /**
     * Connect to the radio and join the channel selected by the user (F-03).
     *
     * [channelKey] is the Meshtastic channel PSK. Most Meshtastic links do not
     * let a client set it, because the channel (and therefore the key) is
     * configured on the node itself; implementations that cannot use it may
     * ignore the argument. It stays in the signature because the security
     * model ("holding the key = being a peer") is a property of the group,
     * not of the link (see SECURITY.md).
     */
    fun connect(channelKey: ByteArray)

    fun disconnect()

    /** Hand one Cairn packet to the radio for transmission. */
    fun send(packet: Packet)

    sealed class State {
        data object Disconnected : State()
        data object Scanning : State()
        data object Connecting : State()
        data class Connected(val nodeName: String?) : State()
        data class Error(val message: String) : State()
    }
}
