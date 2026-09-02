package com.softpos.emv.terminal

import com.softpos.emv.util.Hex

/**
 * The reader-side amount limits of EMV Contactless Book A section 5.5 and Book B section 3.1.
 *
 * All amounts are in minor units of the transaction currency. Null means "not present", which
 * Book B treats as "no limit of that kind" - a production profile has all three set by the
 * acquirer per scheme and per country, and they change with local regulation.
 */
data class ReaderLimits(
    /**
     * Reader Contactless Transaction Limit. An amount at or above it may not be taken over the
     * contactless interface at all; Book B 3.1.1.7 sets Contactless Application Not Allowed.
     */
    val contactlessTransactionLimitMinor: Long? = null,

    /**
     * Reader CVM Required Limit. An amount at or above it needs cardholder verification;
     * Book B 3.1.1.10 sets the CVM Required bit in the TTQ.
     */
    val cvmRequiredLimitMinor: Long? = null,

    /**
     * Reader Contactless Floor Limit. An amount strictly above it must go online; Book B
     * 3.1.1.8 sets the Online Cryptogram Required bit in the TTQ.
     */
    val contactlessFloorLimitMinor: Long? = null,

    /** Book B 3.1.1.5: whether a zero-amount transaction (an account verification) is allowed. */
    val zeroAmountAllowed: Boolean = true,
) {
    init {
        require((contactlessTransactionLimitMinor ?: 0) >= 0) { "transaction limit must not be negative" }
        require((cvmRequiredLimitMinor ?: 0) >= 0) { "CVM required limit must not be negative" }
        require((contactlessFloorLimitMinor ?: 0) >= 0) { "floor limit must not be negative" }
    }
}

/**
 * Entry Point Pre-Processing Indicators, Book B section 3.1.1.
 *
 * @param ttq the Terminal Transaction Qualifiers to actually send, i.e. the configured copy with
 *   the two transaction-specific bits applied.
 */
class PreProcessingIndicators(
    val contactlessApplicationNotAllowed: Boolean,
    val zeroAmount: Boolean,
    val cvmRequiredLimitExceeded: Boolean,
    val floorLimitExceeded: Boolean,
    val ttq: ByteArray,
) {
    /** TTQ byte 2 b7. */
    val cvmRequired: Boolean get() = ttq.size >= 2 && (ttq[1].toInt() and TTQ_CVM_REQUIRED) != 0

    /** TTQ byte 2 b8. */
    val onlineCryptogramRequired: Boolean
        get() = ttq.size >= 2 && (ttq[1].toInt() and TTQ_ONLINE_CRYPTOGRAM_REQUIRED) != 0

    val ttqHex: String get() = Hex.encode(ttq)

    fun describe(): String = buildString {
        append("TTQ ").append(ttqHex)
        if (contactlessApplicationNotAllowed) append(", contactless not allowed")
        if (zeroAmount) append(", zero amount")
        if (floorLimitExceeded) append(", floor limit exceeded")
        if (cvmRequiredLimitExceeded) append(", CVM required limit exceeded")
    }

    companion object {
        const val TTQ_ONLINE_CRYPTOGRAM_REQUIRED = 0x80
        const val TTQ_CVM_REQUIRED = 0x40

        /** TTQ byte 1 b4: offline-only reader. */
        const val TTQ_OFFLINE_ONLY_READER = 0x08
    }
}

/**
 * Entry Point pre-processing, EMV Contactless Book B section 3.1.1, for one transaction.
 *
 * This runs before the card is polled. It exists so a terminal never starts a contactless
 * exchange it will have to abandon, and so the card learns from the TTQ whether it must produce
 * an online cryptogram or expect cardholder verification.
 */
object EntryPointPreProcessing {

    fun run(amountMinor: Long, limits: ReaderLimits?, configuredTtq: ByteArray): PreProcessingIndicators {
        require(amountMinor >= 0) { "amount must not be negative" }

        // 3.1.1.3: start from the configured TTQ with both transaction-specific bits clear.
        val ttq = configuredTtq.copyOf()
        if (ttq.size >= 2) {
            ttq[1] = (ttq[1].toInt() and
                (PreProcessingIndicators.TTQ_ONLINE_CRYPTOGRAM_REQUIRED or PreProcessingIndicators.TTQ_CVM_REQUIRED).inv()).toByte()
        }
        val offlineOnlyReader = ttq.isNotEmpty() && (ttq[0].toInt() and PreProcessingIndicators.TTQ_OFFLINE_ONLY_READER) != 0

        var notAllowed = false
        var zeroAmount = false
        var floorExceeded = false
        var cvmExceeded = false

        if (limits != null) {
            // 3.1.1.5 - zero amount is either an account verification or a refusal.
            if (amountMinor == 0L) {
                if (limits.zeroAmountAllowed) zeroAmount = true else notAllowed = true
            }

            // 3.1.1.7 - at or above the transaction limit the contactless interface is off limits.
            limits.contactlessTransactionLimitMinor?.let { if (amountMinor >= it) notAllowed = true }

            // 3.1.1.8 - strictly above the floor limit means an online cryptogram.
            limits.contactlessFloorLimitMinor?.let { if (amountMinor > it) floorExceeded = true }

            // 3.1.1.10 - at or above the CVM limit means the cardholder must be verified.
            limits.cvmRequiredLimitMinor?.let { if (amountMinor >= it) cvmExceeded = true }
        }

        if (ttq.size >= 2) {
            var byte2 = ttq[1].toInt() and 0xFF
            // An online-capable reader takes a zero-amount transaction online (3.1.1.5); an
            // offline-only one cannot, and its TTQ already says so.
            if (floorExceeded || (zeroAmount && !offlineOnlyReader)) {
                byte2 = byte2 or PreProcessingIndicators.TTQ_ONLINE_CRYPTOGRAM_REQUIRED
            }
            if (cvmExceeded) byte2 = byte2 or PreProcessingIndicators.TTQ_CVM_REQUIRED
            ttq[1] = byte2.toByte()
        }

        return PreProcessingIndicators(
            contactlessApplicationNotAllowed = notAllowed,
            zeroAmount = zeroAmount,
            cvmRequiredLimitExceeded = cvmExceeded,
            floorLimitExceeded = floorExceeded,
            ttq = ttq,
        )
    }
}
