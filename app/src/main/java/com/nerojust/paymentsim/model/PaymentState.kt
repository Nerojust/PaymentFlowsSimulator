package com.nerojust.paymentsim.model

import com.nerojust.paymentsim.BuildConfig

// Slide 6
sealed class PaymentState {
    object Idle : PaymentState()
    object Pending : PaymentState()
    object Confirming : PaymentState()
    object Success : PaymentState()
    data class Failed(val error: String) : PaymentState()
    object Retrying : PaymentState()
}

val PaymentState.label: String
    get() = when (this) {
        PaymentState.Idle -> "IDLE"
        PaymentState.Pending -> "PENDING"
        PaymentState.Confirming -> "CONFIRMING"
        PaymentState.Success -> "SUCCESS"
        is PaymentState.Failed -> "FAILED: $error"
        PaymentState.Retrying -> "RETRYING"
    }

fun isAllowed(from: PaymentState, to: PaymentState): Boolean = when (from) {
    PaymentState.Idle -> to is PaymentState.Pending
    PaymentState.Pending ->
        to is PaymentState.Confirming || to is PaymentState.Retrying || to is PaymentState.Failed
    PaymentState.Confirming ->
        to is PaymentState.Success || to is PaymentState.Failed || to is PaymentState.Retrying
    PaymentState.Retrying -> to is PaymentState.Pending
    // Only when the user starts a new payment. Success must never go to Failed.
    PaymentState.Success, is PaymentState.Failed -> to is PaymentState.Idle
}

/** Returns the new state. An illegal move throws in debug builds and is ignored in release. */
fun transition(
    from: PaymentState,
    to: PaymentState,
    strict: Boolean = BuildConfig.DEBUG,
): PaymentState {
    if (isAllowed(from, to)) return to
    check(!strict) { "Illegal payment state transition: ${from.label} -> ${to.label}" }
    return from
}
