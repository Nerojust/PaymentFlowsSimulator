package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nerojust.paymentsim.TestHarness.Companion.AMOUNT
import com.nerojust.paymentsim.TestHarness.Companion.RECIPIENT
import com.nerojust.paymentsim.TestHarness.Companion.REQUEST
import com.nerojust.paymentsim.client.SafePaymentClient
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.server.FakePaymentServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafePaymentClientTest {

    private val harness = TestHarness()
    private val safeClient get() = harness.safeClient

    @After
    fun tearDown() = harness.close()

    @Test
    fun initiatePayment_online_confirmsWithOneCharge() = runTest {
        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(PaymentState.Success, safeClient.state.value)
        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(0, harness.payment(id).retryCount)
        assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun initiatePayment_offline_persistsThePaymentEvenThoughNothingReachedTheServer() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        val queued = harness.payment(id)
        assertEquals(AMOUNT, queued.amountMinor)
        assertEquals(RECIPIENT, queued.recipient)
        assertEquals("No internet", queued.lastError)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun initiatePayment_sameIntentStillInFlight_reusesTheKey() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val first = safeClient.initiatePayment(AMOUNT, RECIPIENT)
        val second = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(first, second)
        assertEquals(1, harness.clientDb.pendingPaymentDao().unsettled().size)
    }

    @Test
    fun initiatePayment_differentIntent_getsItsOwnKey() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val first = safeClient.initiatePayment(AMOUNT, RECIPIENT)
        val second = safeClient.initiatePayment(AMOUNT + 1, RECIPIENT)

        assertTrue(first != second)
    }

    @Test
    fun initiatePayment_twoPaymentsAtOnce_areBothSavedAndEachChargedOnce() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val ids = listOf(AMOUNT, AMOUNT + 1).map { async { safeClient.initiatePayment(it, RECIPIENT) } }.awaitAll()

        assertEquals(2, harness.clientDb.pendingPaymentDao().unsettled().size)
        harness.configure { it.copy(mode = NetworkMode.ONLINE) }
        safeClient.reconcilePendingPayments()
        assertEquals(ids.sorted(), harness.ledger().mapNotNull { it.idempotencyKey }.sorted())
        ids.forEach { assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(it).status) }
    }

    @Test
    fun initiatePayment_doubleTapAtTheSameMoment_savesOnePayment() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val ids = List(2) { async { safeClient.initiatePayment(AMOUNT, RECIPIENT) } }.awaitAll()

        assertEquals(1, ids.toSet().size)
        assertEquals(1, harness.clientDb.pendingPaymentDao().unsettled().size)
    }

    @Test
    fun initiatePayment_responseDropped_retriesWithTheSameKey() = runTest {
        harness.configure { it.copy(mode = NetworkMode.DROP_AFTER_PROCESSING, dropOnce = true) }

        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(PaymentState.Success, safeClient.state.value)
        assertEquals(1, harness.payment(id).retryCount)
        assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun initiatePayment_declined_failsWithoutRetrying() = runTest {
        val id = safeClient.initiatePayment(FakePaymentServer.DEFAULT_DECLINE_LIMIT_MINOR + 1, RECIPIENT)

        assertEquals(PaymentState.Failed("insufficient_funds"), safeClient.state.value)
        assertEquals(PendingPaymentStatus.FAILED, harness.payment(id).status)
        assertEquals(0, harness.payment(id).retryCount)
        assertTrue(harness.enqueuedWorkers.isEmpty())
        assertEquals(0L, currentTime) // no backoff wait happened
    }

    @Test
    fun retryWithBackoff_offline_waits1s2s4s8s16sThenNeedsReconcile() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        // Virtual time: 1 + 2 + 4 + 8 + 16 seconds plus up to 500ms of jitter per wait.
        assertTrue("waited ${currentTime}ms", currentTime in 31_000..33_500)
        val waits = harness.logMessages().mapNotNull { Regex("""waiting (\d+)s""").find(it)?.groupValues?.get(1) }
        assertEquals(listOf("1", "2", "4", "8", "16"), waits)

        val payment = harness.payment(id)
        assertEquals(PendingPaymentStatus.NEEDS_RECONCILE, payment.status)
        assertEquals(SafePaymentClient.MAX_RETRIES, payment.retryCount)
        assertEquals(listOf(id), harness.enqueuedWorkers)
        assertEquals(PaymentState.Retrying, safeClient.state.value)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun retryWithBackoff_serverErrors_areRetriedLikeNetworkErrors() = runTest {
        harness.configure { it.copy(mode = NetworkMode.SERVER_ERROR) }

        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(PendingPaymentStatus.NEEDS_RECONCILE, harness.payment(id).status)
        assertEquals("HTTP 500", harness.payment(id).lastError)
        assertEquals(0, harness.ledger().size)
    }

    // Scenario 7, client side
    @Test
    fun initiatePayment_keyAlreadyUsedForADifferentRequest_failsWithRequestMismatch() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }
        val id = safeClient.initiatePayment(AMOUNT, RECIPIENT) // queued, never reached the server
        harness.configure { it.copy(mode = NetworkMode.ONLINE) }
        harness.api.createPayment(id, REQUEST.copy(amountMinor = AMOUNT + 100)) // key taken by another request

        safeClient.initiatePayment(AMOUNT, RECIPIENT) // reuses the in-flight key

        assertEquals(PaymentState.Failed(SafePaymentClient.REQUEST_MISMATCH), safeClient.state.value)
        assertEquals(PendingPaymentStatus.FAILED, harness.payment(id).status)
        assertEquals(1, harness.ledger().size) // only the other request's charge
    }

    @Test
    fun reset_clearsQueueAndState() = runTest {
        safeClient.initiatePayment(AMOUNT, RECIPIENT)

        safeClient.reset()

        assertEquals(PaymentState.Idle, safeClient.state.value)
        assertNull(harness.clientDb.pendingPaymentDao().latest())
    }
}
