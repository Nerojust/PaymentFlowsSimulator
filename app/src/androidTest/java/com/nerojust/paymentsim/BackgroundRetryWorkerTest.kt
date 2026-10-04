package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.nerojust.paymentsim.TestHarness.Companion.AMOUNT
import com.nerojust.paymentsim.TestHarness.Companion.RECIPIENT
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.client.work.BackgroundRetryWorker
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.server.db.RememberedPayment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundRetryWorkerTest {

    private val harness = TestHarness()
    private lateinit var appLocator: AppDependencies

    @Before
    fun setUp() {
        appLocator = AppDependencies.instance
        AppDependencies.instance = harness.deps
        runBlocking {
            harness.clientDb.pendingPaymentDao().insert(
                PendingPayment(ID, AMOUNT, RECIPIENT, harness.now, PendingPaymentStatus.NEEDS_RECONCILE),
            )
        }
    }

    @After
    fun tearDown() {
        AppDependencies.instance = appLocator
        harness.close()
    }

    private fun runWorker(paymentId: String? = ID): Result = runBlocking {
        TestListenableWorkerBuilder<BackgroundRetryWorker>(harness.context)
            .apply { if (paymentId != null) setInputData(workDataOf(BackgroundRetryWorker.KEY_PAYMENT_ID to paymentId)) }
            .build()
            .doWork()
    }

    @Test
    fun doWork_simulatedOffline_retriesWithoutTouchingThePayment() {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        assertEquals(Result.retry(), runWorker())

        runBlocking {
            assertEquals(PendingPaymentStatus.NEEDS_RECONCILE, harness.payment(ID).status)
            assertEquals(0, harness.ledger().size)
        }
    }

    @Test
    fun doWork_online_reconcilesThePaymentWithItsOwnKey() {
        assertEquals(Result.success(), runWorker())

        runBlocking {
            assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(ID).status)
            assertEquals(listOf(ID), harness.ledger().map { it.idempotencyKey })
        }
    }

    @Test
    fun doWork_serverStillProcessing_retriesAndDoesNotResend() {
        runBlocking {
            harness.serverDb.serverDao().claim(RememberedPayment(ID, "hash", "processing", null, harness.now))
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
