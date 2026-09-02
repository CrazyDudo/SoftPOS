package com.softpos.emv.terminal

import com.softpos.emv.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryPointPreProcessingTest {

    private val offlineOnlyTtq = "28000000".hexToBytes()
    private val onlineCapableTtq = "20000000".hexToBytes()

    private val limits = ReaderLimits(
        contactlessTransactionLimitMinor = 10_000,
        cvmRequiredLimitMinor = 5_000,
        contactlessFloorLimitMinor = 2_000,
    )

    @Test
    fun `no limits means every amount passes with the configured ttq`() {
        val indicators = EntryPointPreProcessing.run(999_999, null, offlineOnlyTtq)

        assertFalse(indicators.contactlessApplicationNotAllowed)
        assertFalse(indicators.cvmRequired)
        assertFalse(indicators.onlineCryptogramRequired)
        assertEquals("28000000", indicators.ttqHex)
    }

    @Test
    fun `transaction-specific ttq bits are cleared before evaluation`() {
        // A configured TTQ that has both bits set must not leak them into a small transaction.
        val indicators = EntryPointPreProcessing.run(100, limits, "28C00000".hexToBytes())

        assertEquals("28000000", indicators.ttqHex)
    }

    @Test
    fun `at or above the transaction limit contactless is not allowed`() {
        assertFalse(EntryPointPreProcessing.run(9_999, limits, offlineOnlyTtq).contactlessApplicationNotAllowed)
        assertTrue(EntryPointPreProcessing.run(10_000, limits, offlineOnlyTtq).contactlessApplicationNotAllowed)
        assertTrue(EntryPointPreProcessing.run(10_001, limits, offlineOnlyTtq).contactlessApplicationNotAllowed)
    }

    @Test
    fun `at or above the cvm limit the ttq asks for cardholder verification`() {
        // No floor limit here, so the CVM bit is observed on its own.
        val cvmOnly = limits.copy(contactlessFloorLimitMinor = null)
        assertFalse(EntryPointPreProcessing.run(4_999, cvmOnly, offlineOnlyTtq).cvmRequired)

        val indicators = EntryPointPreProcessing.run(5_000, cvmOnly, offlineOnlyTtq)
        assertTrue(indicators.cvmRequiredLimitExceeded)
        assertTrue(indicators.cvmRequired)
        assertFalse(indicators.onlineCryptogramRequired)
        assertEquals("28400000", indicators.ttqHex)
    }

    @Test
    fun `strictly above the floor limit the ttq asks for an online cryptogram`() {
        assertFalse(EntryPointPreProcessing.run(2_000, limits, offlineOnlyTtq).onlineCryptogramRequired)

        val indicators = EntryPointPreProcessing.run(2_001, limits, offlineOnlyTtq)
        assertTrue(indicators.floorLimitExceeded)
        assertTrue(indicators.onlineCryptogramRequired)
        assertEquals("28800000", indicators.ttqHex)
    }

    @Test
    fun `both bits combine`() {
        assertEquals("28C00000", EntryPointPreProcessing.run(6_000, limits, offlineOnlyTtq).ttqHex)
    }

    @Test
    fun `zero amount is allowed by default and goes online only on an online capable reader`() {
        val offline = EntryPointPreProcessing.run(0, limits, offlineOnlyTtq)
        assertTrue(offline.zeroAmount)
        assertFalse(offline.contactlessApplicationNotAllowed)
        assertFalse(offline.onlineCryptogramRequired)

        val online = EntryPointPreProcessing.run(0, limits, onlineCapableTtq)
        assertTrue(online.onlineCryptogramRequired)
    }

    @Test
    fun `zero amount can be refused`() {
        val indicators = EntryPointPreProcessing.run(0, limits.copy(zeroAmountAllowed = false), offlineOnlyTtq)

        assertTrue(indicators.contactlessApplicationNotAllowed)
    }

    @Test
    fun `negative limits are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { ReaderLimits(cvmRequiredLimitMinor = -1) }
    }

    @Test
    fun `describe names every indicator that fired`() {
        val text = EntryPointPreProcessing.run(6_000, limits, offlineOnlyTtq).describe()

        assertTrue(text.contains("TTQ 28C00000"))
        assertTrue(text.contains("floor limit exceeded"))
        assertTrue(text.contains("CVM required limit exceeded"))
    }
}

class TerminalProfileCapabilitiesTest {

    @Test
    fun `terminal capabilities claim sda and dda only when authentication is on`() {
        val profile = TerminalProfile()

        assertEquals("200800", com.softpos.emv.util.Hex.encode(profile.terminalCapabilities(false)))
        assertEquals("2008C0", com.softpos.emv.util.Hex.encode(profile.terminalCapabilities(true)))
    }
}
