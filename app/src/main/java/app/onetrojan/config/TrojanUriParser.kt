package app.onetrojan.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object TrojanUriParser {
    fun parse(value: String): Result<TrojanConfig> = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme.equals("trojan", ignoreCase = true)) { "只接受 trojan:// 配置" }

        val query = parseQuery(uri.rawQuery)
        require(query["allowInsecure"] != "1" && query["allow_insecure"] != "true") {
            "不允许关闭 TLS 证书验证"
        }
        require(query["type"].isNullOrBlank() || query["type"] == "tcp") {
            "目前只支持原生 Trojan TCP 传输"
        }

        val authorityHost = uri.host ?: error("配置缺少服务器地址")
        val serverIp = if (authorityHost.isIpv4Address()) authorityHost else query["ip"]
        require(serverIp != null && serverIp.isIpv4Address()) {
            "严格模式需要数字服务器 IP；域名配置请增加 ip= 参数"
        }

        val sni = query["sni"]
            ?: query["peer"]
            ?: authorityHost.takeUnless(String::isIpv4Address)
            ?: error("使用 IP 连接时必须提供 sni=证书域名")
        val password = decode(uri.rawUserInfo ?: "")

        TrojanConfig(
            serverIp = serverIp,
            port = uri.port.takeIf { it > 0 } ?: 443,
            sni = sni,
            password = password,
            dnsIp = query["dns"] ?: "1.1.1.1",
            alpn = query["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?: emptyList(),
            tlsProfile = query["tlsProfile"]?.lowercase() ?: "chrome",
        ).also { config ->
            val errors = config.validate()
            require(errors.isEmpty()) { errors.joinToString("; ") }
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) return@mapNotNull null
            decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
        }.toMap()
    }

    private fun decode(value: String): String = URLDecoder.decode(
        value.replace("+", "%2B"),
        StandardCharsets.UTF_8.name(),
    )
}
