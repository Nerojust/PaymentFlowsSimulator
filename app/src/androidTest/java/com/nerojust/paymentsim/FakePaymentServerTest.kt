package com.nerojust.paymentsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nerojust.paymentsim.TestHarness.Companion.REQUEST
import com.nerojust.paymentsim.server.FakePaymentServer
import com.nerojust.paymentsim.server.db.RememberedPayment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakePaymentServerTest {

    private val harness = TestHarness()
    private val server get() = harness.deps.server

    @After
    fun tearDown() = harness.close()

    @Test
    fun createPayment_firstRequest_claimsKeyAndChargesOnce() = runTest {
        val reply = server.createPayment("key-1", REQUEST)

        assertEquals(201, reply.code)
        val row = harness.serverDb.serverDao().find("key-1")!!
        assertEquals("succeeded", row.status)
        assertEquals(reply.body, row.resultJson)
        assertEquals(listOf("key-1"), harness.ledger().map { it.idempotencyKey })
    }

    @Test
    fun createPayment_sameKeyReplayed_returnsStoredResultWithoutChargingAgain() = runTest {
        val first = server.createPayment("key-1", REQUEST)
        val replay = server.createPayment("key-1", REQUEST)

        assertEquals(201, replay.code)
        assertEquals(first.body, replay.body)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun createPayment_twoConcurrentRequestsWithSameKey_writeOneLedgerRow() {
        harness.configure { it.copy(processingDelayMs = 300) }

        // Real threads and a real delay: the second request arrives while the first is still processing.
        val codes = runBlocking(Dispatchers.Default) {
            List(2) { async { server.createPayment("key-1", REQUEST).code } }.awaitAll()
        }

        assertEquals(listOf(201, 409), codes.sorted())
        assertEquals(1, runBlocking { harness.ledger() }.size)
    }

    @Test
    fun createPayment_keyStillProcessing_returns409() = runTest {
        // sha256("125000|NGN|Ada Lovelace") is what the server computes for REQUEST; claim it by hand.
        val firstReply = server.createPayment("probe", REQUEST)
        assertEquals(201, firstReply.code)
        val hash = harness.serverDb.serverDao().find("probe")!!.requestHash
        harness.serverDb.serverDao().claim(RememberedPayment("key-1", hash, "processing", null, harness.now))

        val reply = server.createPayment("key-1", REQUEST)

        assertEquals(409, reply.code)
        assertEquals(1, harness.ledger().size) // only the probe
    }

    @Test
    fun createPayment_sameKeyDifferentRequest_returns422() = runTest {
        server.createPayment("key-1", REQUEST)

        val reply = server.createPayment("key-1", REQUEST.copy(amountMinor = REQUEST.amountMinor + 1))

        assertEquals(422, reply.code)
        assertEquals(1, harness.ledger().size)
    }

    @Test
    fun createPayment_idempotencyOff_sameKeyChargesTwice() = runTest {
        harness.configure { it.copy(serverIdempotencyEnabled = false) }

        server.createPayment("key-1", REQUEST)
        server.createPayment("key-1", REQUEST)

        val ledger = harness.ledger()
        assertEquals(2, ledger.size)
        assertEquals(listOf(null, null), ledger.map { it.idempotencyKey })
        assertNull(harness.serverDb.serverDao().find("key-1"))
    }

    @Test
    fun createPayment_amountAboveLimit_declinesWithoutChargingAndReplaysTheDecline() = runTest {
        val tooMuch = REQUEST.copy(amountMinor = FakePaymentServer.DEFAULT_DECLINE_LIMIT_MINOR + 1)

        val first = server.createPayment("key-1", tooMuch)
        val replay = server.createPayment("key-1", tooMuch)

        assertEquals(201, first.code)
        assertEquals(true, first.body.contains("insufficient_funds"))
        assertEquals(first.body, replay.body)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun createPayment_idempotencyOnWithoutKey_returns400() = runTest {
        assertEquals(400, server.createPayment(null, REQUEST).code)
        assertEquals(0, harness.ledger().size)
    }

    @Test
    fun getPayment_knownAndUnknownKey_returnsStatusOr404() = runTest {
        server.createPayment("key-1", REQUEST)

        val found = harness.api.getPayment("key-1")
        val missing = harness.api.getPayment("nope")

        assertEquals(200, found.code())
        assertEquals("succeeded", found.body()?.status)
        assertEquals(404, missing.code())
    }

    @Test
    fun releaseStuckClaims_processingRow_isDroppedSoTheSameKeyCanBeResent() = runTest {
        harness.serverDb.serverDao().claim(RememberedPayment("key-1", "any", "processing", null, harness.now))

        server.releaseStuckClaims()
        val reply = server.createPayment("key-1", REQUEST)

        assertEquals(201, reply.code)
        assertNotEquals(0, harness.ledger().size)
    }
}
