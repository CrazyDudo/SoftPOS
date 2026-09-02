package com.softpos.emv.cvm

import com.softpos.emv.model.EmvKernel
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.TlvDatabase
import com.softpos.emv.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CvmListTest {

    /**
     * X = 1000, Y = 5000, then: enciphered PIN online if over X (continue on failure), signature
     * if terminal supports it (continue), no CVM if under X, fail otherwise.
     */
    private val listHex = "000003E8" + "00001388" + "4207" + "5E03" + "1F06" + "0000"

    @Test
    fun `parses amounts and rules`() {
        val list = assertNotNull(CvmList.parse(listHex.hexToBytes()))

        assertEquals(1000, list.amountX)
        assertEquals(5000, list.amountY)
        assertEquals(4, list.rules.size)
        assertEquals(CvmMethod.ENCIPHERED_PIN_ONLINE, list.rules[0].method)
        assertEquals(CvmCondition.OVER_X, list.rules[0].condition)
        assertTrue(list.rules[0].applyNextIfUnsuccessful)
        assertEquals(CvmMethod.NO_CVM_REQUIRED, list.rules[2].method)
        assertEquals(CvmCondition.UNDER_X, list.rules[2].condition)
        assertEquals(CvmMethod.FAIL, list.rules[3].method)
    }

    @Test
    fun `rejects a list that is too short or has a dangling byte`() {
        assertNull(CvmList.parse("000003E8".hexToBytes()))
        assertNull(CvmList.parse((listHex + "1F").hexToBytes()))
    }

    @Test
    fun `a small purchase needs no cvm`() {
        val decision = decide(amount = 500)

        assertEquals(CvmOutcome.NO_CVM_REQUIRED, decision.outcome)
        assertEquals(CvmSource.CVM_LIST, decision.source)
    }

    @Test
    fun `a large purchase asks for online pin which this reader lacks and falls through to fail`() {
        // Over X: online PIN (unsupported, continue), signature (unsupported, continue),
        // no-CVM rule does not apply over X, then the FAIL rule.
        val decision = decide(amount = 2000)

        assertEquals(CvmOutcome.FAILED, decision.outcome)
        assertTrue(decision.detail.contains("Fail CVM"))
    }

    @Test
    fun `a reader with online pin support gets that decision`() {
        val decision = decide(amount = 2000, supports = setOf(CvmMethod.NO_CVM_REQUIRED, CvmMethod.ENCIPHERED_PIN_ONLINE))

        assertEquals(CvmOutcome.ONLINE_PIN_REQUIRED, decision.outcome)
    }

    @Test
    fun `amount conditions do not apply across currencies`() {
        // Transaction in EUR, application currency USD: the under-X rule cannot apply, and the
        // over-X rule cannot either, so the list reaches FAIL.
        val decision = decide(amount = 500, transactionCurrency = "0978")

        assertEquals(CvmOutcome.FAILED, decision.outcome)
    }

    @Test
    fun `an unsupported method without the continue bit stops the list`() {
        val strict = "000003E8" + "00001388" + "0200" + "1F00"
        val decision = CardholderVerification.fromCvmList(
            assertNotNull(CvmList.parse(strict.hexToBytes())),
            amountMinor = 100,
            transactionCurrencyCode = "0840",
            applicationCurrencyCode = "0840",
            terminalSupports = setOf(CvmMethod.NO_CVM_REQUIRED),
        )

        assertEquals(CvmOutcome.UNSUPPORTED, decision.outcome)
    }

    private fun decide(
        amount: Long,
        transactionCurrency: String = "0840",
        supports: Set<CvmMethod> = setOf(CvmMethod.NO_CVM_REQUIRED),
    ): CvmDecision = CardholderVerification.fromCvmList(
        assertNotNull(CvmList.parse(listHex.hexToBytes())),
        amountMinor = amount,
        transactionCurrencyCode = transactionCurrency,
        applicationCurrencyCode = "0840",
        terminalSupports = supports,
    )
}

class CardTransactionQualifiersTest {

    @Test
    fun `decodes the ctq bits`() {
        val ctq = CardTransactionQualifiers("C080".hexToBytes())

        assertTrue(ctq.onlinePinRequired)
        assertTrue(ctq.signatureRequired)
        assertTrue(ctq.consumerDeviceCvmPerformed)
        assertEquals("C080", ctq.hex)
    }

    @Test
    fun `consumer device cvm wins over a pin request`() {
        assertEquals(
            CvmOutcome.CONSUMER_DEVICE_CVM_PERFORMED,
            CardholderVerification.fromCtq(CardTransactionQualifiers("8080".hexToBytes())).outcome,
        )
        assertEquals(
            CvmOutcome.ONLINE_PIN_REQUIRED,
            CardholderVerification.fromCtq(CardTransactionQualifiers("8000".hexToBytes())).outcome,
        )
        assertEquals(
            CvmOutcome.SIGNATURE_REQUIRED,
            CardholderVerification.fromCtq(CardTransactionQualifiers("4000".hexToBytes())).outcome,
        )
        assertEquals(
            CvmOutcome.NO_CVM_REQUIRED,
            CardholderVerification.fromCtq(CardTransactionQualifiers("0000".hexToBytes())).outcome,
        )
    }

    @Test
    fun `kernel 3 decides from the ctq and kernel 2 from the cvm list`() {
        val db = TlvDatabase.builder()
            .put(EmvTags.CARD_TRANSACTION_QUALIFIERS, "8000".hexToBytes())
            .put(EmvTags.CVM_LIST, ("000003E8" + "00001388" + "1F00").hexToBytes())
            .put(EmvTags.APPLICATION_CURRENCY_CODE, "0840".hexToBytes())
            .build()

        assertEquals(CvmSource.CTQ, CardholderVerification.decide(EmvKernel.KERNEL_3, db, 100, "0840").source)
        assertEquals(CvmSource.CVM_LIST, CardholderVerification.decide(EmvKernel.KERNEL_2, db, 100, "0840").source)
        assertEquals(CvmOutcome.NOT_EVALUATED, CardholderVerification.decide(EmvKernel.KERNEL_2, TlvDatabase.empty(), 100, "0840").outcome)
    }
}
