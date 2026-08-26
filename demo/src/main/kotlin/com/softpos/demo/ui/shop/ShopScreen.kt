package com.softpos.demo.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.softpos.demo.DemoContainer
import com.softpos.demo.PrototypeBanner
import com.softpos.sdk.data.ProductEntity

@Composable
fun ShopScreen(
    container: DemoContainer,
    onCheckout: () -> Unit,
    viewModel: ShopViewModel = viewModel(factory = ShopViewModel.factory(container)),
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val cart by viewModel.cart.collectAsStateWithLifecycle()
    val quantities by container.cart.quantities.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        PrototypeBanner()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(products, key = { it.sku }) { product ->
                ProductRow(
                    product = product,
                    quantity = quantities[product.sku] ?: 0,
                    priceText = viewModel.formatAmount(product.priceMinor),
                    onAdd = { viewModel.addToCart(product.sku) },
                    onRemove = { viewModel.removeFromCart(product.sku) },
                )
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${cart.itemCount} item(s)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        viewModel.formatAmount(cart.totalMinor),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = viewModel::clearCart,
                        enabled = !cart.isEmpty,
                    ) { Text("Clear") }

                    Button(
                        onClick = {
                            viewModel.resetTap()
                            onCheckout()
                        },
                        enabled = !cart.isEmpty,
                        modifier = Modifier.weight(1f),
                    ) { Text("Charge ${viewModel.formatAmount(cart.totalMinor)}") }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(
    product: ProductEntity,
    quantity: Int,
    priceText: String,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$priceText  -  ${product.stock} in stock",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (product.stock == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            FilledTonalIconButton(onClick = onRemove, enabled = quantity > 0) {
                Icon(Icons.Default.Remove, contentDescription = "Remove one ${product.name}")
            }
            Text(
                text = quantity.toString(),
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalIconButton(
                onClick = onAdd,
                // Deliberately allows over-ordering: the failure surfaces during processing, which
                // is what exercises the FAILED and RETRY_SCHEDULED path.
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add one ${product.name}")
            }
        }
    }
}
