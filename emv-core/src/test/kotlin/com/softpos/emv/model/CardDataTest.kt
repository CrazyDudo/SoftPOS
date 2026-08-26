package com.softpos.emv.model

import com.softpos.emv.util.hexToBytes
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AflParserTest {

    @Test
    fun `parses two entries`() {
        val entries = AflParser.parse("0801010010010300".hexToBytes())

        assertEquals(
            listOf(
                AflEntry(sfi = 1, firstRecord = 1, lastRecord = 1, odaRecordCount = 0),
                AflEntry(sfi = 2, firstRecord = 1, lastRecord = 3, odaRecordCount = 0),
            ),
            entries,
        )
    }

    @Test
    fun `records range and count are derived`() {
        val entry = AflParser.parse("10010301".hexToBytes()).single()

        assertEquals(1..3, entry.records)
        assertEquals(3, entry.recordCount)
        assertEquals(1, entry.odaRecordCount)
    }

    @Test
    fun `rejects a length that is not a multiple of four`() {
        assertFailsWith<IllegalArgumentException> { AflParser.parse("080101".hexToBytes()) }
    }

    @Test
    fun `rejects sfi zero`() {
        // SFI 0 addresses the currently selected file, which the AFL may not reference.
        assertFailsWith<IllegalArgumentException> { AflParser.parse("00010100".hexToBytes()) }
    }

    @Test
    fun `rejects a last record before the first`() {
        assertFailsWith<IllegalArgumentException> { AflParser.parse("08030100".hexToBytes()) }
    }

    @Test
    fun `rejects an oda count larger than the range`() {
        assertFailsWith<IllegalArgumentException> { AflParser.parse("08010105".hexToBytes()) }
    }

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertNull(AflParser.parseOrNull("080101".hexToBytes()))
    }
}

class PanTest {

    @Test
    fun `masks everything except the last four digits`() {
        val pan = assertNotNull(Pan.of("4761739001010010"))

        assertEquals("************0010", pan.masked())
        assertEquals("0010", pan.last4)
    }

    @Test
    fun `bin and last four policy keeps the issuer prefix`() {
        val pan = assertNotNull(Pan.of("4761739001010010"))

        assertEquals("476173******0010", pan.masked(MaskPolicy.BIN_AND_LAST_4))
        assertEquals("476173", pan.bin)
    }

    @Test
    fun `none policy reveals no digits`() {
        val pan = assertNotNull(Pan.of("4761739001010010"))

        assertEquals("****************", pan.masked(MaskPolicy.NONE))
    }

    @Test
    fun `validates the luhn checksum`() {
        assertTrue(assertNotNull(Pan.of("4761739001010010")).isLuhnValid())
        assertFalse(assertNotNull(Pan.of("4761739001010011")).isLuhnValid())
    }

    @Test
    fun `rejects non digits and out of range lengths`() {
        assertNull(Pan.of("47617390010100AB"))
        assertNull(Pan.of("1234567"))
        assertNull(Pan.of("12345678901234567890"))
    }

    @Test
    fun `decodes compressed numeric with F padding`() {
        val full = assertNotNull(Pan.fromCompressedNumeric("4761739001010010".hexToBytes()))
        assertEquals(16, full.length)
        assertEquals("0010", full.last4)

        // A 14-digit PAN occupies seven bytes plus one 'F' nibble of padding.
        val padded = assertNotNull(Pan.fromCompressedNumeric("47617390010100FF".hexToBytes()))
        assertEquals(14, padded.length)
        assertEquals("0100", padded.last4)
    }

    @Test
    fun `toString never leaks digits`() {
        val pan = assertNotNull(Pan.of("4761739001010010"))

        assertFalse(pan.toString().contains("4761739001010010"))
    }

    @Test
    fun `close wipes the buffer and later access fails loudly`() {
        val pan = assertNotNull(Pan.of("4761739001010010"))
        pan.close()

        assertEquals("Pan(wiped)", pan.toString())
        assertFailsWith<IllegalStateException> { pan.last4 }
    }

    @Test
    fun `reveal grants scoped access to the full number`() {
        val pan = assertNotNull(Pan.of("4761739001010010"))

        assertEquals("4761739001010010", pan.reveal { it.toString() })
    }
}

class Track2DataTest {

    @Test
    fun `parses pan expiry service code and discretionary data`() {
        val track2 = assertNotNull(
            Track2Data.parse("4761739001010010D25122010000000000000F".hexToBytes()),
        )

        assertEquals("0010", track2.pan.last4)
        assertEquals(ExpiryDate(2025, 12), track2.expiry)
        assertEquals("201", track2.serviceCode)
        assertFalse(track2.serviceCodeIndicatesRestrictedUse)
    }

    @Test
    fun `flags a restricted use service code`() {
        val track2 = assertNotNull(
            Track2Data.parse("4761739001010010D25127010000000000000F".hexToBytes()),
        )

        assertTrue(track2.serviceCodeIndicatesRestrictedUse)
    }

    @Test
    fun `returns null when the separator is missing`() {
        assertNull(Track2Data.parse("47617390010100104761".hexToBytes()))
    }

    @Test
    fun `toString never leaks the pan`() {
        val track2 = assertNotNull(
            Track2Data.parse("4761739001010010D25122010000000000000F".hexToBytes()),
        )

        assertFalse(track2.toString().contains("4761739001010010"))
    }
}

class ExpiryDateTest {

    @Test
    fun `decodes tag 5F24`() {
        assertEquals(ExpiryDate(2025, 12), ExpiryDate.fromTag5F24("251231".hexToBytes()))
    }

    @Test
    fun `rejects a wrong length or an invalid month`() {
        assertNull(ExpiryDate.fromTag5F24("2512".hexToBytes()))
        assertNull(ExpiryDate.fromYyMm("2513"))
    }

    @Test
    fun `a card is valid through the last day of its expiry month`() {
        val expiry = ExpiryDate(2025, 12)

        assertEquals(LocalDate.of(2025, 12, 31), expiry.lastValidDay)
        assertFalse(expiry.isExpiredOn(LocalDate.of(2025, 12, 31)))
        assertTrue(expiry.isExpiredOn(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `formats as year and month`() {
        assertEquals("2025-12", ExpiryDate(2025, 12).toString())
        assertEquals("2026-03", ExpiryDate(2026, 3).toString())
    }
}

class CardSchemeTest {

    @Test
    fun `derives the scheme from a pan prefix`() {
        assertEquals(CardScheme.VISA, CardScheme.fromPanPrefix("4761739001010010"))
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromPanPrefix("5555555555554444"))
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromPanPrefix("2223000048400011"))
        assertEquals(CardScheme.UNKNOWN, CardScheme.fromPanPrefix("9999999999999999"))
        assertEquals(CardScheme.UNKNOWN, CardScheme.fromPanPrefix(""))
    }

    @Test
    fun `each scheme maps to its contactless kernel`() {
        assertEquals(EmvKernel.KERNEL_3, CardScheme.VISA.kernel)
        assertEquals(EmvKernel.KERNEL_2, CardScheme.MASTERCARD.kernel)
        assertEquals(EmvKernel.KERNEL_2, CardScheme.MAESTRO.kernel)
    }
}

class AidRegistryTest {

    @Test
    fun `matches an exact aid`() {
        val match = assertNotNull(AidRegistry.Default.match("A0000000031010"))

        assertEquals(CardScheme.VISA, match.scheme)
        assertEquals(EmvKernel.KERNEL_3, match.kernel)
    }

    @Test
    fun `matches a card aid that extends a registered one`() {
        // EMV 4.4 Book 1 section 12.3.3 allows partial name selection.
        val match = assertNotNull(AidRegistry.Default.match("A00000000410101213"))

        assertEquals(CardScheme.MASTERCARD, match.scheme)
    }

    @Test
    fun `prefers the longest registered match`() {
        val registry = AidRegistry(
            listOf(
                RegisteredAid("A000000004", "RID only", CardScheme.MASTERCARD),
                RegisteredAid("A0000000041010", "Mastercard Credit", CardScheme.MASTERCARD),
            ),
        )

        assertEquals("Mastercard Credit", assertNotNull(registry.match("A0000000041010")).label)
    }

    @Test
    fun `resolves the common brands`() {
        val expected = mapOf(
            "A0000000031010" to CardScheme.VISA,
            "A0000000041010" to CardScheme.MASTERCARD,
            "A0000000043060" to CardScheme.MAESTRO,
            "A00000002501" to CardScheme.AMEX,
            "A0000000651010" to CardScheme.JCB,
            "A000000333010102" to CardScheme.UNIONPAY,
            "A0000001523010" to CardScheme.DISCOVER,
            "A0000003241010" to CardScheme.DISCOVER,
            "A0000002771010" to CardScheme.INTERAC,
            "A0000005241010" to CardScheme.RUPAY,
            "A0000006581010" to CardScheme.MIR,
            "D27600002545500100" to CardScheme.GIROCARD,
            "A0000001211010" to CardScheme.DANKORT,
            "A0000000421010" to CardScheme.CARTES_BANCAIRES,
        )

        for ((aid, scheme) in expected) {
            assertEquals(scheme, assertNotNull(AidRegistry.Default.match(aid), aid).scheme, aid)
        }
    }

    @Test
    fun `each brand maps to its emvco kernel`() {
        assertEquals(EmvKernel.KERNEL_3, assertNotNull(AidRegistry.Default.match("A0000000031010")).kernel)
        assertEquals(EmvKernel.KERNEL_2, assertNotNull(AidRegistry.Default.match("A0000000041010")).kernel)
        assertEquals(EmvKernel.KERNEL_4, assertNotNull(AidRegistry.Default.match("A00000002501")).kernel)
        assertEquals(EmvKernel.KERNEL_5, assertNotNull(AidRegistry.Default.match("A0000000651010")).kernel)
        assertEquals(EmvKernel.KERNEL_7, assertNotNull(AidRegistry.Default.match("A000000333010102")).kernel)
        assertEquals(EmvKernel.KERNEL_6, assertNotNull(AidRegistry.Default.match("A0000003241010")).kernel)
    }

    @Test
    fun `only the visa and mastercard families are marked verified`() {
        // Everything else is a correct AID that no card of that brand has been read with.
        val verified = AidRegistry.Default.entries.filter { it.verified }.map { it.scheme }.toSet()

        assertEquals(setOf(CardScheme.VISA, CardScheme.MASTERCARD, CardScheme.MAESTRO), verified)
    }

    @Test
    fun `the conservative registry excludes other brands`() {
        assertNull(AidRegistry.VisaMastercardOnly.match("A00000002501"))
        assertNotNull(AidRegistry.VisaMastercardOnly.match("A0000000031010"))
    }

    @Test
    fun `rejects an aid from no known scheme`() {
        assertNull(AidRegistry.Default.match("A0000009999999"))
    }

    @Test
    fun `does not partially match on fewer than five bytes`() {
        assertNull(AidRegistry.Default.match("A000"))
    }

    @Test
    fun `registry has no duplicate aids`() {
        val aids = AidRegistry.Default.entries.map { it.aidHex }

        assertEquals(aids.size, aids.toSet().size, "duplicate AID in the default registry")
    }
}
