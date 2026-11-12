package org.cairn.meshtastic

import org.cairn.meshtastic.ProtoWriter.Companion.WIRE_FIXED32
import org.cairn.meshtastic.ProtoWriter.Companion.WIRE_LEN
import org.cairn.meshtastic.ProtoWriter.Companion.WIRE_VARINT

/**
 * Encoder/decoder for the Meshtastic link protocol (`ToRadio`/`FromRadio`).
 *
 * Deliberately asymmetric: we encode only what a Cairn client sends
 * (`want_config_id`, mesh packets, disconnect, heartbeat) and decode a
 * superset of what the device sends back, because the device is the source of
 * truth for node info, positions and config.
 *
 * `proto3` semantics are kept: a field equal to its default value is not
 * written. On LoRa that is not a detail — every byte sent is airtime.
 */
object MeshProto {

    // ------------------------------------------------------------ ToRadio

    fun encodeToRadio(message: ToRadio): ByteArray {
        val w = ProtoWriter(128)
        when (message) {
            is ToRadio.Packet -> w.message(FIELD_PACKET, encodeMeshPacket(message.packet))
            is ToRadio.WantConfig -> w.uint32(FIELD_WANT_CONFIG_ID, message.id)
            ToRadio.Disconnect -> w.bool(FIELD_DISCONNECT, true)
            is ToRadio.Heartbeat -> w.message(FIELD_HEARTBEAT, heartbeat(message.nonce))
        }
        return w.toByteArray()
    }

    fun decodeToRadio(bytes: ByteArray): ToRadio? {
        val r = ProtoReader(bytes)
        var result: ToRadio? = null
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_PACKET && r.wireType == WIRE_LEN ->
                    result = ToRadio.Packet(decodeMeshPacket(r.readMessage()))
                field == FIELD_WANT_CONFIG_ID && r.wireType == WIRE_VARINT ->
                    result = ToRadio.WantConfig(r.readUInt32())
                field == FIELD_DISCONNECT && r.wireType == WIRE_VARINT -> {
                    r.readBool() // consume the value, or the next field read desyncs
                    result = ToRadio.Disconnect
                }
                field == FIELD_HEARTBEAT && r.wireType == WIRE_LEN ->
                    result = ToRadio.Heartbeat(decodeHeartbeat(r.readMessage()))
                else -> r.skipValue()
            }
        }
        return result
    }

    // ------------------------------------------------------------ FromRadio

    fun encodeFromRadio(message: FromRadio): ByteArray {
        val w = ProtoWriter(128)
        when (message) {
            is FromRadio.Mesh -> w.message(FIELD_PACKET, encodeMeshPacket(message.packet))
            is FromRadio.MyInfo -> w.message(FIELD_MY_INFO, encodeMyInfo(message.info))
            is FromRadio.Node -> w.message(FIELD_NODE_INFO, encodeNodeInfo(message.info))
            is FromRadio.ConfigComplete -> w.uint32(FIELD_CONFIG_COMPLETE_ID, message.id)
            is FromRadio.Rebooted -> w.bool(FIELD_REBOOTED, message.value)
            is FromRadio.Metadata -> w.message(FIELD_METADATA, encodeMetadata(message.metadata))
            is FromRadio.Notification -> w.message(FIELD_CLIENT_NOTIFICATION, encodeNotification(message.notification))
            // Unknown/undecodable variants cannot be re-encoded by definition.
            is FromRadio.Other -> Unit
        }
        return w.toByteArray()
    }

    fun decodeFromRadio(bytes: ByteArray): FromRadio {
        val r = ProtoReader(bytes)
        var result: FromRadio? = null
        var unknownField = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_PACKET && r.wireType == WIRE_LEN ->
                    result = FromRadio.Mesh(decodeMeshPacket(r.readMessage()))
                field == FIELD_MY_INFO && r.wireType == WIRE_LEN ->
                    result = FromRadio.MyInfo(decodeMyInfo(r.readMessage()))
                field == FIELD_NODE_INFO && r.wireType == WIRE_LEN ->
                    result = FromRadio.Node(decodeNodeInfo(r.readMessage()))
                field == FIELD_CONFIG_COMPLETE_ID && r.wireType == WIRE_VARINT ->
                    result = FromRadio.ConfigComplete(r.readUInt32())
                field == FIELD_REBOOTED && r.wireType == WIRE_VARINT ->
                    result = FromRadio.Rebooted(r.readBool())
                field == FIELD_METADATA && r.wireType == WIRE_LEN ->
                    result = FromRadio.Metadata(decodeMetadata(r.readMessage()))
                field == FIELD_CLIENT_NOTIFICATION && r.wireType == WIRE_LEN ->
                    result = FromRadio.Notification(decodeNotification(r.readMessage()))
                else -> {
                    if (unknownField == 0) unknownField = field
                    r.skipValue()
                }
            }
        }
        return result ?: FromRadio.Other(unknownField)
    }

    // ------------------------------------------------------------ MeshPacket

    fun encodeMeshPacket(packet: MeshPacket): ByteArray {
        val w = ProtoWriter(160)
        if (packet.from != 0) w.fixed32(FIELD_FROM, packet.from)
        w.fixed32(FIELD_TO, packet.to)
        if (packet.channel != 0) w.uint32(FIELD_CHANNEL, packet.channel)
        val decoded = packet.decoded
        if (decoded != null) {
            w.message(FIELD_DECODED, encodeData(decoded))
        } else if (packet.encrypted != null) {
            w.bytes(FIELD_ENCRYPTED, packet.encrypted)
        }
        if (packet.id != 0) w.fixed32(FIELD_PACKET_ID, packet.id)
        if (packet.hopLimit != 0) w.uint32(FIELD_HOP_LIMIT, packet.hopLimit)
        if (packet.wantAck) w.bool(FIELD_WANT_ACK, true)
        return w.toByteArray()
    }

    fun decodeMeshPacket(r: ProtoReader): MeshPacket {
        var from = 0
        var to = MeshPacket.BROADCAST
        var channel = 0
        var id = 0
        var rxTime: Int? = null
        var rxSnr = 0f
        var rxRssi: Int? = null
        var hopLimit = 0
        var hopStart = 0
        var wantAck = false
        var viaMqtt = false
        var decoded: MeshData? = null
        var encrypted: ByteArray? = null
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_FROM && r.wireType == WIRE_FIXED32 -> from = r.readFixed32()
                field == FIELD_TO && r.wireType == WIRE_FIXED32 -> to = r.readFixed32()
                field == FIELD_CHANNEL && r.wireType == WIRE_VARINT -> channel = r.readUInt32()
                field == FIELD_DECODED && r.wireType == WIRE_LEN -> decoded = decodeData(r.readMessage())
                field == FIELD_ENCRYPTED && r.wireType == WIRE_LEN -> encrypted = r.readBytes()
                field == FIELD_PACKET_ID && r.wireType == WIRE_FIXED32 -> id = r.readFixed32()
                field == FIELD_RX_TIME && r.wireType == WIRE_FIXED32 -> rxTime = r.readFixed32()
                field == FIELD_RX_SNR && r.wireType == WIRE_FIXED32 -> rxSnr = r.readFloat()
                field == FIELD_HOP_LIMIT && r.wireType == WIRE_VARINT -> hopLimit = r.readUInt32()
                field == FIELD_WANT_ACK && r.wireType == WIRE_VARINT -> wantAck = r.readBool()
                field == FIELD_RX_RSSI && r.wireType == WIRE_VARINT -> rxRssi = r.readVarint().toInt()
                field == FIELD_VIA_MQTT && r.wireType == WIRE_VARINT -> viaMqtt = r.readBool()
                field == FIELD_HOP_START && r.wireType == WIRE_VARINT -> hopStart = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshPacket(
            from = from,
            to = to,
            channel = channel,
            id = id,
            rxTime = rxTime,
            rxSnr = rxSnr,
            rxRssi = rxRssi,
            hopLimit = hopLimit,
            hopStart = hopStart,
            wantAck = wantAck,
            viaMqtt = viaMqtt,
            decoded = decoded,
            encrypted = encrypted,
        )
    }

    // ------------------------------------------------------------ Data

    fun encodeData(data: MeshData): ByteArray {
        val w = ProtoWriter(16 + data.payload.size)
        if (data.portnum != 0) w.uint32(FIELD_PORTNUM, data.portnum)
        if (data.payload.isNotEmpty()) w.bytes(FIELD_PAYLOAD, data.payload)
        if (data.wantResponse) w.bool(FIELD_WANT_RESPONSE, true)
        if (data.requestId != 0) w.fixed32(FIELD_REQUEST_ID, data.requestId)
        return w.toByteArray()
    }

    fun decodeData(r: ProtoReader): MeshData {
        var portnum = 0
        var payload = ByteArray(0)
        var wantResponse = false
        var requestId = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_PORTNUM && r.wireType == WIRE_VARINT -> portnum = r.readUInt32()
                field == FIELD_PAYLOAD && r.wireType == WIRE_LEN -> payload = r.readBytes()
                field == FIELD_WANT_RESPONSE && r.wireType == WIRE_VARINT -> wantResponse = r.readBool()
                field == FIELD_REQUEST_ID && r.wireType == WIRE_FIXED32 -> requestId = r.readFixed32()
                else -> r.skipValue()
            }
        }
        return MeshData(portnum = portnum, payload = payload, wantResponse = wantResponse, requestId = requestId)
    }

    // ------------------------------------------------------------ Node / user

    fun encodeMyInfo(info: MeshMyNodeInfo): ByteArray {
        val w = ProtoWriter(16)
        w.uint32(FIELD_MY_NODE_NUM, info.myNodeNum)
        if (info.rebootCount != 0) w.uint32(FIELD_REBOOT_COUNT, info.rebootCount)
        if (info.minAppVersion != 0) w.uint32(FIELD_MIN_APP_VERSION, info.minAppVersion)
        if (info.nodedbCount != 0) w.uint32(FIELD_NODEDB_COUNT, info.nodedbCount)
        return w.toByteArray()
    }

    fun decodeMyInfo(r: ProtoReader): MeshMyNodeInfo {
        var myNodeNum = 0
        var rebootCount = 0
        var minAppVersion = 0
        var nodedbCount = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_MY_NODE_NUM && r.wireType == WIRE_VARINT -> myNodeNum = r.readUInt32()
                field == FIELD_REBOOT_COUNT && r.wireType == WIRE_VARINT -> rebootCount = r.readUInt32()
                field == FIELD_MIN_APP_VERSION && r.wireType == WIRE_VARINT -> minAppVersion = r.readUInt32()
                field == FIELD_NODEDB_COUNT && r.wireType == WIRE_VARINT -> nodedbCount = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshMyNodeInfo(myNodeNum, rebootCount, minAppVersion, nodedbCount)
    }

    fun encodeNodeInfo(info: MeshNodeInfo): ByteArray {
        val w = ProtoWriter(64)
        w.uint32(FIELD_NODE_NUM, info.num)
        info.user?.let { w.message(FIELD_USER, encodeUser(it)) }
        info.position?.let { w.message(FIELD_POSITION, encodePosition(it)) }
        if (info.snr != 0f) w.float(FIELD_NODE_SNR, info.snr)
        if (info.lastHeard != 0) w.fixed32(FIELD_LAST_HEARD, info.lastHeard)
        info.deviceMetrics?.let { w.message(FIELD_DEVICE_METRICS, encodeDeviceMetrics(it)) }
        if (info.channel != 0) w.uint32(FIELD_NODE_CHANNEL, info.channel)
        if (info.viaMqtt) w.bool(FIELD_VIA_MQTT, true)
        info.hopsAway?.let { w.uint32(FIELD_HOPS_AWAY, it) }
        return w.toByteArray()
    }

    fun decodeNodeInfo(r: ProtoReader): MeshNodeInfo {
        var num = 0
        var user: MeshUser? = null
        var position: MeshPosition? = null
        var snr = 0f
        var lastHeard = 0
        var deviceMetrics: MeshDeviceMetrics? = null
        var channel = 0
        var viaMqtt = false
        var hopsAway: Int? = null
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_NODE_NUM && r.wireType == WIRE_VARINT -> num = r.readUInt32()
                field == FIELD_USER && r.wireType == WIRE_LEN -> user = decodeUser(r.readMessage())
                field == FIELD_POSITION && r.wireType == WIRE_LEN -> position = decodePosition(r.readMessage())
                field == FIELD_NODE_SNR && r.wireType == WIRE_FIXED32 -> snr = r.readFloat()
                field == FIELD_LAST_HEARD && r.wireType == WIRE_FIXED32 -> lastHeard = r.readFixed32()
                field == FIELD_DEVICE_METRICS && r.wireType == WIRE_LEN ->
                    deviceMetrics = decodeDeviceMetrics(r.readMessage())
                field == FIELD_NODE_CHANNEL && r.wireType == WIRE_VARINT -> channel = r.readUInt32()
                field == FIELD_VIA_MQTT && r.wireType == WIRE_VARINT -> viaMqtt = r.readBool()
                field == FIELD_HOPS_AWAY && r.wireType == WIRE_VARINT -> hopsAway = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshNodeInfo(num, user, position, snr, lastHeard, deviceMetrics, channel, viaMqtt, hopsAway)
    }

    fun encodeUser(user: MeshUser): ByteArray {
        val w = ProtoWriter(48)
        if (user.id.isNotEmpty()) w.string(FIELD_USER_ID, user.id)
        if (user.longName.isNotEmpty()) w.string(FIELD_USER_LONG_NAME, user.longName)
        if (user.shortName.isNotEmpty()) w.string(FIELD_USER_SHORT_NAME, user.shortName)
        if (user.hwModel != 0) w.uint32(FIELD_USER_HW_MODEL, user.hwModel)
        if (user.isLicensed) w.bool(FIELD_USER_IS_LICENSED, true)
        return w.toByteArray()
    }

    fun decodeUser(r: ProtoReader): MeshUser {
        var id = ""
        var longName = ""
        var shortName = ""
        var hwModel = 0
        var isLicensed = false
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_USER_ID && r.wireType == WIRE_LEN -> id = r.readString()
                field == FIELD_USER_LONG_NAME && r.wireType == WIRE_LEN -> longName = r.readString()
                field == FIELD_USER_SHORT_NAME && r.wireType == WIRE_LEN -> shortName = r.readString()
                field == FIELD_USER_HW_MODEL && r.wireType == WIRE_VARINT -> hwModel = r.readUInt32()
                field == FIELD_USER_IS_LICENSED && r.wireType == WIRE_VARINT -> isLicensed = r.readBool()
                else -> r.skipValue()
            }
        }
        return MeshUser(id, longName, shortName, hwModel, isLicensed)
    }

    fun encodePosition(position: MeshPosition): ByteArray {
        val w = ProtoWriter(24)
        // latitude_i / longitude_i are `optional sfixed32` -> fixed32 on the wire.
        w.fixed32(FIELD_LATITUDE_I, position.latitudeI)
        w.fixed32(FIELD_LONGITUDE_I, position.longitudeI)
        position.timeSec?.let { w.fixed32(FIELD_POSITION_TIME, it) }
        if (position.precisionBits != 0) w.uint32(FIELD_PRECISION_BITS, position.precisionBits)
        return w.toByteArray()
    }

    fun decodePosition(r: ProtoReader): MeshPosition {
        var latitudeI = 0
        var longitudeI = 0
        var timeSec: Int? = null
        var precisionBits = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_LATITUDE_I && r.wireType == WIRE_FIXED32 -> latitudeI = r.readFixed32()
                field == FIELD_LONGITUDE_I && r.wireType == WIRE_FIXED32 -> longitudeI = r.readFixed32()
                field == FIELD_POSITION_TIME && r.wireType == WIRE_FIXED32 -> timeSec = r.readFixed32()
                field == FIELD_PRECISION_BITS && r.wireType == WIRE_VARINT -> precisionBits = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshPosition(latitudeI, longitudeI, timeSec, precisionBits)
    }

    fun encodeDeviceMetrics(metrics: MeshDeviceMetrics): ByteArray {
        val w = ProtoWriter(24)
        metrics.batteryLevel?.let { w.uint32(FIELD_BATTERY_LEVEL, it) }
        metrics.voltage?.let { w.float(FIELD_VOLTAGE, it) }
        metrics.channelUtilization?.let { w.float(FIELD_CHANNEL_UTILIZATION, it) }
        metrics.airUtilTx?.let { w.float(FIELD_AIR_UTIL_TX, it) }
        metrics.uptimeSeconds?.let { w.uint32(FIELD_UPTIME_SECONDS, it) }
        return w.toByteArray()
    }

    fun decodeDeviceMetrics(r: ProtoReader): MeshDeviceMetrics {
        var batteryLevel: Int? = null
        var voltage: Float? = null
        var channelUtilization: Float? = null
        var airUtilTx: Float? = null
        var uptimeSeconds: Int? = null
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_BATTERY_LEVEL && r.wireType == WIRE_VARINT -> batteryLevel = r.readUInt32()
                field == FIELD_VOLTAGE && r.wireType == WIRE_FIXED32 -> voltage = r.readFloat()
                field == FIELD_CHANNEL_UTILIZATION && r.wireType == WIRE_FIXED32 ->
                    channelUtilization = r.readFloat()
                field == FIELD_AIR_UTIL_TX && r.wireType == WIRE_FIXED32 -> airUtilTx = r.readFloat()
                field == FIELD_UPTIME_SECONDS && r.wireType == WIRE_VARINT -> uptimeSeconds = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshDeviceMetrics(batteryLevel, voltage, channelUtilization, airUtilTx, uptimeSeconds)
    }

    // ------------------------------------------------------------ Misc

    fun encodeMetadata(metadata: MeshMetadata): ByteArray {
        val w = ProtoWriter(64)
        if (metadata.firmwareVersion.isNotEmpty()) w.string(FIELD_FIRMWARE_VERSION, metadata.firmwareVersion)
        if (metadata.deviceStateVersion != 0) w.uint32(FIELD_DEVICE_STATE_VERSION, metadata.deviceStateVersion)
        if (metadata.hasWifi) w.bool(FIELD_HAS_WIFI, true)
        if (metadata.hasBluetooth) w.bool(FIELD_HAS_BLUETOOTH, true)
        if (metadata.hasEthernet) w.bool(FIELD_HAS_ETHERNET, true)
        if (metadata.role != 0) w.uint32(FIELD_DEVICE_ROLE, metadata.role)
        if (metadata.hwModel != 0) w.uint32(FIELD_METADATA_HW_MODEL, metadata.hwModel)
        return w.toByteArray()
    }

    fun decodeMetadata(r: ProtoReader): MeshMetadata {
        var firmwareVersion = ""
        var deviceStateVersion = 0
        var hasWifi = false
        var hasBluetooth = false
        var hasEthernet = false
        var role = 0
        var hwModel = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_FIRMWARE_VERSION && r.wireType == WIRE_LEN -> firmwareVersion = r.readString()
                field == FIELD_DEVICE_STATE_VERSION && r.wireType == WIRE_VARINT ->
                    deviceStateVersion = r.readUInt32()
                field == FIELD_HAS_WIFI && r.wireType == WIRE_VARINT -> hasWifi = r.readBool()
                field == FIELD_HAS_BLUETOOTH && r.wireType == WIRE_VARINT -> hasBluetooth = r.readBool()
                field == FIELD_HAS_ETHERNET && r.wireType == WIRE_VARINT -> hasEthernet = r.readBool()
                field == FIELD_DEVICE_ROLE && r.wireType == WIRE_VARINT -> role = r.readUInt32()
                field == FIELD_METADATA_HW_MODEL && r.wireType == WIRE_VARINT -> hwModel = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshMetadata(firmwareVersion, deviceStateVersion, hasWifi, hasBluetooth, hasEthernet, hwModel, role)
    }

    private fun encodeNotification(notification: MeshNotification): ByteArray {
        val w = ProtoWriter(32)
        notification.replyId?.let { w.uint32(FIELD_NOTIFICATION_REPLY_ID, it) }
        if (notification.timeSec != 0) w.fixed32(FIELD_NOTIFICATION_TIME, notification.timeSec)
        if (notification.level != 0) w.uint32(FIELD_NOTIFICATION_LEVEL, notification.level)
        if (notification.message.isNotEmpty()) w.string(FIELD_NOTIFICATION_MESSAGE, notification.message)
        if (notification.type != 0) w.uint32(FIELD_NOTIFICATION_TYPE, notification.type)
        return w.toByteArray()
    }

    private fun decodeNotification(r: ProtoReader): MeshNotification {
        var message = ""
        var level = 0
        var type = 0
        var replyId: Int? = null
        var timeSec = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_NOTIFICATION_REPLY_ID && r.wireType == WIRE_VARINT -> replyId = r.readUInt32()
                field == FIELD_NOTIFICATION_TIME && r.wireType == WIRE_FIXED32 -> timeSec = r.readFixed32()
                field == FIELD_NOTIFICATION_LEVEL && r.wireType == WIRE_VARINT -> level = r.readUInt32()
                field == FIELD_NOTIFICATION_MESSAGE && r.wireType == WIRE_LEN -> message = r.readString()
                field == FIELD_NOTIFICATION_TYPE && r.wireType == WIRE_VARINT -> type = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return MeshNotification(message, level, type, replyId, timeSec)
    }

    private fun heartbeat(nonce: Int): ProtoWriter {
        val w = ProtoWriter(8)
        w.uint32(FIELD_HEARTBEAT_NONCE, nonce)
        return w
    }

    private fun decodeHeartbeat(r: ProtoReader): Int {
        var nonce = 0
        while (true) {
            val field = r.nextField() ?: break
            when {
                field == FIELD_HEARTBEAT_NONCE && r.wireType == WIRE_VARINT -> nonce = r.readUInt32()
                else -> r.skipValue()
            }
        }
        return nonce
    }

    // ------------------------------------------------------------ field numbers
    // Pinned to meshtastic/protobufs. Do not renumber.

    // MeshPacket
    private const val FIELD_FROM = 1
    private const val FIELD_TO = 2
    private const val FIELD_CHANNEL = 3
    private const val FIELD_DECODED = 4
    private const val FIELD_ENCRYPTED = 5
    private const val FIELD_PACKET_ID = 6
    private const val FIELD_RX_TIME = 7
    private const val FIELD_RX_SNR = 8
    private const val FIELD_HOP_LIMIT = 9
    private const val FIELD_WANT_ACK = 10
    private const val FIELD_RX_RSSI = 12
    private const val FIELD_VIA_MQTT = 14
    private const val FIELD_HOP_START = 15

    // Data
    private const val FIELD_PORTNUM = 1
    private const val FIELD_PAYLOAD = 2
    private const val FIELD_WANT_RESPONSE = 3
    private const val FIELD_REQUEST_ID = 6

    // ToRadio
    private const val FIELD_PACKET = 1 // shared with FromRadio
    private const val FIELD_WANT_CONFIG_ID = 3
    private const val FIELD_DISCONNECT = 4
    private const val FIELD_HEARTBEAT = 7
    private const val FIELD_HEARTBEAT_NONCE = 1

    // FromRadio (FIELD_PACKET is shared with ToRadio)
    private const val FIELD_MY_INFO = 3
    private const val FIELD_NODE_INFO = 4
    private const val FIELD_CONFIG_COMPLETE_ID = 7
    private const val FIELD_REBOOTED = 8
    private const val FIELD_METADATA = 13
    private const val FIELD_CLIENT_NOTIFICATION = 16

    // MyNodeInfo
    private const val FIELD_MY_NODE_NUM = 1
    private const val FIELD_REBOOT_COUNT = 8
    private const val FIELD_MIN_APP_VERSION = 11
    private const val FIELD_NODEDB_COUNT = 15

    // NodeInfo
    private const val FIELD_NODE_NUM = 1
    private const val FIELD_USER = 2
    private const val FIELD_POSITION = 3
    private const val FIELD_NODE_SNR = 4
    private const val FIELD_LAST_HEARD = 5
    private const val FIELD_DEVICE_METRICS = 6
    private const val FIELD_NODE_CHANNEL = 7
    private const val FIELD_HOPS_AWAY = 9

    // User
    private const val FIELD_USER_ID = 1
    private const val FIELD_USER_LONG_NAME = 2
    private const val FIELD_USER_SHORT_NAME = 3
    private const val FIELD_USER_HW_MODEL = 5
    private const val FIELD_USER_IS_LICENSED = 6

    // Position
    private const val FIELD_LATITUDE_I = 1
    private const val FIELD_LONGITUDE_I = 2
    private const val FIELD_POSITION_TIME = 4
    private const val FIELD_PRECISION_BITS = 23

    // DeviceMetrics
    private const val FIELD_BATTERY_LEVEL = 1
    private const val FIELD_VOLTAGE = 2
    private const val FIELD_CHANNEL_UTILIZATION = 3
    private const val FIELD_AIR_UTIL_TX = 4
    private const val FIELD_UPTIME_SECONDS = 5

    // DeviceMetadata
    private const val FIELD_FIRMWARE_VERSION = 1
    private const val FIELD_DEVICE_STATE_VERSION = 2
    private const val FIELD_HAS_WIFI = 4
    private const val FIELD_HAS_BLUETOOTH = 5
    private const val FIELD_HAS_ETHERNET = 6
    private const val FIELD_DEVICE_ROLE = 7
    private const val FIELD_METADATA_HW_MODEL = 9

    // ClientNotification
    private const val FIELD_NOTIFICATION_REPLY_ID = 1
    private const val FIELD_NOTIFICATION_TIME = 2
    private const val FIELD_NOTIFICATION_LEVEL = 3
    private const val FIELD_NOTIFICATION_MESSAGE = 4
    private const val FIELD_NOTIFICATION_TYPE = 5
}
