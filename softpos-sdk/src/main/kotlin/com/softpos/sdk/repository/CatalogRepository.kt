package com.softpos.sdk.repository

import com.softpos.sdk.data.ProductEntity
import com.softpos.sdk.data.SoftPosDatabase
import kotlinx.coroutines.flow.Flow

class InsufficientStockException(val sku: String, val requested: Int) :
    Exception("Not enough stock for $sku (wanted $requested)")

/**
 * Products and inventory.
 *
 * Reservation is all-or-nothing: if any line cannot be satisfied, the lines already taken are put
 * back before the failure is reported, so a rejected checkout leaves stock exactly as it was.
 */
class CatalogRepository(private val database: SoftPosDatabase) {

    private val dao = database.products()

    fun observeProducts(): Flow<List<ProductEntity>> = dao.observeAll()

    suspend fun find(sku: String): ProductEntity? = dao.find(sku)

    suspend fun upsertAll(products: List<ProductEntity>) = dao.upsertAll(products)

    suspend fun isEmpty(): Boolean = dao.count() == 0

    /**
     * Takes stock for every line, or none.
     *
     * @throws InsufficientStockException naming the first line that could not be satisfied.
     */
    suspend fun reserve(lines: List<CartLine>) {
        val taken = mutableListOf<CartLine>()
        try {
            for (line in lines) {
                val updated = dao.tryReserveStock(line.sku, line.quantity)
                if (updated == 0) throw InsufficientStockException(line.sku, line.quantity)
                taken += line
            }
        } catch (e: Exception) {
            taken.forEach { dao.releaseStock(it.sku, it.quantity) }
            throw e
        }
    }

    suspend fun release(lines: List<CartLine>) {
        lines.forEach { dao.releaseStock(it.sku, it.quantity) }
    }
}
