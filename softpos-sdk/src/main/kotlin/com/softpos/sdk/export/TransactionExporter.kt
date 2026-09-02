package com.softpos.sdk.export

import com.softpos.sdk.data.TransactionWithDetails
import com.softpos.sdk.data.lineTotalMinor
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
}

/**
 * Renders the local transaction log for inspection.
 *
 * "Export" means writing a file the operator can read; nothing is uploaded. The output carries the
 * same reduced card data the database holds - masked PAN, last four digits, fingerprint - and never
 * the encrypted PAN blob, because a file dropped into shared storage would otherwise re-create the
 * exposure the storage design avoids.
 */
class TransactionExporter(private val clock: Clock) {

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun export(transactions: List<TransactionWithDetails>, format: ExportFormat): String =
        when (format) {
            ExportFormat.CSV -> toCsv(transactions)
            ExportFormat.JSON -> toJson(transactions)
        }

    fun suggestedFileName(format: ExportFormat): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(clock.millis()))
        return "softpos-transactions-$stamp.${format.extension}"
    }

    private fun toCsv(transactions: List<TransactionWithDetails>): String = buildString {
        appendLine(
            listOf(
                "reference", "created", "updated", "state", "attempts", "amount_minor", "currency",
                "item_count", "card_scheme", "card_label", "masked_pan", "card_expiry",
                "card_fingerprint", "failure_reason",
            ).joinToString(","),
        )
        for (details in transactions) {
            val t = details.transaction
            appendLine(
                listOf(
                    t.reference,
                    timestampFormat.format(Instant.ofEpochMilli(t.createdAtEpochMillis)),
                    timestampFormat.format(Instant.ofEpochMilli(t.updatedAtEpochMillis)),
                    t.state.name,
                    t.attemptCount.toString(),
                    t.amountMinor.toString(),
                    t.currency,
                    details.itemCount.toString(),
                    t.card?.scheme.orEmpty(),
                    t.card?.label.orEmpty(),
                    t.card?.maskedPan.orEmpty(),
                    t.card?.expiry.orEmpty(),
                    t.card?.fingerprint.orEmpty(),
                    t.failureReason.orEmpty(),
                ).joinToString(",") { csvField(it) },
            )
        }
    }

    private fun toJson(transactions: List<TransactionWithDetails>): String = buildString {
        appendLine("{")
        appendLine("""  "exportedAt": "${timestampFormat.format(Instant.ofEpochMilli(clock.millis()))}",""")
        appendLine("""  "note": "Offline prototype record. No payment was authorised, cleared or settled.",""")
        appendLine("""  "transactions": [""")
        transactions.forEachIndexed { index, details ->
            val t = details.transaction
            appendLine("    {")
            appendLine("""      "reference": ${jsonString(t.reference)},""")
            appendLine("""      "state": ${jsonString(t.state.name)},""")
            appendLine("""      "attempts": ${t.attemptCount},""")
            appendLine("""      "amountMinor": ${t.amountMinor},""")
            appendLine("""      "currency": ${jsonString(t.currency)},""")
            appendLine("""      "createdAt": ${jsonString(timestampFormat.format(Instant.ofEpochMilli(t.createdAtEpochMillis)))},""")
            appendLine("""      "failureReason": ${jsonString(t.failureReason)},""")
            appendLine("""      "card": ${cardJson(details)},""")
            appendLine("""      "items": [""")
            details.items.forEachIndexed { itemIndex, item ->
                val comma = if (itemIndex == details.items.lastIndex) "" else ","
                appendLine(
                    "        {" +
                        """"sku": ${jsonString(item.sku)}, """ +
                        """"name": ${jsonString(item.name)}, """ +
                        """"quantity": ${item.quantity}, """ +
                        """"unitPriceMinor": ${item.unitPriceMinor}, """ +
                        """"lineTotalMinor": ${item.lineTotalMinor}}$comma""",
                )
            }
            appendLine("      ]")
            appendLine(if (index == transactions.lastIndex) "    }" else "    },")
        }
        appendLine("  ]")
        append("}")
    }

    private fun cardJson(details: TransactionWithDetails): String {
        val card = details.transaction.card ?: return "null"
        return "{" +
            """"scheme": ${jsonString(card.scheme)}, """ +
            """"label": ${jsonString(card.label)}, """ +
            """"maskedPan": ${jsonString(card.maskedPan)}, """ +
            """"last4": ${jsonString(card.last4)}, """ +
            """"expiry": ${jsonString(card.expiry)}, """ +
            """"fingerprint": ${jsonString(card.fingerprint)}}"""
    }

    /**
     * Quotes a CSV field, and defuses the spreadsheet formula prefixes first.
     *
     * Card-supplied text reaches this function. The application label comes from tag 50 or tag
     * 9F12, both of which the card chooses, so a hostile card can name itself
     * `=HYPERLINK("http://…"&A1)` and have that evaluated the moment a merchant opens the export in
     * Excel, Sheets or LibreOffice. Quoting alone does not help: the formula fires inside a quoted
     * cell too. Prefixing with an apostrophe is the standard mitigation - the spreadsheet then
     * treats the cell as literal text and shows the characters as written.
     *
     * The amount and count columns are produced here from `Long`s and are never negative, so
     * guarding `-` costs nothing legitimate.
     */
    private fun csvField(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_PREFIXES) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val escaped = buildString(value.length + 2) {
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }

    private companion object {
        /**
         * A leading one of these starts formula evaluation in Excel, Google Sheets and LibreOffice
         * Calc. Tab and carriage return are in the list because a leading whitespace character is
         * skipped by the parser, which puts the next character back at the start of the cell.
         */
        const val FORMULA_PREFIXES = "=+-@\t\r"
    }
}
