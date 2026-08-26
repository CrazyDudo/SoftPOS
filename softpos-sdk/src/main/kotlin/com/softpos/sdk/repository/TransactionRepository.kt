package com.softpos.sdk.repository

import androidx.room.withTransaction
import com.softpos.emv.txn.RetryPolicy
import com.softpos.emv.txn.TransactionEvent
import com.softpos.emv.txn.TransactionState
import com.softpos.emv.txn.TransactionStateMachine
import com.softpos.emv.txn.TransitionResult
import com.softpos.sdk.data.CardSnapshot
import com.softpos.sdk.data.SoftPosDatabase
import com.softpos.sdk.data.TransactionEntity
import com.softpos.sdk.data.TransactionEventEntity
import com.softpos.sdk.data.TransactionItemEntity
import com.softpos.sdk.data.TransactionWithDetails
import com.softpos.sdk.security.CapturedCard
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.util.Locale
import java.util.UUID

/** One basket line, before it becomes a persisted [TransactionItemEntity]. */
data class CartLine(
    val sku: String,
    val name: String,
    val unitPriceMinor: Long,
    val quantity: Int,
) {
    val lineTotalMinor: Long get() = unitPriceMinor * quantity
}

/**
 * Owns the transaction lifecycle.
 *
 * Every state change funnels through [applyEvent], which asks [TransactionStateMachine] first and
 * writes an audit row on success. Nothing else in the SDK or the demo writes the `state` column, so
 * an illegal transition cannot be introduced by a caller that forgot the rules - it comes back as
 * [TransitionResult.Rejected] instead.
 */
class TransactionRepository(
    private val database: SoftPosDatabase,
    private val clock: Clock,
    private val retryPolicy: RetryPolicy,
) {

    private val dao = database.transactions()

    fun observeAll(): Flow<List<TransactionWithDetails>> = dao.observeAll()

    fun observeByState(vararg states: TransactionState): Flow<List<TransactionWithDetails>> =
        dao.observeByState(states.toList())

    fun observeCount(state: TransactionState): Flow<Int> = dao.observeCount(state)

    suspend fun find(id: String): TransactionWithDetails? = dao.findWithDetails(id)

    suspend fun allWithDetails(): List<TransactionWithDetails> = dao.allWithDetails()

    /**
     * Creates a transaction in [TransactionState.CREATED].
     *
     * @param captured masked card data, or null when the basket is recorded before the tap.
     */
    suspend fun create(
        lines: List<CartLine>,
        currency: String,
        captured: CapturedCard? = null,
    ): String {
        require(lines.isNotEmpty()) { "a transaction needs at least one line" }
        require(lines.all { it.quantity > 0 }) { "line quantities must be positive" }

        val now = clock.millis()
        val id = UUID.randomUUID().toString()

        val entity = TransactionEntity(
            id = id,
            reference = generateReference(now),
            amountMinor = lines.sumOf { it.lineTotalMinor },
            currency = currency,
            state = TransactionState.CREATED,
            attemptCount = 0,
            failureReason = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            nextRetryAtEpochMillis = null,
            card = captured?.toSnapshot(),
            encryptedPan = captured?.encryptedPan,
        )

        database.withTransaction {
            dao.insert(entity)
            dao.insertItems(
                lines.map {
                    TransactionItemEntity(
                        transactionId = id,
                        sku = it.sku,
                        name = it.name,
                        unitPriceMinor = it.unitPriceMinor,
                        quantity = it.quantity,
                    )
                },
            )
        }
        return id
    }

    /** Attaches card data captured after the basket was created. Only legal before submission. */
    suspend fun attachCard(id: String, captured: CapturedCard): Boolean = database.withTransaction {
        val current = dao.find(id) ?: return@withTransaction false
        if (current.state != TransactionState.CREATED) return@withTransaction false

        dao.update(
            current.copy(
                card = captured.toSnapshot(),
                encryptedPan = captured.encryptedPan,
                updatedAtEpochMillis = clock.millis(),
            ),
        )
        true
    }

    /**
     * The single entry point for state changes.
     *
     * @param detail free text recorded on the audit row, and stored as the failure reason on FAIL.
     */
    suspend fun applyEvent(
        id: String,
        event: TransactionEvent,
        detail: String? = null,
    ): TransitionResult = database.withTransaction {
        val current = dao.find(id)
            ?: return@withTransaction TransitionResult.Rejected(
                from = TransactionState.CREATED,
                event = event,
                reason = "No transaction with id $id",
            )

        when (val outcome = TransactionStateMachine.transition(current.state, event)) {
            is TransitionResult.Rejected -> outcome

            is TransitionResult.Allowed -> {
                val now = clock.millis()
                val attempts = if (event == TransactionEvent.BEGIN_PROCESSING) {
                    current.attemptCount + 1
                } else {
                    current.attemptCount
                }

                dao.update(
                    current.copy(
                        state = outcome.to,
                        attemptCount = attempts,
                        failureReason = when (event) {
                            TransactionEvent.FAIL -> detail
                            TransactionEvent.COMPLETE -> null
                            else -> current.failureReason
                        },
                        nextRetryAtEpochMillis = if (event == TransactionEvent.SCHEDULE_RETRY) {
                            now + retryPolicy.delayFor(attempts)
                        } else {
                            null
                        },
                        updatedAtEpochMillis = now,
                    ),
                )
                dao.insertEvent(
                    TransactionEventEntity(
                        transactionId = id,
                        fromState = outcome.from,
                        event = event,
                        toState = outcome.to,
                        detail = detail,
                        atEpochMillis = now,
                    ),
                )
                outcome
            }
        }
    }

    /**
     * Runs [work] between BEGIN_PROCESSING and COMPLETE or FAIL.
     *
     * A thrown exception is treated exactly like a returned failure, so a crash inside [work]
     * cannot strand a transaction in PROCESSING.
     */
    suspend fun process(
        id: String,
        work: suspend (TransactionWithDetails) -> Result<Unit>,
    ): TransitionResult {
        val started = applyEvent(id, TransactionEvent.BEGIN_PROCESSING)
        if (started is TransitionResult.Rejected) return started

        val details = find(id)
            ?: return applyEvent(id, TransactionEvent.FAIL, "Transaction disappeared mid-processing")

        val outcome = try {
            work(details)
        } catch (e: Exception) {
            Result.failure(e)
        }

        return if (outcome.isSuccess) {
            applyEvent(id, TransactionEvent.COMPLETE)
        } else {
            val reason = outcome.exceptionOrNull()?.message ?: "Processing failed"
            applyEvent(id, TransactionEvent.FAIL, reason)
        }
    }

    /**
     * Decides what happens to a FAILED transaction: another attempt if the budget allows,
     * otherwise ABANDONED. Returns the transition that was applied.
     */
    suspend fun resolveFailure(id: String): TransitionResult {
        val current = dao.find(id)
            ?: return TransitionResult.Rejected(TransactionState.FAILED, TransactionEvent.SCHEDULE_RETRY, "No transaction with id $id")

        return if (retryPolicy.shouldRetry(current.attemptCount)) {
            applyEvent(
                id = id,
                event = TransactionEvent.SCHEDULE_RETRY,
                detail = "Attempt ${current.attemptCount} of ${retryPolicy.maxAttempts}",
            )
        } else {
            applyEvent(
                id = id,
                event = TransactionEvent.ABANDON,
                detail = "Retry budget of ${retryPolicy.maxAttempts} attempts exhausted",
            )
        }
    }

    /** Transactions whose scheduled retry time has arrived. */
    suspend fun dueForRetry(): List<TransactionEntity> = dao.dueForRetry(clock.millis())

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Short, sortable, human-readable reference. Not unique across devices and not used as a key -
     * the primary key stays a UUID.
     */
    private fun generateReference(nowMillis: Long): String =
        String.format(Locale.US, "T%08X", (nowMillis / 1000L).toInt())
}

private fun CapturedCard.toSnapshot() = CardSnapshot(
    scheme = card.scheme.name,
    kernel = card.kernel.name,
    aid = card.aidHex,
    label = card.applicationLabel,
    maskedPan = card.maskedPan,
    last4 = card.panLast4,
    expiry = card.expiry,
    expired = card.expired,
    fingerprint = fingerprint,
)
