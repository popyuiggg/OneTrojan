package app.onetrojan.trojan

import android.net.VpnService
import android.net.Network
import android.util.Log
import app.onetrojan.config.TrojanConfig
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class ProtectedTcpGateway(
    private val vpnService: VpnService,
    private val config: TrojanConfig,
    private val underlyingNetwork: Network,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionSlots = Semaphore(MAX_CONCURRENT_CONNECTS, true)
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "one-trojan-protected-tcp").apply { isDaemon = true }
    }
    private val listener = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(IPV4_LOOPBACK, 0), 64)
    }

    val port: Int = listener.localPort

    init {
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (running.get()) {
            val local = try {
                listener.accept()
            } catch (_: Exception) {
                return
            }
            active += local
            executor.execute { handle(local) }
        }
    }

    private fun handle(local: Socket) {
        val remote = Socket()
        var hasSlot = false
        var phase = "waiting"
        active += remote
        try {
            connectionSlots.acquire()
            hasSlot = true
            phase = "connecting"
            underlyingNetwork.bindSocket(remote)
            check(vpnService.protect(remote)) { "Android refused to protect the Trojan socket" }
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.connect(InetSocketAddress(config.serverIp, config.port), CONNECT_TIMEOUT_MS)
            connectionSlots.release()
            hasSlot = false

            phase = "relaying"
            executor.execute {
                runCatching { copy(local.inputStream, remote.outputStream) }
                runCatching { remote.shutdownOutput() }
            }
            copy(remote.inputStream, local.outputStream)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "Protected TCP gateway failed while $phase: ${error.javaClass.simpleName}: ${error.message}")
            // A single failed upstream connection must not terminate the VPN process.
        } finally {
            if (hasSlot) connectionSlots.release()
            active -= local
            active -= remote
            runCatching { local.close() }
            runCatching { remote.close() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (running.get()) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { listener.close() }
        active.toList().forEach { socket -> runCatching { socket.close() } }
        active.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val COPY_BUFFER_SIZE = 32 * 1024
        private const val MAX_CONCURRENT_CONNECTS = 4
        private const val TAG = "OneTrojanGateway"
    }
}
