package com.softpos.emv.terminal

import com.softpos.emv.model.EmvKernel
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.Tag
import com.softpos.emv.tlv.TagValueSource
import com.softpos.emv.util.Hex
import com.softpos.emv.util.longToBcd
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDateTime

/**
 * Terminal-side configuration used to answer a card's PDOL.
 *
 * Every default below describes an offline-only prototype reader that performs no cardholder
 * verification and no offline data authentication. They are honest about what this code does, not
 * copied from a production terminal profile.
 *
 * TODO: before these values are used with anything other than test cards or the developer's own
 *  cards, they must be replaced with a profile agreed with an acquirer. In particular 9F1A, 5F2A,
 *  9F15, 9F16 and 9F1C are merchant-specific and 9F09 is kernel- and version-specific.
 */
data class TerminalProfile(
    /** Terminal Country Code (9F1A), `n3` - ISO 3166-1 numeric. Default 0840 = United States. */
    val countryCode: String = "0840",

    /** Transaction Currency Code (5F2A), `n3` - ISO 4217 numeric. Default 0840 = USD. */
    val currencyCode: String = "0840",

    /** Number of minor units in one major unit, used only for display and receipts. */
    val currencyExponent: Int = 2,

    /**
     * Terminal Type (9F35), `n2`. 0x23 = attended, operated by the merchant, offline only.
     * EMV 4.4 Book 4, Annex A1.
     */
    val terminalType: Int = 0x23,

    /**
     * Terminal Capabilities (9F33), three bytes (EMV 4.4 Book 4, Annex A2):
     *  - byte 1 card data input : 0x20 - IC with contacts. There is no contactless bit in 9F33;
     *    Kernel 2 signals contactless separately through DF8117.
     *  - byte 2 CVM             : 0x08 - "no CVM required". This reader performs no cardholder
     *    verification at all, so no PIN or signature bit is claimed.
     *  - byte 3 security        : 0x00 here. SDA (b8) and DDA (b7) are switched on per read by
     *    [terminalCapabilities] when a CA public key table is loaded and authentication is
     *    enabled; CDA is never claimed because it needs GENERATE AC.
     */
    val terminalCapabilities: String = "200800",

    /**
     * Additional Terminal Capabilities (9F40), five bytes (EMV 4.4 Book 4, Annex A3).
     * Byte 1 0x60 = goods and services. Remaining bytes advertise the data the terminal can
     * present; kept conservative.
     */
    val additionalTerminalCapabilities: String = "6000000000",

    /**
     * Terminal Transaction Qualifiers (9F66), four bytes, Visa Kernel 3 (EMV Contactless Book C-3):
     *  - byte 1 : 0x28 = qVSDC supported (b6) + offline-only reader (b4).
     *             MSD (b8), contact chip (b5), online PIN (b3) and signature (b2) are all off.
     *  - byte 2 : 0x00 = online cryptogram not required, CVM not required.
     *  - byte 3 : 0x00 = no issuer update processing, no consumer device CVM.
     *  - byte 4 : RFU.
     */
    val terminalTransactionQualifiers: String = "28000000",

    /**
     * Reader amount limits for Entry Point pre-processing (EMV Contactless Book B section 3.1).
     * Null means no limits: every amount is accepted and the TTQ goes out as configured.
     */
    val readerLimits: ReaderLimits? = null,

    /** Application Version Number (9F09) per kernel. */
    val visaApplicationVersion: String = "0096",
    val mastercardApplicationVersion: String = "0002",

    val merchantName: String = "SOFTPOS PROTOTYPE",
    val merchantCategoryCode: String = "5999",
    val merchantIdentifier: String = "SOFTPOSPROTOTYPE",
    val terminalIdentification: String = "SFTPOS01",
    val acquirerIdentifier: String = "000000000001",

    val clock: Clock = Clock.systemDefaultZone(),
    val random: SecureRandom = SecureRandom(),
) {

    /**
     * Builds the value source for one transaction.
     *
     * @param amountMinor Amount, Authorised (9F02) in minor units.
     * @param otherAmountMinor Amount, Other (9F03) - cashback. Always zero in this prototype.
     * @param transactionType Transaction Type (9C). 0x00 = purchase of goods and services.
     * @param ttq the Terminal Transaction Qualifiers to send, normally the output of Entry Point
     *   pre-processing; null sends the configured value unchanged.
     * @param offlineDataAuthentication whether this read will verify SDA and DDA, which decides
     *   what Terminal Capabilities byte 3 may claim.
     */
    fun tagSource(
        amountMinor: Long,
        kernel: EmvKernel,
        otherAmountMinor: Long = 0,
        transactionType: Int = 0x00,
        sequenceCounter: Int = 1,
        ttq: ByteArray? = null,
        offlineDataAuthentication: Boolean = false,
    ): TagValueSource {
        val now = LocalDateTime.now(clock)
        val date = longToBcd(
            (now.year % 100) * 10000L + now.monthValue * 100L + now.dayOfMonth,
            3,
        )
        val time = longToBcd(now.hour * 10000L + now.minute * 100L + now.second, 3)
        val unpredictable = ByteArray(4).also(random::nextBytes)

        val values: Map<Tag, ByteArray> = buildMap {
            put(EmvTags.AMOUNT_AUTHORISED, longToBcd(amountMinor, 6))
            put(EmvTags.AMOUNT_OTHER, longToBcd(otherAmountMinor, 6))
            put(EmvTags.TERMINAL_COUNTRY_CODE, Hex.decode(countryCode))
            put(EmvTags.TRANSACTION_CURRENCY_CODE, Hex.decode(currencyCode))
            put(EmvTags.TRANSACTION_DATE, date)
            put(EmvTags.TRANSACTION_TIME, time)
            put(EmvTags.TRANSACTION_TYPE, byteArrayOf(transactionType.toByte()))
            put(EmvTags.UNPREDICTABLE_NUMBER, unpredictable)
            put(EmvTags.TERMINAL_TYPE, byteArrayOf(terminalType.toByte()))
            put(EmvTags.TERMINAL_CAPABILITIES, terminalCapabilities(offlineDataAuthentication))
            put(EmvTags.ADDITIONAL_TERMINAL_CAPABILITIES, Hex.decode(additionalTerminalCapabilities))
            put(EmvTags.TERMINAL_TRANSACTION_QUALIFIERS, ttq ?: Hex.decode(terminalTransactionQualifiers))

            // Terminal Verification Results start clear; nothing in the read-only flow sets a bit.
            put(EmvTags.TVR, ByteArray(5))

            put(
                EmvTags.APPLICATION_VERSION_NUMBER_TERMINAL,
                Hex.decode(
                    when (kernel) {
                        EmvKernel.KERNEL_2 -> mastercardApplicationVersion
                        else -> visaApplicationVersion
                    },
                ),
            )

            put(EmvTags.MERCHANT_NAME_AND_LOCATION, merchantName.toByteArray(Charsets.US_ASCII))
            put(EmvTags.MERCHANT_CATEGORY_CODE, Hex.decode(merchantCategoryCode))
            put(EmvTags.MERCHANT_IDENTIFIER, merchantIdentifier.toByteArray(Charsets.US_ASCII))
            put(EmvTags.TERMINAL_IDENTIFICATION, terminalIdentification.toByteArray(Charsets.US_ASCII))
            put(EmvTags.ACQUIRER_IDENTIFIER, Hex.decode(acquirerIdentifier))

            // Point-of-Service Entry Mode (9F39): 07 = contactless integrated circuit card.
            put(EmvTags.POS_ENTRY_MODE, byteArrayOf(0x07))

            put(EmvTags.TRANSACTION_SEQUENCE_COUNTER, longToBcd(sequenceCounter.toLong(), 4))

            // Terminal Risk Management Data (9F1D) is Kernel 2 specific and issuer configurable.
            // TODO: derive from the real capability set once Kernel 2 behaviour is exercised
            //  against a physical Mastercard test card.
            put(EmvTags.TERMINAL_RISK_MANAGEMENT_DATA, ByteArray(8))
        }

        return TagValueSource { tag -> values[tag] }
    }

    /**
     * Terminal Capabilities (9F33) as sent. Byte 3 claims SDA (b8) and DDA (b7) only when this
     * read will actually verify them - a terminal must not advertise a check it will not make,
     * because the card and the issuer's risk parameters take the claim at face value.
     */
    fun terminalCapabilities(offlineDataAuthentication: Boolean): ByteArray {
        val bytes = Hex.decode(terminalCapabilities)
        if (offlineDataAuthentication && bytes.size >= 3) {
            bytes[2] = (bytes[2].toInt() or CAPABILITY_SDA or CAPABILITY_DDA).toByte()
        }
        return bytes
    }

    fun formatAmount(minorUnits: Long): String {
        if (currencyExponent == 0) return minorUnits.toString()
        val divisor = generateSequence(1L) { it * 10 }.elementAt(currencyExponent)
        val major = minorUnits / divisor
        val minor = minorUnits % divisor
        return "$major." + minor.toString().padStart(currencyExponent, '0')
    }

    private companion object {
        /** 9F33 byte 3 b8. */
        const val CAPABILITY_SDA = 0x80

        /** 9F33 byte 3 b7. */
        const val CAPABILITY_DDA = 0x40
    }
}
