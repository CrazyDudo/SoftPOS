package com.softpos.emv.cvm

import com.softpos.emv.model.EmvKernel
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.TlvDatabase
import com.softpos.emv.util.Hex

/** CVM codes, EMV 4.4 Book 3 Annex C3, bits b6-b1 of the first rule byte. */
enum class CvmMethod(val code: Int, val label: String) {
    FAIL(0x00, "Fail CVM processing"),
    PLAINTEXT_PIN_ICC(0x01, "Plaintext PIN verified by ICC"),
    ENCIPHERED_PIN_ONLINE(0x02, "Enciphered PIN verified online"),
    PLAINTEXT_PIN_ICC_AND_SIGNATURE(0x03, "Plaintext PIN by ICC and signature"),
    ENCIPHERED_PIN_ICC(0x04, "Enciphered PIN verified by ICC"),
    ENCIPHERED_PIN_ICC_AND_SIGNATURE(0x05, "Enciphered PIN by ICC and signature"),
    SIGNATURE(0x1E, "Signature (paper)"),
    NO_CVM_REQUIRED(0x1F, "No CVM required"),
    NOT_AVAILABLE(0x3F, "Not available"),
    UNKNOWN(-1, "Unknown or proprietary CVM"),
    ;

    companion object {
        fun fromCode(code: Int): CvmMethod = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** CVM condition codes, Book 3 Annex C3, second rule byte. */
enum class CvmCondition(val code: Int, val label: String) {
    ALWAYS(0x00, "Always"),
    UNATTENDED_CASH(0x01, "If unattended cash"),
    NOT_CASH_OR_CASHBACK(0x02, "If not unattended cash and not manual cash and not purchase with cashback"),
    TERMINAL_SUPPORTS_CVM(0x03, "If terminal supports the CVM"),
    MANUAL_CASH(0x04, "If manual cash"),
    PURCHASE_WITH_CASHBACK(0x05, "If purchase with cashback"),
    UNDER_X(0x06, "If transaction is in the application currency and is under X value"),
    OVER_X(0x07, "If transaction is in the application currency and is over X value"),
    UNDER_Y(0x08, "If transaction is in the application currency and is under Y value"),
    OVER_Y(0x09, "If transaction is in the application currency and is over Y value"),
    UNKNOWN(-1, "Unknown or proprietary condition"),
    ;

    companion object {
        fun fromCode(code: Int): CvmCondition = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class CvmRule(
    val method: CvmMethod,
    val condition: CvmCondition,
    /** Rule byte 1 b7: when this CVM fails, move on to the next rule instead of failing outright. */
    val applyNextIfUnsuccessful: Boolean,
    val rawHex: String,
)

/**
 * The CVM List, tag 8E (Book 3 section 10.5 and Annex C3): two four-byte amounts X and Y in the
 * application currency, then two-byte rules in priority order.
 */
class CvmList(val amountX: Long, val amountY: Long, val rules: List<CvmRule>) {

    companion object {
        fun parse(bytes: ByteArray): CvmList? {
            if (bytes.size < 8 || (bytes.size - 8) % 2 != 0) return null
            val x = bytes.copyOfRange(0, 4).toUnsignedLong()
            val y = bytes.copyOfRange(4, 8).toUnsignedLong()
            val rules = (8 until bytes.size step 2).map { i ->
                val first = bytes[i].toInt() and 0xFF
                val second = bytes[i + 1].toInt() and 0xFF
                CvmRule(
                    method = CvmMethod.fromCode(first and 0x3F),
                    condition = CvmCondition.fromCode(second),
                    applyNextIfUnsuccessful = (first and 0x40) != 0,
                    rawHex = Hex.encode(bytes, i, 2),
                )
            }
            return CvmList(x, y, rules)
        }

        private fun ByteArray.toUnsignedLong(): Long {
            var acc = 0L
            for (b in this) acc = (acc shl 8) or (b.toLong() and 0xFF)
            return acc
        }
    }
}

/** Card Transaction Qualifiers, tag 9F6C, EMV Contactless Book C-3. */
class CardTransactionQualifiers(bytes: ByteArray) {
    private val byte1 = bytes.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
    private val byte2 = bytes.getOrNull(1)?.toInt()?.and(0xFF) ?: 0

    val onlinePinRequired: Boolean get() = byte1 and 0x80 != 0
    val signatureRequired: Boolean get() = byte1 and 0x40 != 0
    val goOnlineIfOdaFails: Boolean get() = byte1 and 0x20 != 0
    val switchInterfaceIfOdaFails: Boolean get() = byte1 and 0x10 != 0
    val goOnlineIfApplicationExpired: Boolean get() = byte1 and 0x08 != 0
    val consumerDeviceCvmPerformed: Boolean get() = byte2 and 0x80 != 0
    val issuerUpdateSupported: Boolean get() = byte2 and 0x40 != 0

    val hex: String = Hex.encode(bytes)
}

enum class CvmOutcome(val label: String) {
    NO_CVM_REQUIRED("No CVM required"),
    ONLINE_PIN_REQUIRED("Online PIN required"),
    OFFLINE_PIN_REQUIRED("Offline PIN required"),
    SIGNATURE_REQUIRED("Signature required"),
    CONSUMER_DEVICE_CVM_PERFORMED("Verified on the consumer's device"),

    /** The card wants a method this reader does not offer. A real terminal would decline or go online. */
    UNSUPPORTED("CVM required but not supported by this reader"),

    /** A rule said to fail, or the list ran out without a usable rule. */
    FAILED("CVM processing failed"),

    /** The card gave nothing to evaluate, or the kernel is not one this code understands. */
    NOT_EVALUATED("Not evaluated"),
}

enum class CvmSource { CVM_LIST, CTQ, NONE }

data class CvmDecision(val outcome: CvmOutcome, val source: CvmSource, val detail: String) {
    companion object {
        val NotEvaluated = CvmDecision(CvmOutcome.NOT_EVALUATED, CvmSource.NONE, "nothing to evaluate")
    }
}

/**
 * Decides which cardholder verification the card is asking for. It does not perform any: this
 * reader has no PIN pad and takes no signature, so the decision is reported, not acted on.
 *
 * Kernel 3 (Visa) does not use the CVM List; the card states its requirement in the Card
 * Transaction Qualifiers. Kernel 2 (Mastercard) and the contact-style kernels use the CVM List
 * of Book 3 section 10.5.
 */
object CardholderVerification {

    fun decide(
        kernel: EmvKernel,
        db: TlvDatabase,
        amountMinor: Long,
        transactionCurrencyCode: String,
        terminalSupports: Set<CvmMethod> = setOf(CvmMethod.NO_CVM_REQUIRED),
    ): CvmDecision {
        val ctq = db[EmvTags.CARD_TRANSACTION_QUALIFIERS]
        if (kernel == EmvKernel.KERNEL_3 || (ctq != null && db[EmvTags.CVM_LIST] == null)) {
            return ctq?.let { fromCtq(CardTransactionQualifiers(it)) } ?: CvmDecision.NotEvaluated
        }
        val list = db[EmvTags.CVM_LIST]?.let { CvmList.parse(it) } ?: return CvmDecision.NotEvaluated
        val applicationCurrency = db[EmvTags.APPLICATION_CURRENCY_CODE]?.let { Hex.encode(it) }
        return fromCvmList(list, amountMinor, transactionCurrencyCode, applicationCurrency, terminalSupports)
    }

    fun fromCtq(ctq: CardTransactionQualifiers): CvmDecision {
        val outcome = when {
            ctq.consumerDeviceCvmPerformed -> CvmOutcome.CONSUMER_DEVICE_CVM_PERFORMED
            ctq.onlinePinRequired -> CvmOutcome.ONLINE_PIN_REQUIRED
            ctq.signatureRequired -> CvmOutcome.SIGNATURE_REQUIRED
            else -> CvmOutcome.NO_CVM_REQUIRED
        }
        return CvmDecision(outcome, CvmSource.CTQ, "CTQ ${ctq.hex}")
    }

    /**
     * Book 3 section 10.5: walk the rules in order, skip those whose condition does not hold,
     * and stop at the first whose method this terminal supports. An unsupported method fails the
     * list unless the rule says to carry on.
     */
    fun fromCvmList(
        list: CvmList,
        amountMinor: Long,
        transactionCurrencyCode: String,
        applicationCurrencyCode: String?,
        terminalSupports: Set<CvmMethod>,
    ): CvmDecision {
        val sameCurrency = applicationCurrencyCode != null &&
            applicationCurrencyCode.equals(transactionCurrencyCode, ignoreCase = true)

        for (rule in list.rules) {
            val applies = when (rule.condition) {
                CvmCondition.ALWAYS -> true
                // This reader does purchases only: no cash, no cashback, always attended.
                CvmCondition.NOT_CASH_OR_CASHBACK -> true
                CvmCondition.UNATTENDED_CASH, CvmCondition.MANUAL_CASH, CvmCondition.PURCHASE_WITH_CASHBACK -> false
                CvmCondition.TERMINAL_SUPPORTS_CVM -> rule.method in terminalSupports
                CvmCondition.UNDER_X -> sameCurrency && amountMinor < list.amountX
                CvmCondition.OVER_X -> sameCurrency && amountMinor > list.amountX
                CvmCondition.UNDER_Y -> sameCurrency && amountMinor < list.amountY
                CvmCondition.OVER_Y -> sameCurrency && amountMinor > list.amountY
                CvmCondition.UNKNOWN -> false
            }
            if (!applies) continue

            val detail = "rule ${rule.rawHex}: ${rule.method.label}, ${rule.condition.label.lowercase()}"
            if (rule.method == CvmMethod.FAIL) return CvmDecision(CvmOutcome.FAILED, CvmSource.CVM_LIST, detail)
            if (rule.method in terminalSupports) {
                return CvmDecision(outcomeFor(rule.method), CvmSource.CVM_LIST, detail)
            }
            if (!rule.applyNextIfUnsuccessful) {
                return CvmDecision(CvmOutcome.UNSUPPORTED, CvmSource.CVM_LIST, detail)
            }
        }
        return CvmDecision(CvmOutcome.FAILED, CvmSource.CVM_LIST, "no rule applied")
    }

    private fun outcomeFor(method: CvmMethod): CvmOutcome = when (method) {
        CvmMethod.NO_CVM_REQUIRED -> CvmOutcome.NO_CVM_REQUIRED
        CvmMethod.ENCIPHERED_PIN_ONLINE -> CvmOutcome.ONLINE_PIN_REQUIRED
        CvmMethod.PLAINTEXT_PIN_ICC, CvmMethod.ENCIPHERED_PIN_ICC,
        CvmMethod.PLAINTEXT_PIN_ICC_AND_SIGNATURE, CvmMethod.ENCIPHERED_PIN_ICC_AND_SIGNATURE,
        -> CvmOutcome.OFFLINE_PIN_REQUIRED

        CvmMethod.SIGNATURE -> CvmOutcome.SIGNATURE_REQUIRED
        CvmMethod.FAIL -> CvmOutcome.FAILED
        CvmMethod.NOT_AVAILABLE, CvmMethod.UNKNOWN -> CvmOutcome.UNSUPPORTED
    }
}
