package org.cairn.desktop

import org.cairn.desktop.link.MockLink
import org.cairn.meshtastic.CairnPort
import org.cairn.meshtastic.FromRadio
import org.cairn.meshtastic.MeshtasticRadio
import org.cairn.protocol.Flags
import org.cairn.protocol.Hlc
import org.cairn.protocol.MessagePacket
import org.cairn.protocol.ObjectId
import org.cairn.protocol.ObjectType
import org.cairn.protocol.Packet
import org.cairn.protocol.PointPacket
import org.cairn.sync.RadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole desktop pipeline, minus hardware: `MockLink` (real protobuf over a
 * fake node) → `MeshtasticRadio` → Cairn codec.
 */
class MockLinkHarnessTest {

    private val authorId: Byte = 7

    @Test
    fun connect_completes_the_handshake_and_reports_the_node() {
        val link = MockLink(latencyMs = 0)
        val radio = MeshtasticRadio(link, cairnPort = CairnPort.DEFAULT)
        val states = mutableListOf<RadioTransport.State>()
        val meshMessages = mutableListOf<FromRadio>()
        radio.onStateChanged = { states += it }
        radio.onMeshMessage = { meshMessages += it }

        try {
            radio.connect(ByteArray(0))

            assertIs<RadioTransport.State.Connected>(radio.state.value)
            assertEquals("Cairn Dev Node", radio.myNodeName)
            assertEquals(0x00CA_1E01, radio.myNodeNum)
            assertEquals("mock", radio.linkDescription)

            assertTrue(states.any { it is RadioTransport.State.Connecting })
            assertTrue(meshMessages.any { it is FromRadio.ConfigComplete && it.id == 0x0CA1 })
            val peer = meshMessages.filterIsInstance<FromRadio.Node>().map { it.info }
            assertTrue(peer.any { it.user?.longName == "Anna (mock peer)" })
        } finally {
            radio.disconnect()
        }
    }

    @Test
    fun the_mock_also_delivers_asynchronously() {
        val link = MockLink(latencyMs = 5)
        val radio = MeshtasticRadio(link, cairnPort = CairnPort.DEFAULT)
        try {
            radio.connect(ByteArray(0))
            link.awaitIdle()
            assertIs<RadioTransport.State.Connected>(radio.state.value)
            assertEquals("Cairn Dev Node", radio.myNodeName)
        } finally {
            radio.disconnect()
        }
    }

    @Test
    fun a_sent_pin_is_echoed_by_the_mock_peer_and_decoded() {
        val link = MockLink(latencyMs = 0)
        val radio = MeshtasticRadio(link, cairnPort = CairnPort.DEFAULT)
        val received = mutableListOf<Packet>()
        radio.onReceive = { received += it }

        try {
            radio.connect(ByteArray(0))
            val pin = PointPacket(
                id = ObjectId(ObjectType.Pin, authorId, 1),
                predecessor = null,
                flags = Flags(),
                version = Hlc(100, 0),
                latE7 = 525_204_000,
                lonE7 = 134_043_000,
                name = "bridge",
            )
            radio.send(pin)

            assertEquals(1, received.size)
            assertEquals(pin, received.single())
        } finally {
            radio.disconnect()
        }
    }

    @Test
    fun a_sent_message_survives_the_round_trip() {
        val link = MockLink(latencyMs = 0)
        val radio = MeshtasticRadio(link, cairnPort = 300)
        val received = mutableListOf<Packet>()
        radio.onReceive = { received += it }

        try {
            radio.connect(ByteArray(0))
            val message = MessagePacket(
                id = ObjectId(ObjectType.Message, authorId, 2),
                senderId = authorId,
                version = Hlc(200, 3),
                referencedObject = null,
                text = "rendezvous at the north ridge",
            )
            radio.send(message)

            assertEquals(message, received.single())
        } finally {
            radio.disconnect()
        }
    }
}
