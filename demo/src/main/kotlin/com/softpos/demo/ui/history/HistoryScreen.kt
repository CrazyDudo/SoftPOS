package com.softpos.demo.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.softpos.demo.DemoContainer
import com.softpos.demo.PrototypeBanner
import com.softpos.emv.txn.TransactionState
import com.softpos.sdk.data.TransactionWithDetails
import com.softpos.sdk.data.lineTotalMinor
import com.softpos.sdk.export.ExportFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timestampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
fun HistoryScreen(
    container: DemoContainer,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container)),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrototypeBanner()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.exportAndShare(context, ExportFormat.CSV) },
                    enabled = transactions.isNotEmpty(),
                ) { Text("Export CSV") }
                OutlinedButton(
                    onClick = { viewModel.exportAndShare(context, ExportFormat.JSON) },
                    enabled = transactions.isNotEmpty(),
                ) { Text("Export JSON") }
                TextButton(
                    onClick = viewModel::wipeEverything,
                    enabled = transactions.isNotEmpty(),
                ) { Text("Wipe") }
            }

            if (transactions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(transactions, key = { it.transaction.id }) { details ->
                        TransactionCard(
                            details = details,
                            amountText = viewModel.formatAmount(details.transaction.amountMinor),
                            formatAmount = viewModel::formatAmount,
                            onRetry = { viewModel.retryNow(details.transaction.id) },
                            onAbandon = { viewModel.abandon(details.transaction.id) },
                            onCancel = { viewModel.cancel(details.transaction.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionCard(
    details: TransactionWithDetails,
    amountText: String,
    formatAmount: (Long) -> String,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val transaction = details.transaction

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        transaction.reference,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        timestampFormat.format(Instant.ofEpochMilli(transaction.createdAtEpochMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(amountText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StateChip(transaction.state)
                transaction.card?.let { card ->
                    Text(
                        listOfNotNull(card.label ?: card.scheme, card.maskedPan).joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            transaction.failureReason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transaction.state == TransactionState.RETRY_SCHEDULED ||
                    transaction.state == TransactionState.PENDING
                ) {
                    TextButton(onClick = onRetry) { Text("Process now") }
                }
                if (transaction.state == TransactionState.FAILED ||
                    transaction.state == TransactionState.RETRY_SCHEDULED
                ) {
                    TextButton(onClick = onAbandon) { Text("Abandon") }
                }
                if (transaction.state.isCancellable) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Less" else "Details")
                }
            }

            if (expanded) {
                HorizontalDivider()
                Text("Items", style = MaterialTheme.typography.titleSmall)
                details.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.quantity} x ${item.name}", style = MaterialTheme.typography.bodySmall)
                        Text(formatAmount(item.lineTotalMinor), style = MaterialTheme.typography.bodySmall)
                    }
                }

                transaction.card?.fingerprint?.let {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Card fingerprint", style = MaterialTheme.typography.titleSmall)
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Keyed HMAC of the card number. One-way, and tied to a key that never " +
                            "leaves this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("State history", style = MaterialTheme.typography.titleSmall)
                details.events.sortedBy { it.atEpochMillis }.forEach { event ->
                    Text(
                        "${timestampFormat.format(Instant.ofEpochMilli(event.atEpochMillis))}  " +
                            "${event.fromState} -${event.event}-> ${event.toState}" +
                            (event.detail?.let { detail -> "  ($detail)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: TransactionState) {
    val container = when (state) {
        TransactionState.PROCESSED -> MaterialTheme.colorScheme.primaryContainer
        TransactionState.FAILED, TransactionState.ABANDONED -> MaterialTheme.colorScheme.errorContainer
        TransactionState.RETRY_SCHEDULED -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(state.name, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
    )
}
