package app.onetrojan.config

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

object DirectDnsResolver {
    fun resolveIpv4(
        hostname: String,
        dnsIp: String,
        bindSocket: (DatagramSocket) -> Unit,
    ): String {
        require(dnsIp.isIpv4Address()) { "DNS 必须是数字 IPv4 地址" }
        val transactionId = SecureRandom().nextInt(65_536)
        val query = buildQuery(hostname, transactionId)
        val response = ByteArray(MAX_RESPONSE_BYTES)

        DatagramSocket().use { socket ->
            bindSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(dnsIp, DNS_PORT))
            socket.send(DatagramPacket(query, query.size))
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            return parseResponse(response.copyOf(packet.length), transactionId)
        }
    }

    private fun buildQuery(hostname: String, transactionId: Int): ByteArray {
        val labels = hostname.trim().trimEnd('.').split('.')
        require(labels.isNotEmpty() && labels.all { it.isNotEmpty() }) { "服务器域名无效" }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeShort(transactionId)
                output.writeShort(0x0100)
                output.writeShort(1)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
                labels.forEach { label ->
                    val encoded = label.toByteArray(Charsets.US_ASCII)
                    require(encoded.size in 1..63) { "服务器域名无效" }
                    output.writeByte(encoded.size)
                    output.write(encoded)
                }
                output.writeByte(0)
                output.writeShort(TYPE_A)
                output.writeShort(CLASS_IN)
            }
            bytes.toByteArray()
        }
    }

    private fun parseResponse(packet: ByteArray, transactionId: Int): String {
        require(packet.size >= DNS_HEADER_BYTES) { "DNS 响应过短" }
        val input = DataInputStream(packet.inputStream())
        require(input.readUnsignedShort() == transactionId) { "DNS 响应编号不匹配" }
        val flags = input.readUnsignedShort()
        require(flags and 0x8000 != 0) { "收到的不是 DNS 响应" }
        require(flags and 0x000f == 0) { "DNS 返回错误码 ${flags and 0x000f}" }
        val questionCount = input.readUnsignedShort()
        val answerCount = input.readUnsignedShort()
        input.skipBytes(4)

        repeat(questionCount) {
            skipName(input)
            require(input.skipBytes(4) == 4) { "DNS 问题段不完整" }
        }
        repeat(answerCount) {
            skipName(input)
            val type = input.readUnsignedShort()
            val recordClass = input.readUnsignedShort()
            input.skipBytes(4)
            val length = input.readUnsignedShort()
            if (type == TYPE_A && recordClass == CLASS_IN && length == 4) {
                val raw = ByteArray(4).also(input::readFully)
                val address = InetAddress.getByAddress(raw) as Inet4Address
                require(address.isPublicServerAddress()) {
                    "DNS 返回了不可用的保留地址 ${address.hostAddress}"
                }
                return address.hostAddress ?: error("DNS IPv4 地址无效")
            }
            require(input.skipBytes(length) == length) { "DNS 记录不完整" }
        }
        error("DNS 响应中没有 IPv4 地址")
    }

    private fun skipName(input: DataInputStream) {
        while (true) {
            val length = input.readUnsignedByte()
            if (length == 0) return
            if (length and 0xc0 == 0xc0) {
                input.readUnsignedByte()
                return
            }
            require(length in 1..63) { "DNS 名称格式无效" }
            require(input.skipBytes(length) == length) { "DNS 名称不完整" }
        }
    }

    private fun Inet4Address.isPublicServerAddress(): Boolean {
        val first = address[0].toInt() and 0xff
        val second = address[1].toInt() and 0xff
        val isBenchmarkOrFakeIp = first == 198 && second in 18..19
        return !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress &&
            !isSiteLocalAddress && !isMulticastAddress && !isBenchmarkOrFakeIp
    }

    private const val DNS_PORT = 53
    private const val DNS_HEADER_BYTES = 12
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_BYTES = 4_096
}
