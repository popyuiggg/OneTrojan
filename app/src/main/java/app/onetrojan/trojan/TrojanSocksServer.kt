package app.onetrojan.trojan

import android.util.Log
import app.onetrojan.config.TrojanConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TrojanSocksServer(
    private val config: TrojanConfig,
    protect: (Socket) -> Boolean,
    chromeBridgePort: Int? = null,
) : Closeable {
    private val transport = TrojanTlsTransport(config, protect, chromeBridgePort)
    private val running = AtomicBoolean(false)
    private val active = ConcurrentHashMap.newKeySet<Closeable>()
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "one-trojan-relay").apply { isDaemon = true }
    }
    private var listener: ServerSocket? = null

    val port: Int
        get() = listener?.localPort ?: error("SOCKS server is not running")

    fun start() {
        check(running.compareAndSet(false, true)) { "SOCKS server already running" }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(IPV4_LOOPBACK, 0), 64)
        }
        listener = server
        active += server
        executor.execute { acceptLoop(server) }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        active.toList().forEach { closeable -> runCatching { closeable.close() } }
        active.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                active += client
                executor.execute { handleClient(client) }
            } catch (_: Exception) {
                if (running.get()) continue
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            negotiate(input, output)

            require(input.readUnsignedByte() == SOCKS_VERSION) { "Invalid SOCKS request version" }
            val command = input.readUnsignedByte()
            require(input.readUnsignedByte() == 0) { "Invalid SOCKS reserved byte" }
            val destination = SocksAddress.readFrom(input)
            client.soTimeout = 0

            when (command) {
                TrojanTlsTransport.COMMAND_CONNECT -> handleConnect(client, output, destination)
                TrojanTlsTransport.COMMAND_UDP_ASSOCIATE -> handleUdp(client, output, destination)
                else -> sendReply(output, REPLY_COMMAND_NOT_SUPPORTED, SocksAddress.anyIpv4())
            }
        } catch (error: Exception) {
            Log.w(TAG, "SOCKS session failed: ${error.javaClass.simpleName}: ${error.message}")
            runCatching {
                sendReply(DataOutputStream(client.getOutputStream()), REPLY_GENERAL_FAILURE, SocksAddress.anyIpv4())
            }
        } finally {
            active -= client
            runCatching { client.close() }
        }
    }

    private fun negotiate(input: DataInputStream, output: DataOutputStream) {
        require(input.readUnsignedByte() == SOCKS_VERSION) { "Invalid SOCKS greeting" }
        val methodCount = input.readUnsignedByte()
        val methods = ByteArray(methodCount).also(input::readFully)
        if (methods.none { it.toInt() == AUTH_NONE }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_UNACCEPTABLE.toByte()))
            output.flush()
            error("SOCKS client does not support no-auth mode")
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NONE.toByte()))
        output.flush()
    }

    private fun handleConnect(client: Socket, output: DataOutputStream, destination: SocksAddress) {
        require(!destination.isIpv6) { "IPv6 is disabled" }
        val upstream = transport.open(TrojanTlsTransport.COMMAND_CONNECT, rewriteVirtualDns(destination))
        active += upstream
        try {
            sendReply(output, REPLY_SUCCEEDED, SocksAddress.anyIpv4())
            relayBidirectionally(client, upstream)
        } finally {
            active -= upstream
            runCatching { upstream.close() }
        }
    }

    private fun handleUdp(client: Socket, output: DataOutputStream, @Suppress("UNUSED_PARAMETER") requested: SocksAddress) {
        val udpSocket = DatagramSocket(InetSocketAddress(IPV4_LOOPBACK, 0))
        val upstream = transport.open(
            TrojanTlsTransport.COMMAND_UDP_ASSOCIATE,
            SocksAddress.anyIpv4(),
        )
        active += udpSocket
        active += upstream
        try {
            val bound = SocksAddress.fromHost(udpSocket.localAddress.hostAddress ?: "127.0.0.1", udpSocket.localPort)
            sendReply(output, REPLY_SUCCEEDED, bound)
            val clientAddress = AtomicReference<SocketAddress?>()

            executor.execute {
                runCatching { relayUdpToTrojan(udpSocket, upstream, clientAddress) }
                    .onFailure { error ->
                        Log.w(TAG, "UDP client relay failed: ${error.javaClass.simpleName}: ${error.message}")
                        runCatching { client.close() }
                    }
            }
            executor.execute {
                runCatching { relayTrojanToUdp(upstream, udpSocket, clientAddress) }
                    .onFailure { error ->
                        Log.w(TAG, "UDP server relay failed: ${error.javaClass.simpleName}: ${error.message}")
                        runCatching { client.close() }
                    }
            }

            while (client.getInputStream().read() >= 0) {
                // The SOCKS TCP control connection owns the UDP association lifetime.
            }
        } finally {
            active -= udpSocket
            active -= upstream
            udpSocket.close()
            upstream.close()
        }
    }

    private fun relayUdpToTrojan(
        udpSocket: DatagramSocket,
        upstream: Socket,
        clientAddress: AtomicReference<SocketAddress?>,
    ) {
        val packetBuffer = ByteArray(MAX_UDP_PACKET)
        val output = DataOutputStream(upstream.outputStream)
        while (running.get()) {
            val packet = DatagramPacket(packetBuffer, packetBuffer.size)
            udpSocket.receive(packet)
            clientAddress.compareAndSet(null, packet.socketAddress)

            val input = DataInputStream(ByteArrayInputStream(packet.data, packet.offset, packet.length))
            require(input.readUnsignedShort() == 0) { "Invalid SOCKS UDP reserved field" }
            require(input.readUnsignedByte() == 0) { "Fragmented SOCKS UDP is unsupported" }
            val requestedDestination = SocksAddress.readFrom(input)
            require(!requestedDestination.isIpv6) { "IPv6 is disabled" }
            val payload = ByteArray(input.available()).also(input::readFully)

            if (isDnsDestination(requestedDestination)) {
                val response = queryDnsOverTcp(payload)
                sendSocksUdpPacket(
                    udpSocket,
                    packet.socketAddress,
                    requestedDestination,
                    response,
                )
                continue
            }
            val destination = rewriteVirtualDns(requestedDestination)
            require(!destination.isIpv6) { "IPv6 is disabled" }

            synchronized(output) {
                destination.writeTo(output)
                output.writeShort(payload.size)
                output.write(TrojanTlsTransport.CRLF)
                output.write(payload)
                output.flush()
            }
        }
    }

    private fun relayTrojanToUdp(
        upstream: Socket,
        udpSocket: DatagramSocket,
        clientAddress: AtomicReference<SocketAddress?>,
    ) {
        val input = DataInputStream(upstream.inputStream)
        while (running.get()) {
            val source = SocksAddress.readFrom(input)
            val payloadLength = input.readUnsignedShort()
            require(input.readUnsignedByte() == 13 && input.readUnsignedByte() == 10) {
                "Invalid Trojan UDP frame"
            }
            val payload = ByteArray(payloadLength).also(input::readFully)
            val destination = clientAddress.get() ?: continue

            sendSocksUdpPacket(udpSocket, destination, source, payload)
        }
    }

    private fun queryDnsOverTcp(query: ByteArray): ByteArray {
        require(query.size in 12..4096) { "Invalid DNS query length" }
        val framedQuery = ByteArrayOutputStream(query.size + 2).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeShort(query.size)
                output.write(query)
            }
            bytes.toByteArray()
        }
        return transport.open(
            TrojanTlsTransport.COMMAND_CONNECT,
            SocksAddress.fromHost(config.dnsIp, DNS_PORT),
            framedQuery,
        ).use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MS
            val input = DataInputStream(socket.inputStream)
            val length = input.readUnsignedShort()
            require(length in 12..4096) { "Invalid TCP DNS response length" }
            ByteArray(length).also(input::readFully)
        }
    }

    private fun sendSocksUdpPacket(
        udpSocket: DatagramSocket,
        destination: SocketAddress,
        source: SocksAddress,
        payload: ByteArray,
    ) {
        val packetBytes = ByteArrayOutputStream(payload.size + 32).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeShort(0)
                output.writeByte(0)
                source.writeTo(output)
                output.write(payload)
            }
            bytes.toByteArray()
        }
        udpSocket.send(DatagramPacket(packetBytes, packetBytes.size, destination))
    }

    private fun isDnsDestination(destination: SocksAddress): Boolean =
        destination.type == SocksAddress.TYPE_IPV4 && destination.port == DNS_PORT &&
            destination.host() in setOf(VIRTUAL_DNS, config.dnsIp)

    private fun relayBidirectionally(client: Socket, upstream: Socket) {
        val clientToUpstreamDone = AtomicBoolean(false)
        executor.execute {
            runCatching { copy(client.inputStream, upstream.outputStream) }
            runCatching { upstream.shutdownOutput() }
            clientToUpstreamDone.set(true)
        }
        try {
            copy(upstream.inputStream, client.outputStream)
        } finally {
            runCatching { client.shutdownOutput() }
            if (!clientToUpstreamDone.get()) runCatching { client.shutdownInput() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (running.get()) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun sendReply(output: DataOutputStream, reply: Int, address: SocksAddress) {
        output.writeByte(SOCKS_VERSION)
        output.writeByte(reply)
        output.writeByte(0)
        address.writeTo(output)
        output.flush()
    }

    private fun rewriteVirtualDns(destination: SocksAddress): SocksAddress =
        if (destination.type == SocksAddress.TYPE_IPV4 &&
            destination.host() == VIRTUAL_DNS && destination.port == DNS_PORT
        ) {
            SocksAddress.fromHost(config.dnsIp, DNS_PORT)
        } else {
            destination
        }

    companion object {
        private const val SOCKS_VERSION = 5
        private const val AUTH_NONE = 0
        private const val AUTH_UNACCEPTABLE = 255
        private const val REPLY_SUCCEEDED = 0
        private const val REPLY_GENERAL_FAILURE = 1
        private const val REPLY_COMMAND_NOT_SUPPORTED = 7
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val COPY_BUFFER_SIZE = 32 * 1024
        private const val MAX_UDP_PACKET = 65_535
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val TAG = "OneTrojanSocks"
        private const val VIRTUAL_DNS = "10.111.0.2"
        private const val DNS_PORT = 53
        private const val DNS_TIMEOUT_MS = 10_000
    }
}
