package com.nerojust.paymentsim

import android.app.Application
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.log.LogSource
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val deps = AppDependencies(this).also { AppDependencies.instance = it }

        // Slide 18: every start settles whatever the last run left behind.
        deps.scope.launch {
            val unsettled = deps.safeClient.unsettledCount()
            deps.log.log(LogSource.CLIENT, "App restarted. Checking $unsettled unfinished payment(s)")
            if (deps.network.settings.value.crashedOnPurpose) {
                deps.network.update { it.copy(crashedOnPurpose = false) }
                // Coming back to an empty log and a "Ready" status looks like nothing happened, so say what was found.
                val note = when {
                    unsettled > 0 ->
                        "The app is back. It found $unsettled unfinished payment${if (unsettled == 1) "" else "s"} " +
                            "saved on this phone and is checking with the bank."
                    !deps.network.settings.value.useSafeClient ->
                        "The app is back. The Careless app saved nothing, so it cannot know if you paid."
                    else -> "The app is back. No payment was left unfinished, so there is nothing to fix."
                }
                deps.log.log(LogSource.CLIENT, note)
                deps.restartNote.value = note
            }
            deps.server.releaseStuckClaims()
            deps.safeClient.reconcilePendingPayments()
        }
    }
}
