package com.softpos.sdk.export

import com.softpos.emv.txn.TransactionState
import com.softpos.sdk.data.CardSnapshot
import com.softpos.sdk.data.TransactionEntity
import com.softpos.sdk.data.TransactionItemEntity
import com.softpos.sdk.data.TransactionWithDetails
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The application label reaching these rows comes from tag 50 or tag 9F12, which the card supplies.
 * A CSV export is opened in a spreadsheet, so anything the card can put in that column is a
 * potential formula.
 */
class TransactionExporterTest {

    private val exporter = TransactionExporter(
        Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC),
    )

    @Test
    fun `prefixes an equals formula so no spreadsheet evaluates it`() {
        assertTrue(csvRow(label = "=1+1").contains(",'=1+1,"), csvRow(label = "=1+1"))
    }

    @Test
    fun `covers the remaining formula prefixes`() {
        assertTrue(csvRow(label = "+1").contains(",'+1,"))
        assertTrue(csvRow(label = "-1").contains(",'-1,"))
        assertTrue(csvRow(label = "@SUM(A1)").contains(",'@SUM(A1),"))
    }

    @Test
    fun `neutralises a formula that also needs quoting`() {
        val row = csvRow(label = """=HYPERLINK("http://example.invalid")""")

        // Quoting alone would not help - a formula fires inside a quoted cell too - so the
        // apostrophe has to survive the quoting.
        assertTrue(row.contains("\"'=HYPERLINK"), row)
    }

    @Test
    fun `leaves an ordinary label untouched`() {
        val row = csvRow(label = "VISA CREDIT")

        assertTrue(row.contains(",VISA CREDIT,"), row)
        assertFalse(row.contains("'VISA"))
    }

    @Test
    fun `still quotes a field containing a comma`() {
        assertTrue(csvRow(label = "VISA, CREDIT").contains(""""VISA, CREDIT""""))
    }

    @Test
    fun `does not disturb the numeric columns`() {
        val row = csvRow(label = "VISA")

        // reference, created, updated, state, attempts, amount_minor, ...
        val columns = row.split(",")
        assertEquals("1", columns[4])
        assertEquals("1250", columns[5])
    }

    private fun csvRow(label: String): String =
        exporter.export(listOf(details(label)), ExportFormat.CSV)
            .lineSequence()
            .drop(1)
            .first()

    private fun details(label: String) = TransactionWithDetails(
        transaction = TransactionEntity(
            id = "id-1",
            reference = "T00000001",
            amountMinor = 1250,
            currency = "USD",
            state = TransactionState.PROCESSED,
            attemptCount = 1,
            failureReason = null,
            createdAtEpochMillis = 1_767_322_845_000,
            updatedAtEpochMillis = 1_767_322_845_000,
            nextRetryAtEpochMillis = null,
            card = CardSnapshot(
                scheme = "VISA",
                kernel = "KERNEL_3",
                aid = "A0000000031010",
                label = label,
                maskedPan = "************0010",
                last4 = "0010",
                expiry = "2025-12",
                expired = false,
                fingerprint = "0123456789abcdef0123456789abcdef",
            ),
        ),
        items = listOf(
            TransactionItemEntity(
                transactionId = "id-1",
                sku = "ESP-001",
                name = "Espresso",
                unitPriceMinor = 1250,
                quantity = 1,
            ),
        ),
        events = emptyList(),
    )
}
