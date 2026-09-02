package com.softpos.sdk

import android.app.Activity
import android.content.Context
import com.softpos.emv.capk.CapkRegistry
import com.softpos.emv.model.AidRegistry
import com.softpos.emv.model.MaskPolicy
import com.softpos.emv.terminal.TerminalProfile
import com.softpos.emv.txn.RetryPolicy
import com.softpos.sdk.data.SoftPosDatabase
import com.softpos.sdk.export.TransactionExporter
import com.softpos.sdk.nfc.NfcAvailability
import com.softpos.sdk.nfc.NfcCardReader
import com.softpos.sdk.peripheral.CashDrawer
import com.softpos.sdk.peripheral.LoggingReceiptPrinter
import com.softpos.sdk.peripheral.ReceiptPrinter
import com.softpos.sdk.peripheral.UnavailablePeripheral
import com.softpos.sdk.repository.CatalogRepository
import com.softpos.sdk.repository.TransactionRepository
import com.softpos.sdk.security.CardVault
import com.softpos.sdk.security.KeySecurityLevel
import com.softpos.sdk.security.KeystoreCryptoService
import java.time.Clock

/**
 * SDK configuration.
 *
 * Defaults describe an offline prototype: nothing recoverable is written to disk, APDU traces are
 * redacted, and the terminal advertises no cardholder verification and no offline authentication.
 */
data class SoftPosConfig(
    val terminalProfile: TerminalProfile = TerminalProfile(),

    val aidRegistry: AidRegistry = AidRegistry.Default,

    /**
     * Certification Authority Public Keys.
     *
     * Empty by default, and nothing reads it: this project performs no offline data authentication,
     * so a populated table changes no behaviour. See [CapkRegistry] for where real keys come from
     * and how to validate them.
     */
    val capkRegistry: CapkRegistry = CapkRegistry.Empty,

    /** How much of the PAN survives into storage and onto the screen. */
    val maskPolicy: MaskPolicy = MaskPolicy.LAST_4,

    /**
     * Store the full PAN as AES-256-GCM ciphertext under a Keystore key.
     *
     * Off by default, and it should stay off. Turning it on means the device holds data that can be
     * turned back into a card number, which changes what a lost phone costs you. The brief's rule -
     * keep the last four digits and nothing else - is what the default implements. Only switch this
     * on for your own cards while debugging, and switch it off again afterwards.
     */
    val persistEncryptedPan: Boolean = false,

    val retryPolicy: RetryPolicy = RetryPolicy(),

    /** Per-exchange IsoDep timeout. The platform default of roughly 300 ms is tight for some cards. */
    val transceiveTimeoutMillis: Int = 2_000,

    /**
     * Delay before the platform re-checks that the card is still in the field.
     *
     * The presence check transmits its own frame. Set it too low and those frames interleave with
     * an in-progress EMV exchange, which on some cards ends the session early - the symptom is
     * "Tag lost" partway through a read. A full contactless read finishes well inside a second, so
     * a delay around that mark keeps the check out of the way without leaving a departed card
     * undetected for long.
     */
    val presenceCheckDelayMillis: Int = 1_000,

    /**
     * Mirror each read to logcat under [logTag].
     *
     * The APDU trace is redacted exactly as [redactApduTrace] specifies before it is logged. With
     * that flag false, this writes card data into the system log - a log that other tooling on the
     * device may collect.
     */
    val logToLogcat: Boolean = true,

    val logTag: String = "SoftPos",

    val traceApdu: Boolean = true,

    /**
     * Withhold sensitive tag values from the APDU trace.
     *
     * A raw contactless trace contains tag 57, which is a full PAN. Setting this false produces a
     * log you must treat as card data - never attach one to a bug report.
     */
    val redactApduTrace: Boolean = true,

    /** Treat a record the AFL promised but the card lacks as fatal rather than as a warning. */
    val strictAflReads: Boolean = false,

    val databaseName: String? = "softpos.db",

    val merchantName: String = "SoftPOS Prototype",

    val currency: String = "USD",

    val clock: Clock = Clock.systemDefaultZone(),
)

/**
 * Entry point to the SoftPOS SDK.
 *
 * ```kotlin
 * val softPos = SoftPos.create(context, SoftPosConfig())
 *
 * softPos.cardReader.reads(activity) { cart.totalMinor }
 *     .collect { event -> /* CardReadEvent.Completed carries a masked card only */ }
 * ```
 *
 * ## Scope
 *
 * This SDK reads contactless cards offline and records the result locally. It does not authorise,
 * clear or settle anything, it produces no cryptogram, and it verifies no card. It is not built or
 * assessed against PCI MPoC, PCI DSS or EMVCo certification, and must not be used to take real
 * payments. See [com.softpos.emv.flow.EmvReadFlow] for the specific EMV functions left out.
 */
class SoftPos private constructor(
    val config: SoftPosConfig,
    val database: SoftPosDatabase,
    val crypto: KeystoreCryptoService,
    val vault: CardVault,
    val cardReader: NfcCardReader,
    val transactions: TransactionRepository,
    val catalog: CatalogRepository,
    val exporter: TransactionExporter,
    val printer: ReceiptPrinter,
    val cashDrawer: CashDrawer,
) {

    fun nfcAvailability(activity: Activity): NfcAvailability = cardReader.availability(activity)

    /**
     * Where the Keystore key protecting card-derived data actually lives.
     *
     * [KeySecurityLevel.UNKNOWN] until the first card read, which is when the key is generated.
     */
    fun keySecurityLevel(): KeySecurityLevel = crypto.keySecurityLevel()

    /**
     * True when the AES key sits in a dedicated secure element rather than the TEE. Reports false
     * below API 31 even where StrongBox is in use - [keySecurityLevel] is the honest version.
     */
    fun isStrongBoxBacked(): Boolean = crypto.isStrongBoxBacked()

    fun formatAmount(minorUnits: Long): String = config.terminalProfile.formatAmount(minorUnits)

    /**
     * Destroys the Keystore keys and clears the transaction history.
     *
     * Every stored fingerprint and every encrypted blob becomes permanently unreadable, because the
     * keys that produced them no longer exist.
     */
    suspend fun wipeAllCardData() {
        transactions.deleteAll()
        crypto.deleteKeys()
    }

    fun close() = database.close()

    companion object {

        fun create(
            context: Context,
            config: SoftPosConfig = SoftPosConfig(),
            printer: ReceiptPrinter = LoggingReceiptPrinter(),
            cashDrawer: CashDrawer = UnavailablePeripheral("Cash drawer"),
        ): SoftPos {
            val database = SoftPosDatabase.create(context, config.databaseName)
            val crypto = KeystoreCryptoService()
            val vault = CardVault(crypto, config)

            return SoftPos(
                config = config,
                database = database,
                crypto = crypto,
                vault = vault,
                cardReader = NfcCardReader(config, vault),
                transactions = TransactionRepository(database, config.clock, config.retryPolicy),
                catalog = CatalogRepository(database),
                exporter = TransactionExporter(config.clock),
                printer = printer,
                cashDrawer = cashDrawer,
            )
        }
    }
}
