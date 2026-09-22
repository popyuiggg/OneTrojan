package app.onetrojan.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(config: TrojanConfig) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(config.toBytes())
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): TrojanConfig? = runCatching {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)).toTrojanConfig()
    }.getOrNull()

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun TrojanConfig.toBytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(serverIp)
            output.writeInt(port)
            output.writeUTF(sni)
            output.writeUTF(password)
            output.writeUTF(dnsIp)
            output.writeInt(alpn.size)
            alpn.forEach(output::writeUTF)
            output.writeUTF(tlsProfile)
        }
        bytes.toByteArray()
    }

    private fun ByteArray.toTrojanConfig(): TrojanConfig =
        DataInputStream(ByteArrayInputStream(this)).use { input ->
            val serverIp = input.readUTF()
            val port = input.readInt()
            val sni = input.readUTF()
            val password = input.readUTF()
            val dnsIp = input.readUTF()
            val alpn = if (input.available() > 0) {
                List(input.readInt()) { input.readUTF() }
            } else {
                emptyList()
            }
            val tlsProfile = if (input.available() > 0) input.readUTF() else "chrome"
            TrojanConfig(
                serverIp = serverIp,
                port = port,
                sni = sni,
                password = password,
                dnsIp = dnsIp,
                alpn = alpn,
                tlsProfile = tlsProfile,
            )
        }

    companion object {
        private const val PREFERENCES = "secure_trojan_config"
        private const val KEY_IV = "iv"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_ALIAS = "one_trojan_config_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
