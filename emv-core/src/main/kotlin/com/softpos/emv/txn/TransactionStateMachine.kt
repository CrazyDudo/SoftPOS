package com.softpos.emv.txn

/**
 * Lifecycle of a locally recorded transaction.
 *
 * "Processed" means finalised on this device. Nothing here corresponds to an authorisation,
 * a clearing record or a settlement position - this project performs none of those.
 */
enum class TransactionState {
    /** Basket assembled, card not yet read. */
    CREATED,

    /** Card captured and the record is queued for local processing. */
    PENDING,

    /** Local processing under way. */
    PROCESSING,

    /** Finalised successfully. Terminal. */
    PROCESSED,

    /** Processing failed; awaiting a retry decision. */
    FAILED,

    /** A retry is scheduled and will move back to PROCESSING when due. */
    RETRY_SCHEDULED,

    /** Cancelled by the operator. Terminal. */
    CANCELLED,

    /** Retries exhausted or explicitly given up on. Terminal. */
    ABANDONED,
    ;

    val isTerminal: Boolean get() = this == PROCESSED || this == CANCELLED || this == ABANDONED

    /** States where the operator may still walk away from the transaction. */
    val isCancellable: Boolean get() = this == CREATED || this == PENDING || this == FAILED || this == RETRY_SCHEDULED
}

enum class TransactionEvent {
    SUBMIT,
    BEGIN_PROCESSING,
    COMPLETE,
    FAIL,
    SCHEDULE_RETRY,
    CANCEL,
    ABANDON,
}

sealed interface TransitionResult {
    data class Allowed(val from: TransactionState, val event: TransactionEvent, val to: TransactionState) : TransitionResult
    data class Rejected(val from: TransactionState, val event: TransactionEvent, val reason: String) : TransitionResult
}

/**
 * Table-driven state machine. Pure and side-effect free: persistence, retry timing and audit
 * logging all live in the repository that calls this.
 *
 * ```
 *   CREATED ──SUBMIT──▶ PENDING ──BEGIN_PROCESSING──▶ PROCESSING ──COMPLETE──▶ PROCESSED
 *      │                   │                              │
 *      │                   │                              └──FAIL──▶ FAILED
 *      │                   │                                           │
 *      │                   │                        SCHEDULE_RETRY ◀───┤
 *      │                   │                              │            └──ABANDON──▶ ABANDONED
 *      │                   │                              ▼
 *      │                   │                       RETRY_SCHEDULED ──BEGIN_PROCESSING──▶ PROCESSING
 *      └───────────────────┴──────────────CANCEL──────────┴──────────────▶ CANCELLED
 * ```
 *
 * PROCESSING is deliberately not cancellable: a half-finished write should reach FAILED first so
 * the reason is recorded, rather than being erased by a cancel.
 */
object TransactionStateMachine {

    private val TABLE: Map<Pair<TransactionState, TransactionEvent>, TransactionState> = mapOf(
        (TransactionState.CREATED to TransactionEvent.SUBMIT) to TransactionState.PENDING,
        (TransactionState.CREATED to TransactionEvent.CANCEL) to TransactionState.CANCELLED,

        (TransactionState.PENDING to TransactionEvent.BEGIN_PROCESSING) to TransactionState.PROCESSING,
        (TransactionState.PENDING to TransactionEvent.CANCEL) to TransactionState.CANCELLED,

        (TransactionState.PROCESSING to TransactionEvent.COMPLETE) to TransactionState.PROCESSED,
        (TransactionState.PROCESSING to TransactionEvent.FAIL) to TransactionState.FAILED,

        (TransactionState.FAILED to TransactionEvent.SCHEDULE_RETRY) to TransactionState.RETRY_SCHEDULED,
        (TransactionState.FAILED to TransactionEvent.ABANDON) to TransactionState.ABANDONED,
        (TransactionState.FAILED to TransactionEvent.CANCEL) to TransactionState.CANCELLED,

        (TransactionState.RETRY_SCHEDULED to TransactionEvent.BEGIN_PROCESSING) to TransactionState.PROCESSING,
        (TransactionState.RETRY_SCHEDULED to TransactionEvent.ABANDON) to TransactionState.ABANDONED,
        (TransactionState.RETRY_SCHEDULED to TransactionEvent.CANCEL) to TransactionState.CANCELLED,
    )

    fun transition(from: TransactionState, event: TransactionEvent): TransitionResult {
        val to = TABLE[from to event]
            ?: return TransitionResult.Rejected(
                from = from,
                event = event,
                reason = if (from.isTerminal) {
                    "$from is terminal; $event cannot be applied"
                } else {
                    "$event is not valid in $from (allowed: ${allowedEvents(from).joinToString()})"
                },
            )
        return TransitionResult.Allowed(from, event, to)
    }

    fun canTransition(from: TransactionState, event: TransactionEvent): Boolean =
        TABLE.containsKey(from to event)

    fun allowedEvents(from: TransactionState): Set<TransactionEvent> =
        TABLE.keys.filter { it.first == from }.map { it.second }.toSortedSet()

    /** Every reachable `(from, event) -> to` triple. Used by tests and to render the diagram. */
    fun transitions(): List<Triple<TransactionState, TransactionEvent, TransactionState>> =
        TABLE.entries.map { (key, to) -> Triple(key.first, key.second, to) }
}

/**
 * Retry schedule for a failed transaction. Exponential backoff with a ceiling.
 *
 * Because everything is offline, a "retry" only re-runs local finalisation - writing the record,
 * printing a receipt, opening a drawer. There is nothing to re-send.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 5_000,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 300_000,
) {

    init {
        require(maxAttempts >= 0) { "maxAttempts must not be negative" }
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(multiplier >= 1.0) { "multiplier must be at least 1.0" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must not be below baseDelayMillis" }
    }

    /** @param attemptsSoFar how many attempts have already failed. */
    fun shouldRetry(attemptsSoFar: Int): Boolean = attemptsSoFar < maxAttempts

    /** Delay before attempt number [attemptsSoFar] + 1. */
    fun delayFor(attemptsSoFar: Int): Long {
        require(attemptsSoFar >= 0) { "attemptsSoFar must not be negative" }
        val scaled = baseDelayMillis * Math.pow(multiplier, attemptsSoFar.toDouble())
        return if (scaled >= maxDelayMillis) maxDelayMillis else scaled.toLong()
    }
}
