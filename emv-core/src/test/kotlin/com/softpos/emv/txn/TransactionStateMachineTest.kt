package com.softpos.emv.txn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransactionStateMachineTest {

    private fun assertAllowed(
        from: TransactionState,
        event: TransactionEvent,
        expected: TransactionState,
    ) {
        val result = assertIs<TransitionResult.Allowed>(
            TransactionStateMachine.transition(from, event),
            "expected $from --$event--> $expected",
        )
        assertEquals(expected, result.to)
    }

    private fun assertRejected(from: TransactionState, event: TransactionEvent) {
        assertIs<TransitionResult.Rejected>(
            TransactionStateMachine.transition(from, event),
            "expected $from to reject $event",
        )
    }

    @Test
    fun `happy path runs from created to processed`() {
        assertAllowed(TransactionState.CREATED, TransactionEvent.SUBMIT, TransactionState.PENDING)
        assertAllowed(TransactionState.PENDING, TransactionEvent.BEGIN_PROCESSING, TransactionState.PROCESSING)
        assertAllowed(TransactionState.PROCESSING, TransactionEvent.COMPLETE, TransactionState.PROCESSED)
    }

    @Test
    fun `failure path allows a retry loop`() {
        assertAllowed(TransactionState.PROCESSING, TransactionEvent.FAIL, TransactionState.FAILED)
        assertAllowed(TransactionState.FAILED, TransactionEvent.SCHEDULE_RETRY, TransactionState.RETRY_SCHEDULED)
        assertAllowed(
            TransactionState.RETRY_SCHEDULED,
            TransactionEvent.BEGIN_PROCESSING,
            TransactionState.PROCESSING,
        )
    }

    @Test
    fun `a failed transaction can be abandoned`() {
        assertAllowed(TransactionState.FAILED, TransactionEvent.ABANDON, TransactionState.ABANDONED)
        assertAllowed(TransactionState.RETRY_SCHEDULED, TransactionEvent.ABANDON, TransactionState.ABANDONED)
    }

    @Test
    fun `cancel is available before and after processing but not during`() {
        assertAllowed(TransactionState.CREATED, TransactionEvent.CANCEL, TransactionState.CANCELLED)
        assertAllowed(TransactionState.PENDING, TransactionEvent.CANCEL, TransactionState.CANCELLED)
        assertAllowed(TransactionState.FAILED, TransactionEvent.CANCEL, TransactionState.CANCELLED)
        assertAllowed(TransactionState.RETRY_SCHEDULED, TransactionEvent.CANCEL, TransactionState.CANCELLED)

        // A half-finished write must reach FAILED first so the reason survives.
        assertRejected(TransactionState.PROCESSING, TransactionEvent.CANCEL)
    }

    @Test
    fun `terminal states accept nothing`() {
        val terminal = TransactionState.entries.filter { it.isTerminal }
        assertEquals(
            listOf(TransactionState.PROCESSED, TransactionState.CANCELLED, TransactionState.ABANDONED),
            terminal,
        )

        for (state in terminal) {
            for (event in TransactionEvent.entries) {
                assertRejected(state, event)
            }
            assertTrue(TransactionStateMachine.allowedEvents(state).isEmpty())
        }
    }

    @Test
    fun `rejection explains why`() {
        val rejected = assertIs<TransitionResult.Rejected>(
            TransactionStateMachine.transition(TransactionState.CREATED, TransactionEvent.COMPLETE),
        )

        assertTrue(rejected.reason.contains("COMPLETE"))
        assertTrue(rejected.reason.contains("SUBMIT"), "should list what is allowed instead")
    }

    @Test
    fun `rejection from a terminal state says so`() {
        val rejected = assertIs<TransitionResult.Rejected>(
            TransactionStateMachine.transition(TransactionState.PROCESSED, TransactionEvent.FAIL),
        )

        assertTrue(rejected.reason.contains("terminal"))
    }

    @Test
    fun `cannot skip pending and go straight to processing`() {
        assertRejected(TransactionState.CREATED, TransactionEvent.BEGIN_PROCESSING)
    }

    @Test
    fun `cannot complete a transaction that never started processing`() {
        assertRejected(TransactionState.PENDING, TransactionEvent.COMPLETE)
        assertRejected(TransactionState.FAILED, TransactionEvent.COMPLETE)
    }

    @Test
    fun `canTransition agrees with transition`() {
        for (state in TransactionState.entries) {
            for (event in TransactionEvent.entries) {
                val allowed = TransactionStateMachine.transition(state, event) is TransitionResult.Allowed
                assertEquals(allowed, TransactionStateMachine.canTransition(state, event), "$state / $event")
            }
        }
    }

    @Test
    fun `every non terminal state can reach a terminal state`() {
        for (start in TransactionState.entries.filterNot { it.isTerminal }) {
            assertTrue(reachesTerminal(start), "$start cannot reach a terminal state")
        }
    }

    private fun reachesTerminal(
        from: TransactionState,
        seen: MutableSet<TransactionState> = mutableSetOf(),
    ): Boolean {
        if (from.isTerminal) return true
        if (!seen.add(from)) return false
        return TransactionStateMachine.allowedEvents(from).any { event ->
            val next = (TransactionStateMachine.transition(from, event) as TransitionResult.Allowed).to
            reachesTerminal(next, seen)
        }
    }

    @Test
    fun `isCancellable matches the transition table`() {
        for (state in TransactionState.entries) {
            assertEquals(
                TransactionStateMachine.canTransition(state, TransactionEvent.CANCEL),
                state.isCancellable,
                "$state",
            )
        }
    }
}

class RetryPolicyTest {

    @Test
    fun `backs off exponentially`() {
        val policy = RetryPolicy(maxAttempts = 4, baseDelayMillis = 1_000, multiplier = 2.0)

        assertEquals(1_000, policy.delayFor(0))
        assertEquals(2_000, policy.delayFor(1))
        assertEquals(4_000, policy.delayFor(2))
        assertEquals(8_000, policy.delayFor(3))
    }

    @Test
    fun `clamps at the ceiling`() {
        val policy = RetryPolicy(baseDelayMillis = 1_000, multiplier = 10.0, maxDelayMillis = 30_000)

        assertEquals(10_000, policy.delayFor(1))
        assertEquals(30_000, policy.delayFor(2))
        assertEquals(30_000, policy.delayFor(20), "must not overflow at large attempt counts")
    }

    @Test
    fun `stops retrying once the attempt budget is spent`() {
        val policy = RetryPolicy(maxAttempts = 3)

        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(4))
    }

    @Test
    fun `zero max attempts disables retrying`() {
        assertFalse(RetryPolicy(maxAttempts = 0).shouldRetry(0))
    }

    @Test
    fun `rejects a nonsensical configuration`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(baseDelayMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(baseDelayMillis = 10_000, maxDelayMillis = 1_000) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = -1) }
    }
}
