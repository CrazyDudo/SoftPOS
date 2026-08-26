package com.softpos.demo.ui.shop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.softpos.demo.DemoContainer
import com.softpos.demo.PrototypeBanner
import com.softpos.demo.findActivity
import com.softpos.emv.model.RedactedCard
import com.softpos.emv.txn.TransactionState
import com.softpos.sdk.nfc.NfcAvailability

@Composable
fun TapScreen(
    container: DemoContainer,
    onDone: () -> Unit,
    viewModel: ShopViewModel = viewModel(factory = ShopViewModel.factory(container)),
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val tapState by viewModel.tapState.collectAsStateWithLifecycle()
    val cart by viewModel.cart.collectAsStateWithLifecycle()

    val finished = tapState is TapUiState.Completed

    // Reader mode runs only while this screen is composed and no result is on display; leaving the
    // screen cancels the flow, which disables reader mode through awaitClose.
    LaunchedEffect(activity, finished) {
        if (activity == null || finished) return@LaunchedEffect
        container.softPos.cardReader
            .reads(activity) { viewModel.currentTotalMinor() }
            .collect(viewModel::onCardReadEvent)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PrototypeBanner()

        Text(
            text = viewModel.formatAmount(cart.totalMinor),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "${cart.itemCount} item(s)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val state = tapState) {
                TapUiState.Idle, TapUiState.Waiting -> StatusBlock(
                    icon = { Icon(Icons.Default.Nfc, null, modifier = Modifier.size(72.dp)) },
                    title = "Hold a card against the back of the phone",
                    body = "The reader is active. Only the card's masked number and expiry are kept.",
                )

                TapUiState.Reading -> StatusBlock(
                    icon = { CircularProgressIndicator(modifier = Modifier.size(72.dp)) },
                    title = "Reading the card",
                    body = "Keep the card still until this finishes.",
                )

                is TapUiState.Unavailable -> StatusBlock(
                    icon = {
                        Icon(
                            Icons.Default.ErrorOutline,
                            null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    title = when (state.reason) {
                        NfcAvailability.NO_HARDWARE -> "This device has no NFC controller"
                        NfcAvailability.DISABLED -> "NFC is switched off"
                        NfcAvailability.READY -> "NFC is ready"
                    },
                    body = if (state.reason == NfcAvailability.DISABLED) {
                        "Turn NFC on in system settings, then come back to this screen."
                    } else {
                        "A contactless read is not possible on this device."
                    },
                )

                is TapUiState.Failed -> {
                    StatusBlock(
                        icon = {
                            Icon(
                                Icons.Default.ErrorOutline,
                                null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        title = "Could not read the card",
                        body = state.message,
                    )
                    state.trace?.let { TraceCard(it) }
                    OutlinedButton(onClick = viewModel::resetTap) { Text("Try again") }
                }

                is TapUiState.Completed -> {
                    CompletedBlock(state)
                    state.trace?.let { TraceCard(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::resetTap) { Text("New sale") }
                        Button(onClick = onDone) { Text("View history") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompletedBlock(state: TapUiState.Completed) {
    val succeeded = state.finalState == TransactionState.PROCESSED

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (succeeded) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = if (succeeded) "Recorded locally" else "Recorded as ${state.finalState}",
            style = MaterialTheme.typography.titleMedium,
        )

        CardSummary(state.card)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabelledValue("Reference", state.reference)
                LabelledValue("Amount", state.amountText)
                LabelledValue("State", state.finalState.name)
            }
        }

        if (state.warnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Notes", style = MaterialTheme.typography.titleSmall)
                    state.warnings.forEach {
                        Text("- $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardSummary(card: RedactedCard) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = card.maskedPan ?: "No PAN",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            LabelledValue("Expiry", card.expiry ?: "-")
            LabelledValue("Scheme", "${card.scheme.displayName} / ${card.kernel.label}")
            LabelledValue("AID", card.aidHex)
            LabelledValue("Records read", card.recordCount.toString())
            card.expired?.let { LabelledValue("Expired", if (it) "Yes" else "No") }
            card.panLuhnValid?.let { LabelledValue("Luhn check", if (it) "Pass" else "Fail") }
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/**
 * The APDU log for the tap that just happened.
 *
 * Sensitive values are withheld by the SDK before the string reaches this screen; see
 * [com.softpos.sdk.SoftPosConfig.redactApduTrace].
 */
@Composable
private fun TraceCard(trace: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide APDU trace" else "Show APDU trace")
            }
            if (expanded) {
                Text(
                    text = trace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
