package com.nerojust.paymentsim.ui

import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.DemoSettings

/** One tap on screen sets every toggle for a demo. [hint] tells the presenter what to do next. */
data class Scenario(
    val number: Int,
    val title: String,
    val hint: String,
    /** The payoff in one line: what this means for the person paying. */
    val why: String,
    val settings: DemoSettings,
    val amount: String = "1250.00",
)

val scenarios = listOf(
    Scenario(
        1, "Double charge",
        "Tap Pay. It shows an error. Tap Pay again. You are charged twice.",
        "The app forgot the first try, so you pay twice for one thing.",
        DemoSettings(
            scenario = 1,
            useSafeClient = false,
            serverIdempotencyEnabled = false,
            mode = NetworkMode.DROP_AFTER_PROCESSING,
        ),
    ),
    Scenario(
        2, "Safe retry",
        "Tap Pay once. The app tries again with the same key. You are charged once.",
        "The key tells the bank it is the same payment, so trying again is safe.",
        DemoSettings(scenario = 2, mode = NetworkMode.DROP_AFTER_PROCESSING),
    ),
    Scenario(
        3, "No internet",
        "Tap Pay. The app waits 1s, 2s, 4s, 8s, 16s between tries (see What happened). Then tap Go online. Charged once.",
        "Your payment waits on the phone instead of getting lost.",
        DemoSettings(scenario = 3, mode = NetworkMode.OFFLINE),
    ),
    Scenario(
        4, "App crash",
        "Tap Pay, then Crash the app within 4 seconds. Open the app again. Charged once.",
        "The payment was saved before the crash, so the app picks it up again.",
        DemoSettings(scenario = 4, mode = NetworkMode.SLOW),
    ),
    Scenario(
        5, "Forgotten payment",
        "Tap Pay, wait 2 minutes, tap Crash the app, open the app again, tap Go online. " +
            "The app asks before it sends. Nothing is charged until you tap Send it.",
        "Money should never leave by surprise, so the app asks you first.",
        DemoSettings(scenario = 5, mode = NetworkMode.OFFLINE),
    ),
    Scenario(
        6, "Not enough money",
        "Tap Pay. The bank says no. The app does not try again. No charge.",
        "A real no stays a no. Trying again would not help.",
        DemoSettings(scenario = 6),
        amount = "60000.00",
    ),
    Scenario(
        7, "Sneaky amount change",
        "Tap Pay and wait for Paid. Then tap Reuse key with a different amount. The bank refuses. Still 1 charge.",
        "A key belongs to one payment. Nobody can reuse it to change the amount.",
        DemoSettings(scenario = 7),
    ),
    Scenario(
        8, "Many payments",
        "Tap Pay, change the amount, tap Pay again. Do it a few times. Open Payments to see them saved. " +
            "Then tap Go online. Each one is charged once.",
        "Every payment gets its own key, so none is lost and none is charged twice.",
        DemoSettings(scenario = 8, mode = NetworkMode.OFFLINE),
    ),
)
