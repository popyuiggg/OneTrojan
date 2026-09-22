package app.onetrojan

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import app.onetrojan.config.SecureConfigStore
import app.onetrojan.config.TrojanUriParser
import app.onetrojan.tunnel.TunnelController
import app.onetrojan.tunnel.TunnelEvent
import app.onetrojan.tunnel.TunnelPhase
import app.onetrojan.tunnel.TunnelService
import app.onetrojan.tunnel.TunnelSnapshot

class MainActivity : Activity() {
    private lateinit var toggle: Switch
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private var rendering = false

    private val stateListener: (TunnelSnapshot) -> Unit = { snapshot ->
        render(snapshot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        TunnelController.addListener(stateListener)
        handleConfigIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_VPN_PERMISSION, false)) {
            requestEnable()
            intent.removeExtra(EXTRA_REQUEST_VPN_PERMISSION)
        }
    }

    override fun onDestroy() {
        TunnelController.removeListener(stateListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConfigIntent(intent)
    }

    @Deprecated("VpnService permission still uses an activity result Intent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PERMISSION_REQUEST) return

        if (resultCode == RESULT_OK) {
            startTunnelService()
        } else {
            TunnelController.dispatch(
                TunnelEvent.Failed(getString(R.string.vpn_permission_denied)),
            )
        }
    }

    private fun buildContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(36))
            setBackgroundColor(resolveBackgroundColor())

            addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 34f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            addView(stateLabel().also { stateText = it }, marginTop(dp(20)))
            addView(detailLabel().also { detailText = it }, marginTop(dp(10)))

            addView(Space(this@MainActivity), marginTop(dp(40)))

            addView(Switch(this@MainActivity).apply {
                toggle = this
                text = getString(R.string.switch_label)
                textSize = 20f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnCheckedChangeListener { _, checked ->
                    if (rendering) return@setOnCheckedChangeListener
                    if (checked) requestEnable() else requestDisable()
                }
            })

            addView(Button(this@MainActivity).apply {
                text = getString(R.string.config_open)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                }
            }, marginTop(dp(18)))

            addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
        }
    }

    private fun stateLabel() = TextView(this).apply {
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun detailLabel() = TextView(this).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        alpha = 0.72f
        maxWidth = (resources.displayMetrics.density * 320).toInt()
    }

    private fun marginTop(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = top
    }

    private fun requestEnable() {
        if (SecureConfigStore(this).load() == null) {
            Toast.makeText(this, R.string.config_missing, Toast.LENGTH_LONG).show()
            TunnelController.dispatch(TunnelEvent.Disabled)
            return
        }
        TunnelController.dispatch(TunnelEvent.EnableRequested)
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startTunnelService()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST)
        }
    }

    private fun startTunnelService() {
        TunnelController.dispatch(TunnelEvent.PermissionGranted)
        startForegroundService(
            Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_START),
        )
    }

    private fun requestDisable() {
        startService(
            Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP),
        )
        TunnelController.dispatch(TunnelEvent.Disabled)
    }

    private fun render(snapshot: TunnelSnapshot) {
        rendering = true
        try {
            toggle.isChecked = snapshot.phase !in setOf(TunnelPhase.OFF, TunnelPhase.ERROR)
            stateText.text = when (snapshot.phase) {
                TunnelPhase.OFF -> getString(R.string.state_off)
                TunnelPhase.REQUESTING_PERMISSION -> getString(R.string.state_requesting)
                TunnelPhase.STARTING -> getString(R.string.state_starting)
                TunnelPhase.BLOCKING -> getString(R.string.state_blocking)
                TunnelPhase.PROTECTED -> getString(R.string.state_protected)
                TunnelPhase.ERROR -> getString(R.string.state_error)
            }
            detailText.text = snapshot.detail ?: when (snapshot.phase) {
                TunnelPhase.OFF -> getString(R.string.detail_off)
                TunnelPhase.REQUESTING_PERMISSION -> getString(R.string.detail_requesting)
                TunnelPhase.STARTING -> getString(R.string.detail_starting)
                TunnelPhase.BLOCKING -> getString(R.string.detail_blocking)
                TunnelPhase.PROTECTED -> getString(R.string.detail_protected)
                TunnelPhase.ERROR -> getString(R.string.vpn_establish_failed)
            }
        } finally {
            rendering = false
        }
    }

    private fun resolveBackgroundColor(): Int {
        val nightMask = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            Color.rgb(16, 20, 18)
        } else {
            Color.rgb(247, 248, 250)
        }
    }

    private fun handleConfigIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW || intent.data?.scheme != "trojan") return
        val result = TrojanUriParser.parse(intent.data.toString())
        result.onSuccess { config ->
            SecureConfigStore(this).save(config)
            Toast.makeText(this, R.string.config_imported, Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.config_invalid, error.message ?: "unknown error"),
                Toast.LENGTH_LONG,
            ).show()
        }
        intent.data = null
    }

    companion object {
        const val EXTRA_REQUEST_VPN_PERMISSION = "request_vpn_permission"
        private const val VPN_PERMISSION_REQUEST = 101
    }
}
