package app.onetrojan.trojan

import app.onetrojan.config.isIpv4Address
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

data class SocksAddress(
    val type: Int,
    val address: ByteArray,
    val port: Int,
) {
    val isIpv6: Boolean
        get() = type == TYPE_IPV6

    fun writeTo(output: DataOutputStream) {
        output.writeByte(type)
        if (type == TYPE_DOMAIN) output.writeByte(address.size)
        output.write(address)
        output.writeShort(port)
    }

    fun host(): String = when (type) {
        TYPE_IPV4, TYPE_IPV6 -> InetAddress.getByAddress(address).hostAddress ?: ""
        TYPE_DOMAIN -> address.toString(StandardCharsets.UTF_8)
        else -> error("Unsupported address type: $type")
    }

    override fun equals(other: Any?): Boolean =
        other is SocksAddress && type == other.type && address.contentEquals(other.address) && port == other.port

    override fun hashCode(): Int = 31 * (31 * type + address.contentHashCode()) + port

    companion object {
        const val TYPE_IPV4 = 1
        const val TYPE_DOMAIN = 3
        const val TYPE_IPV6 = 4

        fun readFrom(input: DataInputStream): SocksAddress {
            val type = input.readUnsignedByte()
            val length = when (type) {
                TYPE_IPV4 -> 4
                TYPE_DOMAIN -> input.readUnsignedByte()
                TYPE_IPV6 -> 16
                else -> error("Unsupported SOCKS address type: $type")
            }
            require(length > 0) { "Empty SOCKS address" }
            val address = ByteArray(length).also(input::readFully)
            return SocksAddress(type, address, input.readUnsignedShort())
        }

        fun fromHost(host: String, port: Int): SocksAddress {
            require(port in 0..65535) { "Invalid port" }
            if (host.isIpv4Address()) {
                return SocksAddress(
                    TYPE_IPV4,
                    host.split('.').map(String::toInt).map(Int::toByte).toByteArray(),
                    port,
                )
            }
            if (host.contains(':')) {
                val raw = InetAddress.getByName(host).address
                require(raw.size == 16) { "Invalid numeric IPv6 address" }
                return SocksAddress(TYPE_IPV6, raw, port)
            }
            val raw = host.toByteArray(StandardCharsets.UTF_8)
            require(raw.isNotEmpty() && raw.size <= 255) { "Invalid domain name" }
            return SocksAddress(TYPE_DOMAIN, raw, port)
        }

        fun anyIpv4(port: Int = 0) = SocksAddress(TYPE_IPV4, ByteArray(4), port)
    }
}
