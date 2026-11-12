package org.cairn.meshtastic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cairn.protocol.Codec
import org.cairn.protocol.Packet
import org.cairn.sync.RadioTransport
import kotlin.random.Random

/**
 * [RadioTransport] over a Meshtastic node (F-01, F-02).
 *
 * This class is platform-independent on purpose: it takes a [MeshtasticLink]
 * and turns Cairn packets into Meshtastic app-port packets and back. That
 * means the part that used to be "TODO: decode Meshtastic FromRadio protobuf"
 * in the Android driver is now written once, tested on the JVM, and reused by
 * every app (desktop, Android, later iOS).
 *
 * Notes:
 *  - The Meshtastic channel key is configured on the node. The client never
 *    sees it, so [connect] ignores `channelKey` (F-02 / SECURITY.md).
 *  - Cairn traffic is broadcast on one channel index; unicast/ack handling is
 *    left to the mesh (F-84: airtime is the bottleneck, so no acks yet).
 */
class MeshtasticRadio(
    private val link: MeshtasticLink,
    private val channelIndex: Int = 0,
    private val cairnPort: Int = CairnPort.DEFAULT,
    private val wantConfigId: Int = 0x0CA1,
    private val nextPacketId: () -> Int = { Random.nextInt(1, Int.MAX_VALUE) },
) : RadioTransport {

    private val _state = MutableStateFlow<RadioTransport.State>(RadioTransport.State.Disconnected)
    override val state: StateFlow<RadioTransport.State> = _state

    override var onReceive: (Packet) -> Unit = {}
    override var onStateChanged: (RadioTransport.State) -> Unit = {}

    /**
     * Every message the node sends, including node info, positions, firmware
     * metadata and notifications. The UI (or the dev harness) uses this for
     * F-04 / F-10; the sync engine never sees it.
     */
    var onMeshMessage: (FromRadio) -> Unit = {}

    /** A payload arrived on the Cairn port but is not a valid Cairn packet. */
    var onCairnDecodeError: (payload: ByteArray, error: Exception) -> Unit = { _, _ -> }

    /** Node number of the radio we are attached to, once the node told us. */
    var myNodeNum: Int? = null
        private set

    /** Long name of the radio we are attached to, once the node told us. */
    var myNodeName: String? = null
        private set

    val linkDescription: String get() = link.description

    override fun connect(channelKey: ByteArray) {
        // The key belongs to the channel configured on the node; nothing to do.
        emit(RadioTransport.State.Connecting)
        link.onMessage = ::handleFromRadio
        link.onClosed = { error ->
            if (error != null) {
                emit(RadioTransport.State.Error("link lost: ${error.message ?: error::class.simpleName}"))
            } else {
                emit(RadioTransport.State.Disconnected)
            }
        }
        try {
            link.open()
            // Ask for the node DB. The device replies with my_info, one
            // node_info per node, metadata, and finally config_complete_id.
            write(ToRadio.WantConfig(wantConfigId))
        } catch (e: Exception) {
            emit(RadioTransport.State.Error("cannot open ${link.description}: ${e.message}"))
        }
    }

    override fun disconnect() {
        runCatching { write(ToRadio.Disconnect) }
        link.onMessage = {}
        link.onClosed = {}
        runCatching { link.close() }
        emit(RadioTransport.State.Disconnected)
    }

    override fun send(packet: Packet) {
        if (_state.value !is RadioTransport.State.Connected) {
            emit(RadioTransport.State.Error("not connected: cannot send ${packet::class.simpleName}"))
            return
        }
        val payload = Codec.encode(packet)
        val meshPacket = MeshPacket(
            from = 0, // filled in by the firmware
            to = MeshPacket.BROADCAST,
            channel = channelIndex,
            id = nextPacketId(),
            decoded = MeshData(portnum = cairnPort, payload = payload),
        )
        try {
            write(ToRadio.Packet(meshPacket))
        } catch (e: Exception) {
            emit(RadioTransport.State.Error("send failed: ${e.message}"))
        }
    }

    private fun write(message: ToRadio) {
        link.write(MeshProto.encodeToRadio(message))
    }

    private fun handleFromRadio(bytes: ByteArray) {
        val message = try {
            MeshProto.decodeFromRadio(bytes)
        } catch (e: ProtoException) {
            emit(RadioTransport.State.Error("malformed FromRadio (${bytes.size} B): ${e.message}"))
            return
        }
        onMeshMessage(message)

        when (message) {
            is FromRadio.MyInfo -> {
                myNodeNum = message.info.myNodeNum
                // A node DB download is about to follow; stay in Connecting.
            }

            is FromRadio.Node -> {
                if (message.info.num == myNodeNum) {
                    myNodeName = message.info.user?.longName
                    markConnectedIfHandshakeDone()
                }
            }

            is FromRadio.ConfigComplete -> {
                if (message.id == wantConfigId) markConnectedIfHandshakeDone()
            }

            is FromRadio.Mesh -> deliverCairnPacket(message.packet)

            else -> Unit
        }
    }

    private fun deliverCairnPacket(meshPacket: MeshPacket) {
        val data = meshPacket.decoded ?: return // encrypted: not ours to read
        if (data.portnum != cairnPort) return
        val packet = try {
            Codec.decode(data.payload)
        } catch (e: Exception) {
            onCairnDecodeError(data.payload, e)
            return
        } ?: return
        onReceive(packet)
    }

    private fun markConnectedIfHandshakeDone() {
        if (_state.value is RadioTransport.State.Connected) return
        emit(RadioTransport.State.Connected(myNodeName))
    }

    private fun emit(newState: RadioTransport.State) {
        _state.value = newState
        onStateChanged(newState)
    }
}
