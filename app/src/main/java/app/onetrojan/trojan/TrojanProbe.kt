package app.onetrojan.trojan

import app.onetrojan.config.TrojanConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class TrojanProbe(
    private val config: TrojanConfig,
    private val protect: (Socket) -> Boolean,
    private val chromeBridgePort: Int? = null,
) {
    fun verify(): Result<Unit> = runCatching {
        verifyTcpRelay()
        verifyTcpDns()
        verifyUdpDns()
    }

    private fun verifyTcpRelay() {
        runCatching {
            val outer = TrojanTlsTransport(config, protect, chromeBridgePort).open(
                TrojanTlsTransport.COMMAND_CONNECT,
                SocksAddress.fromHost(PROBE_HTTPS_HOST, PROBE_HTTPS_PORT),
            )
            outer.use {
                val inner = SSLContext.getDefault().socketFactory.createSocket(
                    outer,
                    PROBE_HTTPS_HOST,
                    PROBE_HTTPS_PORT,
                    false,
                ) as SSLSocket
                inner.use { socket ->
                    socket.soTimeout = PROBE_TIMEOUT_MS
                    socket.sslParameters = socket.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        serverNames = listOf(SNIHostName(PROBE_HTTPS_HOST))
                    }
                    socket.startHandshake()
                }
            }
        }.getOrElse { error ->
            throw IllegalStateException(
                "Trojan 认证或 TCP 转发失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private fun verifyTcpDns() {
        runCatching {
            val transactionId = SecureRandom().nextInt(65_536)
            val query = dnsQuery(transactionId)
            val framedQuery = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeShort(query.size)
                    output.write(query)
                }
                bytes.toByteArray()
            }
            TrojanTlsTransport(config, protect, chromeBridgePort).open(
                TrojanTlsTransport.COMMAND_CONNECT,
                SocksAddress.fromHost(config.dnsIp, 53),
                framedQuery,
            ).use { socket ->
            socket.soTimeout = PROBE_TIMEOUT_MS
            val input = DataInputStream(socket.inputStream)
            val responseLength = input.readUnsignedShort()
            require(responseLength in 12..4096) { "Invalid DNS response length" }
            val response = ByteArray(responseLength).also(input::readFully)
            val responseId = ((response[0].toInt() and 0xff) shl 8) or
                (response[1].toInt() and 0xff)
            val isResponse = response[2].toInt() and 0x80 != 0
            require(responseId == transactionId) { "代理内 DNS 响应编号不匹配" }
            require(isResponse) { "代理内 DNS 返回了无效响应" }
            }
        }.getOrElse { error ->
            throw IllegalStateException(
                "Trojan 已连接，但代理内 DNS 失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private fun verifyUdpDns() {
        runCatching {
            val transactionId = SecureRandom().nextInt(65_536)
            val query = dnsQuery(transactionId)
            TrojanTlsTransport(config, protect, chromeBridgePort).open(
                TrojanTlsTransport.COMMAND_UDP_ASSOCIATE,
                SocksAddress.anyIpv4(),
            ).use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MS
                DataOutputStream(socket.outputStream).apply {
                    SocksAddress.fromHost(config.dnsIp, DNS_PORT).writeTo(this)
                    writeShort(query.size)
                    write(TrojanTlsTransport.CRLF)
                    write(query)
                    flush()
                }
                val input = DataInputStream(socket.inputStream)
                SocksAddress.readFrom(input)
                val responseLength = input.readUnsignedShort()
                require(input.readUnsignedByte() == 13 && input.readUnsignedByte() == 10) {
                    "Invalid Trojan UDP response frame"
                }
                require(responseLength in 12..4096) { "Invalid UDP DNS response length" }
                val response = ByteArray(responseLength).also(input::readFully)
                val responseId = ((response[0].toInt() and 0xff) shl 8) or
                    (response[1].toInt() and 0xff)
                require(responseId == transactionId) { "代理内 UDP DNS 响应编号不匹配" }
                require(response[2].toInt() and 0x80 != 0) { "代理内 UDP DNS 返回了无效响应" }
            }
        }.getOrElse { error ->
            throw IllegalStateException(
                "Trojan 已连接，但代理内 UDP DNS 失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private fun dnsQuery(transactionId: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeShort(transactionId)
            output.writeShort(0x0100)
            output.writeShort(1)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(0)
            for (label in "example.com".split('.')) {
                val encoded = label.encodeToByteArray()
                output.writeByte(encoded.size)
                output.write(encoded)
            }
            output.writeByte(0)
            output.writeShort(1)
            output.writeShort(1)
        }
        bytes.toByteArray()
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 10_000
        private const val PROBE_HTTPS_HOST = "example.com"
        private const val PROBE_HTTPS_PORT = 443
        private const val DNS_PORT = 53
    }
}
