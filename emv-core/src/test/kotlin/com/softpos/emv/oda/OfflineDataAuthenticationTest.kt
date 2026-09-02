package com.softpos.emv.oda

import com.softpos.emv.capk.CapkRegistry
import com.softpos.emv.model.Pan
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.TlvDatabase
import com.softpos.emv.util.Hex
import com.softpos.emv.util.hexToBytes
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmvCertificatesTest {

    private val pki = TestPki()
    private val pan = "4761739001010010"
    private val today = LocalDate.of(2026, 3, 4)
    private val staticData = "700A5A084761739001010010".hexToBytes()

    @Test
    fun `recovers the issuer key from a valid certificate`() {
        val cert = pki.issuerCertificate(pan)

        val recovered = assertIs<Recovery.Ok<RecoveredPublicKey>>(
            EmvCertificates.recoverIssuerKey(pki.capk, cert.certificate, cert.remainder, cert.exponent, pan, today),
        ).value

        assertTrue(recovered.modulus.contentEquals(pki.issuer.modulus), "issuer modulus must round-trip")
        assertEquals(LocalDate.of(2035, 12, 31), recovered.expiry)
        assertEquals("000001", recovered.serialNumberHex)
    }

    @Test
    fun `a tampered issuer certificate fails on the hash`() {
        val cert = pki.issuerCertificate(pan)
        val remainder = assertNotNull(cert.remainder).copyOf().also { it[0] = (it[0] + 1).toByte() }

        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.recoverIssuerKey(pki.capk, cert.certificate, remainder, cert.exponent, pan, today),
        )
        assertEquals(OdaFailureReason.HASH_MISMATCH, failed.reason)
    }

    @Test
    fun `an issuer certificate for another issuer is rejected`() {
        val cert = pki.issuerCertificate(pan = "5413330089600010")

        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.recoverIssuerKey(pki.capk, cert.certificate, cert.remainder, cert.exponent, pan, today),
        )
        assertEquals(OdaFailureReason.ISSUER_IDENTIFIER_MISMATCH, failed.reason)
    }

    @Test
    fun `an expired issuer certificate is rejected`() {
        val cert = pki.issuerCertificate(pan, expiryMmyy = "0126")

        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.recoverIssuerKey(pki.capk, cert.certificate, cert.remainder, cert.exponent, pan, today),
        )
        assertEquals(OdaFailureReason.CERTIFICATE_EXPIRED, failed.reason)
    }

    @Test
    fun `a certificate signed by a different CA does not recover`() {
        val cert = TestPki().issuerCertificate(pan)

        assertIs<Recovery.Failed>(
            EmvCertificates.recoverIssuerKey(pki.capk, cert.certificate, cert.remainder, cert.exponent, pan, today),
        )
    }

    @Test
    fun `recovers the icc key and binds it to the static data`() {
        val issuer = recoverIssuer()
        val cert = pki.iccCertificate(pan, staticData)

        val recovered = assertIs<Recovery.Ok<RecoveredPublicKey>>(
            EmvCertificates.recoverIccKey(issuer, cert.certificate, cert.remainder, cert.exponent, staticData, pan, today),
        ).value
        assertTrue(recovered.modulus.contentEquals(pki.icc.modulus))

        val altered = staticData.copyOf().also { it[it.size - 1] = 0x11 }
        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.recoverIccKey(issuer, cert.certificate, cert.remainder, cert.exponent, altered, pan, today),
        )
        assertEquals(OdaFailureReason.HASH_MISMATCH, failed.reason)
    }

    @Test
    fun `an icc certificate for another pan is rejected`() {
        val issuer = recoverIssuer()
        val cert = pki.iccCertificate("4761739001010028", staticData)

        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.recoverIccKey(issuer, cert.certificate, cert.remainder, cert.exponent, staticData, pan, today),
        )
        assertEquals(OdaFailureReason.PAN_MISMATCH, failed.reason)
    }

    @Test
    fun `a short icc key fits inside the certificate without a remainder`() {
        val small = TestPki(iccBits = 640)
        val issuerCert = small.issuerCertificate(pan)
        val issuer = assertIs<Recovery.Ok<RecoveredPublicKey>>(
            EmvCertificates.recoverIssuerKey(small.capk, issuerCert.certificate, issuerCert.remainder, issuerCert.exponent, pan, today),
        ).value
        val cert = small.iccCertificate(pan, staticData)
        assertEquals(null, cert.remainder)

        val recovered = assertIs<Recovery.Ok<RecoveredPublicKey>>(
            EmvCertificates.recoverIccKey(issuer, cert.certificate, null, cert.exponent, staticData, pan, today),
        ).value
        assertEquals(640, recovered.modulusBits)
    }

    @Test
    fun `verifies signed static application data`() {
        val issuer = recoverIssuer()
        val ssad = pki.signedStaticData(staticData, dacHex = "1234")

        val ok = assertIs<Recovery.Ok<EmvCertificates.StaticAuthentication>>(
            EmvCertificates.verifySignedStaticData(issuer, ssad, staticData),
        ).value
        assertEquals("1234", ok.dataAuthenticationCodeHex)

        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.verifySignedStaticData(issuer, ssad, staticData + byteArrayOf(0)),
        )
        assertEquals(OdaFailureReason.HASH_MISMATCH, failed.reason)
    }

    @Test
    fun `verifies signed dynamic application data against the terminal challenge`() {
        val icc = RecoveredPublicKey(pki.icc.modulus, pki.icc.exponent, today.plusYears(1), "000002")
        val challenge = "ABABABAB0000000012340840".hexToBytes()
        val sdad = pki.signedDynamicData("0011223344556677", "8000", challenge)

        val ok = assertIs<Recovery.Ok<EmvCertificates.DynamicAuthentication>>(
            EmvCertificates.verifySignedDynamicData(icc, sdad, challenge),
        ).value
        assertEquals("0011223344556677", ok.iccDynamicNumberHex)
        assertEquals("8000", Hex.encode(ok.trailingDynamicData))

        val replayed = "CDCDCDCD0000000012340840".hexToBytes()
        val failed = assertIs<Recovery.Failed>(EmvCertificates.verifySignedDynamicData(icc, sdad, replayed))
        assertEquals(OdaFailureReason.HASH_MISMATCH, failed.reason)
    }

    @Test
    fun `a signature of the wrong length is a length mismatch not a crash`() {
        val issuer = recoverIssuer()

        val failed = assertIs<Recovery.Failed>(
            EmvCertificates.verifySignedStaticData(issuer, ByteArray(64), staticData),
        )
        assertEquals(OdaFailureReason.LENGTH_MISMATCH, failed.reason)
    }

    private fun recoverIssuer(): RecoveredPublicKey {
        val cert = pki.issuerCertificate(pan)
        return assertIs<Recovery.Ok<RecoveredPublicKey>>(
            EmvCertificates.recoverIssuerKey(pki.capk, cert.certificate, cert.remainder, cert.exponent, pan, today),
        ).value
    }
}

class OfflineDataAuthenticationTest {

    private val pki = TestPki()
    private val panDigits = "4761739001010010"
    private val today = LocalDate.of(2026, 3, 4)
    private val staticData = "5A084761739001010010".hexToBytes()
    private val terminalData = "ABABABAB0000000012340840".hexToBytes()
    private val registry = CapkRegistry(listOf(pki.capk))

    private fun database(aipHex: String, dda: Boolean, ctqHex: String = "8000", signedCtqHex: String = "8000"): TlvDatabase {
        val issuerCert = pki.issuerCertificate(panDigits)
        val builder = TlvDatabase.builder()
            .put(EmvTags.AIP, aipHex.hexToBytes())
            .put(EmvTags.CA_PUBLIC_KEY_INDEX, byteArrayOf(pki.index.toByte()))
            .put(EmvTags.ISSUER_PUBLIC_KEY_CERTIFICATE, issuerCert.certificate)
            .put(EmvTags.ISSUER_PUBLIC_KEY_EXPONENT, issuerCert.exponent)
        issuerCert.remainder?.let { builder.put(EmvTags.ISSUER_PUBLIC_KEY_REMAINDER, it) }

        if (dda) {
            val iccCert = pki.iccCertificate(panDigits, staticData)
            val cardAuthenticationData = "01" + "11223344" + ctqHex
            builder
                .put(EmvTags.ICC_PUBLIC_KEY_CERTIFICATE, iccCert.certificate)
                .put(EmvTags.ICC_PUBLIC_KEY_EXPONENT, iccCert.exponent)
                .put(EmvTags.CARD_AUTHENTICATION_RELATED_DATA, cardAuthenticationData.hexToBytes())
                .put(EmvTags.CARD_TRANSACTION_QUALIFIERS, ctqHex.hexToBytes())
                .put(
                    EmvTags.SIGNED_DYNAMIC_APPLICATION_DATA,
                    pki.signedDynamicData("0102030405060708", signedCtqHex, terminalData + cardAuthenticationData.hexToBytes()),
                )
            iccCert.remainder?.let { builder.put(EmvTags.ICC_PUBLIC_KEY_REMAINDER, it) }
        } else {
            builder.put(EmvTags.SIGNED_STATIC_APPLICATION_DATA, pki.signedStaticData(staticData))
        }
        return builder.build()
    }

    private fun input(db: TlvDatabase, dynamic: ByteArray? = terminalData, intact: Boolean = true) = OdaInput(
        db = db,
        aip = assertNotNull(db[EmvTags.AIP]),
        rid = pki.rid,
        pan = Pan.of(panDigits),
        staticData = staticData,
        staticDataIntact = intact,
        terminalDynamicData = dynamic,
    )

    @Test
    fun `fast dda authenticates a genuine card`() {
        val result = OfflineDataAuthentication.authenticate(input(database("3800", dda = true)), registry, true, today)

        val ok = assertIs<OdaResult.Authenticated>(result, result.describe())
        assertEquals(OdaMethod.DDA, ok.method)
        assertTrue(ok.detail.contains("0102030405060708"))
        assertEquals(CardAuthentication.DDA_AUTHENTICATED, result.summary())
        assertEquals(0, result.tvrByte1)
    }

    @Test
    fun `sda authenticates when the card only supports sda`() {
        val result = OfflineDataAuthentication.authenticate(input(database("5800", dda = false)), registry, true, today)

        assertEquals(OdaMethod.SDA, assertIs<OdaResult.Authenticated>(result, result.describe()).method)
    }

    @Test
    fun `a replayed signature fails dda`() {
        val db = database("3800", dda = true)
        val result = OfflineDataAuthentication.authenticate(
            input(db, dynamic = "CDCDCDCD0000000012340840".hexToBytes()),
            registry,
            true,
            today,
        )

        val failed = assertIs<OdaResult.Failed>(result)
        assertEquals(OdaFailureReason.HASH_MISMATCH, failed.reason)
        assertEquals(OdaResult.TVR_DDA_FAILED, failed.tvrByte1)
    }

    @Test
    fun `a ctq that differs from the signed one fails dda`() {
        val result = OfflineDataAuthentication.authenticate(
            input(database("3800", dda = true, ctqHex = "8000", signedCtqHex = "0000")),
            registry,
            true,
            today,
        )

        assertEquals(OdaFailureReason.CTQ_MISMATCH, assertIs<OdaResult.Failed>(result).reason)
    }

    @Test
    fun `a malformed oda record fails rather than skipping authentication`() {
        val result = OfflineDataAuthentication.authenticate(
            input(database("5800", dda = false), intact = false),
            registry,
            true,
            today,
        )

        assertEquals(OdaFailureReason.MALFORMED_RECORD, assertIs<OdaResult.Failed>(result).reason)
    }

    @Test
    fun `an empty capk table means not performed`() {
        val result = OfflineDataAuthentication.authenticate(input(database("3800", dda = true)), CapkRegistry.Empty, true, today)

        assertEquals(OdaSkipReason.NO_CAPK_TABLE, assertIs<OdaResult.NotPerformed>(result).reason)
        assertEquals(OdaResult.TVR_ODA_NOT_PERFORMED, result.tvrByte1)
    }

    @Test
    fun `an unknown capk index means not performed`() {
        val other = TestPki()
        val result = OfflineDataAuthentication.authenticate(
            input(database("3800", dda = true)),
            CapkRegistry(listOf(other.capk.copy(index = 0x01))),
            true,
            today,
        )

        assertEquals(OdaSkipReason.CAPK_NOT_FOUND, assertIs<OdaResult.NotPerformed>(result).reason)
    }

    @Test
    fun `a card offering only cda is reported as such`() {
        val result = OfflineDataAuthentication.authenticate(input(database("1900", dda = false)), registry, true, today)

        assertEquals(OdaSkipReason.CDA_ONLY, assertIs<OdaResult.NotPerformed>(result).reason)
    }

    @Test
    fun `disabled means not performed even with keys loaded`() {
        val result = OfflineDataAuthentication.authenticate(input(database("3800", dda = true)), registry, false, today)

        assertEquals(OdaSkipReason.DISABLED, assertIs<OdaResult.NotPerformed>(result).reason)
    }

    @Test
    fun `static data builder strips the 70 template for low sfis and keeps high sfis whole`() {
        val builder = OfflineDataAuthentication.StaticDataBuilder()
        builder.addRecord(2, "7003AABBCC".hexToBytes())
        builder.addRecord(12, "DEADBEEF".hexToBytes())

        val (bytes, intact) = builder.finish(sdaTagList = "82".hexToBytes(), aip = "3800".hexToBytes())

        assertTrue(intact)
        assertEquals("AABBCCDEADBEEF3800", Hex.encode(bytes))
    }

    @Test
    fun `static data builder flags a low sfi record that is not a template`() {
        val builder = OfflineDataAuthentication.StaticDataBuilder()
        builder.addRecord(1, "5A08476173".hexToBytes())

        val (_, intact) = builder.finish(sdaTagList = null, aip = "3800".hexToBytes())

        assertEquals(false, intact)
    }
}
