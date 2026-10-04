package com.nerojust.paymentsim

import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PaymentState.Confirming
import com.nerojust.paymentsim.model.PaymentState.Failed
import com.nerojust.paymentsim.model.PaymentState.Idle
import com.nerojust.paymentsim.model.PaymentState.Pending
import com.nerojust.paymentsim.model.PaymentState.Retrying
import com.nerojust.paymentsim.model.PaymentState.Success
import com.nerojust.paymentsim.model.isAllowed
import com.nerojust.paymentsim.model.transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PaymentStateTransitionTest {

    private val failed = Failed("insufficient_funds")
    private val allStates = listOf(Idle, Pending, Confirming, Success, failed, Retrying)

    private val allowed: Set<Pair<PaymentState, PaymentState>> = setOf(
        Idle to Pending,
        Pending to Confirming,
        Pending to Retrying,
        Pending to failed,
        Confirming to Success,
        Confirming to failed,
        Confirming to Retrying,
        Retrying to Pending,
        Success to Idle,
        failed to Idle,
    )

    @Test
    fun isAllowed_everyPairOfStates_matchesTheSlideTable() {
        for (from in allStates) {
            for (to in allStates) {
                assertEquals("$from -> $to", (from to to) in allowed, isAllowed(from, to))
            }
        }
    }

    @Test
    fun transition_allowedMove_returnsTheNewState() {
        for ((from, to) in allowed) {
            assertSame(to, transition(from, to, strict = true))
        }
    }

    @Test
    fun transition_everyIllegalMoveInDebug_throws() {
        for (from in allStates) {
            for (to in allStates) {
                if ((from to to) in allowed) continue
                assertThrows("$from -> $to", IllegalStateException::class.java) {
                    transition(from, to, strict = true)
                }
            }
        }
    }

    // Scenario 8
    @Test
    fun transition_successToFailed_isRejected() {
        // Debug builds throw; release builds ignore the move and stay in Success.
        assertThrows(IllegalStateException::class.java) { transition(Success, Failed("late error"), strict = true) }
        assertSame(Success, transition(Success, Failed("late error"), strict = false))
    }
}
