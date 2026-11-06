package com.example.phonesteward.android

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity

/**
 * The paired identity (base URL + token as one value, fix 01-1) encrypted
 * with an Android Keystore AES/GCM key. Only the ciphertext (plus IV) sits
 * in a private SharedPreferences file; the key never leaves AndroidKeyStore.
 * An unreadable or legacy-format entry means unpaired — the app fails closed
 * to its pairing screen. Thin adapter for [IdentityStore]; exercised on the
 * phone (R2), since AndroidKeyStore is unavailable under Robolectric.
 */
class KeystoreIdentityStore(context: Context) : IdentityStore {

    private val prefs =
        context.getSharedPreferences("phone_steward_identity", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): PairedIdentity? {
        val stored = prefs.getString(PREF, null) ?: return null
        return try {
            val plain = decrypt(stored)
            json.decodeFromString(PairedIdentity.serializer(), plain)
        } catch (e: Exception) {
            Log.w(TAG, "identity unreadable; treating as unpaired", e)
            null
        }
    }

    override fun write(identity: PairedIdentity) {
        val encrypted = encrypt(json.encodeToString(identity))
        prefs.edit().putString(PREF, encrypted).apply()
    }

    override fun clear() {
        prefs.edit().remove(PREF).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val ciphertext = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreIdentityStore"
        const val PREF = "identity"
        const val ALIAS = "phone_steward_identity_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
