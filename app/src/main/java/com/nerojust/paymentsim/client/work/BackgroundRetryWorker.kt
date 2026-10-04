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
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import java.util.concurrent.TimeUnit

/** Slide 11: finishes a payment after the in-app retries gave up. It reconciles, it never blindly resends. */
class BackgroundRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = AppDependencies.instance
        val paymentId = inputData.getString(KEY_PAYMENT_ID) ?: return Result.failure()

        // The CONNECTED constraint only knows about real connectivity, so ask the network too.
        if (deps.network.isSimulatedOffline()) {
            deps.log.log(LogSource.WORKER, "Still no internet. Will check payment ${paymentId.shortKey()} later")
            return Result.retry()
        }

        deps.log.log(LogSource.WORKER, "Checking payment ${paymentId.shortKey()} in the background")
        deps.safeClient.reconcilePayment(paymentId)
        return if (deps.safeClient.isUnsettled(paymentId)) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PAYMENT_ID = "paymentId"

        fun enqueue(context: Context, paymentId: String) {
            val request = OneTimeWorkRequestBuilder<BackgroundRetryWorker>()
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
