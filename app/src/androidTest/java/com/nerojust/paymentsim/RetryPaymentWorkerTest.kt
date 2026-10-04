package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.nerojust.paymentsim.TestHarness.Companion.AMOUNT
import com.nerojust.paymentsim.TestHarness.Companion.RECIPIENT
import com.nerojust.paymentsim.client.db.PendingPaymentEntity
import com.nerojust.paymentsim.client.work.RetryPaymentWorker
import com.nerojust.paymentsim.di.ServiceLocator
import com.nerojust.paymentsim.model.PaymentStatus
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.server.db.ProcessedPaymentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetryPaymentWorkerTest {

    private val harness = TestHarness()
    private lateinit var appLocator: ServiceLocator

    @Before
    fun setUp() {
        appLocator = ServiceLocator.instance
        ServiceLocator.instance = harness.locator
        runBlocking {
            harness.clientDb.pendingPaymentDao().insert(
                PendingPaymentEntity(ID, AMOUNT, RECIPIENT, harness.now, PaymentStatus.NEEDS_RECONCILE),
            )
        }
    }

    @After
    fun tearDown() {
        ServiceLocator.instance = appLocator
        harness.close()
    }

    private fun runWorker(paymentId: String? = ID): Result = runBlocking {
        TestListenableWorkerBuilder<RetryPaymentWorker>(harness.context)
            .apply { if (paymentId != null) setInputData(workDataOf(RetryPaymentWorker.KEY_PAYMENT_ID to paymentId)) }
            .build()
            .doWork()
    }

    @Test
    fun doWork_simulatedOffline_retriesWithoutTouchingThePayment() {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        assertEquals(Result.retry(), runWorker())

        runBlocking {
            assertEquals(PaymentStatus.NEEDS_RECONCILE, harness.payment(ID).status)
            assertEquals(0, harness.ledger().size)
        }
    }

    @Test
    fun doWork_online_reconcilesThePaymentWithItsOwnKey() {
        assertEquals(Result.success(), runWorker())

        runBlocking {
            assertEquals(PaymentStatus.CONFIRMED, harness.payment(ID).status)
            assertEquals(listOf(ID), harness.ledger().map { it.idempotencyKey })
        }
    }

    @Test
    fun doWork_serverStillProcessing_retriesAndDoesNotResend() {
        runBlocking {
            harness.serverDb.serverDao().claim(ProcessedPaymentEntity(ID, "hash", "processing", null, harness.now))
        }

        assertEquals(Result.retry(), runWorker())

        runBlocking { assertEquals(0, harness.ledger().size) }
    }

    @Test
    fun doWork_withoutPaymentId_fails() {
        assertEquals(Result.failure(), runWorker(paymentId = null))
    }

    private companion object {
        const val ID = "key-1"
    }
}
