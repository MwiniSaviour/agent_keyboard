package com.agentkeyboard.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.agentkeyboard.app.AkLog
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
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
 */
class KeyVault(private val context: Context) {

    /** Returns the raw-key passphrase bytes; [fresh] is true when a new key was created (DB must be new). */
    fun databaseKey(): Pair<ByteArray, Boolean> {
        val file = File(context.noBackupFilesDir, WRAPPED_FILE)
        if (file.exists()) {
            unwrap(file.readBytes())?.let { return rawKey(it) to false }
            AkLog.w("database key could not be unwrapped; starting a fresh encrypted store")
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

    private fun unwrap(blob: ByteArray): ByteArray? = runCatching {
        require(blob.size > 2 && blob[0] == VERSION)
        val ivLen = blob[1].toInt()
        val iv = blob.copyOfRange(2, 2 + ivLen)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv)) }
        c.doFinal(blob, 2 + ivLen, blob.size - 2 - ivLen)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ak.db.wrap.v1"
        const val WRAPPED_FILE = "db.key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}
