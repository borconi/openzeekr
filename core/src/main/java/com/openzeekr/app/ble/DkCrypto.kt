package com.openzeekr.app.ble

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin crypto for the DK BLE session. All standard primitives (P-256
 * ECDH / ECDSA-SHA256 / AES-128-GCM / SHA-256 / CRC-16-ARC) — no native libs.
 *
 * The one thing plain JCE can't do is expose BOTH coordinates of the ECDH
 * shared point (KeyAgreement returns only X), and the DK KDF needs X (AES key)
 * AND Y (GCM IV). BouncyCastle EC point math provides the full point.
 */
object DkCrypto {

    private const val CURVE = "secp256r1"
    private val bc: BouncyCastleProvider = BouncyCastleProvider().also {
        if (Security.getProvider(it.name) == null) Security.addProvider(it)
    }
    private val curveSpec: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec(CURVE)
    private val rng = SecureRandom()

    // ---------------- EC keys ----------------

    /** Generate an ephemeral P-256 keypair (used per BLE session). */
    fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()

    /** Raw uncompressed public point bytes: 04 || X(32) || Y(32) (65 bytes). */
    fun publicPointUncompressed(pub: PublicKey): ByteArray {
        // Re-parse via BC to get the EC point regardless of the source provider.
        val bcPub = java.security.KeyFactory.getInstance("EC", bc)
            .generatePublic(java.security.spec.X509EncodedKeySpec(pub.encoded))
            as org.bouncycastle.jce.interfaces.ECPublicKey
        val point = bcPub.q.normalize()
        return byteArrayOf(0x04) + fixed32(point.affineXCoord.toBigInteger()) + fixed32(point.affineYCoord.toBigInteger())
    }

    /** The DK "factor" = X(32) || Y(32) (64 bytes, no 0x04 prefix). */
    fun factorBytes(pub: PublicKey): ByteArray = publicPointUncompressed(pub).copyOfRange(1, 65)

    /**
     * Full ECDH: multiply the peer public point by our private scalar and return
     * the resulting affine point as X(32) || Y(32) (64 bytes) — matches the DK
     * native genEcdhKey / genSKey output.
     *
     * @param peerPoint peer public key as 04||X||Y (65B) or X||Y (64B).
     */
    fun ecdhSharedPoint(ourPrivate: PrivateKey, peerPoint: ByteArray): ByteArray {
        val d: BigInteger = (
            java.security.KeyFactory.getInstance("EC", bc)
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(ourPrivate.encoded))
                as org.bouncycastle.jce.interfaces.ECPrivateKey
            ).d
        val encoded = if (peerPoint.size == 64) byteArrayOf(0x04) + peerPoint else peerPoint
        val q = curveSpec.curve.decodePoint(encoded)
        val shared = q.multiply(d).normalize()
        return fixed32(shared.affineXCoord.toBigInteger()) + fixed32(shared.affineYCoord.toBigInteger())
    }

    // ---------------- AES-128-CBC (0x0101/0x0102 pairing epoch) ----------------

    /**
     * The fixed CBC IV baked in `CipherEngine.cbcIv` (verified byte-exact from smali).
     * Used for the CONNECT_CONFIRM (0x0101) / DK_STATUS (0x0102) frames, which are
     * AES-128-CBC/PKCS7 under the per-session [connectKey].
     */
    val CBC_IV: ByteArray = byteArrayOf(
        0x1d, 0x0a, 0x5d, 0xba.toByte(), 0x3c, 0xa5.toByte(), 0xd8.toByte(), 0x07,
        0x53, 0x1d, 0x61, 0x22, 0xa6.toByte(), 0x6c, 0x26, 0x17,
    )

    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    /**
     * Derive the pairing-epoch AES-128 key exactly as `FactorEngine.genConnectkey`:
     *   rnd8       = first 8 bytes of the advertisement broadcast-random
     *   asciiHex16 = uppercase-hex ASCII of rnd8 (16 chars)
     *   connectKey[i] = vinAscii[i] XOR asciiHex16[i]   (i = 0..15)
     * VIN is 17 ASCII chars, so vin[0:16] is used; broadcastRnd must be >= 8 bytes.
     */
    fun deriveConnectKey(vin: String, broadcastRnd: ByteArray): ByteArray {
        require(broadcastRnd.size >= 8) { "broadcastRnd too short (${broadcastRnd.size})" }
        val vinBytes = vin.toByteArray(Charsets.US_ASCII)
        require(vinBytes.size >= 16) { "VIN too short (${vinBytes.size})" }
        val asciiHex = ByteArray(16)
        for (i in 0 until 8) {
            val b = broadcastRnd[i].toInt() and 0xFF
            asciiHex[i * 2] = HEX_UPPER[b ushr 4].code.toByte()
            asciiHex[i * 2 + 1] = HEX_UPPER[b and 0xF].code.toByte()
        }
        return ByteArray(16) { (vinBytes[it].toInt() xor asciiHex[it].toInt()).toByte() }
    }

    /** AES-128-CBC + PKCS7 encrypt (pairing epoch). */
    fun aesCbcEncryptPkcs7(key16: ByteArray, iv16: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")   // JCE PKCS5 == PKCS7 for AES
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"), javax.crypto.spec.IvParameterSpec(iv16))
        return c.doFinal(plaintext)
    }

    /** AES-128-CBC + PKCS7 decrypt (pairing epoch). */
    fun aesCbcDecryptPkcs7(key16: ByteArray, iv16: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), javax.crypto.spec.IvParameterSpec(iv16))
        return c.doFinal(ciphertext)
    }

    // ---------------- AES-128-GCM ----------------

    /**
     * AES-128-GCM encrypt; returns ciphertext || 16-byte tag.
     *
     * The car's iWall GCM feeds the 12-byte IV as Additional Authenticated Data
     * (AAD == nonce). Proven empirically: the car's 0x010c frame verifies its GCM
     * tag ONLY with updateAAD(iv). Without it the tag mismatches and the car
     * rejects our 0x010b SEND_DKEY with EEC_skeyErr (0x1031).
     */
    fun gcmEncrypt(key16: ByteArray, iv12: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"), GCMParameterSpec(128, iv12))
        c.updateAAD(iv12)
        return c.doFinal(plaintext)
    }

    /** AES-128-GCM decrypt of ciphertext || 16-byte tag (AAD == iv, see gcmEncrypt). */
    fun gcmDecrypt(key16: ByteArray, iv12: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), GCMParameterSpec(128, iv12))
        c.updateAAD(iv12)
        return c.doFinal(ciphertextWithTag)
    }

    // ---------------- AES-CMAC + RPA cmacKey unwrap (remote parking 0x0113/0x0116) ----------------

    /** Fallback CMAC key (BleConstant.CMACKEY) used only when there's no per-DK key: 16 × 0x55. */
    val CMAC_FALLBACK_KEY: ByteArray = ByteArray(16) { 0x55 }

    /**
     * AES-CMAC (NIST SP800-38B) truncated to the first 6 bytes — the RPA frame trailer.
     * The car's native `cmacCompute` is stock OpenSSL AES-CMAC (cipher chosen by key length,
     * 16B → AES-128; output cut to [0:6]). See CMAC_FINDINGS.md §2.
     */
    fun aesCmac6(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = CMac(AESEngine.newInstance())
        mac.init(KeyParameter(key))
        mac.update(msg, 0, msg.size)
        val full = ByteArray(mac.macSize)
        mac.doFinal(full, 0)
        return full.copyOfRange(0, 6)
    }

    /**
     * Decrypt the cloud-issued `cmacKeyCert` envelope to the 16-byte AES-CMAC key using our own
     * DK private key. The envelope is a minimal ECIES(P-256)+AES-256-GCM container (NOT X9.63/HMAC):
     *   `[len(4 BE)=L] ‖ ephPub(L = 04‖X‖Y) ‖ ciphertext ‖ gcmTag(16)`
     * where `SK = ECDH(ourPriv, ephPub).X` (raw 32-byte X, no KDF), then AES-256-GCM with
     * `key=SK, iv=SK[0:12], aad=SK[12:16]`, tag = last 16 B. Recovered plaintext = the 16-byte key.
     * Reversed from libiwallca.so `ecies_decrypt`@0x1bcf78 and round-trip validated (CMAC_FINDINGS §7–9).
     */
    fun unwrapCmacKey(envelope: ByteArray, dkPrivate: PrivateKey): ByteArray {
        require(envelope.size >= 4) { "cmacKeyCert envelope too short (${envelope.size})" }
        val l = ((envelope[0].toInt() and 0xFF) shl 24) or ((envelope[1].toInt() and 0xFF) shl 16) or
            ((envelope[2].toInt() and 0xFF) shl 8) or (envelope[3].toInt() and 0xFF)
        require(l in 33..120 && envelope.size >= 4 + l + 16) {
            "cmacKeyCert envelope malformed (size=${envelope.size}, L=$l)"
        }
        val ephPoint = envelope.copyOfRange(4, 4 + l)                     // 04‖X‖Y
        val ctTag = envelope.copyOfRange(4 + l, envelope.size)           // ciphertext ‖ tag
        val sk = ecdhSharedPoint(dkPrivate, ephPoint).copyOfRange(0, 32)  // raw ECDH X (32B)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sk, "AES"), GCMParameterSpec(128, sk, 0, 12))
        c.updateAAD(sk, 12, 4)
        return c.doFinal(ctTag)
    }

    // ---------------- ECDSA / hash ----------------

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** ECDSA-SHA256 signature in DER (as the DK cloud/BLE expect). */
    fun ecdsaSignDer(priv: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }

    fun ecdsaVerifyDer(pub: PublicKey, data: ByteArray, derSig: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(derSig) }

    // ---------------- CRC-16/ARC (frame trailer) ----------------

    private val CRC16_TAB = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c and 0xFFFF
    }

    /** CRC-16/ARC (poly 0xA001 reflected, init 0x0000). Returns 0..0xFFFF. */
    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0
        for (i in 0 until len) crc = (crc ushr 8) xor CRC16_TAB[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        return crc and 0xFFFF
    }

    // ---------------- helpers ----------------

    /** Left-pad / trim a BigInteger to exactly 32 bytes (strip sign byte, pad zeros). */
    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0].toInt() == 0 -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b.copyOfRange(b.size - 32, b.size)
        }
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
}
