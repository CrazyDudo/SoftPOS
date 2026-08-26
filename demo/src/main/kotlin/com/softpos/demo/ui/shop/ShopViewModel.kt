package com.softpos.demo.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.softpos.emv.model.RedactedCard
import com.softpos.emv.txn.TransactionEvent
import com.softpos.emv.txn.TransactionState
import com.softpos.emv.txn.TransitionResult
import com.softpos.demo.CartState
import com.softpos.demo.DemoContainer
import com.softpos.sdk.data.ProductEntity
import com.softpos.sdk.nfc.CardReadEvent
import com.softpos.sdk.nfc.NfcAvailability
import com.softpos.sdk.peripheral.Receipts
import com.softpos.sdk.repository.CartLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface TapUiState {

    data object Idle : TapUiState

    /** Reader mode is on; waiting for a card. */
    data object Waiting : TapUiState

    /** A card is in the field and the EMV exchange is running. */
    data object Reading : TapUiState

    data class Completed(
        val card: RedactedCard,
        val reference: String,
        val amountText: String,
        val finalState: TransactionState,
        val warnings: List<String>,
        val trace: String?,
    ) : TapUiState

    data class Failed(val message: String, val trace: String?) : TapUiState

    data class Unavailable(val reason: NfcAvailability) : TapUiState
}

class ShopViewModel(private val container: DemoContainer) : ViewModel() {

    private val softPos = container.softPos

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    val products: StateFlow<List<ProductEntity>> = softPos.catalog.observeProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cart: StateFlow<CartState> =
        combine(products, container.cart.quantities) { catalogue, quantities ->
            CartState.from(
                catalogue.mapNotNull { product ->
                    val quantity = quantities[product.sku] ?: return@mapNotNull null
                    CartLine(product.sku, product.name, product.priceMinor, quantity)
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CartState())

    private val _tapState = MutableStateFlow<TapUiState>(TapUiState.Idle)
    val tapState: StateFlow<TapUiState> = _tapState.asStateFlow()

    fun currentTotalMinor(): Long = cart.value.totalMinor

    fun formatAmount(minorUnits: Long): String =
        "${softPos.config.currency} ${softPos.formatAmount(minorUnits)}"

    fun addToCart(sku: String) = container.cart.add(sku)

    fun removeFromCart(sku: String) = container.cart.remove(sku)

    fun clearCart() = container.cart.clear()

    fun resetTap() {
        _tapState.value = TapUiState.Idle
    }

    /** Called by the tap screen for each event emitted by the reader flow. */
    fun onCardReadEvent(event: CardReadEvent) {
        when (event) {
            CardReadEvent.Waiting -> _tapState.value = TapUiState.Waiting
            CardReadEvent.CardDetected -> _tapState.value = TapUiState.Reading
            is CardReadEvent.Unavailable -> _tapState.value = TapUiState.Unavailable(event.reason)
            is CardReadEvent.Failed ->
                _tapState.value = TapUiState.Failed("${event.code}: ${event.message}", event.trace)

            is CardReadEvent.Completed -> checkout(event)
        }
    }

    /**
     * Records the basket against the card that was just read and drives it through the state
     * machine. Nothing leaves the device: "processing" means reserving stock and producing a
     * receipt.
     */
    private fun checkout(event: CardReadEvent.Completed) = viewModelScope.launch {
        val lines = cart.value.lines
        if (lines.isEmpty()) {
            _tapState.value = TapUiState.Failed("The basket is empty", null)
            return@launch
        }

        val amountMinor = cart.value.totalMinor
        val id = softPos.transactions.create(lines, softPos.config.currency, event.captured)
        softPos.transactions.applyEvent(id, TransactionEvent.SUBMIT)

        val outcome = softPos.transactions.process(id) { details ->
            runCatching {
                softPos.catalog.reserve(lines)
                try {
                    val receipt = Receipts.from(
                        details = details,
                        merchantName = softPos.config.merchantName,
                        formatAmount = ::formatAmount,
                        timestampText = timestampFormat.format(
                            Instant.ofEpochMilli(details.transaction.createdAtEpochMillis),
                        ),
                    )
                    softPos.printer.print(receipt).getOrThrow()
                } catch (e: Exception) {
                    // Put the stock back so a retry starts from the same position this attempt did.
                    softPos.catalog.release(lines)
                    throw e
                }
            }
        }

        var finalState = (outcome as? TransitionResult.Allowed)?.to ?: TransactionState.FAILED
        if (finalState == TransactionState.FAILED) {
            val resolution = softPos.transactions.resolveFailure(id)
            finalState = (resolution as? TransitionResult.Allowed)?.to ?: finalState
        }

        val record = softPos.transactions.find(id)

        if (finalState == TransactionState.PROCESSED) container.cart.clear()

        _tapState.value = TapUiState.Completed(
            card = event.captured.card,
            reference = record?.transaction?.reference ?: "-",
            amountText = formatAmount(amountMinor),
            finalState = finalState,
            warnings = event.warnings + listOfNotNull(record?.transaction?.failureReason),
            trace = event.trace,
        )
    }

    companion object {
        fun factory(container: DemoContainer) = viewModelFactory {
            initializer { ShopViewModel(container) }
        }
    }
}
