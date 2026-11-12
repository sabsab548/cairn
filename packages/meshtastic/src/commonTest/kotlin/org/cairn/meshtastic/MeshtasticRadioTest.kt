package org.cairn.meshtastic

import org.cairn.protocol.Codec
import org.cairn.protocol.Flags
import org.cairn.protocol.Hlc
import org.cairn.protocol.ObjectId
import org.cairn.protocol.ObjectType
import org.cairn.protocol.Packet
import org.cairn.protocol.PointPacket
import org.cairn.sync.RadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MeshtasticRadio] against a scripted link: no sockets, no threads, fully
 * deterministic. The desktop harness covers the real links; this covers the
 * handshake and the port filtering, which are the parts that decide whether a
 * packet reaches the sync engine at all.
 */
class MeshtasticRadioTest {

    private class ScriptedLink : MeshtasticLink {
        override val description = "scripted"
        override var onMessage: (ByteArray) -> Unit = {}
        override var onClosed: (Throwable?) -> Unit = {}
        val written = mutableListOf<ToRadio?>()
        var opened = false

        override fun open() {
            opened = true
        }

        override fun write(message: ByteArray) {
            written += MeshProto.decodeToRadio(message)
        }

        override fun close() {
            opened = false
        }

        fun emit(message: FromRadio) = onMessage(MeshProto.encodeFromRadio(message))
    }

    private val selfNum = 0x0102_0304

    private fun handshake(link: ScriptedLink, configId: Int = 0x0CA1) {
        link.emit(FromRadio.MyInfo(MeshMyNodeInfo(myNodeNum = selfNum, nodedbCount = 1)))
        link.emit(FromRadio.Node(MeshNodeInfo(num = selfNum, user = MeshUser(longName = "Cairn"))))
        link.emit(FromRadio.ConfigComplete(configId))
    }

    @Test
    fun connect_opens_the_link_and_asks_for_the_node_db() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link)
        radio.connect(ByteArray(0))

        assertTrue(link.opened)
        assertEquals(listOf<ToRadio?>(ToRadio.WantConfig(0x0CA1)), link.written)
        assertIs<RadioTransport.State.Connecting>(radio.state.value)
    }

    @Test
    fun config_complete_with_the_matching_nonce_connects() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link)
        val states = mutableListOf<RadioTransport.State>()
        radio.onStateChanged = { states += it }

        radio.connect(ByteArray(0))
        handshake(link)

        assertEquals(RadioTransport.State.Connected("Cairn"), radio.state.value)
        assertEquals(selfNum, radio.myNodeNum)
        assertTrue(states.last() is RadioTransport.State.Connected)
    }

    @Test
    fun a_wrong_config_nonce_does_not_connect() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link)
        radio.connect(ByteArray(0))
        link.emit(FromRadio.MyInfo(MeshMyNodeInfo(myNodeNum = selfNum)))
        link.emit(FromRadio.ConfigComplete(0x0CA1 + 1))

        assertIs<RadioTransport.State.Connecting>(radio.state.value)
    }

    @Test
    fun own_node_info_completes_the_handshake_without_config_complete() {
        // Some links (BLE) get `my_info` before the node DB is fully streamed;
        // the own NodeInfo is enough to call it connected.
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link)
        radio.connect(ByteArray(0))
        link.emit(FromRadio.MyInfo(MeshMyNodeInfo(myNodeNum = selfNum)))
        link.emit(FromRadio.Node(MeshNodeInfo(num = selfNum, user = MeshUser(longName = "Cairn"))))

        assertEquals(RadioTransport.State.Connected("Cairn"), radio.state.value)
    }

    @Test
    fun another_nodes_info_does_not_connect_us() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link)
        radio.connect(ByteArray(0))
        link.emit(FromRadio.MyInfo(MeshMyNodeInfo(myNodeNum = selfNum)))
        link.emit(FromRadio.Node(MeshNodeInfo(num = 0x0BAD_0BAD, user = MeshUser(longName = "Someone"))))

        assertNull(radio.myNodeName)
        assertIs<RadioTransport.State.Connecting>(radio.state.value)
    }

    @Test
    fun only_packets_on_the_cairn_port_reach_on_receive() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link, cairnPort = 300)
        val packets = mutableListOf<Packet>()
        radio.onReceive = { packets += it }
        radio.connect(ByteArray(0))
        handshake(link)

        val payload = Codec.encode(
            PointPacket(
                id = ObjectId(ObjectType.Pin, 9, 1),
                predecessor = null,
                flags = Flags(),
                version = Hlc(10, 0),
                latE7 = 525_000_000,
                lonE7 = 134_000_000,
                name = "pin",
            ),
        )
        fun mesh(port: Int, bytes: ByteArray, encrypted: Boolean = false) = FromRadio.Mesh(
            MeshPacket(
                from = 0x0BAD_0BAD,
                to = MeshPacket.BROADCAST,
                id = 1,
                decoded = if (encrypted) null else MeshData(port, bytes),
                encrypted = if (encrypted) bytes else null,
            ),
        )

        link.emit(mesh(MeshPort.TEXT_MESSAGE_APP, payload))
        assertTrue(packets.isEmpty(), "a text-app packet must not be treated as Cairn")

        link.emit(mesh(300, payload))
        assertEquals(1, packets.size)
        assertEquals(ObjectId(ObjectType.Pin, 9, 1), packets.single().id)

        link.emit(mesh(300, payload, encrypted = true))
        assertEquals(1, packets.size, "encrypted payloads cannot be decoded by the client")
    }

    @Test
    fun a_payload_on_the_cairn_port_that_is_not_cairn_is_reported() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link, cairnPort = CairnPort.DEFAULT)
        val errors = mutableListOf<Pair<ByteArray, Exception>>()
        radio.onCairnDecodeError = { payload, error -> errors += payload to error }
        radio.connect(ByteArray(0))
        handshake(link)

        link.emit(
            FromRadio.Mesh(
                MeshPacket(
                    from = 1,
                    to = MeshPacket.BROADCAST,
                    id = 1,
                    decoded = MeshData(CairnPort.DEFAULT, byteArrayOf(0x7F, 0x01)),
                ),
            ),
        )

        assertEquals(1, errors.size)
        val (payload, error) = errors.single()
        assertEquals(listOf<Byte>(0x7F, 0x01), payload.toList())
        assertIs<IllegalArgumentException>(error)
    }

    @Test
    fun send_requires_a_connection() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link)
        val states = mutableListOf<RadioTransport.State>()
        radio.onStateChanged = { states += it }

        radio.send(
            PointPacket(
                id = ObjectId(ObjectType.Pin, 1, 1),
                predecessor = null,
                flags = Flags(),
                version = Hlc(1),
                latE7 = 0,
                lonE7 = 0,
                name = null,
            ),
        )

        assertTrue(states.last() is RadioTransport.State.Error)
        assertTrue(link.written.isEmpty())
    }

    @Test
    fun a_sent_packet_is_wrapped_in_a_broadcast_mesh_packet_on_the_cairn_port() {
        val link = ScriptedLink()
        val radio = MeshtasticRadio(link, channelIndex = 2, cairnPort = 400, nextPacketId = { 0x1234 })
        radio.connect(ByteArray(0))
        handshake(link)
        link.written.clear()

        val packet = PointPacket(
            id = ObjectId(ObjectType.Pin, 1, 1),
            predecessor = null,
            flags = Flags(),
            version = Hlc(1),
            latE7 = 1,
            lonE7 = 2,
            name = null,
        )
        radio.send(packet)

        val sent = link.written.single() as ToRadio.Packet
        assertEquals(MeshPacket.BROADCAST, sent.packet.to)
        assertEquals(2, sent.packet.channel)
        assertEquals(0x1234, sent.packet.id)
        assertEquals(400, sent.packet.decoded?.portnum)
        assertEquals(
            Codec.encode(packet).toList(),
            sent.packet.decoded?.payload?.toList(),
        )
    }
}
