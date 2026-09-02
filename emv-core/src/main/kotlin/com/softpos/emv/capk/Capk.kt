package com.softpos.emv.capk

import com.softpos.emv.util.Hex
import com.softpos.emv.util.toHex
import java.security.MessageDigest
import java.time.LocalDate

enum class CapkHashAlgorithm(val code: Int) {
    SHA1(0x01),
    ;

    companion object {
        fun fromCode(code: Int): CapkHashAlgorithm? = entries.firstOrNull { it.code == code }
    }
}

enum class CapkSignatureAlgorithm(val code: Int) {
    RSA(0x01),
    ;

    companion object {
        fun fromCode(code: Int): CapkSignatureAlgorithm? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One Certification Authority Public Key, as published in a scheme's CA public key bulletin.
 *
 * @param rid Registered Application Provider Identifier, five bytes, e.g. `A000000003` for Visa.
 * @param index Certification Authority Public Key Index. A card names the key it was personalised
 *   against in tag 8F, and the terminal looks it up by `(rid, index)`.
 * @param modulus RSA modulus, 1024 to 1984 bits in EMV.
 * @param exponent RSA public exponent, in practice `03` or `010001`.
 * @param checksum the 20-byte SHA-1 published alongside the key. Optional in this type, but a key
 *   loaded without one cannot be validated - see [CapkRegistry.verify].
 * @param expiry last day the key may be used. EMV bulletins give a month; the last day of that
 *   month is the usual interpretation.
 */
data class CapkEntry(
    val rid: String,
    val index: Int,
    val modulus: ByteArray,
    val exponent: ByteArray,
    val hashAlgorithm: CapkHashAlgorithm = CapkHashAlgorithm.SHA1,
    val signatureAlgorithm: CapkSignatureAlgorithm = CapkSignatureAlgorithm.RSA,
    val checksum: ByteArray? = null,
    val expiry: LocalDate? = null,
    val label: String? = null,
) {

    init {
        require(rid.length == 10) { "RID must be five bytes of hex, got '$rid'" }
        require(index in 0..0xFF) { "CA public key index out of range: $index" }
        require(modulus.isNotEmpty()) { "modulus must not be empty" }
        require(exponent.isNotEmpty()) { "exponent must not be empty" }
        require(checksum == null || checksum.size == 20) {
            "CA public key check sum is a 20-byte SHA-1, got ${checksum?.size}"
        }
    }

    val modulusBits: Int get() = modulus.size * 8

    val key: CapkKey get() = CapkKey(rid.uppercase(), index)

    fun isExpiredOn(date: LocalDate): Boolean = expiry != null && date.isAfter(expiry)

    /**
     * SHA-1 over `RID || index || modulus || exponent`, which is how EMV 4.4 Book 2 section 11.2.2
     * defines the CA Public Key Check Sum. Comparing this against the published value is what
     * detects a mistyped or truncated modulus.
     */
    fun computeChecksum(): ByteArray = MessageDigest.getInstance("SHA-1").run {
        update(Hex.decode(rid))
        update(byteArrayOf(index.toByte()))
        update(modulus)
        update(exponent)
        digest()
    }

    /** Null when no published checksum is attached, otherwise whether the key matches it. */
    fun checksumMatches(): Boolean? = checksum?.let { it.contentEquals(computeChecksum()) }

    override fun equals(other: Any?): Boolean = other is CapkEntry &&
        other.rid.equals(rid, ignoreCase = true) &&
        other.index == index &&
        other.modulus.contentEquals(modulus) &&
        other.exponent.contentEquals(exponent)

    override fun hashCode(): Int =
        (rid.uppercase().hashCode() * 31 + index) * 31 + modulus.contentHashCode()

    /** Never renders the modulus; a CAPK dump is noise in a log. */
    override fun toString(): String =
        "CapkEntry($rid idx=$index ${modulusBits}bit exp=${exponent.toHex()} expiry=$expiry)"
}

data class CapkKey(val rid: String, val index: Int)

sealed interface CapkValidation {
    data object Ok : CapkValidation
    data class ChecksumMismatch(val expected: String, val actual: String) : CapkValidation
    data object NoChecksumPublished : CapkValidation
    data class Expired(val expiry: LocalDate) : CapkValidation
    data class UnsupportedModulusLength(val bits: Int) : CapkValidation
}

/**
 * Certification Authority Public Key table.
 *
 * ## What loading it does
 *
 * A CAPK table is the input to offline data authentication - SDA, DDA and CDA (EMV 4.4 Book 2,
 * sections 5, 6 and 6.6). With keys loaded, [com.softpos.emv.flow.EmvReadFlow] verifies SDA and
 * fast DDA against them: the issuer certificate (`90`) is recovered with the CA key the card names
 * in `8F`, the ICC certificate (`9F46`) with the issuer key, and then either the Signed Static
 * Application Data (`93`) or the Signed Dynamic Application Data (`9F4B`) is checked. The outcome
 * is on [com.softpos.emv.model.RawCardData.authentication]. CDA is out of reach because its
 * signature covers a GENERATE AC response, which this project never requests.
 *
 * With the table empty - the default - every read reports authentication as not performed, and a
 * card that reads successfully has not been proven genuine.
 *
 * ## Why no keys ship with this project
 *
 * A CAPK modulus is 1024 to 1984 bits - up to 496 hex characters with no internal structure a human
 * can sanity check. Shipping values that were not copied from an authoritative bulletin would give
 * a table that looks official and silently fails, or worse, appears to work. Real keys come from:
 *
 *  - the scheme's own CA public key bulletin (Visa, Mastercard, Amex, JCB, Discover and UnionPay
 *    each publish and rotate their own), distributed to acquirers, or
 *  - EMVCo, for the test keys used with EMVCo test cards.
 *
 * Load them with [CapkTextParser] and let [verify] check each one against its published checksum
 * before use. That check is the whole point: it is what catches a bad transcription.
 */
class CapkRegistry(entries: List<CapkEntry> = emptyList()) {

    private val byKey: Map<CapkKey, CapkEntry> = entries.associateBy { it.key }

    val entries: List<CapkEntry> get() = byKey.values.toList()

    val size: Int get() = byKey.size

    fun isEmpty(): Boolean = byKey.isEmpty()

    /** Looks up the key a card named in tag 8F. */
    fun find(rid: String, index: Int): CapkEntry? = byKey[CapkKey(rid.uppercase(), index)]

    fun find(rid: ByteArray, index: Int): CapkEntry? = find(rid.toHex(), index)

    fun forRid(rid: String): List<CapkEntry> =
        byKey.values.filter { it.rid.equals(rid, ignoreCase = true) }

    operator fun plus(other: CapkRegistry): CapkRegistry = CapkRegistry(entries + other.entries)

    fun verify(entry: CapkEntry, today: LocalDate = LocalDate.now()): CapkValidation = when {
        entry.modulusBits !in MIN_MODULUS_BITS..MAX_MODULUS_BITS ->
            CapkValidation.UnsupportedModulusLength(entry.modulusBits)

        entry.isExpiredOn(today) -> CapkValidation.Expired(entry.expiry!!)
        entry.checksum == null -> CapkValidation.NoChecksumPublished
        entry.checksumMatches() == false -> CapkValidation.ChecksumMismatch(
            expected = entry.checksum.toHex(),
            actual = entry.computeChecksum().toHex(),
        )

        else -> CapkValidation.Ok
    }

    /** Every entry that is not [CapkValidation.Ok], paired with why. */
    fun validateAll(today: LocalDate = LocalDate.now()): List<Pair<CapkEntry, CapkValidation>> =
        entries.map { it to verify(it, today) }.filter { (_, result) -> result != CapkValidation.Ok }

    companion object {
        /** EMV 4.4 Book 2 section 11.2.1 bounds the CA public key modulus. */
        const val MIN_MODULUS_BITS = 1024
        const val MAX_MODULUS_BITS = 1984

        /**
         * Deliberately empty. See the class documentation for where real keys come from and why
         * none are hardcoded here.
         */
        val Empty = CapkRegistry()
    }
}
