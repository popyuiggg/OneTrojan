package app.onetrojan.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import app.onetrojan.MainActivity
import app.onetrojan.R
import app.onetrojan.config.SecureConfigStore
import app.onetrojan.trojan.TrojanEngine
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class TunnelService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var engine: TrojanEngine? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel()
        }
        return Service.START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (tun != null) return

        startAsForeground()

        val established = try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val underlyingNetworks = connectivity.allNetworks.filter { network ->
                connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }.toTypedArray()
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(VIRTUAL_DNS)
                // The VPN's own Trojan control sockets must use the physical network.
                // Package exclusion is more reliable than per-fd protect on some Android 11 ROMs.
                .addDisallowedApplication(packageName)
                .setUnderlyingNetworks(underlyingNetworks.takeIf { it.isNotEmpty() })
                .setBlocking(true)
                .establish()
        } catch (_: Exception) {
            null
        }

        if (established == null) {
            TunnelController.dispatch(
                TunnelEvent.Failed(getString(R.string.vpn_establish_failed)),
            )
            stopSelf()
            return
        }

        tun = established
        running.set(true)
        TunnelController.dispatch(TunnelEvent.TunEstablished)

        val config = SecureConfigStore(this).load()
        if (config == null) {
            TunnelController.dispatch(TunnelEvent.EngineWaiting("尚未导入 Trojan 配置，流量保持阻断"))
            startDrain(established)
            return
        }

        val trojanEngine = TrojanEngine(
            vpnService = this,
            config = config,
            workingDirectory = cacheDir,
            onVerified = {
                TunnelController.dispatch(TunnelEvent.EngineReady)
                showProtectedNotification()
            },
            onWaiting = { reason -> TunnelController.dispatch(TunnelEvent.EngineWaiting(reason)) },
        )
        engine = trojanEngine
        if (!trojanEngine.start(established)) {
            engine = null
            startDrain(established)
        }
    }

    private fun startDrain(descriptor: ParcelFileDescriptor) {
        drainThread = Thread({ drainAndDrop(descriptor) }, "tun-fail-closed").apply { start() }
    }

    private fun drainAndDrop(descriptor: ParcelFileDescriptor) {
        val buffer = ByteArray(TUN_MTU)
        try {
            FileInputStream(descriptor.fileDescriptor).use { input ->
                while (running.get()) {
                    if (input.read(buffer) < 0) break
                    // Until the Trojan engine is attached, deliberately drop every packet.
                }
            }
        } catch (_: IOException) {
            // Closing the descriptor interrupts this blocking read during a normal stop.
        } finally {
            if (running.getAndSet(false)) {
                TunnelController.dispatch(TunnelEvent.Failed("VPN interface closed unexpectedly"))
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        closeTun()
        TunnelController.dispatch(TunnelEvent.Disabled)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTun() {
        running.set(false)
        engine?.stop()
        engine = null
        try {
            tun?.close()
        } catch (_: IOException) {
            // The interface is already closed.
        }
        tun = null
        drainThread?.interrupt()
        drainThread = null
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val notification = buildNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showProtectedNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(true))
    }

    private fun buildNotification(protected: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(
                getString(
                    if (protected) R.string.notification_title_protected
                    else R.string.notification_title_blocking,
                ),
            )
            .setContentText(
                getString(
                    if (protected) R.string.notification_text_protected
                    else R.string.notification_text_blocking,
                ),
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "app.onetrojan.action.START"
        const val ACTION_STOP = "app.onetrojan.action.STOP"

        private const val NOTIFICATION_CHANNEL = "vpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val TUN_MTU = 1500
        private const val TUN_IPV4 = "10.111.0.1"
        private const val VIRTUAL_DNS = "10.111.0.2"
    }
}
