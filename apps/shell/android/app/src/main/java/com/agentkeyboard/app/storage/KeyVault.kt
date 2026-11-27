package com.agentkeyboard.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.agentkeyboard.app.AkLog
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Database key management (SPEC §17, ADR-0007).
 *
 * A random 256-bit SQLCipher key is wrapped with a non-exportable AES-GCM key in AndroidKeyStore and
 * stored under `noBackupFilesDir` (never in backups or device transfer). The key is handed to
 * SQLCipher in raw-key form (`x'…'`), which skips PBKDF2. On a 4 GB device that saves several hundred
 * milliseconds per open, and the key is already full-entropy.
 *
 * Not user-auth-bound: the keyboard and background replies must work while the device is locked
 * (direct-boot is out of scope; the DB opens after first unlock).
 *
 * Data-loss rule: the store is reset only when the key is provably gone (the wrapping key no longer
 * exists or cannot authenticate the blob, or the blob is corrupt). A transient Keystore failure
 * (daemon busy, `ProviderException`, `KeyStoreException`) is thrown to the caller, which retries on
 * the next request, instead of silently replacing the user's agents, clipboard and audit log.
 */
class KeyVault(private val context: Context) {

    /** Returns the raw-key passphrase bytes; [fresh] is true when a new key was created (DB must be new). */
    fun databaseKey(): Pair<ByteArray, Boolean> {
        val file = File(context.noBackupFilesDir, WRAPPED_FILE)
        if (file.exists()) {
            when (val r = unwrap(file.readBytes())) {
                is Unwrapped.Key -> return rawKey(r.bytes).also { r.bytes.fill(0) } to false
                Unwrapped.Gone -> AkLog.w("database key is permanently unavailable; starting a fresh encrypted store")
            }
        }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val tmp = File(file.parentFile, "$WRAPPED_FILE.tmp")
        tmp.writeBytes(wrap(key))
        if (!tmp.renameTo(file)) error("could not persist database key")
        return rawKey(key).also { key.fill(0) } to true
    }

    private fun rawKey(key: ByteArray): ByteArray {
        val hex = key.joinToString("") { "%02x".format(it) }
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val iv = c.iv
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private sealed interface Unwrapped {
        class Key(val bytes: ByteArray) : Unwrapped
        /** The key can never be recovered: resetting the store is the only way forward. */
        data object Gone : Unwrapped
    }

    /** Throws on transient failures; see the data-loss rule above. */
    private fun unwrap(blob: ByteArray): Unwrapped {
        val ivLen = if (blob.size > 2 && blob[0] == VERSION) blob[1].toInt() else -1
        if (ivLen !in 12..16 || blob.size <= 2 + ivLen) return Unwrapped.Gone // corrupt / unknown format
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = try {
            ks.getKey(ALIAS, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            null
        } ?: return Unwrapped.Gone // Keystore wiped (e.g. lock-screen reset on some OEMs) or never created
        return try {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 2, ivLen)) }
            Unwrapped.Key(c.doFinal(blob, 2 + ivLen, blob.size - 2 - ivLen))
        } catch (e: AEADBadTagException) {
            Unwrapped.Gone // a different key than the one that wrapped this blob
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            Unwrapped.Gone
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ak.db.wrap.v1"
        const val WRAPPED_FILE = "db.key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}
