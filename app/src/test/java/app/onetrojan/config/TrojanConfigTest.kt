package app.onetrojan.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrojanConfigTest {
    @Test
    fun `strict configuration accepts numeric endpoints`() {
        val config = TrojanConfig(
            serverIp = "203.0.113.9",
            port = 443,
            sni = "example.com",
            password = "secret",
            dnsIp = "1.1.1.1",
        )

        assertTrue(config.validate().isEmpty())
    }

    @Test
    fun `hostname is rejected as bootstrap endpoint`() {
        val config = TrojanConfig(
            serverIp = "example.com",
            port = 443,
            sni = "example.com",
            password = "secret",
            dnsIp = "1.1.1.1",
        )

        assertEquals(
            listOf("serverIp must be a numeric IPv4 address"),
            config.validate(),
        )
    }

    @Test
    fun `strict trojan uri carries separate bootstrap ip and sni`() {
        val config = TrojanUriParser.parse(
            "trojan://p%40ss@example.com:443?ip=203.0.113.9&sni=example.com&dns=1.1.1.1",
        ).getOrThrow()

        assertEquals("203.0.113.9", config.serverIp)
        assertEquals("example.com", config.sni)
        assertEquals("p@ss", config.password)
    }

    @Test
    fun `trojan uri preserves alpn preferences`() {
        val config = TrojanUriParser.parse(
            "trojan://secret@example.com:443?ip=203.0.113.9&alpn=h2,http%2F1.1",
        ).getOrThrow()

        assertEquals(listOf("h2", "http/1.1"), config.alpn)
    }

    @Test
    fun `domain-only trojan uri is rejected to avoid bootstrap dns leak`() {
        val result = TrojanUriParser.parse("trojan://secret@example.com:443")

        assertTrue(result.isFailure)
    }

    @Test
    fun `unsupported tls fingerprint is rejected`() {
        val config = TrojanConfig(
            serverIp = "203.0.113.9",
            port = 443,
            sni = "example.com",
            password = "secret",
            dnsIp = "1.1.1.1",
            tlsProfile = "unknown",
        )

        assertEquals(listOf("tlsProfile must be chrome or default"), config.validate())
    }

    @Test
    fun `json text maps supported fields and ignores subscription data`() {
        val draft = TrojanJsonImporter.parse(
            """{
                "type":"Trojan",
                "host":"lax.example.test",
                "ip":"",
                "port":"443",
                "peer":"lax.example.test",
                "password":"secret",
                "dns":"",
                "alpn":"h2,http/1.1",
                "tlsProfile":"chrome",
                "data":"https://example.invalid/subscription"
            }""".trimIndent(),
        ).getOrThrow()

        assertEquals("lax.example.test", draft.host)
        assertEquals("", draft.serverIp)
        assertEquals("443", draft.port)
        assertEquals("lax.example.test", draft.sni)
        assertEquals("secret", draft.password)
        assertEquals("1.1.1.1", draft.dnsIp)
        assertEquals("h2,http/1.1", draft.alpn)
        assertEquals("chrome", draft.tlsProfile)
    }
}
