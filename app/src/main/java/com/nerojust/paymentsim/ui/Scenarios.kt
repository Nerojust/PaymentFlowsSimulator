package com.nerojust.paymentsim.ui

import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.DemoSettings

/** One tap on screen sets every toggle for a demo. [hint] tells the presenter what to do next. */
data class Scenario(
    val number: Int,
    val title: String,
    val hint: String,
    val settings: DemoSettings,
    val amount: String = "1250.00",
)

val scenarios = listOf(
    Scenario(
        1, "Double charge",
        "Tap Pay. It shows an error. Tap Pay again. The customer is charged twice.",
        DemoSettings(
            scenario = 1,
            useSafeClient = false,
            serverIdempotencyEnabled = false,
            mode = NetworkMode.DROP_AFTER_PROCESSING,
        ),
    ),
    Scenario(
        2, "Safe retry",
        "Tap Pay once. The app tries again with the same key. The customer is charged once.",
        DemoSettings(scenario = 2, mode = NetworkMode.DROP_AFTER_PROCESSING),
    ),
    Scenario(
        3, "No internet",
        "Tap Pay. The app waits 1s, 2s, 4s, 8s, 16s between tries (see Log). Then tap Go online. Charged once.",
        DemoSettings(scenario = 3, mode = NetworkMode.OFFLINE),
    ),
    Scenario(
        4, "App crash",
        "Tap Pay, then Kill app within 4 seconds. Open the app again. Charged once.",
        DemoSettings(scenario = 4, mode = NetworkMode.SLOW),
    ),
    Scenario(
        5, "Old payment",
        "Tap Pay, wait 2 minutes, tap Kill app, open the app again, tap Go online. " +
            "The app asks before it sends. Nothing is charged until you tap Send it.",
        DemoSettings(scenario = 5, mode = NetworkMode.OFFLINE),
    ),
    Scenario(
        6, "Not enough money",
        "Tap Pay. The server says no. The app does not try again. No charge.",
        DemoSettings(scenario = 6),
        amount = "60000.00",
    ),
    Scenario(
        7, "Same key, new amount",
        "Tap Pay and wait for SUCCESS. Then tap Reuse key with a different amount. The server refuses. Still 1 charge.",
        DemoSettings(scenario = 7),
    ),
    Scenario(
        8, "Many payments",
        "Tap Pay, change the amount, tap Pay again. Do it a few times. Open Payments to see them saved. " +
            "Then tap Go online. Each one is charged once.",
        DemoSettings(scenario = 8, mode = NetworkMode.OFFLINE),
    ),
)
