package com.softpos.emv.oda

import com.softpos.emv.capk.CapkEntry
import com.softpos.emv.util.Hex
import com.softpos.emv.util.cnToDigitsOrNull
import java.math.BigInteger
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

/** An RSA public key recovered from an EMV certificate, plus the certificate's own metadata. */
class RecoveredPublicKey(
    val modulus: ByteArray,
    val exponent: ByteArray,
    /** Last day of the certificate's expiry month; EMV certificates carry only MMYY. */
    val expiry: LocalDate,
    val serialNumberHex: String,
) {
    val modulusBits: Int get() = modulus.size * 8
}

sealed interface Recovery<out T> {
    data class Ok<T>(val value: T) : Recovery<T>
    data class Failed(val reason: OdaFailureReason, val detail: String) : Recovery<Nothing>
}

/**
 * Raw RSA as EMV uses it (EMV 4.4 Book 2, Annex B2): no padding scheme, the message is the
 * whole modulus-length block, and "verifying" means recovering that block and inspecting it.
 */
object EmvRsa {

    /**
     * `signature ^ exponent mod modulus`, left-padded to the modulus length.
     *
     * Null when the signature is not exactly modulus-length or does not fit under the modulus -
     * both of which EMV treats as a failed recovery rather than a malformed input.
     */
    fun recover(signature: ByteArray, modulus: ByteArray, exponent: ByteArray): ByteArray? {
        if (signature.size != modulus.size) return null
        val n = BigInteger(1, modulus)
        val s = BigInteger(1, signature)
        if (s >= n) return null
        return BigInteger(1, exponent).let { e -> s.modPow(e, n) }.toUnsignedBytes(modulus.size)
    }

    /** Test helper and reference implementation of the signing side: `message ^ d mod n`. */
    fun sign(message: ByteArray, modulus: ByteArray, privateExponent: ByteArray): ByteArray {
        require(message.size == modulus.size) { "message must be exactly modulus length" }
        val n = BigInteger(1, modulus)
        val m = BigInteger(1, message)
        require(m < n) { "message must be numerically smaller than the modulus" }
        return m.modPow(BigInteger(1, privateExponent), n).toUnsignedBytes(modulus.size)
    }

    private fun BigInteger.toUnsignedBytes(length: Int): ByteArray {
        val raw = toByteArray()
        // toByteArray() is two's complement: a leading 0x00 appears when the top bit is set.
        val start = if (raw.size > length) raw.size - length else 0
        val out = ByteArray(length)
        val copyLength = raw.size - start
        raw.copyInto(out, destinationOffset = length - copyLength, startIndex = start)
        return out
    }
}

/**
 * The certificate and signature formats of EMV 4.4 Book 2.
 *
 * Every recovered block has the same skeleton: a 6A header, a format byte, method-specific
 * fields, 'BB' padding, a 20-byte SHA-1 and a BC trailer. The hash covers the recovered bytes
 * between the header and the hash, concatenated with data that lives outside the certificate.
 * Where each format's fields sit is spelled out inline against the Book 2 table it comes from.
 *
 * Only SHA-1 (hash algorithm indicator 01) and RSA (public key algorithm indicator 01) exist in
 * EMV today; anything else fails as [OdaFailureReason.UNSUPPORTED_ALGORITHM] rather than being
 * guessed at.
 */
object EmvCertificates {

    private const val HEADER = 0x6A
    private const val TRAILER = 0xBC
    private const val HASH_LENGTH = 20
    private const val ALGORITHM_SHA1 = 0x01
    private const val ALGORITHM_RSA = 0x01

    private const val FORMAT_SSAD = 0x03
    private const val FORMAT_ISSUER_CERTIFICATE = 0x02
    private const val FORMAT_ICC_CERTIFICATE = 0x04
    private const val FORMAT_SDAD = 0x05

    // ---------------------------------------------------------------------------------------
    // Issuer public key certificate - Book 2 section 6.3, tables 6 and 7
    // ---------------------------------------------------------------------------------------

    /**
     * Recovers the issuer public key from tag 90 using the CA public key the card named.
     *
     * @param pan the card's PAN, checked against the certificate's issuer identifier (its
     *   leftmost 3-8 digits). Null skips that check, which only a unit test should want.
     */
    fun recoverIssuerKey(
        capk: CapkEntry,
        certificate: ByteArray,
        remainder: ByteArray?,
        exponent: ByteArray,
        pan: CharSequence?,
        today: LocalDate,
    ): Recovery<RecoveredPublicKey> {
        val r = EmvRsa.recover(certificate, capk.modulus, capk.exponent)
            ?: return Recovery.Failed(
                OdaFailureReason.LENGTH_MISMATCH,
                "issuer certificate is ${certificate.size} bytes, CA key is ${capk.modulus.size}",
            )
        val n = r.size

        frame(r, FORMAT_ISSUER_CERTIFICATE, "issuer certificate")?.let { return it }

        // Layout: [2..5] issuer identifier, [6..7] MMYY, [8..10] serial, [11] hash alg,
        // [12] PK alg, [13] PK length, [14] exponent length, [15 .. n-22] leftmost PK digits.
        if (r[11].toInt() and 0xFF != ALGORITHM_SHA1) {
            return Recovery.Failed(OdaFailureReason.UNSUPPORTED_ALGORITHM, "hash algorithm ${hex(r[11])}")
        }
        if (r[12].toInt() and 0xFF != ALGORITHM_RSA) {
            return Recovery.Failed(OdaFailureReason.UNSUPPORTED_ALGORITHM, "public key algorithm ${hex(r[12])}")
        }
        val keyLength = r[13].toInt() and 0xFF
        val exponentLength = r[14].toInt() and 0xFF
        if (exponentLength != exponent.size) {
            return Recovery.Failed(
                OdaFailureReason.KEY_LENGTH,
                "certificate says exponent is $exponentLength bytes, tag 9F32 is ${exponent.size}",
            )
        }

        val leftmostLength = n - 36
        val hashInput = r.copyOfRange(1, n - 21) + (remainder ?: ByteArray(0)) + exponent
        if (!sha1(hashInput).contentEquals(r.copyOfRange(n - 21, n - 1))) {
            return Recovery.Failed(OdaFailureReason.HASH_MISMATCH, "issuer certificate hash")
        }

        val issuerIdentifier = r.copyOfRange(2, 6).cnToDigitsOrNull()
            ?: return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "issuer identifier is not numeric")
        if (pan != null && !pan.startsWith(issuerIdentifier)) {
            return Recovery.Failed(OdaFailureReason.ISSUER_IDENTIFIER_MISMATCH, "issuer identifier $issuerIdentifier")
        }

        val expiry = expiryFromMmyy(r, 6)
            ?: return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "issuer certificate expiry is not MMYY")
        if (today.isAfter(expiry)) {
            return Recovery.Failed(OdaFailureReason.CERTIFICATE_EXPIRED, "issuer certificate expired $expiry")
        }

        val modulus = assembleModulus(r, 15, leftmostLength, keyLength, remainder)
            ?: return Recovery.Failed(
                OdaFailureReason.KEY_LENGTH,
                "issuer key length $keyLength does not fit the certificate and remainder",
            )

        return Recovery.Ok(
            RecoveredPublicKey(
                modulus = modulus,
                exponent = exponent,
                expiry = expiry,
                serialNumberHex = Hex.encode(r, 8, 3),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // ICC public key certificate - Book 2 section 6.4, tables 13 and 14
    // ---------------------------------------------------------------------------------------

    /**
     * Recovers the ICC public key from tag 9F46 using the issuer key.
     *
     * @param staticData the static data to be authenticated (Book 3 section 10.3) - the ODA
     *   records plus, when the SDA tag list asks for it, the AIP. It is part of the hash.
     */
    fun recoverIccKey(
        issuer: RecoveredPublicKey,
        certificate: ByteArray,
        remainder: ByteArray?,
        exponent: ByteArray,
        staticData: ByteArray,
        pan: CharSequence?,
        today: LocalDate,
    ): Recovery<RecoveredPublicKey> {
        val r = EmvRsa.recover(certificate, issuer.modulus, issuer.exponent)
            ?: return Recovery.Failed(
                OdaFailureReason.LENGTH_MISMATCH,
                "ICC certificate is ${certificate.size} bytes, issuer key is ${issuer.modulus.size}",
            )
        val n = r.size

        frame(r, FORMAT_ICC_CERTIFICATE, "ICC certificate")?.let { return it }

        // Layout: [2..11] PAN (cn, 10 bytes), [12..13] MMYY, [14..16] serial, [17] hash alg,
        // [18] PK alg, [19] PK length, [20] exponent length, [21 .. n-22] leftmost PK digits.
        if (r[17].toInt() and 0xFF != ALGORITHM_SHA1) {
            return Recovery.Failed(OdaFailureReason.UNSUPPORTED_ALGORITHM, "hash algorithm ${hex(r[17])}")
        }
        if (r[18].toInt() and 0xFF != ALGORITHM_RSA) {
            return Recovery.Failed(OdaFailureReason.UNSUPPORTED_ALGORITHM, "public key algorithm ${hex(r[18])}")
        }
        val keyLength = r[19].toInt() and 0xFF
        val exponentLength = r[20].toInt() and 0xFF
        if (exponentLength != exponent.size) {
            return Recovery.Failed(
                OdaFailureReason.KEY_LENGTH,
                "certificate says exponent is $exponentLength bytes, tag 9F47 is ${exponent.size}",
            )
        }

        val leftmostLength = n - 42
        val hashInput = r.copyOfRange(1, n - 21) + (remainder ?: ByteArray(0)) + exponent + staticData
        if (!sha1(hashInput).contentEquals(r.copyOfRange(n - 21, n - 1))) {
            return Recovery.Failed(OdaFailureReason.HASH_MISMATCH, "ICC certificate hash")
        }

        val certifiedPan = r.copyOfRange(2, 12).cnToDigitsOrNull()
            ?: return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "certified PAN is not numeric")
        if (pan != null && !pan.sameDigitsAs(certifiedPan)) {
            return Recovery.Failed(OdaFailureReason.PAN_MISMATCH, "certificate is for a different PAN")
        }

        val expiry = expiryFromMmyy(r, 12)
            ?: return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "ICC certificate expiry is not MMYY")
        if (today.isAfter(expiry)) {
            return Recovery.Failed(OdaFailureReason.CERTIFICATE_EXPIRED, "ICC certificate expired $expiry")
        }

        val modulus = assembleModulus(r, 21, leftmostLength, keyLength, remainder)
            ?: return Recovery.Failed(
                OdaFailureReason.KEY_LENGTH,
                "ICC key length $keyLength does not fit the certificate and remainder",
            )

        return Recovery.Ok(
            RecoveredPublicKey(
                modulus = modulus,
                exponent = exponent,
                expiry = expiry,
                serialNumberHex = Hex.encode(r, 14, 3),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Signed Static Application Data - Book 2 section 5.4, table 5
    // ---------------------------------------------------------------------------------------

    class StaticAuthentication(val dataAuthenticationCodeHex: String)

    fun verifySignedStaticData(
        issuer: RecoveredPublicKey,
        ssad: ByteArray,
        staticData: ByteArray,
    ): Recovery<StaticAuthentication> {
        val r = EmvRsa.recover(ssad, issuer.modulus, issuer.exponent)
            ?: return Recovery.Failed(
                OdaFailureReason.LENGTH_MISMATCH,
                "SSAD is ${ssad.size} bytes, issuer key is ${issuer.modulus.size}",
            )
        val n = r.size

        frame(r, FORMAT_SSAD, "signed static application data")?.let { return it }
        if (r[2].toInt() and 0xFF != ALGORITHM_SHA1) {
            return Recovery.Failed(OdaFailureReason.UNSUPPORTED_ALGORITHM, "hash algorithm ${hex(r[2])}")
        }

        // Layout: [2] hash alg, [3..4] data authentication code, [5 .. n-22] BB padding.
        val hashInput = r.copyOfRange(1, n - 21) + staticData
        if (!sha1(hashInput).contentEquals(r.copyOfRange(n - 21, n - 1))) {
            return Recovery.Failed(OdaFailureReason.HASH_MISMATCH, "static data hash")
        }
        return Recovery.Ok(StaticAuthentication(Hex.encode(r, 3, 2)))
    }

    // ---------------------------------------------------------------------------------------
    // Signed Dynamic Application Data - Book 2 section 6.5.2, table 17
    // ---------------------------------------------------------------------------------------

    class DynamicAuthentication(
        /** ICC Dynamic Number, tag 9F4C: the card's per-transaction nonce inside the signature. */
        val iccDynamicNumberHex: String,
        /** Whatever followed the dynamic number inside the ICC dynamic data; fDDA puts the CTQ here. */
        val trailingDynamicData: ByteArray,
    )

    /**
     * @param terminalDynamicData the terminal-side half of the hash input. For DDA proper that
     *   is the DDOL data (Book 2 section 6.5.1); for fDDA it is Unpredictable Number, Amount
     *   Authorised, Transaction Currency Code and Card Authentication Related Data
     *   (EMV Contactless Book C-3).
     */
    fun verifySignedDynamicData(
        icc: RecoveredPublicKey,
        sdad: ByteArray,
        terminalDynamicData: ByteArray,
    ): Recovery<DynamicAuthentication> {
        val r = EmvRsa.recover(sdad, icc.modulus, icc.exponent)
            ?: return Recovery.Failed(
                OdaFailureReason.LENGTH_MISMATCH,
                "SDAD is ${sdad.size} bytes, ICC key is ${icc.modulus.size}",
            )
        val n = r.size

        frame(r, FORMAT_SDAD, "signed dynamic application data")?.let { return it }
        if (r[2].toInt() and 0xFF != ALGORITHM_SHA1) {
            return Recovery.Failed(OdaFailureReason.UNSUPPORTED_ALGORITHM, "hash algorithm ${hex(r[2])}")
        }

        // Layout: [2] hash alg, [3] ICC dynamic data length L, [4 .. 4+L-1] ICC dynamic data,
        // then BB padding up to the hash. The dynamic data itself starts with the length of the
        // ICC Dynamic Number, then the number, then method-specific extras.
        val dynamicLength = r[3].toInt() and 0xFF
        if (dynamicLength < 1 || 4 + dynamicLength > n - 21) {
            return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "ICC dynamic data length $dynamicLength")
        }
        val hashInput = r.copyOfRange(1, n - 21) + terminalDynamicData
        if (!sha1(hashInput).contentEquals(r.copyOfRange(n - 21, n - 1))) {
            return Recovery.Failed(OdaFailureReason.HASH_MISMATCH, "dynamic data hash")
        }

        val dynamic = r.copyOfRange(4, 4 + dynamicLength)
        val numberLength = dynamic[0].toInt() and 0xFF
        if (numberLength < 2 || numberLength > 8 || 1 + numberLength > dynamic.size) {
            return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "ICC dynamic number length $numberLength")
        }
        return Recovery.Ok(
            DynamicAuthentication(
                iccDynamicNumberHex = Hex.encode(dynamic, 1, numberLength),
                trailingDynamicData = dynamic.copyOfRange(1 + numberLength, dynamic.size),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Shared checks
    // ---------------------------------------------------------------------------------------

    /** Header, trailer, format and minimum length; null when all four are right. */
    private fun frame(r: ByteArray, expectedFormat: Int, what: String): Recovery.Failed? {
        if (r.size < HASH_LENGTH + 4) {
            return Recovery.Failed(OdaFailureReason.LENGTH_MISMATCH, "$what recovered to only ${r.size} bytes")
        }
        if (r[0].toInt() and 0xFF != HEADER) {
            return Recovery.Failed(OdaFailureReason.BAD_HEADER, "$what header ${hex(r[0])}")
        }
        if (r[r.size - 1].toInt() and 0xFF != TRAILER) {
            return Recovery.Failed(OdaFailureReason.BAD_TRAILER, "$what trailer ${hex(r[r.size - 1])}")
        }
        if (r[1].toInt() and 0xFF != expectedFormat) {
            return Recovery.Failed(OdaFailureReason.BAD_FORMAT, "$what format ${hex(r[1])}")
        }
        return null
    }

    /**
     * A key longer than the certificate can hold spills into the remainder tag; a shorter one is
     * padded inside the certificate with BB bytes. Book 2 sections 6.3 and 6.4, step 9.
     */
    private fun assembleModulus(
        r: ByteArray,
        leftmostOffset: Int,
        leftmostLength: Int,
        keyLength: Int,
        remainder: ByteArray?,
    ): ByteArray? {
        if (keyLength <= 0) return null
        if (keyLength <= leftmostLength) {
            return r.copyOfRange(leftmostOffset, leftmostOffset + keyLength)
        }
        val needed = keyLength - leftmostLength
        if (remainder == null || remainder.size != needed) return null
        return r.copyOfRange(leftmostOffset, leftmostOffset + leftmostLength) + remainder
    }

    private fun expiryFromMmyy(r: ByteArray, offset: Int): LocalDate? {
        val digits = r.copyOfRange(offset, offset + 2).cnToDigitsOrNull() ?: return null
        if (digits.length != 4) return null
        val month = digits.substring(0, 2).toIntOrNull() ?: return null
        val year = digits.substring(2, 4).toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return YearMonth.of(2000 + year, month).atEndOfMonth()
    }

    private fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    private fun hex(b: Byte): String = Hex.encode(byteArrayOf(b))

    /** Compares without materialising the PAN view into a String. */
    private fun CharSequence.sameDigitsAs(other: String): Boolean =
        length == other.length && indices.all { this[it] == other[it] }
}
