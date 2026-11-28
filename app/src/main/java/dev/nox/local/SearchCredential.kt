package dev.nox.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Native settings only. The raw key never enters the WebView, prompts, or logs. */
internal object SearchCredential {
    private const val ALIAS = "nox_brave_search_v1"
    private fun prefs(c: Context) = c.getSharedPreferences("search_credential", Context.MODE_PRIVATE)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey? {
        val existing = store().getKey(ALIAS, null) as? SecretKey
        if (existing != null || !create) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun write(c: Context, raw: String): Boolean = runCatching {
        require(raw.length in 8..256 && raw.none { it.isWhitespace() || it.isISOControl() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key(true)))
        val bytes = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        prefs(c).edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("value", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)
    fun read(c: Context): String? = runCatching {
        val p = prefs(c)
        val value = p.getString("value", null) ?: return null
        val iv = p.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireNotNull(key(false)), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun clear(c: Context) { prefs(c).edit().clear().commit(); runCatching { store().deleteEntry(ALIAS) } }
}
