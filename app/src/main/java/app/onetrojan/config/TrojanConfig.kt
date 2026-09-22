package app.onetrojan.config

data class TrojanConfig(
    val serverIp: String,
    val port: Int,
    val sni: String,
    val password: String,
    val dnsIp: String,
    val alpn: List<String> = emptyList(),
    val tlsProfile: String = "chrome",
) {
    fun validate(): List<String> = buildList {
        if (!serverIp.isIpv4Address()) add("serverIp must be a numeric IPv4 address")
        if (port !in 1..65535) add("port must be between 1 and 65535")
        if (sni.isBlank()) add("sni must not be blank")
        if (password.isBlank()) add("password must not be blank")
        if (!dnsIp.isIpv4Address()) add("dnsIp must be a numeric IPv4 address")
        if (alpn.any { token ->
                token.isBlank() || token.length > 255 || token.any { it.code !in 0x20..0x7e }
            }
        ) {
            add("alpn entries must be non-empty ASCII strings of at most 255 characters")
        }
        if (tlsProfile != "chrome" && tlsProfile != "default") {
            add("tlsProfile must be chrome or default")
        }
    }
}

fun String.isIpv4Address(): Boolean {
    val parts = split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all(Char::isDigit) &&
            part.toIntOrNull() in 0..255 &&
            (part == "0" || !part.startsWith('0'))
    }
}
