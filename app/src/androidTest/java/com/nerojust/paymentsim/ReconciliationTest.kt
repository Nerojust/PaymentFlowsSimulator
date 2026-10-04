package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nerojust.paymentsim.TestHarness.Companion.AMOUNT
import com.nerojust.paymentsim.TestHarness.Companion.RECIPIENT
import com.nerojust.paymentsim.TestHarness.Companion.REQUEST
import com.nerojust.paymentsim.client.SafePaymentClient
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.server.FakePaymentServer
import com.nerojust.paymentsim.server.db.RememberedPayment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconciliationTest {

    private val harness = TestHarness()
    private val safeClient get() = harness.safeClient

    @After
    fun tearDown() = harness.close()

    /** A payment the previous run persisted and then never settled. */
    private suspend fun leftBehind(
        id: String = "key-1",
        amountMinor: Long = AMOUNT,
        status: String = PendingPaymentStatus.PENDING,
    ): String {
        harness.clientDb.pendingPaymentDao().insert(
            PendingPayment(id, amountMinor, RECIPIENT, timestamp = harness.now, status = status),
        )
        return id
    }

    @Test
    fun reconcile_serverSaysSucceeded_confirmsWithoutCharging() = runTest {
        val id = leftBehind()
        harness.api.createPayment(id, REQUEST) // the server processed it, the client never heard back

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(PaymentState.Success, safeClient.state.value)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun reconcile_serverSaysFailed_marksFailedWithTheReason() = runTest {
        val tooMuch = FakePaymentServer.DEFAULT_DECLINE_LIMIT_MINOR + 1
        val id = leftBehind(amountMinor = tooMuch)
        harness.api.createPayment(id, REQUEST.copy(amountMinor = tooMuch))

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.FAILED, harness.payment(id).status)
        assertEquals(PaymentState.Failed("insufficient_funds"), safeClient.state.value)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun reconcile_serverNeverSawIt_resendsWithTheSameKey() = runTest {
        val id = leftBehind(status = PendingPaymentStatus.NEEDS_RECONCILE)

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun reconcile_serverStillProcessing_schedulesAStatusCheckAndDoesNotResend() = runTest {
        val id = leftBehind()
        harness.serverDb.serverDao().claim(RememberedPayment(id, "hash", "processing", null, harness.now))

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.NEEDS_RECONCILE, harness.payment(id).status)
        assertEquals(listOf(id), harness.enqueuedWorkers)
        assertEquals(PaymentState.Retrying, safeClient.state.value)
        assertTrue(harness.logMessages().none { it.startsWith("Sending payment") })
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun reconcile_networkError_schedulesAStatusCheck() = runTest {
        val id = leftBehind()
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.NEEDS_RECONCILE, harness.payment(id).status)
        assertEquals(listOf(id), harness.enqueuedWorkers)
    }

    @Test
    fun reconcile_olderThanTtl_asksTheUserAndSendsNothing() = runTest {
        val id = leftBehind()
        harness.now += SafePaymentClient.DEFAULT_TTL_MS + 1

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.AWAITING_USER_CONFIRMATION, harness.payment(id).status)
        assertEquals(PaymentState.Idle, safeClient.state.value)
        assertTrue(harness.logMessages().none { it.startsWith("Sending payment") || it.startsWith("Asking the server") })
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun reconcilePayment_userConfirmedStalePayment_sendsItWithTheSameKey() = runTest {
        val id = leftBehind(status = PendingPaymentStatus.AWAITING_USER_CONFIRMATION)
        harness.now += SafePaymentClient.DEFAULT_TTL_MS + 1

        safeClient.reconcilePayment(id) // without the user's confirmation: nothing happens
        assertEquals(0, harness.ledger().size)

        safeClient.reconcilePayment(id, userConfirmed = true)

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun cancelPayment_stalePayment_marksFailedAndNeverSends() = runTest {
        val id = leftBehind(status = PendingPaymentStatus.AWAITING_USER_CONFIRMATION)

        safeClient.cancelPayment(id)

        assertEquals(PendingPaymentStatus.FAILED, harness.payment(id).status)
        assertEquals(SafePaymentClient.CANCELLED_BY_USER, harness.payment(id).lastError)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun reconcile_settledPayments_areLeftAlone() = runTest {
        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(1, harness.ledger().size)
    }
}
