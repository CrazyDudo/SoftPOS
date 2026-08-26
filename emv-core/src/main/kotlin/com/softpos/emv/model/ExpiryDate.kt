package com.softpos.emv.model

import com.softpos.emv.util.cnToDigitsOrNull
import java.time.LocalDate
import java.time.YearMonth

/**
 * Application Expiration Date, normalised to a year and month.
 *
 * EMV encodes the date as `YYMMDD` in tag 5F24 and as `YYMM` inside Track 2. The day component is
 * discarded: a card is valid through the final day of its expiry month (EMV 4.4 Book 3,
 * section 10.4 terminal action analysis).
 */
data class ExpiryDate(val year: Int, val month: Int) : Comparable<ExpiryDate> {

    init {
        require(month in 1..12) { "month out of range: $month" }
        require(year in 1900..2199) { "year out of range: $year" }
    }

    val yearMonth: YearMonth get() = YearMonth.of(year, month)

    /** Last calendar day on which the card is still valid. */
    val lastValidDay: LocalDate get() = yearMonth.atEndOfMonth()

    fun isExpiredOn(date: LocalDate): Boolean = date.isAfter(lastValidDay)

    override fun compareTo(other: ExpiryDate): Int = yearMonth.compareTo(other.yearMonth)

    override fun toString(): String = String.format("%04d-%02d", year, month)

    companion object {
        /**
         * EMV carries a two-digit year. Cards are not issued with expiry dates decades out, so the
         * whole two-digit space maps into 2000-2099.
         *
         * TODO: revisit before 2090 or so, when a 'YY' near 99 could plausibly mean 2199. A real
         *  terminal uses a sliding window anchored on the current date.
         */
        private const val CENTURY = 2000

        fun fromYyMm(text: CharSequence): ExpiryDate? {
            if (text.length < 4) return null
            val yy = text.substring(0, 2).toIntOrNull() ?: return null
            val mm = text.substring(2, 4).toIntOrNull() ?: return null
            if (mm !in 1..12) return null
            return ExpiryDate(CENTURY + yy, mm)
        }

        /** Decodes tag 5F24 (`YYMMDD`, BCD). */
        fun fromTag5F24(bytes: ByteArray): ExpiryDate? {
            if (bytes.size != 3) return null
            val digits = bytes.cnToDigitsOrNull() ?: return null
            return fromYyMm(digits)
        }
    }
}
