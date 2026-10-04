package com.nerojust.paymentsim

import android.app.Application
import com.nerojust.paymentsim.di.ServiceLocator
import com.nerojust.paymentsim.log.LogSource
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val locator = ServiceLocator(this).also { ServiceLocator.instance = it }

        // Slide 17: every start settles whatever the last run left behind.
        locator.scope.launch {
            val unsettled = locator.repository.unsettledCount()
            locator.log.log(LogSource.CLIENT, "App restarted: reconciling $unsettled unsettled payments")
            locator.server.releaseStuckClaims()
            locator.repository.reconcilePendingPayments()
        }
    }
}
