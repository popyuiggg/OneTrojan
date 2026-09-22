package app.onetrojan.trojan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrojanProtocolTest {
    @Test
    fun `sha224 password matches known vector`() {
        assertEquals(
            "d63dc919e201d7bc4c825630d2cf25fdc93d4b2f0d46706d29038d01",
            TrojanTlsTransport.trojanPasswordHash("password"),
        )
    }

    @Test
    fun `domain address round trips without dns lookup`() {
        val expected = SocksAddress.fromHost("example.com", 443)
        val bytes = ByteArrayOutputStream().also { output ->
            expected.writeTo(DataOutputStream(output))
        }.toByteArray()

        val actual = SocksAddress.readFrom(DataInputStream(ByteArrayInputStream(bytes)))

        assertEquals(expected, actual)
        assertEquals("example.com", actual.host())
        assertEquals(443, actual.port)
    }

    @Test
    fun `ipv6 destinations are identifiable for hard rejection`() {
        assertTrue(SocksAddress.fromHost("2001:db8::1", 443).isIpv6)
        assertFalse(SocksAddress.fromHost("192.0.2.1", 443).isIpv6)
        assertFalse(SocksAddress.fromHost("example.com", 443).isIpv6)
    }
}
