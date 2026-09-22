package app.onetrojan.config

import org.json.JSONObject

data class TrojanConfigDraft(
    val host: String,
    val serverIp: String,
    val port: String,
    val sni: String,
    val password: String,
    val dnsIp: String,
    val alpn: String,
    val tlsProfile: String,
)

object TrojanJsonImporter {
    fun parse(value: String): Result<TrojanConfigDraft> = runCatching {
        val json = JSONObject(value.trim())
        require(json.optString("type", "Trojan").equals("Trojan", ignoreCase = true)) {
            "JSON 不是 Trojan 配置"
        }
        require(json.optString("obfs").let { it.isBlank() || it.equals("none", true) }) {
            "不支持 obfs"
        }
        require(json.optString("plugin").isBlank()) { "不支持插件传输" }
        require(json.optString("cert").isBlank()) { "暂不支持 JSON 内嵌自定义证书" }

        val host = json.optString("host").trim()
        require(host.isNotEmpty()) { "JSON 缺少 host" }
        val peer = json.optString("peer").trim()
        val ip = json.optString("ip").trim()
        val rawPort = json.opt("port")?.toString()?.trim().orEmpty().ifBlank { "443" }
        require(rawPort.toIntOrNull() in 1..65535) { "端口无效" }

        TrojanConfigDraft(
            host = host,
            serverIp = when {
                ip.isIpv4Address() -> ip
                host.isIpv4Address() -> host
                else -> ""
            },
            port = rawPort,
            sni = peer.ifBlank { host },
            password = json.optString("password"),
            dnsIp = json.optString("dns").trim().takeIf(String::isIpv4Address) ?: "1.1.1.1",
            alpn = json.optString("alpn").trim(),
            tlsProfile = json.optString("tlsProfile", "chrome").trim().lowercase()
                .ifBlank { "chrome" },
        )
    }
}
