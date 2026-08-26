package com.softpos.sdk.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.softpos.emv.txn.TransactionEvent
import com.softpos.emv.txn.TransactionState

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val sku: String,
    val name: String,
    /** Price in minor units, e.g. cents. Never a floating point value. */
    val priceMinor: Long,
    val currency: String,
    val stock: Int,
    val category: String? = null,
)

/**
 * A locally recorded transaction.
 *
 * The card columns hold only reduced data. There is deliberately no column for a full PAN, a
 * cardholder name or track data; [encryptedPan] exists solely for the opt-in path described in
 * [com.softpos.sdk.SoftPosConfig.persistEncryptedPan] and is null in the default configuration.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("state"),
        Index("createdAtEpochMillis"),
        // Column name carries the @Embedded prefix.
        Index("card_fingerprint"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    /** Short human-facing reference shown on screen and on the receipt. */
    val reference: String,
    val amountMinor: Long,
    val currency: String,
    val state: TransactionState,
    val attemptCount: Int,
    val failureReason: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val nextRetryAtEpochMillis: Long?,
    @Embedded(prefix = "card_") val card: CardSnapshot?,
    val encryptedPan: String? = null,
)

/** The reduced card view persisted alongside a transaction. */
data class CardSnapshot(
    val scheme: String,
    val kernel: String,
    val aid: String,
    val label: String?,
    /** Fully masked except the last four digits, e.g. `************0010`. */
    val maskedPan: String?,
    val last4: String?,
    /** `yyyy-MM`. */
    val expiry: String?,
    val expired: Boolean?,
    /** Keyed HMAC of the PAN. One-way, and not reproducible off this device. */
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
)

@Entity(
    tableName = "transaction_items",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId")],
)
data class TransactionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val sku: String,
    val name: String,
    val unitPriceMinor: Long,
    val quantity: Int,
)

/** Kept outside the entity so Room never has to reason about a column-less property. */
val TransactionItemEntity.lineTotalMinor: Long get() = unitPriceMinor * quantity

/**
 * Append-only audit of every accepted state transition.
 *
 * Kept because a state machine that silently corrects itself is impossible to debug: when a
 * transaction ends up ABANDONED this table shows which attempts failed and why.
 */
@Entity(
    tableName = "transaction_events",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId")],
)
data class TransactionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val fromState: TransactionState,
    val event: TransactionEvent,
    val toState: TransactionState,
    val detail: String?,
    val atEpochMillis: Long,
)

/** A transaction with everything attached to it. */
data class TransactionWithDetails(
    @Embedded val transaction: TransactionEntity,
    @Relation(parentColumn = "id", entityColumn = "transactionId")
    val items: List<TransactionItemEntity>,
    @Relation(parentColumn = "id", entityColumn = "transactionId")
    val events: List<TransactionEventEntity>,
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
}
