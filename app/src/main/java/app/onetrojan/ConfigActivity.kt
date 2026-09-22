package app.onetrojan

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.onetrojan.config.SecureConfigStore
import app.onetrojan.config.DirectDnsResolver
import app.onetrojan.config.TrojanConfig
import app.onetrojan.config.TrojanConfigDraft
import app.onetrojan.config.TrojanJsonImporter
import app.onetrojan.tunnel.TunnelController
import app.onetrojan.tunnel.TunnelEvent
import app.onetrojan.tunnel.TunnelPhase
import app.onetrojan.tunnel.TunnelService

class ConfigActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var serverIp: EditText
    private lateinit var port: EditText
    private lateinit var sni: EditText
    private lateinit var password: EditText
    private lateinit var dnsIp: EditText
    private lateinit var alpn: EditText
    private lateinit var tlsProfile: EditText
    private lateinit var resolveStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.config_title)
        setContentView(buildContentView())
        SecureConfigStore(this).load()?.let(::populate)
    }

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
            setBackgroundColor(resolveBackgroundColor())

            addView(TextView(this@ConfigActivity).apply {
                text = getString(R.string.config_title)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@ConfigActivity).apply {
                text = getString(R.string.config_local_only)
                textSize = 14f
                alpha = 0.72f
                setPadding(0, dp(8), 0, dp(16))
            })

            host = addField(this, R.string.config_host, InputType.TYPE_CLASS_TEXT)
            serverIp = addField(this, R.string.config_server_ip, InputType.TYPE_CLASS_TEXT)
            addView(Button(this@ConfigActivity).apply {
                text = getString(R.string.config_resolve_ipv4)
                setOnClickListener { resolveIpv4() }
            })
            addView(TextView(this@ConfigActivity).apply {
                resolveStatus = this
                textSize = 13f
                alpha = 0.72f
                setPadding(0, 0, 0, dp(4))
            })
            port = addField(this, R.string.config_port, InputType.TYPE_CLASS_NUMBER).apply {
                setText(R.string.config_default_port)
            }
            sni = addField(this, R.string.config_sni, InputType.TYPE_CLASS_TEXT)
            password = addField(
                this,
                R.string.config_password,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ).apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            dnsIp = addField(this, R.string.config_dns, InputType.TYPE_CLASS_TEXT).apply {
                setText("1.1.1.1")
            }
            alpn = addField(this, R.string.config_alpn, InputType.TYPE_CLASS_TEXT).apply {
                setText(R.string.config_default_alpn)
            }
            tlsProfile = addField(this, R.string.config_tls_profile, InputType.TYPE_CLASS_TEXT).apply {
                setText(R.string.config_default_tls_profile)
            }

            addView(Button(this@ConfigActivity).apply {
                text = getString(R.string.config_import_json)
                setOnClickListener { showJsonImportDialog() }
            }, topMargin(dp(20)))
            addView(Button(this@ConfigActivity).apply {
                text = getString(R.string.config_save)
                setOnClickListener { saveConfig() }
            }, topMargin(dp(8)))
        }
        return ScrollView(this).apply { addView(form) }
    }

    private fun addField(parent: LinearLayout, label: Int, inputType: Int): EditText {
        val density = resources.displayMetrics.density
        parent.addView(TextView(this).apply {
            text = getString(label)
            textSize = 13f
            alpha = 0.72f
            setPadding(0, (10 * density).toInt(), 0, 0)
        })
        val field = EditText(this).apply {
            this.inputType = inputType
            setSingleLine(true)
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        parent.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return field
    }

    private fun topMargin(value: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = value }

    private fun showJsonImportDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = getString(R.string.config_json_hint)
            gravity = Gravity.TOP or Gravity.START
            minLines = 10
            maxLines = 18
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt(),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.config_import_json)
            .setView(input)
            .setNegativeButton(R.string.config_cancel, null)
            .setPositiveButton(R.string.config_parse_json) { _, _ ->
                importJsonText(input.text.toString())
            }
            .show()
    }

    private fun importJsonText(jsonText: String) {
        val result = runCatching {
            require(jsonText.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
                "JSON 文本过大"
            }
            TrojanJsonImporter.parse(jsonText).getOrThrow()
        }
        result.onSuccess { draft ->
            populate(draft)
            val message = if (draft.serverIp.isBlank()) {
                R.string.config_imported_needs_ip
            } else {
                R.string.config_imported_review
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }.onFailure(::showError)
    }

    @Suppress("DEPRECATION")
    private fun resolveIpv4() {
        val hostname = host.text.toString().trim().ifBlank { sni.text.toString().trim() }
        val resolverIp = dnsIp.text.toString().trim()
        if (hostname.isBlank()) {
            showError(IllegalArgumentException("请先填写服务器域名"))
            return
        }
        resolveStatus.text = getString(R.string.config_resolving)
        Thread({
            val result = runCatching {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val underlyingNetworks = connectivity.allNetworks.filter { network ->
                    connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    } == true
                }
                require(underlyingNetworks.isNotEmpty()) { "找不到可用的非 VPN 网络" }
                var lastError: Throwable? = null
                for (network in underlyingNetworks) {
                    try {
                        return@runCatching DirectDnsResolver.resolveIpv4(
                            hostname,
                            resolverIp,
                        ) { socket -> network.bindSocket(socket) }
                    } catch (error: Exception) {
                        lastError = error
                    }
                }
                throw lastError ?: IllegalStateException("IPv4 解析失败")
            }
            runOnUiThread {
                result.onSuccess { address ->
                    serverIp.setText(address)
                    resolveStatus.text = getString(R.string.config_resolved, address)
                }.onFailure { error ->
                    resolveStatus.text = getString(
                        R.string.config_resolve_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                    showError(error)
                }
            }
        }, "config-ipv4-resolver").start()
    }

    private fun saveConfig() {
        val config = TrojanConfig(
            serverIp = serverIp.text.toString().trim(),
            port = port.text.toString().toIntOrNull() ?: 0,
            sni = sni.text.toString().trim().ifBlank { host.text.toString().trim() },
            password = password.text.toString(),
            dnsIp = dnsIp.text.toString().trim(),
            alpn = alpn.text.toString().split(',').map(String::trim).filter(String::isNotEmpty),
            tlsProfile = tlsProfile.text.toString().trim().lowercase(),
        )
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            showError(IllegalArgumentException(errors.joinToString("；")))
            return
        }
        SecureConfigStore(this).save(config)
        if (TunnelController.snapshot.phase !in setOf(TunnelPhase.OFF, TunnelPhase.ERROR)) {
            startService(
                Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP),
            )
            TunnelController.dispatch(TunnelEvent.Disabled)
        }
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun populate(config: TrojanConfig) = populate(
        TrojanConfigDraft(
            host = config.sni,
            serverIp = config.serverIp,
            port = config.port.toString(),
            sni = config.sni,
            password = config.password,
            dnsIp = config.dnsIp,
            alpn = config.alpn.joinToString(","),
            tlsProfile = config.tlsProfile,
        ),
    )

    private fun populate(draft: TrojanConfigDraft) {
        host.setText(draft.host)
        serverIp.setText(draft.serverIp)
        port.setText(draft.port)
        sni.setText(draft.sni)
        password.setText(draft.password)
        dnsIp.setText(draft.dnsIp)
        alpn.setText(draft.alpn)
        tlsProfile.setText(draft.tlsProfile)
    }

    private fun showError(error: Throwable) {
        Toast.makeText(
            this,
            getString(R.string.config_invalid, error.message ?: "unknown error"),
            Toast.LENGTH_LONG,
        ).show()
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

    companion object {
        private const val MAX_JSON_BYTES = 128 * 1024
    }
}
