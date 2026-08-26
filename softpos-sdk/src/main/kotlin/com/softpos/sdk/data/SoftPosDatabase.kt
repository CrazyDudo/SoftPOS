package com.softpos.sdk.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.softpos.emv.txn.TransactionEvent
import com.softpos.emv.txn.TransactionState
import kotlinx.coroutines.flow.Flow

class EnumConverters {
    @TypeConverter
    fun toState(value: String): TransactionState = TransactionState.valueOf(value)

    @TypeConverter
    fun fromState(value: TransactionState): String = value.name

    @TypeConverter
    fun toEvent(value: String): TransactionEvent = TransactionEvent.valueOf(value)

    @TypeConverter
    fun fromEvent(value: TransactionEvent): String = value.name
}

@Dao
interface ProductDao {

    @Query("SELECT * FROM products ORDER BY name")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE sku = :sku")
    suspend fun find(sku: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(products: List<ProductEntity>)

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    /**
     * Decrements stock without letting it go negative.
     *
     * The guard lives in SQL rather than in Kotlin so a concurrent checkout cannot read, decide and
     * write over the top of another one.
     */
    @Query("UPDATE products SET stock = stock - :quantity WHERE sku = :sku AND stock >= :quantity")
    suspend fun tryReserveStock(sku: String, quantity: Int): Int

    @Query("UPDATE products SET stock = stock + :quantity WHERE sku = :sku")
    suspend fun releaseStock(sku: String, quantity: Int)
}

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity)

    @Insert
    suspend fun insertItems(items: List<TransactionItemEntity>)

    @Insert
    suspend fun insertEvent(event: TransactionEventEntity)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun find(id: String): TransactionEntity?

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findWithDetails(id: String): TransactionWithDetails?

    @Transaction
    @Query("SELECT * FROM transactions ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<TransactionWithDetails>>

    @Transaction
    @Query("SELECT * FROM transactions WHERE state IN (:states) ORDER BY createdAtEpochMillis DESC")
    fun observeByState(states: List<TransactionState>): Flow<List<TransactionWithDetails>>

    @Transaction
    @Query("SELECT * FROM transactions ORDER BY createdAtEpochMillis DESC")
    suspend fun allWithDetails(): List<TransactionWithDetails>

    /** Retries that have come due, oldest first. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE state = 'RETRY_SCHEDULED'
          AND nextRetryAtEpochMillis IS NOT NULL
          AND nextRetryAtEpochMillis <= :now
        ORDER BY nextRetryAtEpochMillis ASC
        """,
    )
    suspend fun dueForRetry(now: Long): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE state = :state")
    fun observeCount(state: TransactionState): Flow<Int>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Database(
    entities = [
        ProductEntity::class,
        TransactionEntity::class,
        TransactionItemEntity::class,
        TransactionEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class SoftPosDatabase : RoomDatabase() {

    abstract fun products(): ProductDao

    abstract fun transactions(): TransactionDao

    companion object {
        /**
         * @param databaseName pass null for an in-memory database, which is what tests use.
         *
         * No `fallbackToDestructiveMigration`: a schema change should fail loudly during
         * development rather than quietly delete the transaction history on a real device.
         */
        fun create(context: Context, databaseName: String?): SoftPosDatabase {
            val builder = if (databaseName == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, SoftPosDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, SoftPosDatabase::class.java, databaseName)
            }
            return builder.build()
        }
    }
}
