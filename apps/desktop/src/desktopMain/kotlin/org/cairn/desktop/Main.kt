package org.cairn.desktop

import org.cairn.desktop.link.LinkSpec
import org.cairn.desktop.link.MockLink
import org.cairn.desktop.link.SerialLink
import org.cairn.desktop.link.TcpLink
import org.cairn.meshtastic.CairnPort
import org.cairn.meshtastic.FromRadio
import org.cairn.meshtastic.MeshNodeInfo
import org.cairn.meshtastic.MeshPort
import org.cairn.meshtastic.MeshPosition
import org.cairn.meshtastic.MeshProto
import org.cairn.meshtastic.MeshtasticRadio
import org.cairn.meshtastic.ProtoReader
import org.cairn.protocol.Codec
import org.cairn.protocol.DigestPacket
import org.cairn.protocol.Flags
import org.cairn.protocol.Hlc
import org.cairn.protocol.HybridClock
import org.cairn.protocol.MessagePacket
import org.cairn.protocol.ObjectId
import org.cairn.protocol.ObjectType
import org.cairn.protocol.Packet
import org.cairn.protocol.PointPacket
import org.cairn.protocol.TombstonePacket
import org.cairn.sync.Event
import org.cairn.sync.RadioTransport
import org.cairn.sync.SyncEngine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.roundToInt

/** 2026-01-01T00:00:00Z — the epoch of [Hlc.sec]. */
private const val EPOCH_2026_SECONDS = 1_767_225_600L

fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println()
        System.err.println(Options.USAGE)
        return
    } ?: run {
        println(Options.USAGE)
        return
    }

    val link = when (val spec = options.link) {
        is LinkSpec.Tcp -> TcpLink(spec.host, spec.port)
        is LinkSpec.Serial -> SerialLink(spec.device, spec.baud)
        LinkSpec.Mock -> MockLink()
    }

    val radio = MeshtasticRadio(
        link = link,
        channelIndex = options.channel,
        cairnPort = options.cairnPort,
    )
    val clock = HybridClock { ((System.currentTimeMillis() / 1000) - EPOCH_2026_SECONDS).toInt() }
    val sync = SyncEngine { System.currentTimeMillis() }
    val session = Session(radio, sync, clock, options.authorId, options.cairnPort)

    println("Cairn desktop dev harness")
    println("  link      ${link.description}")
    println("  channel   ${options.channel}")
    println("  cairn port ${options.cairnPort}")
    println("  author id ${options.authorId.toInt() and 0xFF}")
    println()
    println("Type `help` for commands, `quit` to exit.")
    println()

    radio.connect(ByteArray(0))
    session.repl()

    radio.disconnect()
    println("bye.")
}

/** Command line options; [parse] returns null when `--help` was asked for. */
private class Options(
    val link: LinkSpec,
    val channel: Int,
    val cairnPort: Int,
    val authorId: Byte,
) {
    companion object {
        val USAGE = """
            Usage: cairn-desktop --link <spec> [options]

            Link specs:
              tcp:HOST[:PORT]        Meshtastic TCP API (default port ${LinkSpec.DEFAULT_TCP_PORT})
              serial:DEVICE[:BAUD]   USB serial node (default ${LinkSpec.DEFAULT_BAUD} baud)
              mock                   In-process fake node, no hardware

            Options:
              --channel N   Meshtastic channel index to use (default 0)
              --port N      Cairn application port, 256..511 (default ${CairnPort.DEFAULT})
              --author N    Cairn author ID, 1..255 (default: random)
              --help        Show this help

            Examples:
              cairn-desktop --link mock
              cairn-desktop --link serial:/dev/ttyACM0
              cairn-desktop --link tcp:192.168.0.42:4403 --channel 1
        """.trimIndent()

        fun parse(args: Array<String>): Options? {
            var link: LinkSpec? = null
            var channel = 0
            var port = CairnPort.DEFAULT
            var author = (1..255).random().toByte()

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--help", "-h" -> return null
                    "--link" -> link = LinkSpec.parse(requireValue(args, ++i, arg))
                    "--channel" -> channel = requireInt(args, ++i, arg)
                    "--port" -> port = requireInt(args, ++i, arg)
                    "--author" -> author = requireInt(args, ++i, arg).toByte()
                    else -> throw IllegalArgumentException("unknown option '$arg'")
                }
                i++
            }

            val resolved = link ?: throw IllegalArgumentException("--link is required")
            require(channel >= 0) { "channel must be >= 0" }
            require(CairnPort.isValid(port)) {
                "port $port is outside the Meshtastic private range ${CairnPort.MIN}..${CairnPort.MAX} (F-02)"
            }
            require((author.toInt() and 0xFF) != 0) { "author id 0 is reserved (ADR-004)" }
            return Options(resolved, channel, port, author)
        }

        private fun requireValue(args: Array<String>, index: Int, option: String): String {
            require(index < args.size) { "$option needs a value" }
            return args[index]
        }

        private fun requireInt(args: Array<String>, index: Int, option: String): Int {
            val raw = requireValue(args, index, option)
            return raw.toIntOrNull() ?: throw IllegalArgumentException("$option expects a number, got '$raw'")
        }
    }
}

/**
 * The interactive half: keeps the node list and the Cairn object store, prints
 * what happens, and turns typed commands into packets.
 *
 * It is intentionally text-only. The point of the harness is to debug the
 * radio link and the sync engine, not to build a UI — a map UI comes later on
 * top of the same modules.
 */
private class Session(
    private val radio: MeshtasticRadio,
    private val sync: SyncEngine,
    private val clock: HybridClock,
    private val authorId: Byte,
    private val cairnPort: Int,
) {
    private val consoleLock = Any()
    private val nodes = LinkedHashMap<Int, MeshNodeInfo>()
    private var counter = 0

    init {
        radio.onStateChanged = { state -> say("[radio] ${describe(state)}") }
        radio.onMeshMessage = { message -> handleMeshMessage(message) }
        radio.onReceive = { packet -> ingest(packet) }
        radio.onCairnDecodeError = { payload, error ->
            say("[radio] dropping ${payload.size} B on port $cairnPort: ${error.message}")
        }
    }

    fun repl() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            synchronized(consoleLock) {
                print("cairn> ")
                System.out.flush()
            }
            val line = reader.readLine() ?: return
            val keepGoing = try {
                execute(line.trim())
            } catch (e: Exception) {
                say("error: ${e.message}")
                true
            }
            if (!keepGoing) return
        }
    }

    private fun execute(line: String): Boolean {
        if (line.isEmpty()) return true
        val parts = line.split(Regex("\\s+"), limit = 2)
        val rest = parts.getOrNull(1).orEmpty()
        when (parts[0]) {
            "help", "?" -> printHelp()
            "status" -> printStatus()
            "nodes" -> printNodes()
            "state" -> printState()
            "point" -> sendPin(rest)
            "position", "pos" -> sendPosition(rest)
            "msg", "message" -> sendMessage(rest)
            "delete", "rm" -> sendDelete(rest)
            "quit", "exit", "q" -> return false
            else -> say("unknown command '${parts[0]}' — try `help`")
        }
        return true
    }

    // ---------------------------------------------------------- commands

    private fun sendPin(rest: String) {
        val (lat, lon, name) = parseCoordinates(rest)
        val packet = PointPacket(
            id = nextId(ObjectType.Pin),
            predecessor = null,
            flags = Flags(),
            version = clock.now(),
            latE7 = (lat * 1e7).roundToInt(),
            lonE7 = (lon * 1e7).roundToInt(),
            name = name,
        )
        transmit(packet)
    }

    private fun sendPosition(rest: String) {
        val (lat, lon, _) = parseCoordinates(rest)
        val packet = PointPacket(
            id = nextId(ObjectType.UserPosition),
            predecessor = null,
            flags = Flags(),
            version = clock.now(),
            latE7 = (lat * 1e7).roundToInt(),
            lonE7 = (lon * 1e7).roundToInt(),
            name = null,
        )
        transmit(packet)
    }

    private fun sendMessage(rest: String) {
        require(rest.isNotBlank()) { "usage: msg <text>" }
        val packet = MessagePacket(
            id = nextId(ObjectType.Message),
            senderId = authorId,
            version = clock.now(),
            referencedObject = null,
            text = rest,
        )
        transmit(packet)
    }

    private fun sendDelete(rest: String) {
        val target = parseObjectId(rest)
        val packet = TombstonePacket(id = nextId(ObjectType.Pin), targetId = target, version = clock.now())
        transmit(packet)
    }

    private fun transmit(packet: Packet) {
        if (radio.state.value !is RadioTransport.State.Connected) {
            say("not connected yet — wait for the handshake to finish")
            return
        }
        val size = Codec.encode(packet).size
        if (size > Codec.MAX_PACKET_BYTES) {
            say("refusing to send: ${size} B > ${Codec.MAX_PACKET_BYTES} B (N-05)")
            return
        }
        radio.send(packet)
        say("→ ${describe(packet)} (${size} B)")
    }

    // ---------------------------------------------------------- inbound

    private fun ingest(packet: Packet) {
        versionOf(packet)?.let { clock.observe(it) }
        val events = sync.ingest(packet)
        if (events.isEmpty()) {
            say("← ${describe(packet)} (ignored: older or duplicate)")
            return
        }
        say("← ${describe(packet)}")
        events.forEach { event -> say("   ${describe(event)}") }
    }

    private fun handleMeshMessage(message: FromRadio) {
        when (message) {
            is FromRadio.Node -> {
                synchronized(nodes) { nodes[message.info.num] = message.info }
                say("[node] ${describe(message.info)}")
            }

            is FromRadio.MyInfo -> say(
                "[node] attached radio is 0x${message.info.myNodeNum.toUInt().toString(16)} " +
                    "(${message.info.nodedbCount} nodes in its DB)",
            )

            is FromRadio.Metadata -> say(
                "[node] firmware ${message.metadata.firmwareVersion}" +
                    (if (message.metadata.hasWifi) ", wifi" else "") +
                    (if (message.metadata.hasBluetooth) ", ble" else ""),
            )

            is FromRadio.Notification -> say("[node] ${message.notification.message}")

            is FromRadio.Mesh -> handleMeshPacket(message)

            else -> Unit
        }
    }

    private fun handleMeshPacket(message: FromRadio.Mesh) {
        val packet = message.packet
        val data = packet.decoded ?: return
        if (data.portnum == cairnPort) return // handled by onReceive
        when (data.portnum) {
            MeshPort.TEXT_MESSAGE_APP ->
                say("[mesh] text from 0x${packet.from.toUInt().toString(16)}: ${data.payload.decodeToString()}")

            MeshPort.POSITION_APP -> {
                val position = runCatching { MeshProto.decodePosition(ProtoReader(data.payload)) }.getOrNull()
                if (position != null && position.isPlausible) {
                    synchronized(nodes) {
                        val existing = nodes[packet.from]
                        nodes[packet.from] = (existing ?: MeshNodeInfo(num = packet.from)).copy(position = position)
                    }
                    say("[mesh] position from 0x${packet.from.toUInt().toString(16)}: ${describe(position)}")
                }
            }

            else -> say(
                "[mesh] packet from 0x${packet.from.toUInt().toString(16)} " +
                    "on port ${data.portnum} (${data.payload.size} B) — not Cairn",
            )
        }
    }

    // ---------------------------------------------------------- printing

    private fun printHelp() {
        say(
            """
            commands
              status              connection state
              nodes               nodes heard on the mesh
              state               Cairn objects held by the sync engine
              point LAT LON [NAME]    send a pin (F-20)
              position LAT LON        send your position (F-10)
              msg TEXT                send a group message (F-50)
              delete Pin#N            send a tombstone for an object (F-41)
              quit                    exit
            """.trimIndent(),
        )
    }

    private fun printStatus() {
        say("link     ${radio.linkDescription}")
        say("state    ${describe(radio.state.value)}")
        say("node     ${radio.myNodeName ?: "(unknown)"}" + (radio.myNodeNum?.let { " 0x${it.toUInt().toString(16)}" } ?: ""))
        say("clock    ${clock.last}")
    }

    private fun printNodes() {
        val snapshot = synchronized(nodes) { nodes.values.toList() }
        if (snapshot.isEmpty()) {
            say("(no nodes yet)")
            return
        }
        snapshot.forEach { say(describe(it)) }
    }

    private fun printState() {
        val stored = sync.snapshot()
        if (stored.isEmpty()) {
            say("(no Cairn objects yet)")
            return
        }
        stored.forEach { entry ->
            val packet = entry.packet
            val line = if (packet == null) {
                "deleted   ${entry.version}"
            } else {
                "${describe(packet)}  v=${entry.version}"
            }
            say(line)
        }
    }

    // ---------------------------------------------------------- helpers

    private fun nextId(type: ObjectType): ObjectId {
        val id = ObjectId(type, authorId, counter % 0x1_0000)
        counter++
        return id
    }

    private fun parseCoordinates(rest: String): Triple<Double, Double, String?> {
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(tokens.size >= 2) { "usage: point LAT LON [NAME]" }
        val lat = tokens[0].toDoubleOrNull() ?: throw IllegalArgumentException("bad latitude '${tokens[0]}'")
        val lon = tokens[1].toDoubleOrNull() ?: throw IllegalArgumentException("bad longitude '${tokens[1]}'")
        require(lat in -90.0..90.0) { "latitude $lat outside -90..90" }
        require(lon in -180.0..180.0) { "longitude $lon outside -180..180" }
        val name = rest.substringAfter(tokens[1], "").trim().ifEmpty { null }
        return Triple(lat, lon, name)
    }

    private fun parseObjectId(text: String): ObjectId {
        val raw = text.trim()
        require(raw.isNotEmpty()) { "usage: delete Pin#3" }
        // Accept `Pin#3` (counter) or `3` (counter, pin assumed).
        val (type, counterText) = if (raw.contains('#')) {
            val typeName = raw.substringBefore('#')
            val type = ObjectType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown object type '$typeName'")
            type to raw.substringAfter('#')
        } else {
            ObjectType.Pin to raw
        }
        val counter = counterText.toIntOrNull() ?: throw IllegalArgumentException("bad counter '$counterText'")
        return ObjectId(type, authorId, counter)
    }

    private fun versionOf(packet: Packet): Hlc? = when (packet) {
        is PointPacket -> packet.version
        is MessagePacket -> packet.version
        is TombstonePacket -> packet.version
        is DigestPacket -> null
    }

    private fun describe(state: RadioTransport.State): String = when (state) {
        RadioTransport.State.Disconnected -> "disconnected"
        RadioTransport.State.Scanning -> "scanning"
        RadioTransport.State.Connecting -> "connecting"
        is RadioTransport.State.Connected -> "connected" + (state.nodeName?.let { " as $it" } ?: "")
        is RadioTransport.State.Error -> "error: ${state.message}"
    }

    private fun describe(node: MeshNodeInfo): String {
        val name = node.user?.longName?.ifEmpty { null } ?: "0x${node.num.toUInt().toString(16)}"
        val short = node.user?.shortName?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        val battery = node.deviceMetrics?.batteryLevel?.let { " battery=${it}%" } ?: ""
        val hops = node.hopsAway?.let { " hops=$it" } ?: ""
        val position = node.position?.takeIf { it.isPlausible }?.let { " at ${describe(it)}" } ?: ""
        return "$name$short snr=${node.snr}$battery$hops$position"
    }

    private fun describe(position: MeshPosition): String =
        String.format(Locale.ROOT, "%.6f, %.6f", position.latitude, position.longitude)

    private fun describe(packet: Packet): String = when (packet) {
        is PointPacket -> {
            val coords = String.format(Locale.ROOT, "%.6f, %.6f", packet.latE7 / 1e7, packet.lonE7 / 1e7)
            val name = packet.name?.let { " \"$it\"" } ?: ""
            val flags = buildList {
                if (packet.flags.emergency) add("emergency")
                if (packet.flags.closed) add("closed")
                if (packet.flags.deleted) add("deleted")
            }.joinToString(",").let { if (it.isEmpty()) "" else " [$it]" }
            "${packet.id.short()}$name @ $coords$flags"
        }

        is MessagePacket -> "message \"${packet.text}\""
        is TombstonePacket -> "tombstone ${packet.targetId.short()}"
        is DigestPacket -> "digest ${packet.filterBytes.size} B"
    }

    private fun describe(event: Event): String = when (event) {
        is Event.ObjectUpserted -> "stored ${event.id.short()}"
        is Event.ObjectDeleted -> "deleted ${event.id.short()}"
        is Event.PendingRefResolved ->
            "pending ref resolved for ${event.waitingId.short()}" + (if (event.orphaned) " (orphaned: route split)" else "")
    }

    /** `Pin#3@7` — always shows the author, so packets from peers are distinct. */
    private fun ObjectId.short(): String = "${type.name}#$counter@${authorId.toInt() and 0xFF}"

    private fun say(message: String) {
        synchronized(consoleLock) { println(message) }
    }
}
