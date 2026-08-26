package com.softpos.emv.tlv

import com.softpos.emv.testing.TestCards
import com.softpos.emv.util.hexToBytes
import com.softpos.emv.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlvDatabaseTest {

    @Test
    fun `flattens nested templates so children stay addressable`() {
        val db = TlvDatabase.parse(TestCards.PPSE_FCI_VISA.hexToBytes())

        assertTrue(EmvTags.FCI_TEMPLATE in db)
        assertTrue(EmvTags.APPLICATION_TEMPLATE in db)
        assertEquals(TestCards.VISA_AID, db.hex(EmvTags.ADF_NAME))
        assertEquals("VISA", db.text(EmvTags.APPLICATION_LABEL))
    }

    @Test
    fun `keeps duplicates in arrival order`() {
        val db = TlvDatabase.parse(TestCards.PPSE_FCI_TWO_APPS.hexToBytes())

        val aids = db.getAll(EmvTags.ADF_NAME).map { it.toHex() }
        assertEquals(listOf(TestCards.MASTERCARD_AID, TestCards.VISA_AID), aids)
        // Indexed access returns the first occurrence.
        assertEquals(TestCards.MASTERCARD_AID, db.hex(EmvTags.ADF_NAME))
    }

    @Test
    fun `int reads a big endian value`() {
        val db = TlvDatabase.parse("87010250021234".hexToBytes())

        assertEquals(2, db.int(EmvTags.APPLICATION_PRIORITY_INDICATOR))
        assertEquals(0x1234, db.int(EmvTags.APPLICATION_LABEL))
    }

    @Test
    fun `bcd reads a numeric amount`() {
        val db = TlvDatabase.parse("9F0206000000001234".hexToBytes())

        assertEquals(1234L, db.bcd(EmvTags.AMOUNT_AUTHORISED))
    }

    @Test
    fun `missing tags return null rather than throwing`() {
        val db = TlvDatabase.empty()

        assertNull(db[EmvTags.PAN])
        assertNull(db.int(EmvTags.PAN))
        assertNull(db.text(EmvTags.PAN))
        assertNull(db.bcd(EmvTags.PAN))
        assertFalse(EmvTags.PAN in db)
    }

    @Test
    fun `wipeSensitive removes and zeroes card identifying values`() {
        val panBytes = "4761739001010010".hexToBytes()
        val db = TlvDatabase.builder()
            .put(EmvTags.PAN, panBytes)
            .put(EmvTags.APPLICATION_PRIORITY_INDICATOR, byteArrayOf(0x01))
            .build()

        db.wipeSensitive()

        assertFalse(EmvTags.PAN in db)
        assertTrue(EmvTags.APPLICATION_PRIORITY_INDICATOR in db)
        // The caller's array is overwritten too, not merely dropped from the map.
        assertTrue(panBytes.all { it == 0.toByte() })
    }

    @Test
    fun `a constructed template keeps no copy of its sensitive children`() {
        // Regression: the flattener used to store tag 70's raw value, which is the concatenation of
        // its children. That left a second copy of the PAN behind a tag that is not itself
        // sensitive, so neither wipeSensitive() nor describe() redaction could reach it.
        val db = TlvDatabase.parse(TestCards.RECORD_SFI2_REC1.hexToBytes())

        assertTrue(EmvTags.RECORD_TEMPLATE in db, "the template should still be recorded as present")
        assertEquals(0, assertNotNull(db[EmvTags.RECORD_TEMPLATE]).size)

        db.wipeSensitive()

        val remaining = db.tags().flatMap { db.getAll(it) }.joinToString("") { it.toHex() }
        assertFalse(remaining.contains(TestCards.VISA_PAN))
    }

    @Test
    fun `describe redacts sensitive values by default`() {
        val db = TlvDatabase.parse(TestCards.RECORD_SFI2_REC1.hexToBytes())

        val redacted = db.describe()
        assertFalse(redacted.contains(TestCards.VISA_PAN))
        assertTrue(redacted.contains("redacted"))

        assertTrue(db.describe(redactSensitive = false).contains(TestCards.VISA_PAN))
    }

    @Test
    fun `plus merges two databases`() {
        val a = TlvDatabase.parse("870101".hexToBytes())
        val b = TlvDatabase.parse("5004".hexToBytes() + "56495341".hexToBytes())

        val merged = a + b

        assertTrue(EmvTags.APPLICATION_PRIORITY_INDICATOR in merged)
        assertEquals("VISA", merged.text(EmvTags.APPLICATION_LABEL))
    }

    @Test
    fun `unknown primitive tags are treated as sensitive`() {
        // Over-redaction is the safe default for proprietary elements we have never seen.
        assertTrue(EmvTags.isSensitive(Tag.of("DFAB01")))
        assertFalse(EmvTags.isSensitive(Tag.of("70")))
    }
}
