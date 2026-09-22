package app.onetrojan.trojan

import app.onetrojan.config.TrojanConfig
import java.io.DataOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class TrojanTlsTransport(
    private val config: TrojanConfig,
    private val protect: (Socket) -> Boolean,
    private val chromeBridgePort: Int? = null,
) {
    fun open(
        command: Int,
        destination: SocksAddress,
        initialPayload: ByteArray = ByteArray(0),
    ): Socket {
        if (config.tlsProfile == "chrome") {
            return openChromeBridge(command, destination, initialPayload)
        }
        return openPlatformTls(command, destination, initialPayload)
    }

    private fun openChromeBridge(
        command: Int,
        destination: SocksAddress,
        initialPayload: ByteArray,
    ): Socket {
        val bridgePort = checkNotNull(chromeBridgePort) { "Chrome TLS bridge is unavailable" }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(IPV4_LOOPBACK, bridgePort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            DataOutputStream(socket.outputStream).apply {
                write(BRIDGE_MAGIC)
                writeByte(command)
                destination.writeTo(this)
                writeInt(initialPayload.size)
                write(initialPayload)
                flush()
            }
            val input = DataInputStream(socket.inputStream)
            when (input.readUnsignedByte()) {
                0 -> return socket
                1 -> {
                    val length = input.readUnsignedShort()
                    val message = ByteArray(length).also(input::readFully)
                        .toString(StandardCharsets.UTF_8)
                    error(message)
                }
                else -> error("Invalid Chrome TLS bridge response")
            }
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    private fun openPlatformTls(
        command: Int,
        destination: SocksAddress,
        initialPayload: ByteArray,
    ): SSLSocket {
        val rawSocket = Socket()
        try {
            // Android 11 may not allocate the socket's native file descriptor until bind/connect.
            // Bind first so VpnService.protect() can reliably exclude it from its own tunnel.
            rawSocket.bind(InetSocketAddress(0))
            check(protect(rawSocket)) { "Android refused to protect the Trojan socket" }
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.connect(InetSocketAddress(config.serverIp, config.port), CONNECT_TIMEOUT_MS)
        } catch (error: Exception) {
            rawSocket.close()
            throw error
        }

        val sslSocket = try {
            SSLContext.getDefault().socketFactory.createSocket(
                rawSocket,
                config.sni,
                config.port,
                true,
            ) as SSLSocket
        } catch (error: Exception) {
            rawSocket.close()
            throw error
        }

        try {
            sslSocket.soTimeout = IO_TIMEOUT_MS
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(config.sni))
                if (config.alpn.isNotEmpty()) {
                    applicationProtocols = config.alpn.toTypedArray()
                }
            }
            val supported = sslSocket.supportedProtocols.toSet()
            sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                .filter(supported::contains)
                .toTypedArray()
            sslSocket.startHandshake()

            DataOutputStream(sslSocket.outputStream).apply {
                write(trojanPasswordHash(config.password).toByteArray(StandardCharsets.US_ASCII))
                write(CRLF)
                writeByte(command)
                destination.writeTo(this)
                write(CRLF)
                write(initialPayload)
                flush()
            }
            return sslSocket
        } catch (error: Exception) {
            sslSocket.close()
            throw error
        }
    }

    companion object {
        const val COMMAND_CONNECT = 1
        const val COMMAND_UDP_ASSOCIATE = 3
        val CRLF = byteArrayOf(13, 10)

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 30_000
        private val BRIDGE_MAGIC = "OTB1".toByteArray(StandardCharsets.US_ASCII)
        private const val IPV4_LOOPBACK = "127.0.0.1"

        fun trojanPasswordHash(password: String): String =
            MessageDigest.getInstance("SHA-224")
                .digest(password.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
