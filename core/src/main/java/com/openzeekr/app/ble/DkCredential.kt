package com.openzeekr.app.ble

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The provisioned digital-key material this device holds, everything
 * [RealDkSession] needs to run the BLE handshake + control a car.
 *
 * Sourced from cloud provisioning (create-app-certificate + key-info ->
 * DkDetaiBean) — see the Python client / M2 port. For M1 bring-up it can be
 * imported from a captured DkDetaiBean plus our own enrolled cert+key.
 *
 * @param dkId          BLE/connect key id (the cloud "bookId", e.g. "8000a5cb")
 * @param cloudDkId     long cloud dkId (for key-info); not used on the BLE path
 * @param vin           vehicle VIN
 * @param dkCertDer     our provisioned DK leaf cert, DER bytes (base64 "cert" decoded)
 * @param dkPrivateKey  the EC private key matching dkCert (we generated it at enrolment)
 * @param digitalKey    the DkDetaiBean.digitalKey blob (base64-decoded) — sent in SEND_DKEY
 * @param cmacKeyCert   DkDetaiBean.cmacKeyCert bytes (hex-decoded); car pubkey + secret
 * @param coefSmall     coefSmallParam bytes (hex-decoded) — uploaded on char 2A12 (0x0172)
 */
data class DkCredential(
    val dkId: String,
    val cloudDkId: String,
    val vin: String,
    val deviceId: String, // our 64-hex deviceId (getDeviceID) — feeds phoneId
    val dkCertDer: ByteArray,
    val dkPrivateKey: PrivateKey,
    val digitalKey: ByteArray,
    val cmacKeyCert: ByteArray,
    val coefSmall: ByteArray,
    val coefBig: ByteArray = ByteArray(0),
    val mobileCode: ByteArray = ByteArray(0),
) {
    val dkCert: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(dkCertDer)) as X509Certificate
    }

    // ---- ConfirmCodePayloadNew calibration fields, computed exactly like stock p0/n ----
    /** bigCalibrationDataHash(4) = SHA256(coefBigParam bytes)[0:4]; zeros if empty (p0.n.a). */
    val bigCalibHash4: ByteArray by lazy { calibHash4(coefBig) }
    /** smallCalibrationDataHash(4) = SHA256(coefSmallParam bytes)[0:4]; zeros if empty. */
    val smallCalibHash4: ByteArray by lazy { calibHash4(coefSmall) }
    /**
     * phoneType(3) = hexStringToBytes(CalibrationData.getMobileCode()), else new byte[3] (zeros) ONLY when
     * there is no CalibrationData at all (stock confirm builder, p0/n.smali:188-210). So a mobileCode of
     * "FFFFFF" is sent as-is (FF FF FF) - stock does the same - and empty falls back to zeros. (Tested:
     * forcing zeros for FFFFFF did NOT change the position-4 finalize, so phoneType is not the blocker
     * unless stock's getMobileCode() differs from ours - capture pending.) padded/truncated to 3.
     */
    val phoneType3: ByteArray by lazy { ByteArray(3) { if (it < mobileCode.size) mobileCode[it] else 0 } }

    private fun calibHash4(b: ByteArray): ByteArray =
        if (b.isEmpty()) ByteArray(4) else DkCrypto.sha256(b).copyOfRange(0, 4)

    /** phoneId for ConfirmCodePayloadNew = fills(deviceIdBytes, 8) = deviceId[0:8]. */
    val phoneId8: ByteArray by lazy {
        val b = hexToBytes(deviceId)
        ByteArray(8) { if (it < b.size) b[it] else 0 }
    }

    /**
     * dkID field in ConfirmCodePayloadNew = hexStringToBytes(getDkID()) where getDkID() returns
     * the BOOKID (a short hex string, e.g. "8000a6af"), NOT the long numeric cloud dkId. Proven by
     * decrypting the stock 0x0101: dkID = 8000a69a (4 bytes) = the bookId. Using the 8-byte numeric
     * dkId was the bug (wrong value AND wrong length -> misaligned payload -> EEC_confirmFailed).
     * NOTE: [dkId] here is the bookId (DkIdentity.credential() maps K_BOOKID -> dkId).
     */
    val dkIdBytes: ByteArray by lazy { hexToBytes(dkId) }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        /** Build from the stored/importable string forms (as the cloud returns them). */
        fun from(
            dkId: String,
            cloudDkId: String,
            vin: String,
            deviceId: String,
            certBase64: String, // create-app-certificate data.cert (base64 DER, no PEM armor)
            privateKeyPem: String, // our enrolled EC private key (PKCS#8 PEM)
            digitalKeyBase64: String,
            cmacKeyCertHex: String,
            coefSmallHex: String,
            coefBigHex: String = "",
            mobileCodeHex: String = "",
        ): DkCredential = DkCredential(
            dkId = dkId,
            cloudDkId = cloudDkId,
            vin = vin,
            deviceId = deviceId,
            dkCertDer = Base64.decode(certBase64.trim(), Base64.DEFAULT),
            dkPrivateKey = parsePkcs8Ec(privateKeyPem),
            digitalKey = Base64.decode(digitalKeyBase64.trim(), Base64.DEFAULT),
            cmacKeyCert = hexToBytes(cmacKeyCertHex),
            coefSmall = hexToBytes(coefSmallHex),
            coefBig = hexToBytes(coefBigHex),
            mobileCode = hexToBytes(mobileCodeHex),
        )

        private fun parsePkcs8Ec(pem: String): PrivateKey {
            val body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
            val der = Base64.decode(body, Base64.DEFAULT)
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        fun hexToBytes(hex: String): ByteArray {
            val s = hex.trim().replace(Regex("\\s"), "")
            val out = ByteArray(s.length / 2)
            for (i in out.indices) out[i] = ((hexVal(s[i * 2]) shl 4) or hexVal(s[i * 2 + 1])).toByte()
            return out
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("bad hex '$c'")
        }
    }
}
