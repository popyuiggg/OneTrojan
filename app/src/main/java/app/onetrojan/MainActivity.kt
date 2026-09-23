package app.onetrojan

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import app.onetrojan.UiKit.dp
import app.onetrojan.config.SecureConfigStore
import app.onetrojan.config.TrojanUriParser
import app.onetrojan.tunnel.TunnelController
import app.onetrojan.tunnel.TunnelEvent
import app.onetrojan.tunnel.TunnelPhase
import app.onetrojan.tunnel.TunnelService
import app.onetrojan.tunnel.TunnelSnapshot

class MainActivity : Activity() {
    private lateinit var toggle: Switch
    private lateinit var toggleContainer: LinearLayout
    private lateinit var toggleText: TextView
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var statusCard: LinearLayout
    private var rendering = false
    private var lastRenderedPhase: TunnelPhase? = null
    private var ribbonAnimator: ValueAnimator? = null

    private val stateListener: (TunnelSnapshot) -> Unit = { snapshot ->
        render(snapshot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.setSystemBars(this)
        setContentView(buildContentView())
        TunnelController.addListener(stateListener)
        handleConfigIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_VPN_PERMISSION, false)) {
            requestEnable()
            intent.removeExtra(EXTRA_REQUEST_VPN_PERMISSION)
        }
    }

    override fun onDestroy() {
        ribbonAnimator?.cancel()
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

    @Suppress("DEPRECATION")
    private fun buildContentView(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
            background = null

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.brand_mark)
                contentDescription = getString(R.string.app_name)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(134), dp(134)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 34f
                setTextColor(UiKit.color(UiKit.TEXT))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, marginTop(dp(8)))

            statusCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(30), dp(22), dp(30))
                background = neutralStatusCard()
                elevation = dp(7).toFloat()
                addView(stateLabel().also { stateText = it })
                addView(detailLabel().also { detailText = it }, marginTop(dp(12)))
            }
            addView(statusCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) })

            addView(LinearLayout(this@MainActivity).apply {
                toggleContainer = this
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), 0, dp(18), 0)
                background = neutralControl()
                elevation = dp(5).toFloat()
                addView(TextView(this@MainActivity).apply {
                    toggleText = this
                    text = getString(R.string.switch_off)
                    textSize = 18f
                    setTextColor(UiKit.color(UiKit.TEXT))
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(this@MainActivity).apply {
                    toggle = this
                    showText = false
                    minWidth = dp(62)
                    thumbTintList = UiKit.tint(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(UiKit.color(UiKit.TEXT), UiKit.color(UiKit.TEXT_MUTED)),
                    )
                    trackTintList = UiKit.tint(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(UiKit.color(UiKit.ORANGE_DARK), UiKit.color(UiKit.SURFACE)),
                    )
                    setOnCheckedChangeListener { _, checked ->
                        if (rendering) return@setOnCheckedChangeListener
                        if (checked) requestEnable() else requestDisable()
                    }
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(74),
            ).apply { topMargin = dp(28) })

            addView(UiKit.styleSecondaryButton(Button(this@MainActivity).apply {
                text = getString(R.string.config_open)
                setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_preferences, 0, 0, 0)
                compoundDrawablePadding = dp(10)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                    UiKit.color(UiKit.TEXT_MUTED),
                )
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                }
            }), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60),
            ).apply { topMargin = dp(18) })
        }
        return ScrollView(this).apply {
            isFillViewport = true
            background = BrandBackgroundDrawable()
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun stateLabel() = TextView(this).apply {
        textSize = 24f
        setTextColor(UiKit.color(UiKit.TEXT))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun detailLabel() = TextView(this).apply {
        textSize = 15f
        setTextColor(UiKit.color(UiKit.TEXT_MUTED))
        gravity = Gravity.CENTER
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
            toggleText.text = if (toggle.isChecked) {
                getString(R.string.switch_on)
            } else {
                getString(R.string.switch_off)
            }
            toggleContainer.background = if (toggle.isChecked) {
                UiKit.rounded(UiKit.ORANGE, dp(24).toFloat())
            } else {
                neutralControl()
            }
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
            statusCard.background = when (snapshot.phase) {
                TunnelPhase.PROTECTED -> protectedStatusCard(snapshot.phase)
                TunnelPhase.ERROR -> UiKit.rounded(
                    UiKit.SURFACE,
                    dp(28).toFloat(),
                    UiKit.ERROR,
                    dp(1),
                )
                else -> neutralStatusCard()
            }
            if (snapshot.phase != TunnelPhase.PROTECTED) ribbonAnimator?.cancel()
            lastRenderedPhase = snapshot.phase
        } finally {
            rendering = false
        }
    }

    private fun protectedStatusCard(phase: TunnelPhase): StatusRibbonDrawable {
        val drawable = StatusRibbonDrawable(dp(28).toFloat(), dp(1))
        val shouldAnimate = lastRenderedPhase != null && lastRenderedPhase != phase
        if (!shouldAnimate) {
            drawable.progress = 1f
            return drawable
        }
        drawable.progress = 0f
        ribbonAnimator?.cancel()
        ribbonAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { animation ->
                drawable.progress = animation.animatedValue as Float
            }
            start()
        }
        return drawable
    }

    private fun neutralStatusCard() = UiKit.rounded(
        UiKit.SURFACE,
        dp(28).toFloat(),
        UiKit.BORDER,
        dp(1),
    )

    private fun neutralControl() = UiKit.rounded(
        UiKit.SURFACE_RAISED,
        dp(24).toFloat(),
        UiKit.BORDER,
        dp(1),
    )

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
