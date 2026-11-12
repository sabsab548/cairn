package org.cairn.desktop.link

/**
 * What `--link` accepts.
 *
 * Deliberately explicit instead of guessing: a dev harness that silently
 * connects to the wrong thing is worse than one that refuses.
 */
sealed class LinkSpec {

    abstract val description: String

    /** Meshtastic TCP API (port 4403), e.g. a WiFi node or `meshtasticd`. */
    data class Tcp(val host: String, val port: Int) : LinkSpec() {
        override val description: String get() = "tcp:$host:$port"
    }

    /** USB-serial node, e.g. `/dev/ttyACM0` on Linux. */
    data class Serial(val device: String, val baud: Int) : LinkSpec() {
        override val description: String get() = "serial:$device@$baud"
    }

    /** In-process fake node: no hardware, useful for demos and tests. */
    data object Mock : LinkSpec() {
        override val description: String get() = "mock"
    }

    companion object {
        const val DEFAULT_TCP_PORT = 4403
        const val DEFAULT_BAUD = 115_200

        fun parse(spec: String): LinkSpec {
            val s = spec.trim()
            if (s == "mock" || s == "fake") return Mock

            if (s.startsWith("tcp:")) {
                val rest = s.removePrefix("tcp:")
                val host: String
                val port: Int
                val colon = rest.lastIndexOf(':')
                if (colon < 0) {
                    host = rest
                    port = DEFAULT_TCP_PORT
                } else {
                    host = rest.substring(0, colon)
                    port = rest.substring(colon + 1).toIntOrNull()
                        ?: throw IllegalArgumentException("bad port in '$spec'")
                }
                require(host.isNotEmpty()) { "missing host in '$spec'" }
                return Tcp(host, port)
            }

            if (s.startsWith("serial:")) {
                val rest = s.removePrefix("serial:")
                require(rest.isNotEmpty()) { "missing device in '$spec'" }
                val colon = rest.lastIndexOf(':')
                return if (colon < 0) {
                    Serial(rest, DEFAULT_BAUD)
                } else {
                    val baud = rest.substring(colon + 1).toIntOrNull()
                        ?: throw IllegalArgumentException("bad baud rate in '$spec'")
                    Serial(rest.substring(0, colon), baud)
                }
            }

            throw IllegalArgumentException(
                "unknown link '$spec' (expected tcp:HOST[:PORT], serial:DEVICE[:BAUD] or mock)",
            )
        }
    }
}
