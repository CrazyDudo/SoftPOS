package com.softpos.sdk.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.softpos.emv.apdu.ApduTransportException
import com.softpos.emv.flow.EmvReadFlow
import com.softpos.emv.flow.EmvReadResult
import com.softpos.emv.flow.ReadErrorCode
import com.softpos.emv.flow.ReadOptions
import com.softpos.emv.flow.ReadStage
import com.softpos.sdk.SoftPosConfig
import com.softpos.sdk.security.CapturedCard
import com.softpos.sdk.security.CardVault
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class NfcAvailability {
    READY,
    NO_HARDWARE,
    DISABLED,
}

sealed interface CardReadEvent {

    /** Reader mode is active and the terminal is waiting for a card. */
    data object Waiting : CardReadEvent

    /** A tag entered the field; the EMV exchange is starting. */
    data object CardDetected : CardReadEvent

    /**
     * The card was read. Raw card data has already been wiped by [CardVault.ingest] - only the
     * masked view reaches this event.
     *
     * @param warnings non-fatal anomalies, e.g. a record the AFL promised but the card lacks.
     * @param trace redacted APDU log, present only when tracing is enabled in the configuration.
     */
    data class Completed(
        val captured: CapturedCard,
        val warnings: List<String>,
        val trace: String?,
    ) : CardReadEvent

    data class Failed(
        val stage: ReadStage,
        val code: ReadErrorCode,
        val message: String,
        val trace: String?,
    ) : CardReadEvent

    data class Unavailable(val reason: NfcAvailability) : CardReadEvent
}

/**
 * Drives `NfcAdapter.enableReaderMode` and turns each tap into a [CardReadEvent].
 *
 * Reader mode rather than foreground dispatch, because it is the only API that lets a terminal
 * take exclusive control of the controller, suppress Android Beam and the platform tap sound, and
 * skip the NDEF check that would otherwise delay every exchange.
 */
class NfcCardReader(
    private val config: SoftPosConfig,
    private val vault: CardVault,
) {

    fun availability(activity: Activity): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return NfcAvailability.NO_HARDWARE
        return if (adapter.isEnabled) NfcAvailability.READY else NfcAvailability.DISABLED
    }

    /**
     * Emits reader events for as long as the flow is collected; reader mode is disabled when
     * collection stops.
     *
     * @param amountMinor evaluated per tap, so the amount always reflects the current basket.
     *   The value feeds tag 9F02 in the PDOL response.
     */
    fun reads(activity: Activity, amountMinor: () -> Long): Flow<CardReadEvent> = callbackFlow {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) {
            trySend(CardReadEvent.Unavailable(NfcAvailability.NO_HARDWARE))
            close()
            return@callbackFlow
        }
        if (!adapter.isEnabled) {
            trySend(CardReadEvent.Unavailable(NfcAvailability.DISABLED))
            close()
            return@callbackFlow
        }

        // ReaderCallback is invoked on a binder thread, never the main thread, so the blocking
        // APDU exchange below is safe to run inline.
        val callback = NfcAdapter.ReaderCallback { tag -> handleTag(tag, amountMinor()) { trySend(it) } }

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, config.presenceCheckDelayMillis)
        }

        adapter.enableReaderMode(activity, callback, READER_FLAGS, extras)
        trySend(CardReadEvent.Waiting)

        awaitClose { adapter.disableReaderMode(activity) }
    }

    private fun handleTag(tag: Tag, amountMinor: Long, emit: (CardReadEvent) -> Unit) {
        emit(CardReadEvent.CardDetected)
        val startedAt = SystemClock.elapsedRealtime()
        log("Tag detected: techs=${tag.techList.joinToString { it.substringAfterLast('.') }} amount=$amountMinor")

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            log("Tag has no IsoDep; not a contactless payment card", warn = true)
            emit(
                CardReadEvent.Failed(
                    stage = ReadStage.PPSE_SELECT,
                    code = ReadErrorCode.TRANSPORT_ERROR,
                    message = "Tag does not support ISO-DEP; contactless payment cards do",
                    trace = null,
                ),
            )
            return
        }

        val options = ReadOptions(
            traceApdu = config.traceApdu,
            redactTrace = config.redactApduTrace,
            strictAflReads = config.strictAflReads,
        )

        try {
            IsoDepTransceiver.open(isoDep, config.transceiveTimeoutMillis).use { transceiver ->
                log("Connected: maxTransceiveLength=${transceiver.maxTransceiveLength}")

                val result = EmvReadFlow(
                    transceiver = transceiver,
                    terminal = config.terminalProfile,
                    amountMinor = amountMinor,
                    registry = config.aidRegistry,
                    options = options,
                ).execute()

                val trace = if (config.traceApdu) result.trace.format() else null
                val elapsed = SystemClock.elapsedRealtime() - startedAt

                when (result) {
                    is EmvReadResult.Success -> {
                        // ingest() consumes and wipes the raw card data; nothing past this line can
                        // reach the PAN, including the log statement below.
                        val captured = vault.ingest(result.card)
                        log(
                            "Read OK in ${elapsed}ms: ${captured.card.scheme.displayName} " +
                                "${captured.card.maskedPan} exp=${captured.card.expiry} " +
                                "aid=${captured.card.aidHex} records=${captured.card.recordCount}",
                        )
                        result.warnings.forEach { log("  warning: $it", warn = true) }
                        trace?.let { log(it) }
                        emit(CardReadEvent.Completed(captured, result.warnings, trace))
                    }

                    is EmvReadResult.Failure -> {
                        log(
                            "Read FAILED in ${elapsed}ms at ${result.stage}/${result.code}: " +
                                "${result.message}" + (result.statusWord?.let { " SW=$it" } ?: ""),
                            warn = true,
                        )
                        trace?.let { log(it) }
                        emit(CardReadEvent.Failed(result.stage, result.code, result.message, trace))
                    }
                }
            }
        } catch (e: ApduTransportException) {
            log("Transport failure after ${SystemClock.elapsedRealtime() - startedAt}ms: ${e.message}", warn = true)
            emit(
                CardReadEvent.Failed(
                    stage = ReadStage.PPSE_SELECT,
                    code = ReadErrorCode.TRANSPORT_ERROR,
                    message = e.message ?: "Card connection lost",
                    trace = null,
                ),
            )
        }
    }

    private fun log(message: String, warn: Boolean = false) {
        if (!config.logToLogcat) return
        // Logcat truncates a single entry near 4 kB; a full trace can exceed that.
        message.lineSequence().chunkedBy(MAX_LOG_CHARS).forEach { chunk ->
            if (warn) Log.w(config.logTag, chunk) else Log.i(config.logTag, chunk)
        }
    }

    private companion object {
        /**
         * NFC-A and NFC-B cover Visa and Mastercard contactless. NFC-F and NFC-V are not payment
         * technologies and are left out so the controller does not poll for them.
         *
         * SKIP_NDEF_CHECK removes a round trip the platform would otherwise make on every tap, and
         * NO_PLATFORM_SOUNDS keeps the terminal in charge of its own feedback.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        const val MAX_LOG_CHARS = 3_500
    }
}

/** Regroups lines so no emitted chunk exceeds [limit] characters. */
private fun Sequence<String>.chunkedBy(limit: Int): Sequence<String> = sequence {
    val buffer = StringBuilder()
    for (line in this@chunkedBy) {
        // A single line longer than the limit is split rather than dropped.
        var remaining: CharSequence = line
        while (remaining.length > limit) {
            if (buffer.isNotEmpty()) {
                yield(buffer.toString())
                buffer.setLength(0)
            }
            yield(remaining.substring(0, limit))
            remaining = remaining.subSequence(limit, remaining.length)
        }
        if (buffer.length + remaining.length + 1 > limit) {
            yield(buffer.toString())
            buffer.setLength(0)
        }
        if (buffer.isNotEmpty()) buffer.append('\n')
        buffer.append(remaining)
    }
    if (buffer.isNotEmpty()) yield(buffer.toString())
}
