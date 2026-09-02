package com.softpos.emv.oda

import com.softpos.emv.capk.CapkEntry
import com.softpos.emv.util.Hex
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec

/**
 * A throwaway EMV public key infrastructure: one CA, one issuer, one card, all generated when the
 * test runs. Nothing here is a real key, and the certificate encodings are hand-built from
 * EMV 4.4 Book 2 tables 5, 6, 13 and 17 so that the code under test is checked against the
 * specification rather than against itself.
 *
 * Every key uses the 65537 public exponent EMV permits alongside 3.
 */
class TestPki(
    caBits: Int = 1024,
    issuerBits: Int = 1024,
    iccBits: Int = 1024,
) {
    class Key(val modulus: ByteArray, val exponent: ByteArray, val privateExponent: ByteArray)

    val rid = "A000000003"
    val index = 0x92

    val ca: Key = generate(caBits)
    val issuer: Key = generate(issuerBits)
    val icc: Key = generate(iccBits)

    /** The CA key as a terminal would load it, checksum included so [com.softpos.emv.capk.CapkRegistry.verify] passes. */
    val capk: CapkEntry
        get() {
            val withoutChecksum = CapkEntry(rid, index, ca.modulus, ca.exponent, expiry = java.time.LocalDate.of(2035, 12, 31))
            return withoutChecksum.copy(checksum = withoutChecksum.computeChecksum())
        }

    // ---------------------------------------------------------------------------------------
    // Certificates
    // ---------------------------------------------------------------------------------------

    class Certificate(val certificate: ByteArray, val remainder: ByteArray?, val exponent: ByteArray)

    /** Book 2 table 6: issuer public key certificate signed by the CA. */
    fun issuerCertificate(pan: String, expiryMmyy: String = "1235", issuerIdentifierDigits: Int = 6): Certificate {
        val n = ca.modulus.size
        val leftmostLength = n - 36
        val leftmost = issuer.modulus.copyOfRange(0, minOf(leftmostLength, issuer.modulus.size))
        val remainder = if (issuer.modulus.size > leftmostLength) issuer.modulus.copyOfRange(leftmostLength, issuer.modulus.size) else null

        val body = java.io.ByteArrayOutputStream().apply {
            write(0x02)
            write(cn(pan.take(issuerIdentifierDigits), 4))
            write(Hex.decode(expiryMmyy))
            write(Hex.decode("000001"))
            write(0x01)
            write(0x01)
            write(issuer.modulus.size)
            write(issuer.exponent.size)
            write(leftmost)
            if (leftmost.size < leftmostLength) write(ByteArray(leftmostLength - leftmost.size) { 0xBB.toByte() })
        }.toByteArray()

        val hash = sha1(body + (remainder ?: ByteArray(0)) + issuer.exponent)
        val block = byteArrayOf(0x6A) + body + hash + byteArrayOf(0xBC.toByte())
        check(block.size == n) { "issuer certificate block is ${block.size}, expected $n" }
        return Certificate(EmvRsa.sign(block, ca.modulus, ca.privateExponent), remainder, issuer.exponent)
    }

    /** Book 2 table 13: ICC public key certificate signed by the issuer, over [staticData]. */
    fun iccCertificate(pan: String, staticData: ByteArray, expiryMmyy: String = "1235"): Certificate {
        val n = issuer.modulus.size
        val leftmostLength = n - 42
        val leftmost = icc.modulus.copyOfRange(0, minOf(leftmostLength, icc.modulus.size))
        val remainder = if (icc.modulus.size > leftmostLength) icc.modulus.copyOfRange(leftmostLength, icc.modulus.size) else null

        val body = java.io.ByteArrayOutputStream().apply {
            write(0x04)
            write(cn(pan, 10))
            write(Hex.decode(expiryMmyy))
            write(Hex.decode("000002"))
            write(0x01)
            write(0x01)
            write(icc.modulus.size)
            write(icc.exponent.size)
            write(leftmost)
            if (leftmost.size < leftmostLength) write(ByteArray(leftmostLength - leftmost.size) { 0xBB.toByte() })
        }.toByteArray()

        val hash = sha1(body + (remainder ?: ByteArray(0)) + icc.exponent + staticData)
        val block = byteArrayOf(0x6A) + body + hash + byteArrayOf(0xBC.toByte())
        check(block.size == n) { "ICC certificate block is ${block.size}, expected $n" }
        return Certificate(EmvRsa.sign(block, issuer.modulus, issuer.privateExponent), remainder, icc.exponent)
    }

    /** Book 2 table 5: Signed Static Application Data over [staticData]. */
    fun signedStaticData(staticData: ByteArray, dacHex: String = "0BAD"): ByteArray {
        val n = issuer.modulus.size
        val body = byteArrayOf(0x03, 0x01) + Hex.decode(dacHex) + ByteArray(n - 26) { 0xBB.toByte() }
        val hash = sha1(body + staticData)
        val block = byteArrayOf(0x6A) + body + hash + byteArrayOf(0xBC.toByte())
        check(block.size == n)
        return EmvRsa.sign(block, issuer.modulus, issuer.privateExponent)
    }

    /**
     * Book 2 table 17: Signed Dynamic Application Data. The ICC dynamic data is the dynamic number
     * preceded by its length, followed by whatever the method appends - fDDA version 01 appends
     * the CTQ.
     */
    fun signedDynamicData(
        iccDynamicNumberHex: String,
        trailingHex: String,
        terminalDynamicData: ByteArray,
    ): ByteArray {
        val n = icc.modulus.size
        val number = Hex.decode(iccDynamicNumberHex)
        val dynamic = byteArrayOf(number.size.toByte()) + number + Hex.decode(trailingHex)
        val body = byteArrayOf(0x05, 0x01, dynamic.size.toByte()) + dynamic +
            ByteArray(n - dynamic.size - 25) { 0xBB.toByte() }
        val hash = sha1(body + terminalDynamicData)
        val block = byteArrayOf(0x6A) + body + hash + byteArrayOf(0xBC.toByte())
        check(block.size == n)
        return EmvRsa.sign(block, icc.modulus, icc.privateExponent)
    }

    // ---------------------------------------------------------------------------------------

    private fun generate(bits: Int): Key {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4))
        val pair = generator.generateKeyPair()
        val public = pair.public as RSAPublicKey
        val private = pair.private as RSAPrivateKey
        return Key(
            modulus = unsigned(public.modulus, bits / 8),
            exponent = unsigned(public.publicExponent, 3),
            privateExponent = unsigned(private.privateExponent, bits / 8),
        )
    }

    private fun unsigned(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val start = if (raw.size > length) raw.size - length else 0
        val out = ByteArray(length)
        raw.copyInto(out, destinationOffset = length - (raw.size - start), startIndex = start)
        return out
    }

    /** Compressed numeric: digits left-justified, padded with F nibbles to [bytes]. */
    private fun cn(digits: String, bytes: Int): ByteArray = Hex.decode(digits.padEnd(bytes * 2, 'F'))

    private fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
}
