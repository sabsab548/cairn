package org.cairn.android.transport

import org.cairn.protocol.Packet

/**
 * Generic outbound/inbound radio transport. Implemented by the BLE driver on
 * Android and by CoreBluetooth on iOS. The sync engine only sees this
 * interface.
 */
interface RadioTransport {
    val state: State
    val onReceive: (Packet) -> Unit
    val onStateChanged: (State) -> Unit

    fun connect(channelKey: ByteArray)
    fun disconnect()
    fun send(packet: Packet)

    sealed class State {
        data object Disconnected : State()
        data object Scanning : State()
        data object Connecting : State()
        data class Connected(val nodeName: String?) : State()
        data class Error(val message: String) : State()
    }
}
