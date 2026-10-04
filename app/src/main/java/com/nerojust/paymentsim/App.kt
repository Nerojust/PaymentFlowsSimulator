package com.nerojust.paymentsim

import android.app.Application
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.log.LogSource
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val deps = AppDependencies(this).also { AppDependencies.instance = it }

        // Slide 17: every start settles whatever the last run left behind.
        deps.scope.launch {
            val unsettled = deps.safeClient.unsettledCount()
            deps.log.log(LogSource.CLIENT, "App restarted. Checking $unsettled unfinished payment(s)")
            deps.server.releaseStuckClaims()
            deps.safeClient.reconcilePendingPayments()
        }
    }
}
