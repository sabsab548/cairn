package org.cairn.desktop.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkSpecTest {

    @Test
    fun mock_and_fake_are_aliases() {
        assertEquals(LinkSpec.Mock, LinkSpec.parse("mock"))
        assertEquals(LinkSpec.Mock, LinkSpec.parse("  fake  "))
    }

    @Test
    fun tcp_defaults_to_4403() {
        assertEquals(LinkSpec.Tcp("192.168.0.7", 4403), LinkSpec.parse("tcp:192.168.0.7"))
        assertEquals(LinkSpec.Tcp("localhost", 4403), LinkSpec.parse("tcp:localhost"))
    }

    @Test
    fun tcp_accepts_an_explicit_port() {
        assertEquals(LinkSpec.Tcp("meshtastic.local", 1234), LinkSpec.parse("tcp:meshtastic.local:1234"))
    }

    @Test
    fun tcp_rejects_a_bad_port() {
        assertFailsWith<IllegalArgumentException> { LinkSpec.parse("tcp:host:notaport") }
    }

    @Test
    fun tcp_rejects_a_missing_host() {
        assertFailsWith<IllegalArgumentException> { LinkSpec.parse("tcp::4403") }
    }

    @Test
    fun serial_defaults_to_115200() {
        assertEquals(LinkSpec.Serial("/dev/ttyACM0", 115_200), LinkSpec.parse("serial:/dev/ttyACM0"))
    }

    @Test
    fun serial_accepts_an_explicit_baud() {
        assertEquals(LinkSpec.Serial("/dev/ttyUSB0", 9_600), LinkSpec.parse("serial:/dev/ttyUSB0:9600"))
    }

    @Test
    fun serial_rejects_a_bad_baud() {
        assertFailsWith<IllegalArgumentException> { LinkSpec.parse("serial:/dev/ttyACM0:fast") }
    }

    @Test
    fun serial_rejects_a_missing_device() {
        assertFailsWith<IllegalArgumentException> { LinkSpec.parse("serial:") }
    }

    @Test
    fun unknown_scheme_is_rejected_with_the_expected_syntax() {
        val error = assertFailsWith<IllegalArgumentException> { LinkSpec.parse("bluetooth:AA:BB:CC") }
        assertEquals(
            "unknown link 'bluetooth:AA:BB:CC' (expected tcp:HOST[:PORT], serial:DEVICE[:BAUD] or mock)",
            error.message,
        )
    }

    @Test
    fun description_round_trips() {
        assertEquals("tcp:10.0.0.2:4403", LinkSpec.parse("tcp:10.0.0.2").description)
        assertEquals("serial:/dev/ttyACM1@115200", LinkSpec.parse("serial:/dev/ttyACM1").description)
        assertEquals("mock", LinkSpec.Mock.description)
    }
}
