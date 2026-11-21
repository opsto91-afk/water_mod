package org.levimc.zaphre.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Additional keys are encrypted with a device-local Android Keystore key. */
internal object ExtraKeyStore {
    private const val ALIAS = "peta_additional_api_keys_v1"
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun save(context: Context, provider: String, keys: List<String>) {
        val prefs = context.getSharedPreferences("peta_secret_keys", Context.MODE_PRIVATE)
        if (keys.isEmpty() && !prefs.contains(provider)) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val ciphertext = cipher.doFinal(JSONArray(keys).toString().toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        check(prefs.edit().putString(provider, stored).commit()) { "Could not save additional keys" }
    }
    fun load(context: Context, provider: String): List<String> {
        val stored = context.getSharedPreferences("peta_secret_keys", Context.MODE_PRIVATE).getString(provider, null) ?: return emptyList()
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted keys" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return List(array.length()) { array.getString(it) }
    }
}
