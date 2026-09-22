package app.onetrojan.tunnel

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object TunnelController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val machine = TunnelStateMachine()
    private val listeners = CopyOnWriteArraySet<(TunnelSnapshot) -> Unit>()

    val snapshot: TunnelSnapshot
        @Synchronized get() = machine.snapshot

    @Synchronized
    fun dispatch(event: TunnelEvent) {
        val next = machine.dispatch(event)
        mainHandler.post {
            listeners.forEach { listener -> listener(next) }
        }
    }

    fun addListener(listener: (TunnelSnapshot) -> Unit) {
        listeners += listener
        mainHandler.post { listener(snapshot) }
    }

    fun removeListener(listener: (TunnelSnapshot) -> Unit) {
        listeners -= listener
    }
}
