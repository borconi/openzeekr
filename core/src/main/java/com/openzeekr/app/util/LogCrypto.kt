package com.openzeekr.app.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * One-way encryption for a copied debug log.
 *
 * A user can read their own log on-screen (the LogViewer stays plaintext), but the "Copy"
 * action emits an ENCRYPTED base64 blob that only the OpenZeekr developers can decrypt - so a
 * log pasted into a GitHub issue leaks nothing (tokens, VIN, DK/BLE key material, device ids).
 *
 * Asymmetric by design: the app carries ONLY the RSA-4096 PUBLIC key ([PUBLIC_KEY_B64]). The
 * matching PRIVATE key never ships and is never committed (devs keep it offline; see
 * tools/log-decrypt/ + scripts/decrypt_log.py). Extracting the APK therefore does NOT let an
 * attacker decrypt a pasted log.
 *
 * Hybrid scheme (standard JCA, no extra deps):
 *   1. random AES-256 key + 12-byte IV,
 *   2. AES-256-GCM encrypt the log (128-bit tag, appended to the ciphertext by JCA),
 *   3. RSA-OAEP(SHA-256, MGF1-SHA-256) wrap the AES key with the baked public key.
 *
 * Blob layout (then base64, NO_WRAP):
 *   magic "OZ" (2) | version (1) | wrappedKeyLen (2, big-endian) | wrappedKey | IV (12) | GCM(ct+tag)
 */
object LogCrypto {

    /** Magic + version prefix (keep in sync with scripts/decrypt_log.py). */
    private const val MAGIC0 = 'O'.code
    private const val MAGIC1 = 'Z'.code
    private const val VERSION = 1

    /**
     * RSA-4096 PUBLIC key, X.509 SubjectPublicKeyInfo, base64 (DER). Safe to ship / commit.
     * The PRIVATE half is held only by the developers (tools/log-decrypt/private_key.pem,
     * gitignored). Regenerate BOTH with: openssl genpkey -algorithm RSA -pkeyopt
     * rsa_keygen_bits:4096 ... (see scripts/README.md) and paste the new public DER here.
     */
    private const val PUBLIC_KEY_B64 =
        "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAwJFw8YbTa2FrSfUAeEoW" +
            "UJVPmW1j3vTCxBmw4MNDrK6GxBMgUjm3eWzPOnWHNoKDlHamKYtMVgfUaoO0L8Q" +
            "OqIY2bp3eeuPx/iqsh6mwZTVi1TLmvTtlwrVPqzqGNsLdD2QI4DXp95gRyBn6eI" +
            "VDESrNxdLlQurXisBVPfRzL+LY28F6Xn2lk6itoaF47eYDVnGOTOexSox7kg5VGy" +
            "EiXHuRTTPy8Bw0RbgNQYzcgFmF2FeBslf9BVQyX/lh5Ujx6YFNnM9MMSTgQPlqA" +
            "y4htH3tCQqta9Z+fEypBVkpYJvwh98LXvkgxeiGR9Klc9Exz5iK259KyZvusDIk" +
            "AdplF061+fKjBoHJclygCa1joDhuLoFKNV9wjk5Dld9a3GcWcT4V9eJT0gdG6Cp" +
            "MuYYvBqbz+zIscJAID5MYbeUUEmU7qUKWjau/OPeE0hBFCQCqQv22ehWrJEI1OK" +
            "CdBkgRB/nPh6WtXFjZD0hqwpOcyc4oyHbkIdaZVY62XQKIbFxMUxzy+jy1KE13Z" +
            "Fkwl2Kr6R+Eb0pfym5kmNj0pWf1fjq+f+pSFO1o2uvCbSK18CwkV7DvDIJpH3xj" +
            "LvWB3dqytUCcXw+rj7pLGLGy4K9inTgKVNwrhy1cJI1wkUYiz8Gdae7GAwg+Y/E" +
            "u5NLRB35xX0+3ndLVB+w0rZb/eu3Dmv0CAwEAAQ=="

    /**
     * Encrypt [plaintext] to a base64 blob, or return null on ANY failure. Callers MUST treat
     * null as "copy nothing" - never fall back to copying the plaintext log.
     */
    fun encryptToBase64(plaintext: String): String? = runCatching {
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))

        val rng = SecureRandom()
        val aesKey = ByteArray(32).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }

        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        }
        val ciphertext = gcm.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Pin BOTH the OAEP digest and the MGF1 digest to SHA-256 (some providers otherwise
        // default MGF1 to SHA-1), so the Python decryptor interops exactly.
        val oaep = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT,
        )
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pub, oaep)
        }
        val wrappedKey = rsa.doFinal(aesKey)

        val out = ByteArrayOutputStream()
        out.write(MAGIC0); out.write(MAGIC1); out.write(VERSION)
        out.write((wrappedKey.size ushr 8) and 0xFF)
        out.write(wrappedKey.size and 0xFF)
        out.write(wrappedKey)
        out.write(iv)
        out.write(ciphertext)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
