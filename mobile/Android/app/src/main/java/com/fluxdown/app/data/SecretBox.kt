package com.fluxdown.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小型静态加密盒：Android Keystore 内不可导出的 AES-256-GCM 密钥加密短文本（远端主机访问密钥）。
 *
 * 密文格式 = Base64( iv[12] ‖ ciphertext+tag )；[aad] 绑定条目身份（主机 id），防止把 A 的密文换到 B 上。
 * 密钥由 Keystore 随设备保管：备份还原到新设备或清除锁屏后密钥失效时 [open] 抛 [SecretUnavailableException]，
 * 调用方应按“访问密钥已丢失”处理（让用户重新输入），而不是崩溃。
 */
class SecretBox(private val alias: String = DEFAULT_ALIAS) {
    class SecretUnavailableException(cause: Throwable) : Exception("secret unavailable: ${cause.message}", cause)

    fun seal(plain: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    fun open(sealed: String, aad: String): String = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: java.security.GeneralSecurityException) {
        throw SecretUnavailableException(e)
    } catch (e: IllegalArgumentException) {
        throw SecretUnavailableException(e)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "fluxdown.host.secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
