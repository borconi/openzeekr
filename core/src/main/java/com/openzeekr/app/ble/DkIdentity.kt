package com.openzeekr.app.ble

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The device's OWN digital-key identity: a P-256 keypair we generate and keep
 * (unlike the stock app's device-bound Keystore key), a stable 64-hex deviceId,
 * and the cloud-provisioned material once enrolled.
 *
 * This is what makes openzeekr work on ANY phone/account: we mint our own key,
 * enrol our own cert, and (after binding) fetch our own key material — the
 * private key never has to come from another device.
 *
 * Persisted in its own EncryptedSharedPreferences (separate from ConfigStore).
 */
class DkIdentity private constructor(private val prefs: android.content.SharedPreferences) {

    // ---- stable deviceId (64-hex, matches the stock getDeviceID format) ----
    val deviceId: String
        get() = prefs.getString(K_DEVICE_ID, null) ?: run {
            val id = DkCrypto.randomBytes(32).joinToString("") { "%02x".format(it) }
            prefs.edit().putString(K_DEVICE_ID, id).apply(); id
        }

    // ---- our EC keypair (generated once, persisted) ----
    fun keyPair(): KeyPair {
        val priv = prefs.getString(K_PRIV, null)
        val pub = prefs.getString(K_PUB, null)
        if (priv != null && pub != null) {
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))),
            )
        }
        val kp = DkCrypto.generateEcKeyPair()
        prefs.edit()
            .putString(K_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(K_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    val privateKey: PrivateKey get() = keyPair().private
    val publicKey: PublicKey get() = keyPair().public

    /** PKCS#8 PEM of our private key (for DkCredential.from / export). */
    fun privateKeyPem(): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"

    /**
     * PKCS#10 CSR (PEM) for our keypair, as create-app-certificate expects.
     *
     * Subject matches the proven-working enrolment (provision_eu.py APP_SUBJECT):
     * C=CN, ST=ZheJiang, L=Hangzhou, O=ECARX, OU=CloudDept, CN=<short hex>. The
     * server overrides CN with our deviceId in the issued cert, so the CN value
     * here is cosmetic — but the full DN structure is what the gateway accepts
     * (a partial subject 400s).
     */
    fun buildCsrPem(): String {
        val kp = keyPair()
        val subject = X500Name(
            "C=CN,ST=ZheJiang,L=Hangzhou,O=ECARX,OU=CloudDept,CN=${deviceId.take(8)}"
        )
        val csr = JcaPKCS10CertificationRequestBuilder(subject, kp.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        return "-----BEGIN CERTIFICATE REQUEST-----\n" +
            Base64.encodeToString(csr.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE REQUEST-----\n"
    }

    /** ECDSA-SHA256 (DER, base64) over userId+deviceId+vin — the DK body signature. */
    fun signDkMessage(userId: String, vin: String): String {
        val msg = (userId + deviceId + vin).toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(DkCrypto.ecdsaSignDer(privateKey, msg), Base64.NO_WRAP)
    }

    // ---- provisioned material (persisted once enrolled + bound) ----
    fun saveProvisioned(certBase64: String, dkId: String, bookId: String,
                        digitalKeyB64: String, cmacKeyCertHex: String, coefSmallHex: String, vin: String,
                        coefBigHex: String = "", mobileCodeHex: String = "") {
        prefs.edit()
            .putString(K_CERT, certBase64).putString(K_DKID, dkId).putString(K_BOOKID, bookId)
            .putString(K_DIGKEY, digitalKeyB64).putString(K_CMAC, cmacKeyCertHex)
            .putString(K_COEF, coefSmallHex).putString(K_VIN, vin)
            .putString(K_COEFBIG, coefBigHex).putString(K_MOBILECODE, mobileCodeHex)
            .apply()
    }

    val isProvisioned: Boolean get() = prefs.getString(K_CERT, null) != null && prefs.getString(K_DIGKEY, null) != null

    /** Build the runtime credential for RealDkSession (null until provisioned). */
    fun credential(): DkCredential? {
        val cert = prefs.getString(K_CERT, null) ?: return null
        val dig = prefs.getString(K_DIGKEY, null) ?: return null
        return DkCredential.from(
            dkId = prefs.getString(K_BOOKID, "") ?: "",
            cloudDkId = prefs.getString(K_DKID, "") ?: "",
            vin = prefs.getString(K_VIN, "") ?: "",
            deviceId = deviceId,
            certBase64 = cert,
            privateKeyPem = privateKeyPem(),
            digitalKeyBase64 = dig,
            cmacKeyCertHex = prefs.getString(K_CMAC, "") ?: "",
            coefSmallHex = prefs.getString(K_COEF, "") ?: "",
            coefBigHex = prefs.getString(K_COEFBIG, "") ?: "",
            mobileCodeHex = prefs.getString(K_MOBILECODE, "") ?: "",
        )
    }

    /** Wipe provisioned material (keep the keypair+deviceId) to re-provision. */
    fun clearProvisioned() {
        prefs.edit().remove(K_CERT).remove(K_DKID).remove(K_BOOKID)
            .remove(K_DIGKEY).remove(K_CMAC).remove(K_COEF).apply()
    }

    /** Full wipe — removes EVERYTHING, including the keypair + deviceId, so the phone holds NO key
     *  material afterwards (a true "remove key", not a re-provision). */
    fun wipeAll() = prefs.edit().clear().apply()

    /**
     * Export the full provisioned identity (keypair + deviceId + cloud material) as a flat
     * string map, to CLONE onto a companion device (the Wear app). Includes the PRIVATE key,
     * so transfer it only over a secure channel to another device you own (the encrypted Wear
     * Data Layer between a paired phone + watch). Null until provisioned.
     */
    fun exportCredentialBlob(): Map<String, String>? {
        if (!isProvisioned) return null
        return CLONE_KEYS.mapNotNull { k -> prefs.getString(k, null)?.let { k to it } }.toMap()
    }

    /**
     * Import an identity blob from [exportCredentialBlob] (received from the phone). The watch
     * becomes a clone of the phone's key — same cert/dkId/deviceId — so the car authenticates
     * it as the same digital key (used one device at a time).
     */
    fun importCredentialBlob(blob: Map<String, String>) {
        prefs.edit().apply { CLONE_KEYS.forEach { k -> blob[k]?.let { putString(k, it) } } }.apply()
    }

    companion object {
        private const val FILE = "openzeekr_dk_identity"
        private const val K_DEVICE_ID = "device_id"
        private const val K_PRIV = "priv"; private const val K_PUB = "pub"
        private const val K_CERT = "cert"; private const val K_DKID = "dk_id"; private const val K_BOOKID = "book_id"
        private const val K_DIGKEY = "digital_key"; private const val K_CMAC = "cmac_key_cert"
        private const val K_COEF = "coef_small"; private const val K_VIN = "vin"
        private const val K_COEFBIG = "coef_big"; private const val K_MOBILECODE = "mobile_code"

        /** Every persisted key that makes up the transferable identity (phone → watch clone). */
        private val CLONE_KEYS = listOf(
            K_DEVICE_ID, K_PRIV, K_PUB, K_CERT, K_DKID, K_BOOKID,
            K_DIGKEY, K_CMAC, K_COEF, K_VIN, K_COEFBIG, K_MOBILECODE,
        )

        @Volatile private var INSTANCE: DkIdentity? = null
        fun get(context: Context): DkIdentity = INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }

        private fun build(appCtx: Context): DkIdentity {
            val masterKey = MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                appCtx, FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return DkIdentity(prefs)
        }
    }
}
