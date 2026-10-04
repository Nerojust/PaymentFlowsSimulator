package com.nerojust.paymentsim.model

/** Client-side status of a row in `pending_payments`. */
object PendingPaymentStatus {
    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
    const val FAILED = "failed"
    const val NEEDS_RECONCILE = "needs_reconcile"
    const val AWAITING_USER_CONFIRMATION = "awaiting_user_confirmation"
}
