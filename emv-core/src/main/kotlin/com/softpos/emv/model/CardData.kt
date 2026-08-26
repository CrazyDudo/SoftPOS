package com.softpos.emv.model

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
) : AutoCloseable {

    val aidHex: String get() = Hex.encode(aid)

    /**
     * Application Interchange Profile bit b7 of byte 1 - "Cardholder verification is supported".
     * Reported for diagnostics; no CVM processing happens in this prototype.
     */
    val cvmSupported: Boolean? get() = aip?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and 0x20) != 0 }

    /** AIP byte 1 b6 - "Terminal risk management is to be performed". */
    val terminalRiskManagementRequested: Boolean?
        get() = aip?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and 0x10) != 0 }

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
) {
    val displayName: String get() = applicationLabel ?: scheme.displayName
}
