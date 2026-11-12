package org.cairn.meshtastic

/**
 * The subset of Meshtastic's protobuf messages Cairn needs.
 *
 * Field numbers are pinned to the upstream `.proto` files
 * (`meshtastic/mesh.proto`, `meshtastic/portnums.proto`,
 * `meshtastic/telemetry.proto`). Only fields we read or write are modelled;
 * everything else is skipped by [ProtoReader.skipValue], so a firmware update
 * that adds fields does not break us.
 */

/** Meshtastic port numbers (see `meshtastic/portnums.proto`). */
object MeshPort {
    const val TEXT_MESSAGE_APP = 1
    const val POSITION_APP = 3
    const val NODEINFO_APP = 4
    const val ROUTING_APP = 5
    const val ADMIN_APP = 6
}

/**
 * Cairn's application port (F-02: "private application port in Meshtastic's
 * private range 256–511").
 *
 * 256 is Meshtastic's generic `PRIVATE_APP` port. A dedicated number would
 * collide less with other private apps, but 256 needs no firmware patch and
 * is forwarded on every setup; both ends must agree anyway, so it is
 * configurable and [DEFAULT] is the shared default.
 */
object CairnPort {
    const val DEFAULT = 256
    const val MIN = 256
    const val MAX = 511

    fun isValid(port: Int): Boolean = port in MIN..MAX
}

/** `Data` — the payload of a mesh packet (formerly `SubPacket`). */
class MeshData(
    val portnum: Int,
    val payload: ByteArray,
    val wantResponse: Boolean = false,
    val requestId: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is MeshData &&
                portnum == other.portnum &&
                wantResponse == other.wantResponse &&
                requestId == other.requestId &&
                payload.contentEquals(other.payload))

    override fun hashCode(): Int {
        var h = portnum
        h = 31 * h + payload.contentHashCode()
        h = 31 * h + wantResponse.hashCode()
        h = 31 * h + requestId
        return h
    }

    override fun toString(): String =
        "MeshData(port=$portnum, ${payload.size} B, wantResponse=$wantResponse, requestId=$requestId)"
}

/** `MeshPacket` — the mesh envelope (16-byte LoRa header is firmware-internal). */
class MeshPacket(
    val from: Int = 0,
    val to: Int = BROADCAST,
    val channel: Int = 0,
    val id: Int = 0,
    val rxTime: Int? = null,
    val rxSnr: Float = 0f,
    val rxRssi: Int? = null,
    val hopLimit: Int = 0,
    val hopStart: Int = 0,
    val wantAck: Boolean = false,
    val viaMqtt: Boolean = false,
    val decoded: MeshData? = null,
    val encrypted: ByteArray? = null,
) {
    override fun toString(): String =
        "MeshPacket(from=0x${from.toUInt().toString(16)}, to=0x${to.toUInt().toString(16)}, " +
            "ch=$channel, id=0x${id.toUInt().toString(16)}, hops=$hopStart/$hopLimit, " +
            "snr=$rxSnr, decoded=$decoded)"

    companion object {
        /** `0xFFFFFFFF` = "to the channel", i.e. broadcast (mesh.proto). */
        const val BROADCAST = -1 // 0xFFFFFFFF as Int
    }
}

/** `Position` (only the location fields Cairn uses). */
data class MeshPosition(
    val latitudeI: Int,
    val longitudeI: Int,
    val timeSec: Int? = null,
    val precisionBits: Int = 0,
) {
    val latitude: Double get() = latitudeI / 1e7
    val longitude: Double get() = longitudeI / 1e7

    val isPlausible: Boolean get() = latitudeI != 0 || longitudeI != 0
}

/** `User` (see `mesh.proto`). */
data class MeshUser(
    val id: String = "",
    val longName: String = "",
    val shortName: String = "",
    val hwModel: Int = 0,
    val isLicensed: Boolean = false,
)

/** `DeviceMetrics` (see `telemetry.proto`). */
data class MeshDeviceMetrics(
    val batteryLevel: Int? = null,
    val voltage: Float? = null,
    val channelUtilization: Float? = null,
    val airUtilTx: Float? = null,
    val uptimeSeconds: Int? = null,
)

/** `NodeInfo` — one entry of the node database the device streams to clients. */
data class MeshNodeInfo(
    val num: Int = 0,
    val user: MeshUser? = null,
    val position: MeshPosition? = null,
    val snr: Float = 0f,
    val lastHeard: Int = 0,
    val deviceMetrics: MeshDeviceMetrics? = null,
    val channel: Int = 0,
    val viaMqtt: Boolean = false,
    val hopsAway: Int? = null,
)

/** `MyNodeInfo` — the node number of the radio we are attached to. */
data class MeshMyNodeInfo(
    val myNodeNum: Int = 0,
    val rebootCount: Int = 0,
    val minAppVersion: Int = 0,
    val nodedbCount: Int = 0,
)

/** `DeviceMetadata` — what the firmware is and supports. */
data class MeshMetadata(
    val firmwareVersion: String = "",
    val deviceStateVersion: Int = 0,
    val hasWifi: Boolean = false,
    val hasBluetooth: Boolean = false,
    val hasEthernet: Boolean = false,
    val hwModel: Int = 0,
    val role: Int = 0,
)

/** `ClientNotification` — user-facing message from the device. */
data class MeshNotification(
    val message: String = "",
    val level: Int = 0,
    val type: Int = 0,
    val replyId: Int? = null,
    val timeSec: Int = 0,
)

/**
 * Messages the device sends to the client (`FromRadio` in `mesh.proto`).
 *
 * `Other` carries a field number we do not model (module config, channel,
 * queue status, …). It is reported rather than silently dropped so the dev
 * harness can log what the firmware is talking about.
 */
sealed class FromRadio {
    data class Mesh(val packet: MeshPacket) : FromRadio()
    data class MyInfo(val info: MeshMyNodeInfo) : FromRadio()
    data class Node(val info: MeshNodeInfo) : FromRadio()
    data class ConfigComplete(val id: Int) : FromRadio()
    data class Rebooted(val value: Boolean) : FromRadio()
    data class Metadata(val metadata: MeshMetadata) : FromRadio()
    data class Notification(val notification: MeshNotification) : FromRadio()

    /** An unmodelled `FromRadio.payload_variant` field. */
    data class Other(val field: Int) : FromRadio()
}

/** Messages the client sends to the device (`ToRadio` in `mesh.proto`). */
sealed class ToRadio {
    data class Packet(val packet: MeshPacket) : ToRadio()
    data class WantConfig(val id: Int) : ToRadio()
    data object Disconnect : ToRadio()
    data class Heartbeat(val nonce: Int) : ToRadio()
}
