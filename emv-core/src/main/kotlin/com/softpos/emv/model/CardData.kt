package com.softpos.emv.model

import com.softpos.emv.cvm.CvmDecision
import com.softpos.emv.cvm.CvmOutcome
import com.softpos.emv.oda.CardAuthentication
import com.softpos.emv.oda.OdaResult
import com.softpos.emv.oda.OdaSkipReason
import com.softpos.emv.terminal.PreProcessingIndicators
import com.softpos.emv.tlv.TlvDatabase
import com.softpos.emv.util.Hex
import java.time.LocalDate

/** One entry of the candidate list built from the PPSE, before an application is selected. */
data class CardCandidate(
    val aid: ByteArray,
    val label: String?,
    val preferredName: String?,
    /**
     * Application Priority Indicator (tag 87). Bits b4-b1 hold the priority, 1 being highest;
     * b8 set means the cardholder must confirm before the application is used.
     */
    val priority: Int?,
    val registered: RegisteredAid?,
) {
    val aidHex: String get() = Hex.encode(aid)

    val scheme: CardScheme get() = registered?.scheme ?: CardScheme.UNKNOWN

    val kernel: EmvKernel get() = registered?.kernel ?: EmvKernel.UNSUPPORTED

    /** Missing priority sorts last, per EMV 4.4 Book 1 section 12.4. */
    val sortKey: Int get() = priority?.and(0x0F)?.takeIf { it != 0 } ?: Int.MAX_VALUE

    val requiresCardholderConfirmation: Boolean get() = ((priority ?: 0) and 0x80) != 0

    val displayName: String get() = preferredName ?: label ?: registered?.label ?: aidHex

    override fun equals(other: Any?): Boolean = other is CardCandidate && other.aidHex == aidHex

    override fun hashCode(): Int = aidHex.hashCode()
}

/** Application Interchange Profile, byte 1. EMV 4.4 Book 3, Annex C1. */
object AipBits {
    const val SDA_SUPPORTED = 0x40
    const val DDA_SUPPORTED = 0x20
    const val CVM_SUPPORTED = 0x10
    const val TERMINAL_RISK_MANAGEMENT = 0x08
    const val ISSUER_AUTHENTICATION_SUPPORTED = 0x04
    const val CDA_SUPPORTED = 0x01
}

/**
 * Everything read from the card, including elements that identify the cardholder.
 *
 * This type is the "in memory, raw" side of the boundary the project brief draws. It is
 * [AutoCloseable] and every consumer is expected to wrap it in `use { }`. Nothing of this type may
 * be persisted, logged or handed outside the SDK; call [redact] and pass the result instead.
 */
class RawCardData(
    val aid: ByteArray,
    val applicationLabel: String?,
    val preferredName: String?,
    val scheme: CardScheme,
    val kernel: EmvKernel,
    val pan: Pan?,
    val panSequenceNumber: Int?,
    val expiry: ExpiryDate?,
    val cardholderName: String?,
    val track2: Track2Data?,
    val aip: ByteArray?,
    val afl: List<AflEntry>,
    val tlv: TlvDatabase,
    /** Offline data authentication outcome; see [com.softpos.emv.oda.OfflineDataAuthentication]. */
    val authentication: OdaResult = OdaResult.NotPerformed(OdaSkipReason.DISABLED),
    /** What cardholder verification the card asked for; nothing is performed here. */
    val cvm: CvmDecision = CvmDecision.NotEvaluated,
    /** Entry Point pre-processing indicators for the amount this read was made for. */
    val preProcessing: PreProcessingIndicators? = null,
) : AutoCloseable {

    val aidHex: String get() = Hex.encode(aid)

    private fun aipByte1Has(mask: Int): Boolean? = aip?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and mask) != 0 }

    /** AIP byte 1 b7 - the card supports Static Data Authentication. */
    val sdaSupported: Boolean? get() = aipByte1Has(AipBits.SDA_SUPPORTED)

    /** AIP byte 1 b6 - the card supports Dynamic Data Authentication. */
    val ddaSupported: Boolean? get() = aipByte1Has(AipBits.DDA_SUPPORTED)

    /** AIP byte 1 b5 - "Cardholder verification is supported". */
    val cvmSupported: Boolean? get() = aipByte1Has(AipBits.CVM_SUPPORTED)

    /** AIP byte 1 b4 - "Terminal risk management is to be performed". */
    val terminalRiskManagementRequested: Boolean? get() = aipByte1Has(AipBits.TERMINAL_RISK_MANAGEMENT)

    /** AIP byte 1 b1 - the card supports Combined DDA / AC generation. */
    val cdaSupported: Boolean? get() = aipByte1Has(AipBits.CDA_SUPPORTED)

    fun redact(
        policy: MaskPolicy = MaskPolicy.LAST_4,
        today: LocalDate = LocalDate.now(),
    ): RedactedCard = RedactedCard(
        aidHex = aidHex,
        scheme = scheme,
        kernel = kernel,
        applicationLabel = preferredName ?: applicationLabel,
        maskedPan = pan?.masked(policy),
        panLast4 = pan?.last4,
        panSequenceNumber = panSequenceNumber,
        expiry = expiry?.toString(),
        expired = expiry?.isExpiredOn(today),
        panLuhnValid = pan?.isLuhnValid(),
        cardholderNamePresent = !cardholderName.isNullOrBlank(),
        recordCount = afl.sumOf { it.recordCount },
        authentication = authentication.summary(),
        authenticationDetail = authentication.describe(),
        cvm = cvm.outcome,
        cvmDetail = cvm.detail,
        cvmRequiredByReader = preProcessing?.cvmRequired ?: false,
        onlineCryptogramRequired = preProcessing?.onlineCryptogramRequired ?: false,
    )

    /**
     * Zeroes the PAN, track data and every sensitive TLV value.
     *
     * Known limitation: [cardholderName] and [applicationLabel] are `String`s, so their contents
     * cannot be overwritten on the JVM and remain until garbage collection.
     * TODO: move the cardholder name onto a CharArray too if this prototype ever handles data
     *  belonging to anyone other than the developer's own test cards.
     */
    override fun close() {
        track2?.close()
        pan?.close()
        tlv.wipeSensitive()
    }

    override fun toString(): String = "RawCardData(${scheme.displayName}, ${pan?.masked() ?: "no PAN"})"
}

/**
 * The only card representation allowed to leave the SDK, reach the UI or be written to disk.
 *
 * Every field here is either non-identifying or already reduced. There is deliberately no
 * cardholder name, no track data and no full PAN - [cardholderNamePresent] records that a name was
 * on the card without carrying it.
 */
data class RedactedCard(
    val aidHex: String,
    val scheme: CardScheme,
    val kernel: EmvKernel,
    val applicationLabel: String?,
    val maskedPan: String?,
    val panLast4: String?,
    val panSequenceNumber: Int?,
    /** Formatted `yyyy-MM`. */
    val expiry: String?,
    val expired: Boolean?,
    val panLuhnValid: Boolean?,
    val cardholderNamePresent: Boolean,
    val recordCount: Int,
    val authentication: CardAuthentication = CardAuthentication.NOT_PERFORMED,
    val authenticationDetail: String = "",
    val cvm: CvmOutcome = CvmOutcome.NOT_EVALUATED,
    val cvmDetail: String = "",
    /** The reader's own CVM Required Limit was reached (TTQ byte 2 b7 was set on the way out). */
    val cvmRequiredByReader: Boolean = false,
    /** The reader's floor limit was exceeded (TTQ byte 2 b8 was set on the way out). */
    val onlineCryptogramRequired: Boolean = false,
) {
    val displayName: String get() = applicationLabel ?: scheme.displayName
}
