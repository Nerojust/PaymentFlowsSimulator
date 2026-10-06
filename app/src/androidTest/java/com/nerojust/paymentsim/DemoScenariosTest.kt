package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.nerojust.paymentsim.TestHarness.Companion.AMOUNT
import com.nerojust.paymentsim.TestHarness.Companion.RECIPIENT
import com.nerojust.paymentsim.TestHarness.Companion.REQUEST
import com.nerojust.paymentsim.client.SafePaymentClient
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.server.FakePaymentServer
import com.nerojust.paymentsim.ui.duplicateChargeIds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The demo scenarios from the README, end to end through Retrofit, the interceptor and the network. */
@RunWith(AndroidJUnit4::class)
class DemoScenariosTest {

    private val harness = TestHarness()

    @After
    fun tearDown() = harness.close()

    @Test
    fun scenario1_naiveClientWithoutIdempotency_chargesTwice() = runTest {
        harness.configure {
            it.copy(mode = NetworkMode.DROP_AFTER_PROCESSING, dropOnce = true, serverIdempotencyEnabled = false)
        }
        val naive = harness.deps.naiveClient

        naive.pay(AMOUNT, RECIPIENT)        // charged, response lost, "failed"
        assertEquals(false, naive.isLoading.value) // so the user can tap again
        naive.pay(AMOUNT, RECIPIENT)

        val ledger = harness.serverDb.serverDao().ledger()
        assertEquals(2, ledger.size)
        assertEquals(2, duplicateChargeIds(ledger).size) // drives the "Duplicate charge detected" banner
    }

    @Test
    fun scenario1b_naiveClientEvenWithServerIdempotency_stillChargesTwice() = runTest {
        // A fresh key per tap defeats server-side idempotency: the patterns only work together.
        harness.configure { it.copy(mode = NetworkMode.DROP_AFTER_PROCESSING, dropOnce = true) }

        harness.deps.naiveClient.pay(AMOUNT, RECIPIENT)
        harness.deps.naiveClient.pay(AMOUNT, RECIPIENT)

        assertEquals(2, harness.ledger().size)
    }

    @Test
    fun scenario2_safeClientWithIdempotency_retriesWithSameKeyAndChargesOnce() = runTest {
        harness.configure { it.copy(mode = NetworkMode.DROP_AFTER_PROCESSING, dropOnce = true) }

        harness.safeClient.state.test {
            assertEquals(PaymentState.Idle, awaitItem())
            val id = harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)

            assertEquals(PaymentState.Pending, awaitItem())
            assertEquals(PaymentState.Confirming, awaitItem())
            assertEquals(PaymentState.Retrying, awaitItem())  // response lost
            assertEquals(PaymentState.Pending, awaitItem())
            assertEquals(PaymentState.Confirming, awaitItem())
            assertEquals(PaymentState.Success, awaitItem())   // server returned the stored result
            assertEquals(listOf(id), harness.ledger().map { it.idempotencyKey })
        }
        assertTrue(harness.logMessages().any { it.contains("Sending back the saved answer. No new charge") })
    }

    @Test
    fun scenario2b_safeClientWithoutServerIdempotency_stillChargesTwice() = runTest {
        harness.configure {
            it.copy(mode = NetworkMode.DROP_AFTER_PROCESSING, dropOnce = true, serverIdempotencyEnabled = false)
        }

        harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)

        assertEquals(2, harness.ledger().size)
    }

    @Test
    fun scenario3_offlineThenOnline_queuesBacksOffAndSettlesWithOneCharge() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }

        val id = harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)
        assertEquals(PendingPaymentStatus.NEEDS_RECONCILE, harness.payment(id).status)
        assertEquals(listOf(id), harness.enqueuedWorkers)

        harness.configure { it.copy(mode = NetworkMode.ONLINE) }
        harness.safeClient.reconcilePendingPayments() // what the worker / connectivity change runs

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(PaymentState.Success, harness.safeClient.state.value)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun scenario4_killedBeforeTheServerSawIt_reconcilesOnRelaunchWithOneCharge() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }
        val id = harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)
        harness.configure { it.copy(mode = NetworkMode.SLOW) }

        val relaunched = harness.relaunch() // process died: state machine and log are gone, databases are not
        assertEquals(PaymentState.Idle, relaunched.safeClient.state.value)
        assertEquals(1, relaunched.safeClient.unsettledCount())
        relaunched.safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(PaymentState.Success, relaunched.safeClient.state.value)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun scenario4b_killedAfterTheServerChargedIt_reconcilesOnRelaunchWithoutASecondCharge() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }
        val id = harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)
        harness.configure { it.copy(mode = NetworkMode.ONLINE) }
        harness.api.createPayment(id, REQUEST) // the request that was in flight when the app died

        val relaunched = harness.relaunch()
        relaunched.safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun scenario5_stalePayment_isNotChargedUntilTheUserTapsSend() = runTest {
        harness.configure { it.copy(mode = NetworkMode.OFFLINE) }
        val id = harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)

        harness.now += SafePaymentClient.DEFAULT_TTL_MS + 60_000 // wait past the TTL
        val relaunched = harness.relaunch()
        harness.configure { it.copy(mode = NetworkMode.ONLINE) }
        relaunched.safeClient.reconcilePendingPayments()

        assertEquals(PendingPaymentStatus.AWAITING_USER_CONFIRMATION, harness.payment(id).status) // dialog is showing
        assertEquals(0, harness.ledger().size)

        relaunched.safeClient.reconcilePayment(id, userConfirmed = true) // "Send"

        assertEquals(PendingPaymentStatus.CONFIRMED, harness.payment(id).status)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun scenario6_declinedPayment_failsWithoutRetriesOrCharges() = runTest {
        val id = harness.safeClient.initiatePayment(FakePaymentServer.DEFAULT_DECLINE_LIMIT_MINOR + 1, RECIPIENT)

        assertEquals(PaymentState.Failed("insufficient_funds"), harness.safeClient.state.value)
        assertEquals(0, harness.payment(id).retryCount)
        assertTrue(harness.logMessages().none { it.contains("waiting") })
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun scenario7_sameKeyWithDifferentAmount_returns422AndDoesNotCharge() = runTest {
        val id = harness.safeClient.initiatePayment(AMOUNT, RECIPIENT)

        val response = harness.api.createPayment(id, REQUEST.copy(amountMinor = AMOUNT + 100))
        harness.safeClient.debugReuseKeyWithDifferentAmount() // the on-screen debug button

        assertEquals(422, response.code())
        assertTrue(harness.logMessages().any { it.contains("The bank refused it") })
        assertEquals(1, harness.ledger().size) // only the original payment
    }
}
