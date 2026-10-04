package com.nerojust.paymentsim.client.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nerojust.paymentsim.di.ServiceLocator
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import java.util.concurrent.TimeUnit

/** Slide 11: finishes a payment after the in-app retries gave up. It reconciles, it never blindly resends. */
class RetryPaymentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.instance
        val paymentId = inputData.getString(KEY_PAYMENT_ID) ?: return Result.failure()

        // The CONNECTED constraint only knows about real connectivity, so ask the simulator too.
        if (locator.simulator.isSimulatedOffline()) {
            locator.log.log(LogSource.WORKER, "still (simulated) offline, will retry key ${paymentId.shortKey()}")
            return Result.retry()
        }

        locator.log.log(LogSource.WORKER, "reconciling key ${paymentId.shortKey()}")
        locator.repository.reconcilePayment(paymentId)
        return if (locator.repository.isUnsettled(paymentId)) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PAYMENT_ID = "paymentId"

        fun enqueue(context: Context, paymentId: String) {
            val request = OneTimeWorkRequestBuilder<RetryPaymentWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .setInputData(workDataOf(KEY_PAYMENT_ID to paymentId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("retry-payment-$paymentId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
