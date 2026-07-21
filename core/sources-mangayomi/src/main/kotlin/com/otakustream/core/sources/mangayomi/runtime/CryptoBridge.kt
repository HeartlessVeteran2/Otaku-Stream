package com.otakustream.core.sources.mangayomi.runtime

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// AES helpers extensions rely on for the many hosts that obfuscate their stream URLs. Exposed to
// JS as encryptAESCryptoJS / decryptAESCryptoJS / cryptoHandler (matching the Mangayomi host API).
internal object CryptoBridge {

    // CryptoJS AES.encrypt(text, passphrase): OpenSSL "Salted__" format with the EVP_BytesToKey
    // (MD5) key/iv derivation, base64-encoded — the exact scheme CryptoJS produces.
    fun encryptAesCryptoJs(plainText: String, password: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpKdf(password.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val out = SALTED_PREFIX + salt + encrypted
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decryptAesCryptoJs(cipherBase64: String, password: String): String {
        val data = Base64.decode(cipherBase64, Base64.DEFAULT)
        require(data.size > 16 && data.copyOfRange(0, 8).contentEquals(SALTED_PREFIX)) {
            "Not a CryptoJS salted ciphertext"
        }
        val salt = data.copyOfRange(8, 16)
        val body = data.copyOfRange(16, data.size)
        val (key, iv) = evpKdf(password.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    // Raw AES/CBC with caller-supplied key + iv (UTF-8 bytes). encrypt: plaintext -> base64;
    // decrypt: base64 -> plaintext.
    fun cryptoHandler(text: String, iv: String, secretKey: String, encrypt: Boolean): String {
        val keySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        return if (encrypt) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(Base64.decode(text, Base64.DEFAULT)), Charsets.UTF_8)
        }
    }

    // OpenSSL EVP_BytesToKey with MD5, 1 iteration → 32-byte key + 16-byte iv.
    private fun evpKdf(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val derived = ArrayList<Byte>()
        var block = ByteArray(0)
        while (derived.size < KEY_LEN + IV_LEN) {
            md5.reset()
            md5.update(block)
            md5.update(password)
            md5.update(salt)
            block = md5.digest()
            derived.addAll(block.toList())
        }
        val all = derived.toByteArray()
        return all.copyOfRange(0, KEY_LEN) to all.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)
    }

    private const val KEY_LEN = 32
    private const val IV_LEN = 16
    private val SALTED_PREFIX = "Salted__".toByteArray(Charsets.US_ASCII)
}
