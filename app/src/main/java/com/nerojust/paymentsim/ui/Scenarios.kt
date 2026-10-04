package com.nerojust.paymentsim.ui

import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.NetworkSettings

/** One tap on screen sets every toggle for a demo scenario. [hint] tells the presenter what to do next. */
data class Scenario(
    val number: Int,
    val title: String,
    val hint: String,
    val settings: NetworkSettings,
    val amount: String = "1250.00",
)

val scenarios = listOf(
    Scenario(
        1, "Duplicate charge",
        "Tap Pay. It reports an error. Tap Pay again. Expect 2 charges.",
        NetworkSettings(
            scenario = 1,
            safeClient = false,
            serverIdempotencyEnabled = false,
            mode = NetworkMode.DROP_AFTER_PROCESSING,
        ),
    ),
    Scenario(
        2, "Safe retry",
        "Tap Pay once. The retry reuses the same key. Expect 1 charge.",
        NetworkSettings(scenario = 2, mode = NetworkMode.DROP_AFTER_PROCESSING),
    ),
    Scenario(
        3, "Offline queue",
        "Tap Pay and watch the 1s, 2s, 4s, 8s, 16s waits. Then tap Go online. Expect 1 charge.",
        NetworkSettings(scenario = 3, mode = NetworkMode.OFFLINE),
    ),
    Scenario(
        4, "Crash mid-payment",
        "Tap Pay, then Kill app within 4 seconds. Relaunch. Expect 1 charge.",
        NetworkSettings(scenario = 4, mode = NetworkMode.SLOW),
    ),
    Scenario(
        5, "Stale payment",
        "Tap Pay, wait 2 minutes, Kill app, relaunch, tap Go online. Nothing is charged until you tap Send.",
        NetworkSettings(scenario = 5, mode = NetworkMode.OFFLINE),
    ),
    Scenario(
        6, "Decline",
        "Tap Pay. Expect FAILED: insufficient_funds, no retries, 0 charges.",
        NetworkSettings(scenario = 6),
        amount = "60000.00",
    ),
    Scenario(
        7, "Key reuse",
        "Tap Pay, wait for SUCCESS, then tap Reuse key, different amount. Expect a 422 and still 1 charge.",
        NetworkSettings(scenario = 7),
    ),
)
