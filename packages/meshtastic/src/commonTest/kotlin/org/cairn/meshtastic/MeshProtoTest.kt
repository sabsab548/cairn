package org.cairn.meshtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshProtoTest {

    private fun roundTripFromRadio(message: FromRadio): FromRadio =
        MeshProto.decodeFromRadio(MeshProto.encodeFromRadio(message))

    private fun roundTripToRadio(message: ToRadio): ToRadio? =
        MeshProto.decodeToRadio(MeshProto.encodeToRadio(message))

    @Test
    fun want_config_round_trips() {
        val decoded = roundTripToRadio(ToRadio.WantConfig(0x0CA1))
        assertEquals(ToRadio.WantConfig(0x0CA1), decoded)
    }

    @Test
    fun want_config_uses_field_3() {
        val bytes = MeshProto.encodeToRadio(ToRadio.WantConfig(1))
        // field 3, wire type 0 -> key 0x18
        assertEquals(0x18, bytes[0].toInt())
    }

    @Test
    fun disconnect_and_heartbeat_round_trip() {
        assertEquals(ToRadio.Disconnect, roundTripToRadio(ToRadio.Disconnect))
        assertEquals(ToRadio.Heartbeat(1234), roundTripToRadio(ToRadio.Heartbeat(1234)))
    }

    @Test
    fun cairn_packet_survives_the_mesh_envelope() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val packet = MeshPacket(
            to = MeshPacket.BROADCAST,
            channel = 2,
            id = 0x1234,
            decoded = MeshData(portnum = CairnPort.DEFAULT, payload = payload),
        )
        val decoded = roundTripToRadio(ToRadio.Packet(packet)) as ToRadio.Packet
        val p = decoded.packet
        assertEquals(-1, p.to) // 0xFFFFFFFF
        assertEquals(2, p.channel)
        assertEquals(0x1234, p.id)
        assertEquals(CairnPort.DEFAULT, p.decoded?.portnum)
        assertTrue(p.decoded!!.payload.contentEquals(payload))
    }

    @Test
    fun mesh_packet_without_to_decodes_as_broadcast() {
        val w = ProtoWriter().apply { uint32(3, 1) } // only a channel field
        val packet = MeshProto.decodeMeshPacket(ProtoReader(w.toByteArray()))
        assertEquals(MeshPacket.BROADCAST, packet.to)
    }

    @Test
    fun my_info_round_trips() {
        val info = MeshMyNodeInfo(myNodeNum = 0x1A2B3C4D, rebootCount = 3, nodedbCount = 7)
        val decoded = roundTripFromRadio(FromRadio.MyInfo(info)) as FromRadio.MyInfo
        assertEquals(info, decoded.info)
    }

    @Test
    fun config_complete_round_trips() {
        val decoded = roundTripFromRadio(FromRadio.ConfigComplete(0x0CA1)) as FromRadio.ConfigComplete
        assertEquals(0x0CA1, decoded.id)
    }

    @Test
    fun node_info_round_trips_with_user_position_and_battery() {
        val node = MeshNodeInfo(
            num = 0x01020304,
            user = MeshUser(id = "!01020304", longName = "Anna", shortName = "AN"),
            position = MeshPosition(latitudeI = 525_000_000, longitudeI = 134_000_000, timeSec = 1_700_000_000),
            snr = 7.5f,
            lastHeard = 1_700_000_100,
            deviceMetrics = MeshDeviceMetrics(batteryLevel = 83, voltage = 4.05f, uptimeSeconds = 3600),
            hopsAway = 2,
        )
        val decoded = roundTripFromRadio(FromRadio.Node(node)) as FromRadio.Node
        assertEquals(node, decoded.info)
    }

    @Test
    fun position_scales_to_degrees() {
        val p = MeshPosition(latitudeI = 525_000_000, longitudeI = 134_000_000)
        assertEquals(52.5, p.latitude)
        assertEquals(13.4, p.longitude)
        assertTrue(p.isPlausible)
    }

    @Test
    fun metadata_round_trips() {
        val meta = MeshMetadata(
            firmwareVersion = "2.5.20.abc1234",
            hasWifi = true,
            hasBluetooth = true,
            hwModel = 43,
            role = 1,
        )
        val decoded = roundTripFromRadio(FromRadio.Metadata(meta)) as FromRadio.Metadata
        assertEquals(meta, decoded.metadata)
    }

    @Test
    fun notification_round_trips() {
        val n = MeshNotification(message = "duty cycle exceeded", level = 40, type = 4)
        val decoded = roundTripFromRadio(FromRadio.Notification(n)) as FromRadio.Notification
        assertEquals(n, decoded.notification)
    }

    @Test
    fun unknown_from_radio_field_is_reported_not_thrown() {
        // Field 9 = module config, which we deliberately do not model.
        val w = ProtoWriter().apply { message(9, ProtoWriter().apply { uint32(1, 1) }) }
        assertEquals(FromRadio.Other(9), MeshProto.decodeFromRadio(w.toByteArray()))
    }

    @Test
    fun new_unknown_fields_inside_known_messages_are_ignored() {
        // A NodeInfo with an extra field the codec does not know (e.g. 13).
        val w = ProtoWriter().apply {
            uint32(1, 42) // num
            string(13, "future")
        }
        val decoded = MeshProto.decodeFromRadio(
            ProtoWriter().apply { message(4, w) }.toByteArray(),
        ) as FromRadio.Node
        assertEquals(42, decoded.info.num)
    }

    @Test
    fun encrypted_packet_has_no_decoded_payload() {
        val packet = MeshPacket(to = MeshPacket.BROADCAST, id = 5, encrypted = byteArrayOf(1, 2, 3))
        val decoded = (roundTripToRadio(ToRadio.Packet(packet)) as ToRadio.Packet).packet
        assertNull(decoded.decoded)
        assertTrue(decoded.encrypted!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun default_valued_fields_are_not_written() {
        // A minimal packet: only `to` (always written) and the payload.
        val packet = MeshPacket(
            to = MeshPacket.BROADCAST,
            decoded = MeshData(portnum = CairnPort.DEFAULT, payload = byteArrayOf(1)),
        )
        val bytes = MeshProto.encodeMeshPacket(packet)
        // 1 (tag) + 4 (fixed32) + 1 (tag) + 1 (len) + data(1+1+1+1+1)
        assertTrue(bytes.size < 20, "expected a compact packet, got ${bytes.size} B")
    }
}
