package app.onetrojan.tunnel

enum class TunnelPhase {
    OFF,
    REQUESTING_PERMISSION,
    STARTING,
    BLOCKING,
    PROTECTED,
    ERROR,
}

sealed interface TunnelEvent {
    data object EnableRequested : TunnelEvent
    data object PermissionGranted : TunnelEvent
    data object TunEstablished : TunnelEvent
    data class EngineWaiting(val reason: String) : TunnelEvent
    data object EngineReady : TunnelEvent
    data class Failed(val reason: String) : TunnelEvent
    data object Disabled : TunnelEvent
}

data class TunnelSnapshot(
    val phase: TunnelPhase,
    val detail: String? = null,
)

class TunnelStateMachine {
    var snapshot: TunnelSnapshot = TunnelSnapshot(TunnelPhase.OFF)
        private set

    fun dispatch(event: TunnelEvent): TunnelSnapshot {
        snapshot = when (event) {
            TunnelEvent.EnableRequested -> TunnelSnapshot(TunnelPhase.REQUESTING_PERMISSION)
            TunnelEvent.PermissionGranted -> TunnelSnapshot(TunnelPhase.STARTING)
            TunnelEvent.TunEstablished -> TunnelSnapshot(TunnelPhase.BLOCKING)
            is TunnelEvent.EngineWaiting -> TunnelSnapshot(TunnelPhase.BLOCKING, event.reason)
            TunnelEvent.EngineReady -> {
                if (snapshot.phase == TunnelPhase.BLOCKING) {
                    TunnelSnapshot(TunnelPhase.PROTECTED)
                } else {
                    snapshot
                }
            }
            is TunnelEvent.Failed -> TunnelSnapshot(TunnelPhase.ERROR, event.reason)
            TunnelEvent.Disabled -> TunnelSnapshot(TunnelPhase.OFF)
        }
        return snapshot
    }
}
