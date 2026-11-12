package org.cairn.desktop.link

import org.cairn.meshtastic.FromRadio
import org.cairn.meshtastic.MeshData
import org.cairn.meshtastic.MeshDeviceMetrics
import org.cairn.meshtastic.MeshMetadata
import org.cairn.meshtastic.MeshMyNodeInfo
import org.cairn.meshtastic.MeshNodeInfo
import org.cairn.meshtastic.MeshPacket
import org.cairn.meshtastic.MeshPosition
import org.cairn.meshtastic.MeshProto
import org.cairn.meshtastic.MeshUser
import org.cairn.meshtastic.MeshtasticLink
import org.cairn.meshtastic.ToRadio
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An in-process fake Meshtastic node.
 *
 * No hardware, no radio, no pairing — but it speaks the real protocol: it
 * answers `want_config` with a node DB, and echoes any mesh packet back as if
 * a peer had transmitted it. That makes it possible to exercise the whole
 * pipeline (link → protobuf → Cairn codec → sync engine → UI) in CI and on a
 * laptop, which is the point of a dev harness.
 */
class MockLink(
    private val latencyMs: Long = 40,
    private val selfNodeNum: Int = 0x00CA_1E01,
    private val peerNodeNum: Int = 0x00BE_EF01,
) : MeshtasticLink {

    override val description: String = "mock"

    override var onMessage: (ByteArray) -> Unit = {}
    override var onClosed: (Throwable?) -> Unit = {}

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cairn-mock-node").apply { isDaemon = true }
    }

    @Volatile
    private var open = false

    override fun open() {
        open = true
    }

    override fun write(message: ByteArray) {
        if (!open) throw IllegalStateException("mock link is not open")
        val toRadio = runCatching { MeshProto.decodeToRadio(message) }.getOrNull() ?: return
        val replies = when (toRadio) {
            is ToRadio.WantConfig -> nodeDatabase(toRadio.id)
            is ToRadio.Packet -> echoFromPeer(toRadio.packet)
            ToRadio.Disconnect -> emptyList()
            is ToRadio.Heartbeat -> emptyList()
        }
        replies.forEach { deliver(it) }
    }

    override fun close() {
        open = false
        executor.shutdownNow()
    }

    private fun deliver(message: FromRadio) {
        val bytes = MeshProto.encodeFromRadio(message)
        if (latencyMs <= 0) {
            onMessage(bytes)
            return
        }
        runCatching {
            executor.execute {
                if (!open) return@execute
                try {
                    Thread.sleep(latencyMs)
                } catch (_: InterruptedException) {
                    return@execute
                }
                if (open) onMessage(bytes)
            }
        }
    }

    private fun nodeDatabase(wantConfigId: Int): List<FromRadio> = listOf(
        FromRadio.MyInfo(MeshMyNodeInfo(myNodeNum = selfNodeNum, rebootCount = 12, nodedbCount = 2)),
        FromRadio.Metadata(
            MeshMetadata(
                firmwareVersion = "2.6.0.mock",
                hasWifi = true,
                hasBluetooth = true,
                hwModel = 43, // HELTEC_V3, just so the harness prints something
            ),
        ),
        FromRadio.Node(
            MeshNodeInfo(
                num = selfNodeNum,
                user = MeshUser(id = nodeId(selfNodeNum), longName = "Cairn Dev Node", shortName = "DEV"),
                deviceMetrics = MeshDeviceMetrics(batteryLevel = 97, voltage = 4.12f, uptimeSeconds = 600),
                hopsAway = 0,
            ),
        ),
        FromRadio.Node(
            MeshNodeInfo(
                num = peerNodeNum,
                user = MeshUser(id = nodeId(peerNodeNum), longName = "Anna (mock peer)", shortName = "AN"),
                position = MeshPosition(latitudeI = 525_204_000, longitudeI = 134_043_000, timeSec = 1_767_230_000),
                snr = 6.25f,
                deviceMetrics = MeshDeviceMetrics(batteryLevel = 64, voltage = 3.91f),
                hopsAway = 2,
            ),
        ),
        FromRadio.ConfigComplete(wantConfigId),
    )

    /** Pretend the peer transmitted what we just sent, so the loop closes. */
    private fun echoFromPeer(sent: MeshPacket): List<FromRadio> {
        val decoded = sent.decoded ?: return emptyList()
        val echoed = MeshPacket(
            from = peerNodeNum,
            to = MeshPacket.BROADCAST,
            channel = sent.channel,
            id = sent.id,
            rxTime = (System.currentTimeMillis() / 1000).toInt(),
            rxSnr = 6.25f,
            hopLimit = 3,
            hopStart = 3,
            decoded = MeshData(
                portnum = decoded.portnum,
                payload = decoded.payload,
                wantResponse = decoded.wantResponse,
            ),
        )
        return listOf(FromRadio.Mesh(echoed))
    }

    private fun nodeId(num: Int): String = "!" + num.toUInt().toString(16).padStart(8, '0')

    /** Blocks until queued replies have been delivered. Test-only helper. */
    internal fun awaitIdle(timeoutMs: Long = 2_000) {
        if (latencyMs <= 0) return
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
