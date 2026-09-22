package app.onetrojan.trojan

import android.net.VpnService
import android.os.ParcelFileDescriptor
import app.onetrojan.config.TrojanConfig
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TrojanEngine(
    private val vpnService: VpnService,
    private val config: TrojanConfig,
    private val workingDirectory: File,
    private val onVerified: () -> Unit,
    private val onWaiting: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socksServer: TrojanSocksServer? = null
    private var chromeTlsBridge: ChromeTlsBridge? = null
    private val nativeStarted = AtomicBoolean(false)
    private var probeThread: Thread? = null

    fun start(tun: ParcelFileDescriptor): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            val bridgePort = if (config.tlsProfile == "chrome") {
                ChromeTlsBridge(vpnService, config).also { chromeTlsBridge = it }.port
            } else {
                null
            }
            val server = TrojanSocksServer(config, vpnService::protect, bridgePort)
                .also { socksServer = it }
            server.start()
            val nativeConfig = File(workingDirectory, "hev-socks5-tunnel.yml")
            nativeConfig.writeText(hevConfig(server.port))
            startProbeLoop(tun, nativeConfig)
            true
        } catch (error: Exception) {
            onWaiting(error.message ?: "Trojan engine failed to start")
            stop()
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        if (nativeStarted.getAndSet(false)) {
            runCatching { TProxyService.TProxyStopService() }
        }
        socksServer?.close()
        socksServer = null
        chromeTlsBridge?.close()
        chromeTlsBridge = null
        probeThread?.interrupt()
        probeThread = null
    }

    private fun startProbeLoop(tun: ParcelFileDescriptor, nativeConfig: File) {
        probeThread = Thread({
            val probe = TrojanProbe(config, vpnService::protect, chromeTlsBridge?.port)
            while (running.get()) {
                val verification = probe.verify()
                if (verification.isSuccess) {
                    if (!running.get()) return@Thread
                    if (TProxyService.TProxyStartService(nativeConfig.absolutePath, tun.fd)) {
                        nativeStarted.set(true)
                        if (!running.get()) {
                            if (nativeStarted.getAndSet(false)) TProxyService.TProxyStopService()
                            return@Thread
                        }
                        onVerified()
                        return@Thread
                    }
                    if (running.get()) onWaiting("TUN 转发引擎启动失败；正在重试")
                } else if (running.get()) {
                    val error = verification.exceptionOrNull()
                    val reason = error?.message?.takeIf(String::isNotBlank)
                        ?: error?.javaClass?.simpleName
                        ?: "未知错误"
                    onWaiting("Trojan 验证失败：$reason；正在重试")
                }
                try {
                    Thread.sleep(PROBE_RETRY_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "trojan-connectivity-probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun hevConfig(socksPort: Int): String = """
        tunnel:
          mtu: 1500
          multi-queue: false
          ipv4: 10.111.0.1
          icmp: 'off'
        socks5:
          address: 127.0.0.1
          port: $socksPort
          udp: 'udp'
        misc:
          connect-timeout: 15000
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-file: null
          log-level: warn
        """.trimIndent()

    companion object {
        private const val PROBE_RETRY_MS = 10_000L
    }
}
