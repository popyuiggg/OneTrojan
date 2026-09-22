package app.onetrojan.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.onetrojan.MainActivity
import app.onetrojan.R
import app.onetrojan.config.SecureConfigStore
import app.onetrojan.tunnel.TunnelController
import app.onetrojan.tunnel.TunnelEvent
import app.onetrojan.tunnel.TunnelPhase
import app.onetrojan.tunnel.TunnelService
import app.onetrojan.tunnel.TunnelSnapshot

class QuickToggleService : TileService() {
    private val stateListener: (TunnelSnapshot) -> Unit = { snapshot ->
        updateTile(snapshot)
    }

    override fun onStartListening() {
        super.onStartListening()
        TunnelController.addListener(stateListener)
    }

    override fun onStopListening() {
        TunnelController.removeListener(stateListener)
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val phase = TunnelController.snapshot.phase
            if (phase in ACTIVE_PHASES) {
                startService(
                    Intent(this, TunnelService::class.java)
                        .setAction(TunnelService.ACTION_STOP),
                )
                TunnelController.dispatch(TunnelEvent.Disabled)
                return@unlockAndRun
            }

            if (VpnService.prepare(this) != null || SecureConfigStore(this).load() == null) {
                val openApp = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_REQUEST_VPN_PERMISSION, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        openApp,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(openApp)
                }
                return@unlockAndRun
            }

            TunnelController.dispatch(TunnelEvent.EnableRequested)
            TunnelController.dispatch(TunnelEvent.PermissionGranted)
            startForegroundService(
                Intent(this, TunnelService::class.java)
                    .setAction(TunnelService.ACTION_START),
            )
        }
    }

    private fun updateTile(snapshot: TunnelSnapshot) {
        val tile = qsTile ?: return
        tile.state = when (snapshot.phase) {
            TunnelPhase.OFF, TunnelPhase.ERROR -> Tile.STATE_INACTIVE
            TunnelPhase.REQUESTING_PERMISSION -> Tile.STATE_UNAVAILABLE
            TunnelPhase.STARTING, TunnelPhase.BLOCKING, TunnelPhase.PROTECTED -> Tile.STATE_ACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = when (snapshot.phase) {
            TunnelPhase.OFF -> getString(R.string.state_off)
            TunnelPhase.REQUESTING_PERMISSION -> getString(R.string.state_requesting)
            TunnelPhase.STARTING -> getString(R.string.state_starting)
            TunnelPhase.BLOCKING -> getString(R.string.state_blocking)
            TunnelPhase.PROTECTED -> getString(R.string.state_protected)
            TunnelPhase.ERROR -> snapshot.detail ?: getString(R.string.state_error)
        }
        tile.updateTile()
    }

    companion object {
        private val ACTIVE_PHASES = setOf(
            TunnelPhase.REQUESTING_PERMISSION,
            TunnelPhase.STARTING,
            TunnelPhase.BLOCKING,
            TunnelPhase.PROTECTED,
        )
    }
}
