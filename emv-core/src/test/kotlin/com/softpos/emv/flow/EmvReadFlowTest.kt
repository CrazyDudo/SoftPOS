package com.softpos.emv.flow

import com.softpos.emv.apdu.ApduTransceiver
import com.softpos.emv.apdu.ApduTransportException
import com.softpos.emv.model.CardScheme
import com.softpos.emv.model.EmvKernel
import com.softpos.emv.model.ExpiryDate
import com.softpos.emv.terminal.TerminalProfile
import com.softpos.emv.testing.SimulatedApplication
import com.softpos.emv.testing.SimulatedCard
import com.softpos.emv.testing.TestCards
import com.softpos.emv.tlv.EmvTags
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmvReadFlowTest {

    private val terminal = TerminalProfile(
        clock = Clock.fixed(Instant.parse("2026-03-04T10:15:30Z"), ZoneOffset.UTC),
        random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0xAB.toByte())
        },
    )

    private fun flow(
        card: ApduTransceiver,
        amountMinor: Long = 1234,
        options: ReadOptions = ReadOptions(),
    ) = EmvReadFlow(card, terminal, amountMinor, options = options)

    // ---------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------

    @Test
    fun `reads a visa card end to end`() {
        val result = flow(TestCards.visaCard()).execute()

        val success = assertIs<EmvReadResult.Success>(result, (result as? EmvReadResult.Failure)?.toString())
        success.card.use { card ->
            assertEquals(CardScheme.VISA, card.scheme)
            assertEquals(EmvKernel.KERNEL_3, card.kernel)
            assertEquals(TestCards.VISA_AID, card.aidHex)
            assertEquals("VISA CREDIT", card.applicationLabel)
            assertEquals("0010", assertNotNull(card.pan).last4)
            assertEquals(ExpiryDate(2025, 12), card.expiry)
            assertEquals("CARDHOLDER/TEST", card.cardholderName)
            assertTrue(assertNotNull(card.pan).isLuhnValid())
        }
    }

    @Test
    fun `issues the expected command sequence`() {
        val card = TestCards.visaCard()

        flow(card).execute()

        assertEquals(
            listOf(
                // SELECT PPSE
                "00A404000E325041592E535953" + "2E4444463031" + "00",
                // SELECT the Visa ADF
                "00A4040007" + TestCards.VISA_AID + "00",
                // GPO carrying TTQ 28000000 and amount 1234 as n12
                "80A800000C830A2800000000000000123400",
                // READ RECORD across both AFL entries
                "00B2010C00",
                "00B2011400",
                "00B2021400",
                "00B2031400",
            ),
            card.commands,
        )
    }

    @Test
    fun `fills the pdol from the terminal profile`() {
        val card = TestCards.visaCard()

        flow(card, amountMinor = 999_99).execute()

        val gpo = card.commands.first { it.startsWith("80A8") }
        assertTrue(gpo.contains("28000000"), "TTQ should come from the terminal profile")
        assertTrue(gpo.contains("000000099999"), "amount should be BCD n12")
    }

    @Test
    fun `sends an empty template when the application has no pdol`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(TestCards.VISA_AID to TestCards.visaApplication(fci = TestCards.VISA_ADF_FCI_NO_PDOL)),
        )

        assertIs<EmvReadResult.Success>(flow(card).execute())
        assertTrue(card.commands.any { it == "80A80000028300" + "00" })
    }

    @Test
    fun `accepts a format 1 gpo response`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(TestCards.VISA_AID to TestCards.visaApplication(gpo = TestCards.GPO_FORMAT_1)),
        )

        val success = assertIs<EmvReadResult.Success>(flow(card).execute())
        success.card.use {
            assertEquals("1980", it.tlv.hex(EmvTags.AIP))
            assertEquals(4, it.afl.sumOf { entry -> entry.recordCount })
        }
    }

    // ---------------------------------------------------------------------------------------
    // Application selection
    // ---------------------------------------------------------------------------------------

    @Test
    fun `falls back to the aid list when there is no ppse`() {
        val card = SimulatedCard(
            ppseFciHex = null,
            applications = mapOf(TestCards.VISA_AID to TestCards.visaApplication()),
        )

        val success = assertIs<EmvReadResult.Success>(flow(card).execute())

        success.card.use { assertEquals(CardScheme.VISA, it.scheme) }
        assertTrue(success.warnings.any { it.contains("AID list") })
    }

    @Test
    fun `fails cleanly when the aid list fallback is disabled`() {
        val card = SimulatedCard(null, mapOf(TestCards.VISA_AID to TestCards.visaApplication()))

        val failure = assertIs<EmvReadResult.Failure>(
            flow(card, options = ReadOptions(allowAidListFallback = false)).execute(),
        )

        assertEquals(ReadStage.PPSE_SELECT, failure.stage)
        assertEquals(ReadErrorCode.NO_SUPPORTED_APPLICATION, failure.code)
    }

    @Test
    fun `rejects a card whose only application is unregistered`() {
        val card = SimulatedCard(TestCards.PPSE_FCI_UNSUPPORTED, emptyMap())

        val failure = assertIs<EmvReadResult.Failure>(flow(card).execute())

        assertEquals(ReadErrorCode.NO_SUPPORTED_APPLICATION, failure.code)
    }

    @Test
    fun `moves to the next candidate when gpo returns conditions not satisfied`() {
        // EMV Contactless Book B 3.3.2.4: 6985 removes the candidate rather than ending the read.
        val card = SimulatedCard(
            TestCards.PPSE_FCI_TWO_APPS,
            mapOf(
                TestCards.MASTERCARD_AID to SimulatedApplication(
                    fciHex = TestCards.MASTERCARD_ADF_FCI,
                    gpoResponseHex = "",
                    gpoStatusWord = 0x6985,
                ),
                TestCards.VISA_AID to TestCards.visaApplication(),
            ),
        )

        val success = assertIs<EmvReadResult.Success>(flow(card).execute())

        success.card.use { assertEquals(CardScheme.VISA, it.scheme) }
        assertTrue(success.warnings.any { it.contains(TestCards.MASTERCARD_AID) })
        // Priority 1 (Mastercard) really was tried first.
        assertEquals(TestCards.MASTERCARD_AID, card.commands[1].substring(10, 24))
    }

    @Test
    fun `a blocked application ends the read rather than falling through`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(
                TestCards.VISA_AID to SimulatedApplication(
                    fciHex = "",
                    gpoResponseHex = "",
                    selectStatusWord = 0x6283,
                ),
            ),
        )

        val failure = assertIs<EmvReadResult.Failure>(flow(card).execute())

        assertEquals(ReadErrorCode.CARD_BLOCKED, failure.code)
        assertFalse(failure.recoverable)
    }

    // ---------------------------------------------------------------------------------------
    // Record reading
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a missing record is a warning by default`() {
        val records = TestCards.VISA_RECORDS.filterKeys { it != (2 to 2) }
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(TestCards.VISA_AID to TestCards.visaApplication(records = records)),
        )

        val success = assertIs<EmvReadResult.Success>(flow(card).execute())

        assertTrue(success.warnings.any { it.contains("sfi=2 rec=2") })
    }

    @Test
    fun `a missing record ends the read in strict mode`() {
        val records = TestCards.VISA_RECORDS.filterKeys { it != (2 to 2) }
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(TestCards.VISA_AID to TestCards.visaApplication(records = records)),
        )

        val failure = assertIs<EmvReadResult.Failure>(
            flow(card, options = ReadOptions(strictAflReads = true)).execute(),
        )

        assertEquals(ReadStage.READ_RECORDS, failure.stage)
    }

    @Test
    fun `falls back to track 2 when tag 5A is absent`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(
                TestCards.VISA_AID to TestCards.visaApplication(
                    records = mapOf(
                        (1 to 1) to TestCards.RECORD_SFI1_REC1,
                        (2 to 1) to TestCards.RECORD_SFI2_REC1_NO_PAN,
                        (2 to 2) to TestCards.RECORD_SFI2_REC2,
                        (2 to 3) to TestCards.RECORD_SFI2_REC3,
                    ),
                ),
            ),
        )

        val success = assertIs<EmvReadResult.Success>(flow(card).execute())

        success.card.use { assertEquals("0010", assertNotNull(it.pan).last4) }
    }

    @Test
    fun `fails when neither tag 5A nor track 2 yields a pan`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(
                TestCards.VISA_AID to TestCards.visaApplication(
                    records = mapOf((2 to 1) to TestCards.RECORD_SFI2_REC1_NO_PAN),
                ),
            ),
        )

        val failure = assertIs<EmvReadResult.Failure>(flow(card).execute())

        assertEquals(ReadStage.DATA_EXTRACTION, failure.stage)
        assertEquals(ReadErrorCode.MISSING_MANDATORY_DATA, failure.code)
    }

    @Test
    fun `stops after the record ceiling`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(TestCards.VISA_AID to TestCards.visaApplication()),
        )

        val success = assertIs<EmvReadResult.Success>(
            flow(card, options = ReadOptions(maxRecordsToRead = 2)).execute(),
        )

        assertEquals(2, card.commands.count { it.startsWith("00B2") })
        assertTrue(success.warnings.any { it.contains("Stopped after 2 records") })
    }

    // ---------------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a lost tag becomes a transport failure`() {
        val card = SimulatedCard(
            TestCards.PPSE_FCI_VISA,
            mapOf(TestCards.VISA_AID to TestCards.visaApplication()),
            failAtCommand = 3,
        )

        val failure = assertIs<EmvReadResult.Failure>(flow(card).execute())

        assertEquals(ReadErrorCode.TRANSPORT_ERROR, failure.code)
    }

    @Test
    fun `a response shorter than a status word is a transport failure`() {
        val truncating = ApduTransceiver { ByteArray(1) }

        val failure = assertIs<EmvReadResult.Failure>(flow(truncating).execute())

        assertEquals(ReadErrorCode.TRANSPORT_ERROR, failure.code)
    }

    @Test
    fun `a transceiver that throws does not escape as an exception`() {
        val broken = ApduTransceiver { throw ApduTransportException("radio down") }

        val failure = assertIs<EmvReadResult.Failure>(flow(broken).execute())

        assertEquals(ReadErrorCode.TRANSPORT_ERROR, failure.code)
        assertEquals("radio down", failure.message)
    }

    // ---------------------------------------------------------------------------------------
    // Data protection
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the apdu trace never contains the pan`() {
        val result = flow(TestCards.visaCard()).execute()

        val trace = assertIs<EmvReadResult.Success>(result).trace.format()
        // Command hex is rendered byte-spaced, so check a whitespace-free copy too - otherwise a
        // leak of "47 61 73 90 ..." would slip past a plain contains() check.
        val compact = trace.filterNot { it.isWhitespace() }

        assertFalse(compact.contains(TestCards.VISA_PAN), "trace leaked the PAN")
        assertFalse(compact.contains("CARDHOLDER"), "trace leaked the cardholder name")
        assertTrue(trace.contains("57=<19B>"), "track 2 should be present but withheld")
        // Non-sensitive elements stay legible, otherwise the trace is useless for debugging.
        assertTrue(trace.contains("SELECT PPSE"))
        assertTrue(trace.contains("251231"), "expiry date is not sensitive and should be shown")
    }

    @Test
    fun `an unredacted trace is available when explicitly requested`() {
        val result = flow(
            TestCards.visaCard(),
            options = ReadOptions(redactTrace = false),
        ).execute()

        val compact = assertIs<EmvReadResult.Success>(result).trace.format()
            .filterNot { it.isWhitespace() }

        assertTrue(compact.contains(TestCards.VISA_PAN))
    }

    @Test
    fun `redact produces a card safe to persist`() {
        val success = assertIs<EmvReadResult.Success>(flow(TestCards.visaCard()).execute())

        val redacted = success.card.use { it.redact(today = LocalDate.of(2026, 3, 4)) }

        assertEquals("************0010", redacted.maskedPan)
        assertEquals("0010", redacted.panLast4)
        assertEquals("2025-12", redacted.expiry)
        assertEquals(true, redacted.expired)
        assertEquals(true, redacted.panLuhnValid)
        assertEquals(CardScheme.VISA, redacted.scheme)
        assertTrue(redacted.cardholderNamePresent)

        // Nothing in the redacted record reproduces the number or the name.
        assertFalse(redacted.toString().contains(TestCards.VISA_PAN))
        assertFalse(redacted.toString().contains("CARDHOLDER"))
    }

    @Test
    fun `close wipes the pan and the sensitive tlv values`() {
        val success = assertIs<EmvReadResult.Success>(flow(TestCards.visaCard()).execute())
        val card = success.card

        assertNotNull(card.tlv[EmvTags.TRACK_2_EQUIVALENT_DATA])
        card.close()

        assertNull(card.tlv[EmvTags.TRACK_2_EQUIVALENT_DATA])
        assertNull(card.tlv[EmvTags.PAN])
        assertNull(card.tlv[EmvTags.CARDHOLDER_NAME])
        // Non-sensitive elements survive so the record stays useful for diagnostics.
        assertNotNull(card.tlv[EmvTags.AIP])
    }
}
