package org.cairn.desktop.link

import org.cairn.meshtastic.MeshtasticLink
import org.cairn.meshtastic.StreamFramer
import org.cairn.meshtastic.StreamFraming
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Meshtastic over TCP (the device's API server, default port 4403).
 *
 * This is the most convenient link on a PC: no permissions, no BlueZ
 * plumbing, and it survives the harness restarting. It works against a node
 * with WiFi enabled, and against `meshtasticd`.
 *
 * Wire format is the same stream framing as USB serial.
 */
class TcpLink(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
) : MeshtasticLink {

    override val description: String = "tcp:$host:$port"

    override var onMessage: (ByteArray) -> Unit = {}
    override var onClosed: (Throwable?) -> Unit = {}

    private val framer = StreamFramer()
    private val writeLock = Any()

    @Volatile
    private var closing = false

    private var socket: Socket? = null
    private var out: OutputStream? = null

    override fun open() {
        closing = false
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        out = s.getOutputStream()
        thread(name = "cairn-tcp-reader", isDaemon = true) { readLoop(s) }
    }

    override fun write(message: ByteArray) {
        val stream = out ?: throw IllegalStateException("$description is not open")
        val framed = StreamFraming.frame(message)
        synchronized(writeLock) {
            stream.write(framed)
            stream.flush()
        }
    }

    override fun close() {
        closing = true
        out = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun readLoop(s: Socket) {
        try {
            val input = s.getInputStream()
            val chunk = ByteArray(4096)
            while (!closing) {
                val n = input.read(chunk)
                if (n < 0) break
                for (message in framer.feed(chunk, 0, n)) onMessage(message)
            }
            if (!closing) onClosed(null)
        } catch (t: Throwable) {
            if (!closing) onClosed(t)
        }
    }
}
