package com.softpos.emv.model

import com.softpos.emv.util.Hex
import com.softpos.emv.util.toHex

/**
 * An AID the terminal is willing to select.
 *
 * @param aidHex Registered Application Provider Identifier (5 bytes) plus the Proprietary
 *   Application Identifier Extension, e.g. `A0000000031010`.
 * @param partialSelectionAllowed whether a card ADF name that merely *starts with* [aidHex] should
 *   be accepted. EMV 4.4 Book 1 section 12.3.3 permits partial name selection; issuers commonly
 *   append a product extension such as `...1010 1213`.
 * @param verified whether this project has actually exercised the entry. Everything except the
 *   Visa and Mastercard families is unverified: the AID is correct, but no card of that brand has
 *   been read with it.
 */
data class RegisteredAid(
    val aidHex: String,
    val label: String,
    val scheme: CardScheme,
    val partialSelectionAllowed: Boolean = true,
    val verified: Boolean = false,
) {
    val bytes: ByteArray get() = Hex.decode(aidHex)
    val kernel: EmvKernel get() = scheme.kernel

    /** First five bytes: the Registered Application Provider Identifier. */
    val rid: String get() = aidHex.take(10)
}

/**
 * Terminal AID list. Two roles, mirroring EMV 4.4 Book 1 section 12.3:
 *  1. filtering the candidate list a card advertises through the PPSE, and
 *  2. supplying names to probe directly when a card has no PPSE (the "list of AIDs" method).
 *
 * ## What being in this list does and does not mean
 *
 * Listing an AID means the terminal will SELECT that application and try to read it with the
 * generic flow: PPSE, SELECT, GET PROCESSING OPTIONS, READ RECORD. That prefix is common to every
 * EMVCo kernel, so basic data extraction usually works across brands.
 *
 * It does **not** mean the brand's kernel is implemented. Kernels 1 and 4 through 7 diverge from
 * the Visa and Mastercard shapes after GPO - different PDOL expectations, different response
 * templates, brand-specific data elements. Entries carry [RegisteredAid.verified] to keep that
 * distinction visible rather than implied.
 */
class AidRegistry(entries: List<RegisteredAid>) {

    val entries: List<RegisteredAid> = entries.sortedByDescending { it.aidHex.length }

    fun match(aid: ByteArray): RegisteredAid? = match(aid.toHex())

    /**
     * Longest match wins, so `A00000000410101213` resolves to the Mastercard credit entry rather
     * than to a shorter RID-only entry that also prefixes it.
     */
    fun match(aidHex: String): RegisteredAid? {
        val candidate = aidHex.uppercase()
        return entries.firstOrNull { registered ->
            when {
                candidate == registered.aidHex -> true
                registered.partialSelectionAllowed && candidate.startsWith(registered.aidHex) -> true
                // A PPSE may advertise a truncated name that the full registered AID extends.
                registered.partialSelectionAllowed && registered.aidHex.startsWith(candidate) &&
                    candidate.length >= MIN_PARTIAL_MATCH_CHARS -> true

                else -> false
            }
        }
    }

    fun supports(aid: ByteArray): Boolean = match(aid) != null

    fun forScheme(scheme: CardScheme): List<RegisteredAid> = entries.filter { it.scheme == scheme }

    /** Names to SELECT one by one when no PPSE is available. */
    fun probeList(): List<RegisteredAid> = entries.sortedBy { it.aidHex }

    operator fun plus(other: AidRegistry): AidRegistry = AidRegistry(entries + other.entries)

    companion object {
        /** An RID is five bytes; refuse to partially match on anything shorter. */
        private const val MIN_PARTIAL_MATCH_CHARS = 10

        // --- Visa, RID A000000003 ------------------------------------------------------------
        val VISA_CREDIT_DEBIT = RegisteredAid("A0000000031010", "Visa Credit or Debit", CardScheme.VISA, verified = true)
        val VISA_ELECTRON = RegisteredAid("A0000000032010", "Visa Electron", CardScheme.VISA, verified = true)
        val V_PAY = RegisteredAid("A0000000032020", "V PAY", CardScheme.VISA, verified = true)
        val VISA_INTERLINK = RegisteredAid("A0000000033010", "Visa Interlink", CardScheme.VISA, verified = true)
        val VISA_PLUS = RegisteredAid("A0000000038010", "Visa Plus", CardScheme.VISA, verified = true)
        val VISA_COMMON_DEBIT = RegisteredAid("A0000000980840", "Visa US Common Debit", CardScheme.VISA)

        // --- Mastercard, RIDs A000000004 and A000000005 --------------------------------------
        val MASTERCARD_CREDIT_DEBIT =
            RegisteredAid("A0000000041010", "Mastercard Credit or Debit", CardScheme.MASTERCARD, verified = true)
        val MASTERCARD_US_MAESTRO =
            RegisteredAid("A0000000042203", "Mastercard US Maestro", CardScheme.MAESTRO, verified = true)
        val MAESTRO = RegisteredAid("A0000000043060", "Maestro", CardScheme.MAESTRO, verified = true)
        val CIRRUS = RegisteredAid("A0000000046000", "Cirrus", CardScheme.MASTERCARD, verified = true)
        val MAESTRO_UK = RegisteredAid("A0000000050001", "Maestro UK", CardScheme.MAESTRO, verified = true)

        // --- American Express, RID A000000025 -------------------------------------------------
        val AMEX = RegisteredAid("A00000002501", "American Express", CardScheme.AMEX)

        // --- JCB, RID A000000065 --------------------------------------------------------------
        val JCB = RegisteredAid("A0000000651010", "JCB", CardScheme.JCB)

        // --- UnionPay, RID A000000333 ---------------------------------------------------------
        val UNIONPAY_DEBIT = RegisteredAid("A000000333010101", "UnionPay Debit", CardScheme.UNIONPAY)
        val UNIONPAY_CREDIT = RegisteredAid("A000000333010102", "UnionPay Credit", CardScheme.UNIONPAY)
        val UNIONPAY_QUASI_CREDIT = RegisteredAid("A000000333010103", "UnionPay Quasi-credit", CardScheme.UNIONPAY)
        val UNIONPAY_ELECTRONIC_CASH = RegisteredAid("A000000333010106", "UnionPay Electronic Cash", CardScheme.UNIONPAY)
        val UNIONPAY_US_COMMON_DEBIT = RegisteredAid("A000000333010108", "UnionPay US Common Debit", CardScheme.UNIONPAY)

        // --- Discover and Diners, RIDs A000000152 and A000000324 ------------------------------
        val DISCOVER = RegisteredAid("A0000001523010", "Discover or Diners Club International", CardScheme.DISCOVER)
        val DISCOVER_DPAS = RegisteredAid("A0000003241010", "Discover D-PAS", CardScheme.DISCOVER)

        // --- Domestic schemes -----------------------------------------------------------------
        val INTERAC = RegisteredAid("A0000002771010", "Interac", CardScheme.INTERAC)
        val RUPAY = RegisteredAid("A0000005241010", "RuPay", CardScheme.RUPAY)
        val MIR_CREDIT = RegisteredAid("A0000006581010", "Mir Credit", CardScheme.MIR)
        val MIR_DEBIT = RegisteredAid("A0000006582010", "Mir Debit", CardScheme.MIR)
        val GIROCARD = RegisteredAid("D27600002545500100", "girocard", CardScheme.GIROCARD)
        val DANKORT = RegisteredAid("A0000001211010", "Dankort", CardScheme.DANKORT)
        val CARTES_BANCAIRES = RegisteredAid("A0000000421010", "Cartes Bancaires", CardScheme.CARTES_BANCAIRES)

        /**
         * The two families this project actually implements kernels' worth of behaviour for, in the
         * sense of having been exercised end to end against the simulated card.
         */
        val VisaMastercardOnly = AidRegistry(
            listOf(
                VISA_CREDIT_DEBIT, VISA_ELECTRON, V_PAY, VISA_INTERLINK, VISA_PLUS, VISA_COMMON_DEBIT,
                MASTERCARD_CREDIT_DEBIT, MASTERCARD_US_MAESTRO, MAESTRO, CIRRUS, MAESTRO_UK,
            ),
        )

        /** Common international and domestic brands beyond Visa and Mastercard. */
        val OtherBrands = AidRegistry(
            listOf(
                AMEX, JCB,
                UNIONPAY_DEBIT, UNIONPAY_CREDIT, UNIONPAY_QUASI_CREDIT,
                UNIONPAY_ELECTRONIC_CASH, UNIONPAY_US_COMMON_DEBIT,
                DISCOVER, DISCOVER_DPAS,
                INTERAC, RUPAY, MIR_CREDIT, MIR_DEBIT, GIROCARD, DANKORT, CARTES_BANCAIRES,
            ),
        )

        /**
         * Everything above. Reading a brand outside the Visa and Mastercard families relies on the
         * generic flow and is unverified - see the class documentation.
         */
        val Default = VisaMastercardOnly + OtherBrands
    }
}
