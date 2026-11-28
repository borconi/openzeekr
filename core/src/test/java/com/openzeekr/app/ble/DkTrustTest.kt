package com.openzeekr.app.ble

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** Vehicle-cert leaf validation with generated test CAs (no real car or Geely keys involved). */
class DkTrustTest {

    private val serial = AtomicLong(1)
    private val day = 86_400_000L

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun cert(
        subject: String,
        subjectKey: KeyPair,
        issuer: X509Certificate?,
        issuerKey: KeyPair,
        ca: Boolean,
        notBefore: Date = Date(System.currentTimeMillis() - day),
        notAfter: Date = Date(System.currentTimeMillis() + 365 * day),
    ): X509Certificate {
        val issuerName = issuer?.let { X500Name.getInstance(it.subjectX500Principal.encoded) } ?: X500Name(subject)
        val b = JcaX509v3CertificateBuilder(
            issuerName, BigInteger.valueOf(serial.getAndIncrement()), notBefore, notAfter,
            X500Name(subject), subjectKey.public,
        )
        b.addExtension(Extension.basicConstraints, true, if (ca) BasicConstraints(0) else BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.private)
        return JcaX509CertificateConverter().getCertificate(b.build(signer))
    }

    private val rootKey = keyPair()
    private val root = cert("CN=Test Root CA,O=Test", rootKey, null, rootKey, ca = true)
    private val policyKey = keyPair()
    private val policy = cert("CN=Test Global policy CA,O=Test", policyKey, root, rootKey, ca = true)
    private val ecuKey = keyPair()
    private val ecuIssuing = cert("CN=ECU Issuing EU-CA,O=Test", ecuKey, policy, policyKey, ca = true)
    private val issuers = listOf(ecuIssuing)

    @Test
    fun acceptsEndEntityLeafFromEcuIssuingCa() {
        val leaf = cert("CN=vehicle,O=Test", keyPair(), ecuIssuing, ecuKey, ca = false)
        DkTrust.requireVehicleLeaf(leaf, issuers)
    }

    @Test
    fun rejectsLeafSignedDirectlyByRoot() {
        val leaf = cert("CN=vehicle,O=Test", keyPair(), root, rootKey, ca = false)
        assertThrows(IllegalStateException::class.java) { DkTrust.requireVehicleLeaf(leaf, issuers) }
    }

    @Test
    fun rejectsLeafSignedDirectlyByPolicyCa() {
        val leaf = cert("CN=vehicle,O=Test", keyPair(), policy, policyKey, ca = false)
        assertThrows(IllegalStateException::class.java) { DkTrust.requireVehicleLeaf(leaf, issuers) }
    }

    @Test
    fun rejectsCaCertSignedByEcuIssuingCa() {
        val subCa = cert("CN=rogue sub-CA,O=Test", keyPair(), ecuIssuing, ecuKey, ca = true)
        assertThrows(IllegalStateException::class.java) { DkTrust.requireVehicleLeaf(subCa, issuers) }
    }

    @Test
    fun rejectsTheIssuingCaItself() {
        // Self-check: the policy-signed ECU issuing CA must not pass as a vehicle cert.
        assertThrows(IllegalStateException::class.java) { DkTrust.requireVehicleLeaf(ecuIssuing, listOf(policy)) }
    }

    @Test
    fun rejectsExpiredLeaf() {
        val leaf = cert(
            "CN=vehicle,O=Test", keyPair(), ecuIssuing, ecuKey, ca = false,
            notBefore = Date(System.currentTimeMillis() - 10 * day),
            notAfter = Date(System.currentTimeMillis() - day),
        )
        assertThrows(IllegalStateException::class.java) { DkTrust.requireVehicleLeaf(leaf, issuers) }
    }

    @Test
    fun rejectsWhenNoIssuers() {
        val leaf = cert("CN=vehicle,O=Test", keyPair(), ecuIssuing, ecuKey, ca = false)
        assertThrows(IllegalStateException::class.java) { DkTrust.requireVehicleLeaf(leaf, emptyList()) }
    }

    @Test
    fun ecuIssuingCaFilterMatchesOnlyIssuingCas() {
        assertTrue(DkTrust.isEcuIssuingCa(ecuIssuing))
        assertFalse(DkTrust.isEcuIssuingCa(root))
        assertFalse(DkTrust.isEcuIssuingCa(policy))
        // A non-CA leaf named like an issuing CA is not an issuing CA.
        assertFalse(DkTrust.isEcuIssuingCa(cert("CN=ECU Issuing fake,O=Test", keyPair(), ecuIssuing, ecuKey, ca = false)))
    }

    @Test
    fun bakedStoreHasFourEcuIssuingCas() {
        // ECU Issuing CN-CA, ECU Issuing EU-CA, ZK ECU Issuing CN-CA, ZK ECU Issuing EU-CA.
        assertEquals(4, DkTrust.ecuIssuingCaCount)
    }
}
