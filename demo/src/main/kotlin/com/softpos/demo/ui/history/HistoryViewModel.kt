package com.softpos.demo.ui.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.softpos.emv.txn.TransactionEvent
import com.softpos.emv.txn.TransactionState
import com.softpos.emv.txn.TransitionResult
import com.softpos.demo.DemoContainer
import com.softpos.sdk.data.TransactionWithDetails
import com.softpos.sdk.export.ExportFormat
import com.softpos.sdk.repository.CartLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HistoryViewModel(private val container: DemoContainer) : ViewModel() {

    private val softPos = container.softPos

    val transactions: StateFlow<List<TransactionWithDetails>> = softPos.transactions.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun formatAmount(minorUnits: Long): String =
        "${softPos.config.currency} ${softPos.formatAmount(minorUnits)}"

    /**
     * Runs a pending or scheduled attempt now rather than waiting for its backoff to elapse.
     *
     * The work is the same reservation the original attempt made, so an attempt that failed for
     * lack of stock fails again and eventually exhausts the retry budget. The transition is still
     * the state machine's decision: a transaction in the wrong state comes back rejected and
     * nothing changes.
     */
    fun retryNow(id: String) = viewModelScope.launch {
        val outcome = softPos.transactions.process(id) { details ->
            runCatching {
                softPos.catalog.reserve(
                    details.items.map { CartLine(it.sku, it.name, it.unitPriceMinor, it.quantity) },
                )
            }
        }

        if (outcome is TransitionResult.Allowed && outcome.to == TransactionState.FAILED) {
            _message.value = describe(softPos.transactions.resolveFailure(id))
        } else {
            _message.value = describe(outcome)
        }
    }

    fun abandon(id: String) = viewModelScope.launch {
        _message.value = describe(
            softPos.transactions.applyEvent(id, TransactionEvent.ABANDON, "Abandoned by the operator"),
        )
    }

    fun cancel(id: String) = viewModelScope.launch {
        _message.value = describe(
            softPos.transactions.applyEvent(id, TransactionEvent.CANCEL, "Cancelled by the operator"),
        )
    }

    /**
     * Writes the log to the cache and hands it to the share sheet.
     *
     * The file carries the same reduced card data the database holds. The encrypted PAN blob is
     * never exported, so a shared file cannot be turned back into a card number.
     */
    fun exportAndShare(context: Context, format: ExportFormat) = viewModelScope.launch {
        runCatching {
            val content = softPos.exporter.export(softPos.transactions.allWithDetails(), format)
            val fileName = softPos.exporter.suggestedFileName(format)

            val uri = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(directory, fileName)
                file.writeText(content)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export transactions").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            fileName
        }.onSuccess {
            _message.value = "Exported $it"
        }.onFailure {
            _message.value = "Export failed: ${it.message}"
        }
    }

    fun wipeEverything() = viewModelScope.launch {
        softPos.wipeAllCardData()
        _message.value = "Transaction history cleared and Keystore keys destroyed"
    }

    private fun describe(outcome: TransitionResult): String = when (outcome) {
        is TransitionResult.Allowed -> "${outcome.from} -> ${outcome.to}"
        is TransitionResult.Rejected -> "Rejected: ${outcome.reason}"
    }

    companion object {
        fun factory(container: DemoContainer) = viewModelFactory {
            initializer { HistoryViewModel(container) }
        }
    }
}
