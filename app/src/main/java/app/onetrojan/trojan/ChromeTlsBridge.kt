package app.onetrojan.trojan

import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.onetrojan.config.TrojanConfig
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import utlsbridge.Utlsbridge

class ChromeTlsBridge(
    vpnService: VpnService,
    config: TrojanConfig,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
    private val underlyingNetwork = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let { capabilities ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    } ?: error("No underlying non-VPN network is available")
    private val protectedGateway = ProtectedTcpGateway(vpnService, config, underlyingNetwork)

    val port: Int = Utlsbridge.start(
        protectedGateway.port.toLong(),
        config.sni,
        config.password,
        config.alpn.joinToString(","),
    ).toInt()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Utlsbridge.stop()
            protectedGateway.close()
        }
    }
}
