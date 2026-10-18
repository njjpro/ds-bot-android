package com.aiyoyo.dshlauncher

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Instance(
    val id: String,
    var name: String,
    var url: String,
    val createdAt: Long,
    var lastUsedAt: Long
)

/** 用 Android Keystore 里的 AES/GCM 密钥加密 URL——URL 里的 token 就是登录凭证。 */
object UrlCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dsh_launcher_url_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(body, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val body = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = body.copyOfRange(0, IV_BYTES)
        val cipherText = body.copyOfRange(IV_BYTES, body.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()
}

class InstanceStore(context: Context) {

    private val prefs = context.getSharedPreferences("dsh_launcher", Context.MODE_PRIVATE)

    fun load(): MutableList<Instance> {
        val raw = prefs.getString(KEY_LIST, null) ?: return mutableListOf()
        val list = mutableListOf<Instance>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = UrlCipher.decrypt(obj.getString("url")) ?: continue
                list += Instance(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = url,
                    createdAt = obj.optLong("createdAt"),
                    lastUsedAt = obj.optLong("lastUsedAt")
                )
            }
        }
        return list.sortedByDescending { it.lastUsedAt }.toMutableList()
    }

    fun save(list: List<Instance>) {
        val array = JSONArray()
        list.forEach { instance ->
            array.put(
                JSONObject().apply {
                    put("id", instance.id)
                    put("name", instance.name)
                    put("url", UrlCipher.encrypt(instance.url))
                    put("createdAt", instance.createdAt)
                    put("lastUsedAt", instance.lastUsedAt)
                }
            )
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun upsert(instance: Instance) {
        val list = load()
        val index = list.indexOfFirst { it.id == instance.id }
        if (index >= 0) list[index] = instance else list += instance
        save(list)
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun touch(id: String) {
        val list = load()
        list.firstOrNull { it.id == id }?.let {
            it.lastUsedAt = System.currentTimeMillis()
            save(list)
        }
    }

    fun newInstance(name: String, url: String): Instance = Instance(
        id = UUID.randomUUID().toString(),
        name = name,
        url = url,
        createdAt = System.currentTimeMillis(),
        lastUsedAt = System.currentTimeMillis()
    )

    companion object {
        private const val KEY_LIST = "instances_v1"
    }
}

object UrlTools {

    /**
     * 只补齐 scheme，绝不改写 host/端口/path：
     * DSH 的会话 cookie 绑定 authority，改写会导致认证失效。
     */
    fun normalize(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "https://$text"
        }
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return text
    }

    fun defaultName(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        return uri.authority ?: uri.host ?: url
    }

    fun displayTarget(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val path = uri.path.orEmpty().trimEnd('/')
        return (uri.authority ?: "") + if (path.isEmpty() || path == "/") "" else path
    }

    /** scheme + host + 有效端口 完全一致才算站内；DSH 的 cookie 也按 authority 绑定。 */
    fun sameOrigin(a: String, b: String): Boolean {
        val ua = runCatching { Uri.parse(a) }.getOrNull() ?: return false
        val ub = runCatching { Uri.parse(b) }.getOrNull() ?: return false
        if (!ua.scheme.equals(ub.scheme, true)) return false
        if (ua.host != ub.host) return false
        fun effectivePort(uri: Uri): Int =
            if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        return effectivePort(ua) == effectivePort(ub)
    }
}
