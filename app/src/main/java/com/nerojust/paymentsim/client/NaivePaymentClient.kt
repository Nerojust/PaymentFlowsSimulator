package com.nerojust.paymentsim.client

import com.nerojust.paymentsim.client.api.PaymentApi
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Slide 2: the client that double charges. Every bug in here is deliberate, do not fix them.
 */
class NaivePaymentClient(private val api: PaymentApi, private val log: EventLog) {

    // BUG: a boolean instead of a state machine. It cannot tell "failed" from "unknown".
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun pay(amountMinor: Long, recipient: String) {
        _isLoading.value = true
        _lastError.value = null
        try {
            // BUG: a new key on every tap and nothing persisted, so a retry is a brand new payment.
            val key = UUID.randomUUID().toString()
            log.log(LogSource.CLIENT, "NAIVE: POST /payments with fresh key ${key.shortKey()}")
            val response = api.createPayment(key, PaymentRequest(amountMinor, PaymentRepository.CURRENCY, recipient))
            val status = response.body()?.status
            if (status == "succeeded") {
                log.log(LogSource.CLIENT, "NAIVE: payment succeeded")
            } else {
                _lastError.value = response.body()?.reason ?: "HTTP ${response.code()}"
                log.log(LogSource.CLIENT, "NAIVE: payment failed: ${_lastError.value}")
            }
        } catch (e: Exception) {
            // BUG: broad catch. "No response" is treated as "it failed", and the user is invited to tap again.
            _lastError.value = "${e.message}. Tap Pay to try again"
            log.log(LogSource.CLIENT, "NAIVE: ${e.message}, assuming it failed, Pay is enabled again")
        }
        _isLoading.value = false
    }

    fun reset() {
        _isLoading.value = false
        _lastError.value = null
    }
}
