package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nerojust.paymentsim.TestHarness.Companion.AMOUNT
import com.nerojust.paymentsim.TestHarness.Companion.RECIPIENT
import com.nerojust.paymentsim.TestHarness.Companion.REQUEST
import com.nerojust.paymentsim.client.PaymentRepository
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PaymentStatus
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.server.FakePaymentServer
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentRepositoryTest {

    private val harness = TestHarness()
    private val repository get() = harness.repository

    @After
    fun tearDown() = harness.close()

    @Test
    fun initiatePayment_online_confirmsWithOneCharge() = runTest {
        val id = repository.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(PaymentState.Success, repository.state.value)
        assertEquals(PaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(0, harness.payment(id).retryCount)
        assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun initiatePayment_offline_persistsThePaymentEvenThoughNothingReachedTheServer() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val id = repository.initiatePayment(AMOUNT, RECIPIENT)

        val queued = harness.payment(id)
        assertEquals(AMOUNT, queued.amountMinor)
        assertEquals(RECIPIENT, queued.recipient)
        assertEquals("Simulated offline", queued.lastError)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun initiatePayment_sameIntentStillInFlight_reusesTheKey() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val first = repository.initiatePayment(AMOUNT, RECIPIENT)
        val second = repository.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(first, second)
        assertEquals(1, harness.clientDb.pendingPaymentDao().unsettled().size)
    }

    @Test
    fun initiatePayment_differentIntent_getsItsOwnKey() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val first = repository.initiatePayment(AMOUNT, RECIPIENT)
        val second = repository.initiatePayment(AMOUNT + 1, RECIPIENT)

        assertTrue(first != second)
    }

    @Test
    fun initiatePayment_responseDropped_retriesWithTheSameKey() = runTest {
        harness.configure { it.copy(mode = NetworkMode.DROP_AFTER_PROCESSING, dropOnce = true) }

        val id = repository.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(PaymentState.Success, repository.state.value)
        assertEquals(1, harness.payment(id).retryCount)
        assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun initiatePayment_declined_failsWithoutRetrying() = runTest {
        val id = repository.initiatePayment(FakePaymentServer.DEFAULT_DECLINE_LIMIT_MINOR + 1, RECIPIENT)

        assertEquals(PaymentState.Failed("insufficient_funds"), repository.state.value)
        assertEquals(PaymentStatus.FAILED, harness.payment(id).status)
        assertEquals(0, harness.payment(id).retryCount)
        assertTrue(harness.enqueuedWorkers.isEmpty())
        assertEquals(0L, currentTime) // no backoff wait happened
    }

    @Test
    fun retryWithBackoff_offline_waits1s2s4s8s16sThenNeedsReconcile() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val id = repository.initiatePayment(AMOUNT, RECIPIENT)

        // Virtual time: 1 + 2 + 4 + 8 + 16 seconds plus up to 500ms of jitter per wait.
        assertTrue("waited ${currentTime}ms", currentTime in 31_000..33_500)
        val waits = harness.logMessages().mapNotNull { Regex("""waiting (\d+)s""").find(it)?.groupValues?.get(1) }
        assertEquals(listOf("1", "2", "4", "8", "16"), waits)

        val payment = harness.payment(id)
        assertEquals(PaymentStatus.NEEDS_RECONCILE, payment.status)
        assertEquals(PaymentRepository.MAX_RETRIES, payment.retryCount)
        assertEquals(listOf(id), harness.enqueuedWorkers)
        assertEquals(PaymentState.Retrying, repository.state.value)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun retryWithBackoff_serverErrors_areRetriedLikeNetworkErrors() = runTest {
        harness.configure { it.copy(mode = NetworkMode.SERVER_ERROR) }

        val id = repository.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(PaymentStatus.NEEDS_RECONCILE, harness.payment(id).status)
        assertEquals("HTTP 500", harness.payment(id).lastError)
        assertEquals(0, harness.ledger().size)
    }

    // Scenario 7, client side
    @Test
    fun initiatePayment_keyAlreadyUsedForADifferentRequest_failsWithRequestMismatch() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }
        val id = repository.initiatePayment(AMOUNT, RECIPIENT) // queued, never reached the server
        harness.configure { it.copy(mode = NetworkMode.ONLINE) }
        harness.api.createPayment(id, REQUEST.copy(amountMinor = AMOUNT + 100)) // key taken by another request

        repository.initiatePayment(AMOUNT, RECIPIENT) // reuses the in-flight key

        assertEquals(PaymentState.Failed(PaymentRepository.REQUEST_MISMATCH), repository.state.value)
        assertEquals(PaymentStatus.FAILED, harness.payment(id).status)
        assertEquals(1, harness.ledger().size) // only the other request's charge
    }

    @Test
    fun reset_clearsQueueAndState() = runTest {
        repository.initiatePayment(AMOUNT, RECIPIENT)

        repository.reset()

        assertEquals(PaymentState.Idle, repository.state.value)
        assertNull(harness.clientDb.pendingPaymentDao().latest())
    }
}
