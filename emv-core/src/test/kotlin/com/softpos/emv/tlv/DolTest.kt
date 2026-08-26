package com.softpos.emv.tlv

import com.softpos.emv.util.hexToBytes
import com.softpos.emv.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

class DolTest {

    @Test
    fun `parses a pdol of two entries`() {
        val entries = DolParser.parse("9F66049F0206".hexToBytes())

        assertEquals(
            listOf(
                DolEntry(EmvTags.TERMINAL_TRANSACTION_QUALIFIERS, 4),
                DolEntry(EmvTags.AMOUNT_AUTHORISED, 6),
            ),
            entries,
        )
        assertEquals(10, DolParser.totalLength(entries))
    }

    @Test
    fun `parses single byte tags mixed with two byte tags`() {
        val entries = DolParser.parse("9A039C019F3704".hexToBytes())

        assertEquals(
            listOf(
                DolEntry(EmvTags.TRANSACTION_DATE, 3),
                DolEntry(EmvTags.TRANSACTION_TYPE, 1),
                DolEntry(EmvTags.UNPREDICTABLE_NUMBER, 4),
            ),
            entries,
        )
    }

    @Test
    fun `builds a pdol response in order`() {
        val entries = DolParser.parse("9F66049F0206".hexToBytes())
        val source = TagValueSource { tag ->
            when (tag) {
                EmvTags.TERMINAL_TRANSACTION_QUALIFIERS -> "28000000".hexToBytes()
                EmvTags.AMOUNT_AUTHORISED -> "000000001234".hexToBytes()
                else -> null
            }
        }

        assertEquals("28000000000000001234", DolBuilder.build(entries, source).toHex())
    }

    @Test
    fun `unknown tags are zero filled to the requested length`() {
        // EMV 4.4 Book 3 section 5.4: the field must be present and correctly sized even when the
        // terminal has nothing to put in it.
        val entries = DolParser.parse("9F1D08".hexToBytes())

        assertEquals("0000000000000000", DolBuilder.build(entries) { null }.toHex())
    }

    @Test
    fun `numeric values are right justified and left padded`() {
        val fitted = DolBuilder.fit("1234".hexToBytes(), length = 6, format = TagFormat.NUMERIC)

        assertEquals("000000001234", fitted.toHex())
    }

    @Test
    fun `numeric values that are too long lose their leftmost bytes`() {
        val fitted = DolBuilder.fit("0000000012345678".hexToBytes(), length = 6, format = TagFormat.NUMERIC)

        assertEquals("000012345678", fitted.toHex())
    }

    @Test
    fun `compressed numeric values are left justified and F padded`() {
        val fitted = DolBuilder.fit("1234".hexToBytes(), length = 4, format = TagFormat.COMPRESSED_NUMERIC)

        assertEquals("1234FFFF", fitted.toHex())
    }

    @Test
    fun `binary values are left justified and zero padded`() {
        val fitted = DolBuilder.fit("AB".hexToBytes(), length = 3, format = TagFormat.BINARY)

        assertEquals("AB0000", fitted.toHex())
    }

    @Test
    fun `binary values that are too long lose their rightmost bytes`() {
        val fitted = DolBuilder.fit("AABBCCDD".hexToBytes(), length = 2, format = TagFormat.BINARY)

        assertEquals("AABB", fitted.toHex())
    }

    @Test
    fun `an exact length value is passed through unchanged`() {
        val fitted = DolBuilder.fit("AABB".hexToBytes(), length = 2, format = TagFormat.BINARY)

        assertEquals("AABB", fitted.toHex())
    }
}
