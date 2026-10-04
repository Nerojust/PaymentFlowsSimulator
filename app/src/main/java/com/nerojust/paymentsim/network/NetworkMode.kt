package com.nerojust.paymentsim.network

enum class NetworkMode {
    ONLINE,
    OFFLINE,                 // throw IOException before the server sees the request
    DROP_AFTER_PROCESSING,   // let the server fully process, then throw IOException instead of returning
    SLOW,                    // add configurable latency (default 4s) then succeed
    SERVER_ERROR,            // return 500 without processing
}
