package com.softpos.emv.model

/**
 * EMVCo contactless kernels.
 *
 * The read flow in this project implements only the shared entry-point behaviour that precedes
 * kernel activation (PPSE, selection, GPO, READ RECORD). The kernel is recorded for diagnostics and
 * to pick an Application Version Number; no kernel-specific processing happens anywhere.
 */
enum class EmvKernel(val id: Int, val label: String) {
    /** JCB and the legacy combined kernel. EMV Contactless Book C-1. */
    KERNEL_1(1, "JCB / legacy (Kernel 1)"),

    /** Mastercard PayPass. EMV Contactless Book C-2. */
    KERNEL_2(2, "Mastercard PayPass (Kernel 2)"),

    /** Visa qVSDC / payWave. EMV Contactless Book C-3. */
    KERNEL_3(3, "Visa qVSDC (Kernel 3)"),

    /** American Express ExpressPay. EMV Contactless Book C-4. */
    KERNEL_4(4, "Amex ExpressPay (Kernel 4)"),

    /** JCB J/Speedy. EMV Contactless Book C-5. */
    KERNEL_5(5, "JCB J/Speedy (Kernel 5)"),

    /** Discover D-PAS. EMV Contactless Book C-6. */
    KERNEL_6(6, "Discover D-PAS (Kernel 6)"),

    /** UnionPay QuickPass. EMV Contactless Book C-7. */
    KERNEL_7(7, "UnionPay QuickPass (Kernel 7)"),

    UNSUPPORTED(0, "Unknown kernel"),
}

enum class CardScheme(val displayName: String, val kernel: EmvKernel) {
    VISA("Visa", EmvKernel.KERNEL_3),
    MASTERCARD("Mastercard", EmvKernel.KERNEL_2),
    MAESTRO("Maestro", EmvKernel.KERNEL_2),
    AMEX("American Express", EmvKernel.KERNEL_4),
    JCB("JCB", EmvKernel.KERNEL_5),
    UNIONPAY("UnionPay", EmvKernel.KERNEL_7),
    DISCOVER("Discover", EmvKernel.KERNEL_6),
    DINERS("Diners Club", EmvKernel.KERNEL_6),
    INTERAC("Interac", EmvKernel.UNSUPPORTED),
    RUPAY("RuPay", EmvKernel.UNSUPPORTED),
    MIR("Mir", EmvKernel.UNSUPPORTED),
    GIROCARD("girocard", EmvKernel.UNSUPPORTED),
    DANKORT("Dankort", EmvKernel.UNSUPPORTED),
    CARTES_BANCAIRES("Cartes Bancaires", EmvKernel.UNSUPPORTED),
    UNKNOWN("Unknown", EmvKernel.UNSUPPORTED),
    ;

    companion object {
        /**
         * Best-effort scheme guess from the PAN's leading digits (ISO/IEC 7812 issuer identifiers),
         * used only when the AID is not in the registry. The AID is authoritative; this exists so an
         * unrecognised card still shows something useful on screen.
         */
        fun fromPanPrefix(digits: CharSequence): CardScheme {
            if (digits.isEmpty()) return UNKNOWN
            val first = digits[0]
            val two = digits.prefixInt(2)
            val three = digits.prefixInt(3)
            val four = digits.prefixInt(4)
            val six = digits.prefixInt(6)

            return when {
                first == '4' -> VISA
                two != null && two in 51..55 -> MASTERCARD
                four != null && four in 2221..2720 -> MASTERCARD
                two != null && (two == 34 || two == 37) -> AMEX
                four != null && four in 3528..3589 -> JCB
                two == 36 || two == 38 -> DINERS
                three != null && three in 300..305 -> DINERS
                two == 65 -> DISCOVER
                four == 6011 -> DISCOVER
                six != null && six in 622126..622925 -> UNIONPAY
                two == 62 || two == 81 -> UNIONPAY
                // RuPay also issues in 65 and 81, which Discover and UnionPay claim above. The
                // ranges genuinely overlap between schemes; this fallback picks one and the AID
                // remains authoritative.
                two == 60 -> RUPAY
                two == 22 && four != null && four in 2200..2204 -> MIR
                two != null && two in listOf(50, 56, 57, 58, 67) -> MAESTRO
                else -> UNKNOWN
            }
        }

        private fun CharSequence.prefixInt(length: Int): Int? =
            if (this.length >= length) substring(0, length).toIntOrNull() else null
    }
}
