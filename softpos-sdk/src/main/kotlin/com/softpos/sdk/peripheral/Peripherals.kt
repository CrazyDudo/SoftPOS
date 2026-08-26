package com.softpos.sdk.peripheral

import android.util.Log
import com.softpos.sdk.data.TransactionWithDetails
import com.softpos.sdk.data.lineTotalMinor

/**
 * Optional peripherals.
 *
 * The interfaces are real; the implementations shipped here are not. Printers, cash drawers and
 * scanners each speak a vendor-specific protocol over Bluetooth SPP, USB or a serial bridge, and
 * writing one blind would mean inventing a command set. See [LoggingReceiptPrinter] for what is
 * actually wired up, and the TODOs on each interface for what a real integration must supply.
 */

data class Receipt(
    val merchantName: String,
    val reference: String,
    val lines: List<String>,
    val totalText: String,
    val cardText: String?,
    val timestampText: String,
    val footer: String,
)

interface ReceiptPrinter {
    val isAvailable: Boolean
    suspend fun print(receipt: Receipt): Result<Unit>
}

interface CashDrawer {
    val isAvailable: Boolean

    /** Most drawers open via a pulse on the printer's DK port rather than a direct connection. */
    suspend fun open(): Result<Unit>
}

interface BarcodeScanner {
    val isAvailable: Boolean

    /** @return the decoded symbol, or null if scanning was cancelled. */
    suspend fun scan(): Result<String?>
}

/** Prints to logcat. Enough to exercise the receipt path without any hardware attached. */
class LoggingReceiptPrinter(private val tag: String = "SoftPosReceipt") : ReceiptPrinter {

    override val isAvailable: Boolean = true

    override suspend fun print(receipt: Receipt): Result<Unit> {
        Log.i(tag, buildString {
            appendLine("--------------------------------")
            appendLine(receipt.merchantName)
            appendLine(receipt.timestampText)
            appendLine("Ref ${receipt.reference}")
            appendLine("--------------------------------")
            receipt.lines.forEach { appendLine(it) }
            appendLine("--------------------------------")
            appendLine(receipt.totalText)
            receipt.cardText?.let { appendLine(it) }
            appendLine(receipt.footer)
            appendLine("--------------------------------")
        })
        return Result.success(Unit)
    }
}

/**
 * Stand-in for hardware that is not attached.
 *
 * Reports itself unavailable and fails every call, so a caller that forgets to check
 * [isAvailable] surfaces the problem instead of silently appearing to succeed.
 */
class UnavailablePeripheral(private val name: String) : ReceiptPrinter, CashDrawer, BarcodeScanner {

    override val isAvailable: Boolean = false

    private fun <T> unavailable(): Result<T> = Result.failure(IllegalStateException("$name is not connected"))

    override suspend fun print(receipt: Receipt): Result<Unit> = unavailable()

    override suspend fun open(): Result<Unit> = unavailable()

    override suspend fun scan(): Result<String?> = unavailable()
}

/**
 * TODO: Bluetooth SPP printer.
 *  Needs BLUETOOTH_CONNECT at runtime on API 31+, a bonded device chosen by the operator, an
 *  RFCOMM socket to the well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB, and an encoder
 *  for the target command set (ESC/POS on most thermal printers, but not all). Left unimplemented
 *  rather than guessed at.
 *
 * TODO: USB printer.
 *  Needs UsbManager permission, interface and endpoint discovery, and bulk transfers.
 *
 * TODO: barcode scanning.
 *  Either CameraX plus an on-device decoder, or a hardware scanner that emits key events.
 */

object Receipts {

    fun from(
        details: TransactionWithDetails,
        merchantName: String,
        formatAmount: (Long) -> String,
        timestampText: String,
    ): Receipt {
        val transaction = details.transaction
        return Receipt(
            merchantName = merchantName,
            reference = transaction.reference,
            lines = details.items.map { item ->
                "${item.quantity} x ${item.name}".padEnd(24).take(24) +
                    formatAmount(item.lineTotalMinor).padStart(8)
            },
            totalText = "TOTAL".padEnd(24) + formatAmount(transaction.amountMinor).padStart(8),
            cardText = transaction.card?.let { card ->
                listOfNotNull(card.label ?: card.scheme, card.maskedPan).joinToString("  ")
            },
            timestampText = timestampText,
            // States plainly what this receipt is not, so a printed slip is never mistaken for
            // evidence that a payment was taken.
            footer = "OFFLINE PROTOTYPE - NOT A PAYMENT RECEIPT",
        )
    }
}
