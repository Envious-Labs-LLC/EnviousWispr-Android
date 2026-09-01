package com.envi.wispr.providers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Stores only encrypted blobs in the app's no-backup directory. Plaintext never enters preferences or logs. */
class AndroidKeystoreSecretStore(context: Context) : SecretStore {
    private val file: File = File(context.applicationContext.noBackupFilesDir, "provider-secrets.v1")
    // All instances in the process share the same file and Keystore alias.
    private val lock = processLock

    /**
     * Two locks, because two things read this file: every instance in THIS process (the monitor) and the
     * `:polish` process, which reads the key at request time while the main process may be replacing it
     * (the file lock). Without the second, a write's temp file could be deleted by the other process's
     * `recoverFiles` mid-save, failing the save or leaving the engine without a key (#69 code review).
     */
    private inline fun <T> withStorageLock(action: () -> T): T = synchronized(lock) {
        RandomAccessFile(lockFile(), "rw").channel.use { channel ->
            val fileLock = channel.lock()
            try {
                action()
            } finally {
                fileLock.release()
            }
        }
    }

    override fun put(provider: Provider, secret: String) = withStorageLock {
        require(secret.isNotBlank()) { "secret must not be blank" }
        val values = readAll().toMutableMap()
        values[provider.name] = encrypt(provider, secret)
        writeAll(values)
    }

    override fun get(provider: Provider): String? = withStorageLock {
        readAll()[provider.name]?.let { decrypt(provider, it) }
    }

    override fun remove(provider: Provider) = withStorageLock {
        val values = readAll().toMutableMap()
        if (values.remove(provider.name) != null) {
            if (values.isEmpty()) {
                deleteStorageFiles()
                deleteKey()
            } else {
                writeAll(values)
            }
        }
    }

    private fun readAll(): Map<String, String> {
        recoverFiles()
        if (!file.exists()) return emptyMap()
        return file.readLines(StandardCharsets.UTF_8).mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
    }

    private fun writeAll(values: Map<String, String>) {
        recoverFiles()
        val temporary = temporaryFile()
        val backup = backupFile()
        val encoded = values.entries.joinToString("\n") { "${it.key}=${it.value}" }
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) {
            check(!backup.exists() || backup.delete()) { "could not clear provider secret backup" }
            check(file.renameTo(backup)) { "could not back up provider secrets" }
        }
        try {
            check(temporary.renameTo(file)) { "could not persist provider secrets" }
            if (backup.exists()) check(backup.delete()) { "could not clear provider secret backup" }
        } catch (failure: RuntimeException) {
            if (!file.exists() && backup.exists()) backup.renameTo(file)
            throw failure
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun recoverFiles() {
        val temporary = temporaryFile()
        val backup = backupFile()
        if (file.exists()) {
            check(!temporary.exists() || temporary.delete()) { "could not clear stale provider secret temp" }
            check(!backup.exists() || backup.delete()) { "could not clear stale provider secret backup" }
        } else if (backup.exists()) {
            check(backup.renameTo(file)) { "could not restore provider secrets" }
            check(!temporary.exists() || temporary.delete()) { "could not clear stale provider secret temp" }
        } else {
            check(!temporary.exists() || temporary.delete()) { "could not clear stale provider secret temp" }
        }
    }

    private fun deleteStorageFiles() {
        check(!file.exists() || file.delete()) { "could not delete provider secrets" }
        check(!temporaryFile().exists() || temporaryFile().delete()) { "could not delete provider secret temp" }
        check(!backupFile().exists() || backupFile().delete()) { "could not delete provider secret backup" }
    }

    private fun temporaryFile() = File(file.parentFile, "${file.name}.tmp")

    /** Never deleted with the storage files: a lock file that vanishes mid-lock is no lock. */
    private fun lockFile() = File(file.parentFile, "${file.name}.lock")

    private fun backupFile() = File(file.parentFile, "${file.name}.bak")

    private fun encrypt(provider: Provider, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(provider.name.toByteArray(StandardCharsets.UTF_8))
        val nonce = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return "$FORMAT_VERSION:${Base64.getEncoder().encodeToString(nonce + encrypted)}"
    }

    private fun decrypt(provider: Provider, value: String): String {
        val separator = value.indexOf(':')
        require(separator > 0 && value.substring(0, separator) == FORMAT_VERSION) {
            "unsupported encrypted provider secret version"
        }
        val packed = Base64.getDecoder().decode(value.substring(separator + 1))
        require(packed.size > NONCE_BYTES) { "invalid encrypted provider secret" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, NONCE_BYTES)))
        cipher.updateAAD(provider.name.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(packed.copyOfRange(NONCE_BYTES, packed.size)), StandardCharsets.UTF_8)
    }

    private fun key() = (KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        .getKey(KEY_ALIAS, null) ?: generateKey())

    private fun deleteKey() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .deleteEntry(KEY_ALIAS)
    }

    private fun generateKey() = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        generateKey()
    }

    private companion object {
        val processLock = Any()
        const val KEY_ALIAS = "enviouswispr.provider-secrets.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
