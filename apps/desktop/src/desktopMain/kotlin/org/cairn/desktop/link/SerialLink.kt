package org.cairn.desktop.link

import org.cairn.meshtastic.MeshtasticLink
import org.cairn.meshtastic.StreamFramer
import org.cairn.meshtastic.StreamFraming
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Meshtastic over USB serial (`/dev/ttyACM0`, `/dev/ttyUSB0`, …).
 *
 * The JVM has no termios API, so the tty is put into raw mode with `stty`
 * before it is opened. Raw mode matters: in canonical mode the kernel buffers
 * input until a newline and mangles the binary stream.
 *
 * Like the reference Python client, opening the link writes a burst of `0xC3`
 * bytes first. That wakes a sleeping device and forces its parser to
 * resynchronise if it was mid-frame when the previous client vanished.
 */
class SerialLink(
    private val device: String,
    private val baud: Int = 115_200,
) : MeshtasticLink {

    override val description: String = "serial:$device@$baud"

    override var onMessage: (ByteArray) -> Unit = {}
    override var onClosed: (Throwable?) -> Unit = {}

    private val framer = StreamFramer()
    private val writeLock = Any()

    @Volatile
    private var closing = false

    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        closing = false
        configureTty()
        val i = FileInputStream(device)
        val o = FileOutputStream(device)
        input = i
        output = o

        // Wake-up burst, as in meshtastic-python's StreamInterface.connect().
        synchronized(writeLock) {
            o.write(ByteArray(32) { StreamFraming.START2.toByte() })
            o.flush()
        }
        Thread.sleep(100)

        thread(name = "cairn-serial-reader", isDaemon = true) { readLoop(i) }
    }

    override fun write(message: ByteArray) {
        val stream = output ?: throw IllegalStateException("$description is not open")
        val framed = StreamFraming.frame(message)
        synchronized(writeLock) {
            stream.write(framed)
            stream.flush()
        }
    }

    override fun close() {
        closing = true
        val i = input
        val o = output
        input = null
        output = null
        runCatching { i?.close() }
        runCatching { o?.close() }
    }

    private fun readLoop(stream: InputStream) {
        try {
            val chunk = ByteArray(4096)
            while (!closing) {
                val n = stream.read(chunk)
                if (n < 0) break
                for (message in framer.feed(chunk, 0, n)) onMessage(message)
            }
            if (!closing) onClosed(null)
        } catch (t: Throwable) {
            if (!closing) onClosed(t)
        }
    }

    /**
     * `stty` flag differs between GNU/Linux (`-F`) and macOS/BSD (`-f`).
     * Both accept `raw -echo <baud>`; the baud rate is mostly ignored by USB
     * CDC-ACM devices but is required for real UART bridges.
     */
    private fun configureTty() {
        val flag = if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) "-f" else "-F"
        val command = listOf("stty", flag, device, "raw", "-echo", baud.toString())
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IllegalStateException(
                "cannot run `stty` to configure $device: ${e.message}. " +
                    "Is the device plugged in and do you have permission to access it?",
                e,
            )
        }
        val outputText = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        if (!finished || process.exitValue() != 0) {
            throw IllegalStateException(
                "`${command.joinToString(" ")}` failed" +
                    (if (outputText.isEmpty()) "" else ": $outputText"),
            )
        }
    }
}
