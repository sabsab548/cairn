package org.cairn.desktop.link

import org.cairn.meshtastic.FromRadio
import org.cairn.meshtastic.MeshProto
import org.cairn.meshtastic.StreamFramer
import org.cairn.meshtastic.StreamFraming
import org.cairn.meshtastic.ToRadio
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [TcpLink] against an in-process server that speaks the Meshtastic
 * stream framing, i.e. without a radio.
 */
class TcpLinkTest {

    @Test
    fun exchanges_framed_messages_with_a_server() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val rawFromClient = ByteArrayOutputStream()
        val clientMessage = LinkedBlockingQueue<ByteArray>()

        thread(name = "fake-node", isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val framer = StreamFramer()
                val buffer = ByteArray(512)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(rawFromClient) { rawFromClient.write(buffer, 0, n) }
                    val messages = framer.feed(buffer, 0, n)
                    if (messages.isEmpty()) continue
                    clientMessage.add(messages.first())

                    // Reply the way a node answers want_config.
                    val reply = MeshProto.encodeFromRadio(FromRadio.ConfigComplete(0x0CA1))
                    output.write(StreamFraming.frame(reply))
                    output.flush()
                    break
                }
            }
        }

        val link = TcpLink("127.0.0.1", server.localPort, connectTimeoutMs = 2_000)
        val reply = CompletableFuture<ByteArray>()
        link.onMessage = { reply.complete(it) }

        try {
            link.open()
            link.write(MeshProto.encodeToRadio(ToRadio.WantConfig(0x0CA1)))

            val received = clientMessage.poll(2, TimeUnit.SECONDS)
            assertTrue(received != null, "server did not receive a complete frame")
            assertEquals(ToRadio.WantConfig(0x0CA1), MeshProto.decodeToRadio(received!!))

            val answer = reply.get(2, TimeUnit.SECONDS)
            assertEquals(FromRadio.ConfigComplete(0x0CA1), MeshProto.decodeFromRadio(answer))

            // The link must have written the stream header, not a bare protobuf.
            val raw = synchronized(rawFromClient) { rawFromClient.toByteArray() }
            assertTrue(raw.size >= StreamFraming.HEADER_LEN, "nothing was written")
            assertContentEquals(
                byteArrayOf(StreamFraming.START1.toByte(), StreamFraming.START2.toByte()),
                raw.copyOfRange(0, 2),
            )
            val declared = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            assertEquals(raw.size - StreamFraming.HEADER_LEN, declared)
        } finally {
            link.close()
            server.close()
        }
    }

    @Test
    fun close_is_idempotent_and_write_after_close_fails_cleanly() {
        val link = TcpLink("127.0.0.1", 1, connectTimeoutMs = 100)
        link.close()
        link.close()
        assertTrue(runCatching { link.write(byteArrayOf(1)) }.isFailure)
    }
}
