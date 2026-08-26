package com.softpos.demo

import com.softpos.sdk.data.ProductEntity
import com.softpos.sdk.repository.CartLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The basket, shared between the shop screen and the tap screen.
 *
 * Quantities are kept against a SKU rather than against a product snapshot, so a price edited in
 * the catalog is picked up the next time the basket is priced.
 */
class CartStore {

    private val _quantities = MutableStateFlow<Map<String, Int>>(emptyMap())
    val quantities: StateFlow<Map<String, Int>> = _quantities.asStateFlow()

    fun add(sku: String) = _quantities.update { it + (sku to (it[sku] ?: 0) + 1) }

    fun remove(sku: String) = _quantities.update { current ->
        val next = (current[sku] ?: 0) - 1
        if (next <= 0) current - sku else current + (sku to next)
    }

    fun clear() = _quantities.update { emptyMap() }

    fun linesFor(products: List<ProductEntity>): List<CartLine> {
        val quantities = _quantities.value
        return products.mapNotNull { product ->
            val quantity = quantities[product.sku] ?: return@mapNotNull null
            CartLine(product.sku, product.name, product.priceMinor, quantity)
        }
    }
}

data class CartState(
    val lines: List<CartLine> = emptyList(),
    val totalMinor: Long = 0,
    val itemCount: Int = 0,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        fun from(lines: List<CartLine>) = CartState(
            lines = lines,
            totalMinor = lines.sumOf { it.lineTotalMinor },
            itemCount = lines.sumOf { it.quantity },
        )
    }
}
